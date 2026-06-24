/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.util.ProxyOptions
import org.dexpace.sdk.transport.jdkhttp.internal.RequestAdapter
import org.dexpace.sdk.transport.jdkhttp.internal.ResponseAdapter
import org.dexpace.sdk.transport.jdkhttp.internal.bridgeAsyncResponse
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
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
 *   The transport's [close] is a no-op for BYO-supplied clients — the caller owns the
 *   client's lifecycle.
 * - [builder] — SDK-managed config: only the knobs the SDK pipeline cares about
 *   (connect/response timeout, proxy, follow-redirects, HTTP version). Internally
 *   constructs a [java.net.http.HttpClient]. The transport owns the resulting client and
 *   will close it on [close] when the JDK runtime supports it (JDK 21+ adds
 *   [AutoCloseable] to `java.net.http.HttpClient`).
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
    /**
     * `true` when the SDK created the underlying [java.net.http.HttpClient] via
     * [Builder.build]; `false` when the caller handed in their own client via [create].
     * Drives the close-or-no-op decision in [close] per the SDK's ownership-aware
     * lifecycle contract.
     */
    private val owned: Boolean,
    /**
     * The executor passed to [java.net.http.HttpClient.Builder.executor], when SDK-owned.
     * `null` when the JDK is using its default internal executor (which the JDK manages
     * itself) or when the transport was built via the BYO [create] factory. Only this
     * field's executor — if any — is shut down by [close]; user-supplied executors are
     * left untouched.
     */
    private val ownedExecutor: ExecutorService?,
) : HttpClient, AsyncHttpClient {
    private val log: ClientLogger = ClientLogger("org.dexpace.sdk.transport.jdkhttp.JdkHttpTransport")
    private val requestAdapter: RequestAdapter = RequestAdapter(log)
    private val responseAdapter: ResponseAdapter = ResponseAdapter(log)

    /**
     * Latches `true` on the first [close] so subsequent calls are no-ops. `AtomicBoolean`
     * keeps the latch lock-free and virtual-thread-friendly per the SDK's concurrency rules.
     */
    private val closed: AtomicBoolean = AtomicBoolean(false)

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
     * error — including a request-adaptation failure, which runs on the calling thread but is
     * delivered through the future rather than thrown synchronously.
     *
     * Cancelling the returned future cancels the underlying JDK exchange, and a response that
     * arrives in the cancellation race window is closed so its connection is not leaked (see
     * [bridgeAsyncResponse]). The intermediate `thenApply`-style future the JDK hands back does
     * not propagate cancellation to the `sendAsync` exchange on its own, so the bridge wires that
     * through explicitly. Consumers should still call `Response.close()` on success-path
     * completions to release the body's connection back to the pool.
     */
    override fun executeAsync(request: Request): CompletableFuture<Response> {
        // Held outside the try so the catch can release the JDK exchange if dispatch succeeded but a
        // later step threw (see the catch). Null until `sendAsync` returns: a throw at or before
        // dispatch leaves nothing to clean up.
        var inFlight: CompletableFuture<HttpResponse<InputStream>>? = null
        return try {
            val jdkRequest = requestAdapter.adapt(request, responseTimeout)
            // `sendAsync` is inside the guard too. Its contract does not promise that every failure
            // is delivered through the returned future: the JDK's own Javadoc permits a synchronous
            // `IllegalArgumentException` for a request it rejects, and a custom or future
            // `HttpClient` is free to throw on the caller's thread instead of returning a failed
            // future. Guarding the dispatch keeps the error-delivery contract documented above
            // intact whichever way a failure surfaces. (Today's stock JDK client packages such
            // failures into an already-failed future — e.g. on a closed client — which the bridge
            // propagates and so never reaches this catch; the guard is for the throwing case.)
            inFlight = client.sendAsync(jdkRequest, HttpResponse.BodyHandlers.ofInputStream())
            bridgeAsyncResponse(inFlight) { jdkResponse -> responseAdapter.adapt(request, jdkResponse) }
        } catch (e: Exception) {
            // The async contract is that errors arrive through the returned future. The dispatch
            // path above runs on the caller's thread and can throw — request adaptation rejecting a
            // CONNECT request, a synchronous `sendAsync` rejection, or an unexpected adapter bug
            // such as an NPE — so route any of these into a failed future instead of throwing
            // synchronously where a future-composing caller's .exceptionally/.handle would never
            // observe it. The breadth is intentional: catching `Exception` (not `RuntimeException`)
            // keeps a future adapter step's checked exception on the future too. Only `Error` (OOM
            // and other JVM-fatal conditions) is left to propagate up the caller's stack rather
            // than be packaged into a future that may never be awaited.
            //
            // If `sendAsync` already returned an in-flight exchange, the only way control reaches
            // here is `bridgeAsyncResponse` throwing before it wired that future's cancellation
            // propagation — its result is never returned, so cancel the exchange directly to release
            // its connection rather than leak it on a future nothing will await. The cancel is a
            // no-op when `inFlight` is null (the throw happened at or before dispatch).
            inFlight?.cancel(true)
            CompletableFuture.failedFuture<Response>(e)
        }
    }

    /**
     * Releases SDK-owned JDK HTTP resources. When this transport was built via [builder]:
     *
     *  1. The underlying [java.net.http.HttpClient] is closed when the JDK runtime exposes
     *     [AutoCloseable] on it. The interface was added in JDK 21 (JEP 461) along with
     *     `shutdown()` / `shutdownNow()` / `awaitTermination`; on JDK 11–20 the JDK client
     *     has no public close hook, so the close is skipped (the client's internal selector
     *     and daemon executor are released by the GC when the transport reference is
     *     dropped).
     *  2. Any [ExecutorService] the SDK created and passed to the JDK client builder is
     *     shut down. Today the [Builder] does not expose an executor knob, so this branch
     *     is unreachable from public API; the field is wired in advance so a future
     *     `Builder.executor(...)` addition can opt in without changing the close contract.
     *
     * When the caller supplied the [java.net.http.HttpClient] via [create] this method is a
     * no-op: the caller owns the client's lifecycle and may continue using it after the
     * transport is closed.
     *
     * Idempotent — repeated calls latch on the first invocation. Interrupt-safe — the close
     * path uses non-blocking shutdown semantics so `Thread.interrupt()` is preserved as-is.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        if (!owned) {
            return
        }
        // `java.net.http.HttpClient` became `AutoCloseable` in JDK 21 (JEP 461). The
        // `is AutoCloseable` check emits an `instanceof` bytecode that is valid on JDK 11
        // (where it always evaluates `false`) and on JDK 21+ (where the interface is
        // present). This keeps the module compilable on Java 11 while still releasing
        // resources promptly when the runtime supports it.
        val closeableClient: AutoCloseable? = client as? AutoCloseable
        if (closeableClient != null) {
            try {
                closeableClient.close()
            } catch (e: RuntimeException) {
                log.atWarning()
                    .event("jdkhttp.close.client.failed")
                    .cause(e)
                    .log("java.net.http.HttpClient close failed")
            }
        }
        ownedExecutor?.let { exec ->
            try {
                exec.shutdown()
            } catch (e: SecurityException) {
                log.atWarning()
                    .event("jdkhttp.close.executor.shutdown.denied")
                    .cause(e)
                    .log("JDK transport executor refused shutdown")
            }
        }
    }

    public companion object {
        /**
         * BYO factory: wrap a fully-configured [java.net.http.HttpClient]. The supplied
         * client is used verbatim — the SDK does not override connect timeout, redirect
         * policy, version, or executor, and [close] will NOT shut down this client (the
         * caller owns its lifecycle). No per-request response timeout is applied.
         */
        @JvmStatic
        public fun create(client: java.net.http.HttpClient): JdkHttpTransport =
            JdkHttpTransport(client, null, owned = false, ownedExecutor = null)

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
        ): JdkHttpTransport = JdkHttpTransport(client, responseTimeout, owned = false, ownedExecutor = null)

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
        private val log: ClientLogger = ClientLogger("org.dexpace.sdk.transport.jdkhttp.JdkHttpTransport.Builder")
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
         * library defaults. The transport owns the resulting client, so
         * [JdkHttpTransport.close] will release it on JDK 21+ (where the JDK client gained
         * [AutoCloseable]) and is a no-op on JDK 11–20 (where the JDK client has no public
         * close hook).
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
                when (httpVersion) {
                    HttpVersion.HTTP_2 -> java.net.http.HttpClient.Version.HTTP_2
                    HttpVersion.HTTP_1_1 -> java.net.http.HttpClient.Version.HTTP_1_1
                },
            )
            proxy?.let { applyProxy(clientBuilder, it) }
            // No SDK-owned executor today — the JDK client uses its internal cached daemon
            // executor when one isn't supplied via `Builder.executor`. When the Builder
            // exposes an executor knob in the future, capture it here and pass it through
            // to the [JdkHttpTransport] constructor so [close] can shut it down.
            return JdkHttpTransport(clientBuilder.build(), responseTimeout, owned = true, ownedExecutor = null)
        }

        /**
         * Wires the SDK's [ProxyOptions] into the JDK client builder. Three concerns:
         *  1. The proxy type maps onto [java.net.Proxy.Type]. The JDK client supports HTTP
         *     proxies natively; SOCKS proxies route through `java.net.Socket` for the
         *     low-level connect, but the JDK client cannot accept a SOCKS proxy on its own
         *     builder. For SOCKS, configure a [java.net.ProxySelector] system-wide and
         *     leave [ProxyOptions.type] as HTTP from the SDK builder. This adapter silently
         *     returns without applying SOCKS — it does not throw, so callers who pass SOCKS
         *     via this builder see direct connections, not failures.
         *  2. Non-proxy hosts are honoured via a [ProxySelector] subclass that returns
         *     `Proxy.NO_PROXY` for matching hosts and the configured proxy otherwise.
         *  3. Credentials (when present) are wired through a process-scoped
         *     [ProxyAuthenticator]. The JDK client picks up this authenticator at request
         *     time; it answers **only** proxy (407) challenges whose host/port match the
         *     configured proxy, returning `null` for origin-server (401) challenges so the
         *     proxy credentials never leak to an origin host. The `java.net.http` client's
         *     built-in handling of a registered `Authenticator` covers the **Basic** scheme
         *     only — this transport does **not** perform Digest proxy authentication.
         *
         * A configured [ProxyOptions.challengeHandler] is **not** honoured by this transport:
         * `java.net.http.HttpClient` exposes no per-407 hook through which a custom
         * `ChallengeHandler` (e.g. Digest) could be invoked, so the handler is dropped with a
         * loud warning rather than silently ignored. Proxy authentication falls back to Basic
         * auth derived from [ProxyOptions.username] / [ProxyOptions.password] via
         * [ProxyAuthenticator]. To authenticate against a Digest-only proxy, pass a
         * pre-configured `java.net.http.HttpClient` carrying your own `Authenticator` to
         * [create]; the SDK uses that client as-is.
         *
         * Credentials are deliberately never logged.
         */
        private fun applyProxy(
            clientBuilder: java.net.http.HttpClient.Builder,
            options: ProxyOptions,
        ) {
            if (options.challengeHandler != null) {
                log.atWarning()
                    .event("proxy.auth.challenge_handler.unsupported")
                    .log(
                        "ProxyOptions.challengeHandler is set but the JDK transport cannot invoke a " +
                            "custom ChallengeHandler: java.net.http.HttpClient exposes no per-407 hook. " +
                            "The handler is ignored; proxy auth falls back to Basic auth derived from " +
                            "ProxyOptions.username / ProxyOptions.password. For Digest proxy auth, pass a " +
                            "pre-configured java.net.http.HttpClient with your own Authenticator to create().",
                    )
            }
            if (options.type != ProxyOptions.Type.HTTP) {
                // SOCKS via the JDK client builder is not supported; silently return without
                // applying. See KDoc above for the system-wide workaround.
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
                clientBuilder.authenticator(ProxyAuthenticator(options.address, user, pass))
            }
        }
    }

    /**
     * Process-scoped [Authenticator] that answers **only** proxy (407) challenges with the
     * configured proxy credentials. The JDK invokes the installed authenticator for both
     * `PROXY` (407) and `SERVER` (401) challenges; returning the proxy credentials for a
     * `SERVER` challenge would leak them to any origin host that emits a 401, so this
     * authenticator returns `null` for every non-proxy challenge — letting origin 401s fall
     * through to the SDK's own auth handling unanswered.
     *
     * The match is tightened further: the credentials are returned only when the challenging
     * host and port also match the configured proxy address, so a second proxy in the chain
     * (or a CONNECT tunnel to a different proxy) never receives these credentials either.
     *
     * The proxy password is held as a defensive copy and never logged.
     *
     * `internal` (not `private`) only so the module's own unit test can exercise the
     * proxy-vs-origin challenge discrimination directly; it is not part of the public API.
     */
    internal class ProxyAuthenticator(
        private val proxyAddress: InetSocketAddress,
        private val username: String,
        password: String,
    ) : Authenticator() {
        private val password: CharArray = password.toCharArray()

        override fun getPasswordAuthentication(): PasswordAuthentication? {
            if (requestorType != RequestorType.PROXY) {
                // Origin-server (401) challenge — never answer with proxy credentials.
                return null
            }
            val host = requestingHost
            val port = requestingPort
            val proxyHost = proxyAddress.hostString
            if (host != null && proxyHost != null && !host.equals(proxyHost, ignoreCase = true)) {
                return null
            }
            if (port != -1 && port != proxyAddress.port) {
                return null
            }
            return PasswordAuthentication(username, password.copyOf())
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
