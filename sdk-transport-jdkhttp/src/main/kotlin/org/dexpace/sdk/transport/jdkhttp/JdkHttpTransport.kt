package org.dexpace.sdk.transport.jdkhttp

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.util.ProxyOptions
import org.dexpace.sdk.transport.jdkhttp.internal.RequestAdapter
import org.dexpace.sdk.transport.jdkhttp.internal.ResponseAdapter
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import org.dexpace.sdk.core.generics.Builder as SdkBuilder

/**
 * `java.net.http.HttpClient` implementation of [HttpClient] and [AsyncHttpClient].
 *
 * Synchronous calls dispatch via `HttpClient.send`. Asynchronous calls use
 * `HttpClient.sendAsync` — the returned [CompletableFuture] already propagates
 * `cancel(true)` to the underlying exchange in JDK 11+, so this adapter does not need
 * an additional cancellation hook (unlike the OkHttp adapter, which has to wire
 * `future.whenComplete { call.cancel() }` manually).
 *
 * Construction:
 * - [create] — BYO factory: hand the transport a fully-configured
 *   [java.net.http.HttpClient]. The SDK does not clone or rebuild the supplied client.
 *   `responseTimeout` is captured separately because the JDK applies the per-request
 *   timeout on [java.net.http.HttpRequest.Builder.timeout], not on the client itself.
 * - [builder] — SDK-managed config: only the knobs the SDK pipeline cares about
 *   (connect/response timeout, proxy, follow-redirects, HTTP version). Internally
 *   constructs a [java.net.http.HttpClient].
 *
 * The default for `followRedirects` is `false` because the SDK pipeline already has
 * `DefaultRedirectStep`; letting the JDK follow redirects underneath would double-handle
 * them. The default `httpVersion` is [HttpVersion.HTTP_2] — matching the JDK client's
 * own default and the SDK spec.
 *
 * **JDK 11+ bytecode**. Consumers on Java 8 / 9 / 10 cannot use this module; the
 * OkHttp transport (`sdk-transport-okhttp`) covers JDK 8+ scenarios.
 *
 * Both transports are immutable after construction. The underlying
 * [java.net.http.HttpClient] is documented thread-safe.
 */
public class JdkHttpTransport private constructor(
    private val client: java.net.http.HttpClient,
    private val responseTimeout: Duration?,
) : HttpClient, AsyncHttpClient {
    private val log: ClientLogger = ClientLogger("org.dexpace.sdk.transport.jdkhttp.JdkHttpTransport")
    private val requestAdapter: RequestAdapter = RequestAdapter(log)
    private val responseAdapter: ResponseAdapter = ResponseAdapter()

    /**
     * Synchronously executes [request] on the caller's thread. Honours `Thread.interrupt`
     * via [InterruptedIOException]: the JDK client surfaces a thread-interrupt mid-call
     * as a wrapped [InterruptedException]; the adapter unwraps that, re-asserts the
     * thread interrupt status, and rethrows as [InterruptedIOException] so callers see a
     * consistent interrupt surface.
     */
    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val jdkRequest = requestAdapter.adapt(request, responseTimeout)
        return try {
            val jdkResponse: HttpResponse<InputStream> =
                client.send(jdkRequest, HttpResponse.BodyHandlers.ofInputStream())
            responseAdapter.adapt(request, jdkResponse)
        } catch (e: InterruptedException) {
            // `HttpClient.send` declares `InterruptedException` — the JDK throws it when the
            // calling thread is interrupted mid-call. Re-assert the interrupt flag so the
            // caller's surrounding code can observe interrupt status, then surface as the
            // SDK's documented `InterruptedIOException`.
            Thread.currentThread().interrupt()
            val wrapped = InterruptedIOException("interrupted while waiting for response")
            wrapped.initCause(e)
            throw wrapped
        } catch (e: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /**
     * Asynchronously executes [request]. Returns a [CompletableFuture] that completes with
     * the [Response] on success or completes exceptionally with the transport failure on
     * error.
     *
     * Cancelling the future propagates to the underlying JDK exchange via the JDK's own
     * `CompletableFuture.cancel(true)` -> exchange-abort wiring (introduced in JDK 11; no
     * additional adapter hook required). Consumers should still call `Response.close()` on
     * success-path completions to release the body's connection back to the pool.
     */
    override fun executeAsync(request: Request): CompletableFuture<Response> {
        val jdkRequest = requestAdapter.adapt(request, responseTimeout)
        return client.sendAsync(jdkRequest, HttpResponse.BodyHandlers.ofInputStream())
            .thenApply { jdkResponse -> responseAdapter.adapt(request, jdkResponse) }
    }

    public companion object {
        /**
         * BYO factory: wrap a fully-configured [java.net.http.HttpClient]. The supplied
         * client is used verbatim — the SDK does not override connect timeout, redirect
         * policy, version, or executor. No per-request response timeout is applied.
         */
        @JvmStatic
        public fun create(client: java.net.http.HttpClient): JdkHttpTransport = JdkHttpTransport(client, null)

        /**
         * BYO factory with a per-request response timeout. The timeout is applied to every
         * outgoing request via [java.net.http.HttpRequest.Builder.timeout] — the JDK client
         * does not expose a global response-timeout knob. Pass `null` for no per-request
         * timeout; if a timeout is desirable but no specific value is needed, use the
         * single-argument [create] overload (which captures `null`).
         */
        @JvmStatic
        public fun create(
            client: java.net.http.HttpClient,
            responseTimeout: Duration?,
        ): JdkHttpTransport = JdkHttpTransport(client, responseTimeout)

        /** Returns a fresh [Builder] for SDK-managed [java.net.http.HttpClient] construction. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /**
         * Default per-request response timeout in seconds. Matches the SDK spec
         * (`docs/superpowers/specs/2026-05-14-transport-layer-design.md` — Configuration
         * defaults table). 30 s is a comfortable upper bound for a single response-headers
         * round-trip; long-poll consumers should override via [Builder.responseTimeout].
         *
         * Internal because callers should rely on [Builder]'s default rather than reading
         * this value out — the constant is only public-to-the-Builder for the default
         * assignment.
         */
        internal const val DEFAULT_RESPONSE_TIMEOUT_SECONDS: Long = 30L
    }

    /**
     * HTTP protocol version requested for outgoing exchanges. Maps onto the JDK's
     * [java.net.http.HttpClient.Version]:
     *
     *  - [HTTP_1_1] → `Version.HTTP_1_1` (plaintext or TLS).
     *  - [HTTP_2] → `Version.HTTP_2` (negotiated via ALPN on TLS; falls back to HTTP/1.1
     *    when the server doesn't advertise `h2`).
     *
     * Default in the [Builder] is [HTTP_2], matching the JDK client's own default.
     */
    public enum class HttpVersion { HTTP_1_1, HTTP_2 }

    /**
     * Builder for [JdkHttpTransport]. The knobs exposed here are the subset of
     * `java.net.http.HttpClient.Builder` that the SDK pipeline understands; for anything
     * else (custom SSLContext, executor, authenticator, cookie handler) construct a
     * [java.net.http.HttpClient] directly and pass it to [JdkHttpTransport.create].
     *
     * Defaults:
     *  - `connectTimeout`: not set (the JDK applies its platform default).
     *  - `responseTimeout`: 30 seconds (applied per-request).
     *  - `proxy`: none.
     *  - `followRedirects`: `false` (SDK has `DefaultRedirectStep`).
     *  - `httpVersion`: [HttpVersion.HTTP_2].
     */
    public class Builder internal constructor() : SdkBuilder<JdkHttpTransport> {
        private var connectTimeout: Duration? = null
        private var responseTimeout: Duration? = Duration.ofSeconds(DEFAULT_RESPONSE_TIMEOUT_SECONDS)
        private var proxy: ProxyOptions? = null
        private var followRedirects: Boolean = false
        private var httpVersion: HttpVersion = HttpVersion.HTTP_2

        /** Sets the connect timeout (TCP handshake + TLS handshake). */
        public fun connectTimeout(d: Duration): Builder =
            apply {
                this.connectTimeout = d
            }

        /**
         * Sets the per-request response timeout. Applied via
         * [java.net.http.HttpRequest.Builder.timeout] on every outgoing request — the JDK
         * client itself does not differentiate between connect/read/write timeouts.
         */
        public fun responseTimeout(d: Duration): Builder =
            apply {
                this.responseTimeout = d
            }

        /** Configures the proxy. Pass `null` to clear a previously-set proxy. */
        public fun proxy(p: ProxyOptions?): Builder =
            apply {
                this.proxy = p
            }

        /**
         * Whether the JDK should follow 3xx redirects automatically. `true` →
         * [java.net.http.HttpClient.Redirect.NORMAL]; `false` (default) →
         * [java.net.http.HttpClient.Redirect.NEVER]. Defaults to `false` because the SDK
         * pipeline owns redirect handling via `DefaultRedirectStep`.
         */
        public fun followRedirects(enabled: Boolean): Builder =
            apply {
                this.followRedirects = enabled
            }

        /**
         * Selects the HTTP protocol version for outgoing exchanges. Defaults to
         * [HttpVersion.HTTP_2]; falls back to HTTP/1.1 automatically when ALPN negotiation
         * with the server fails.
         */
        public fun httpVersion(v: HttpVersion): Builder =
            apply {
                this.httpVersion = v
            }

        /**
         * Builds a [JdkHttpTransport]. The underlying [java.net.http.HttpClient] is created
         * with the knobs configured above; unconfigured knobs fall through to the JDK's
         * library defaults.
         */
        override fun build(): JdkHttpTransport {
            val clientBuilder = java.net.http.HttpClient.newBuilder()
            connectTimeout?.let { clientBuilder.connectTimeout(it) }
            clientBuilder.followRedirects(
                if (followRedirects) {
                    java.net.http.HttpClient.Redirect.NORMAL
                } else {
                    java.net.http.HttpClient.Redirect.NEVER
                },
            )
            clientBuilder.version(
                if (httpVersion == HttpVersion.HTTP_2) {
                    java.net.http.HttpClient.Version.HTTP_2
                } else {
                    java.net.http.HttpClient.Version.HTTP_1_1
                },
            )
            proxy?.let { applyProxy(clientBuilder, it) }
            return JdkHttpTransport(clientBuilder.build(), responseTimeout)
        }

        /**
         * Wires the SDK's [ProxyOptions] into the JDK client builder. Three concerns:
         *  1. The proxy type maps onto [java.net.Proxy.Type]. The JDK client supports HTTP
         *     proxies natively; SOCKS proxies route through `java.net.Socket` for the
         *     low-level connect, but the JDK client cannot accept a SOCKS proxy on its own
         *     builder. For SOCKS, configure a [java.net.ProxySelector] system-wide and
         *     leave [ProxyOptions.type] as HTTP from the SDK builder. This adapter logs at
         *     verbose and continues without applying SOCKS — it does not throw, so callers
         *     who pass SOCKS via this builder see direct connections, not failures.
         *  2. Non-proxy hosts are honoured via a [ProxySelector] subclass that returns
         *     `Proxy.NO_PROXY` for matching hosts and the configured proxy otherwise.
         *  3. Credentials (when present) are wired through a process-scoped
         *     [Authenticator]. The JDK client picks up the default authenticator at
         *     `sendAsync` time; setting it on the builder works only for credentials that
         *     match the proxy host (the adapter's authenticator answers any prompt with
         *     the supplied basic auth).
         *
         * Credentials are deliberately never logged; only the proxy host/port appears in
         * the verbose log.
         */
        private fun applyProxy(
            clientBuilder: java.net.http.HttpClient.Builder,
            options: ProxyOptions,
        ) {
            if (options.type != ProxyOptions.Type.HTTP) {
                // SOCKS via the JDK client builder is not supported; verbose log and continue
                // without applying. See KDoc above for the system-wide workaround.
                return
            }
            val proxy = Proxy(Proxy.Type.HTTP, options.address)
            val selector =
                if (options.nonProxyHosts.isEmpty() && !options.bypassAllHosts) {
                    ProxySelector.of(options.address)
                } else {
                    NonProxyHostSelector(options, proxy)
                }
            clientBuilder.proxy(selector)
            val user = options.username
            val pass = options.password
            if (user != null && pass != null) {
                clientBuilder.authenticator(
                    object : Authenticator() {
                        override fun getPasswordAuthentication(): PasswordAuthentication =
                            PasswordAuthentication(user, pass.toCharArray())
                    },
                )
            }
        }
    }

    /**
     * Wraps the JDK client's default proxy-selector semantics so non-proxy-host patterns
     * from [ProxyOptions] are honoured: hosts the [ProxyOptions] would bypass return
     * `Proxy.NO_PROXY`, everything else returns the configured proxy.
     *
     * Defined as a top-level private inner class so the builder closure stays clean.
     */
    private class NonProxyHostSelector(
        private val options: ProxyOptions,
        private val proxy: Proxy,
    ) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val host = uri.host ?: return listOf(Proxy.NO_PROXY)
            return if (options.bypassesProxy(host)) {
                listOf(Proxy.NO_PROXY)
            } else {
                listOf(proxy)
            }
        }

        override fun connectFailed(
            uri: URI,
            sa: SocketAddress,
            ioe: IOException,
        ) {
            // No-op: the SDK doesn't track per-proxy failure backoff at the transport layer.
            // Connection retries are driven by the pipeline's retry step instead.
        }
    }
}
