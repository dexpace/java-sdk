/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.okhttp

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.util.ProxyOptions
import org.dexpace.sdk.transport.okhttp.internal.RequestAdapter
import org.dexpace.sdk.transport.okhttp.internal.ResponseAdapter
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Response as OkResponse
import org.dexpace.sdk.core.generics.Builder as SdkBuilder

/**
 * OkHttp 5.x implementation of [HttpClient] and [AsyncHttpClient].
 *
 * Synchronous calls dispatch on the caller's thread (`Call.execute`). Asynchronous calls
 * use OkHttp's `Call.enqueue` and complete the returned [CompletableFuture] from OkHttp's
 * dispatcher pool. Cancellation of that future propagates to the underlying `Call` via
 * `Call.cancel`.
 *
 * Construction:
 * - [create] — BYO factory: hand the transport a fully-configured [OkHttpClient]
 *   (interceptors, dispatcher, SSL, connection pool, etc. are the caller's concern). The
 *   SDK does not clone or rebuild the supplied client. The transport's [close] is a no-op
 *   in this case — the caller owns the client's lifecycle.
 * - [builder] — SDK-managed config: only the knobs the SDK pipeline cares about
 *   (timeouts, proxy, followRedirects). Internally constructs an [OkHttpClient]. When the
 *   transport is built this way it owns the underlying client, so [close] shuts down its
 *   dispatcher executor, evicts its connection pool, and closes its cache (if any).
 *
 * The default for `followRedirects` is `false` because the SDK pipeline already has
 * `DefaultRedirectStep`; letting OkHttp follow redirects underneath would double-handle
 * them. For the same reason an SDK-managed client disables OkHttp's
 * `retryOnConnectionFailure`, leaving the SDK pipeline as the single retry authority.
 *
 * Both transports are immutable after construction. The underlying [OkHttpClient] is
 * documented thread-safe.
 */
public class OkHttpTransport private constructor(
    private val client: OkHttpClient,
    /**
     * `true` when the SDK created the underlying [OkHttpClient] via [Builder.build]; `false`
     * when the caller handed in their own client via [create]. Drives the close-or-no-op
     * decision in [close] per the SDK's ownership-aware lifecycle contract.
     */
    private val owned: Boolean,
) : HttpClient, AsyncHttpClient {
    private val log: ClientLogger = ClientLogger("org.dexpace.sdk.transport.okhttp.OkHttpTransport")
    private val requestAdapter: RequestAdapter = RequestAdapter(log)
    private val responseAdapter: ResponseAdapter = ResponseAdapter()

    /**
     * Latches `true` on the first [close] so subsequent calls are no-ops. `AtomicBoolean`
     * keeps the latch lock-free and virtual-thread-friendly per the SDK's concurrency rules.
     */
    private val closed: AtomicBoolean = AtomicBoolean(false)

    /**
     * Synchronously executes [request] on the caller's thread. Honours `Thread.interrupt`
     * via `InterruptedIOException`: the interrupt flag is re-asserted before the
     * exception is rethrown so callers see a consistent interrupt state.
     */
    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val okRequest = requestAdapter.adapt(request)
        val call = client.newCall(okRequest)
        val okResponse =
            try {
                call.execute()
            } catch (e: InterruptedIOException) {
                Thread.currentThread().interrupt()
                throw e
            }
        // ResponseAdapter.adapt acquires the body source and constructs the SDK Response; if any
        // step throws (e.g. `Io.provider` not installed, an unparseable Content-Type) it closes
        // `okResponse` — and its live socket — before propagating, so no extra guard is needed here.
        return responseAdapter.adapt(request, okResponse)
    }

    /**
     * Asynchronously executes [request]. Returns a [CompletableFuture] that completes with
     * the [Response] on success or completes exceptionally with the transport failure on
     * error. Cancelling the future cancels the underlying OkHttp [Call].
     */
    override fun executeAsync(request: Request): CompletableFuture<Response> {
        val okRequest = requestAdapter.adapt(request)
        val call = client.newCall(okRequest)
        val future = CompletableFuture<Response>()
        call.enqueue(
            object : Callback {
                override fun onResponse(
                    call: Call,
                    response: OkResponse,
                ) {
                    try {
                        future.complete(responseAdapter.adapt(request, response))
                    } catch (t: Throwable) {
                        // ResponseAdapter.adapt already closed the response on failure; we only
                        // need to surface the error to the future.
                        future.completeExceptionally(t)
                    }
                }

                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    future.completeExceptionally(e)
                }
            },
        )
        future.whenComplete { _, _ ->
            if (future.isCancelled) {
                call.cancel()
            }
        }
        return future
    }

    /**
     * Releases SDK-owned OkHttp resources. When this transport was built via [builder] the
     * underlying [OkHttpClient] is closed by:
     *
     *  1. `dispatcher().executorService().shutdown()` — graceful shutdown of OkHttp's
     *     dispatcher pool; in-flight calls finish before threads exit.
     *  2. `connectionPool().evictAll()` — closes idle keep-alive connections so their
     *     sockets are returned to the OS promptly.
     *  3. `cache()?.close()` — closes the response cache (if one is configured) so its
     *     file descriptors are released.
     *
     * When the caller supplied the [OkHttpClient] via [create] this method is a no-op: the
     * caller owns the client's lifecycle and may continue using it after the transport is
     * closed.
     *
     * Idempotent — repeated calls latch on the first invocation. Interrupt-safe — the
     * shutdown calls don't block waiting for executor termination, so `Thread.interrupt()`
     * is preserved as-is for callers' subsequent operations.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        if (!owned) {
            return
        }
        // `shutdown()` is non-blocking — it stops accepting new tasks and lets in-flight
        // ones finish. We deliberately do NOT call `awaitTermination` here because the
        // SDK's close() contract is non-blocking; callers who need to wait can schedule
        // shutdown via their own coordinator. `shutdownNow()` is also avoided to honour
        // OkHttp's documented graceful-drain semantics.
        try {
            client.dispatcher.executorService.shutdown()
        } catch (e: SecurityException) {
            log.atWarning()
                .event("okhttp.close.dispatcher.shutdown.denied")
                .cause(e)
                .log("OkHttp dispatcher executor refused shutdown")
        }
        try {
            client.connectionPool.evictAll()
        } catch (e: RuntimeException) {
            // evictAll iterates the pool under lock; any unexpected runtime failure must
            // not leak out of close() and prevent the cache.close() below.
            log.atWarning()
                .event("okhttp.close.connection-pool.evict.failed")
                .cause(e)
                .log("OkHttp connection-pool eviction failed")
        }
        try {
            client.cache?.close()
        } catch (e: IOException) {
            log.atWarning()
                .event("okhttp.close.cache.close.failed")
                .cause(e)
                .log("OkHttp response cache close failed")
        }
    }

    public companion object {
        /**
         * BYO factory: wrap a fully-configured [OkHttpClient]. The supplied client is used
         * verbatim — the SDK does not override `followRedirects`, timeouts, or interceptors,
         * and [close] will NOT shut down this client (the caller owns its lifecycle).
         */
        @JvmStatic
        public fun create(client: OkHttpClient): OkHttpTransport = OkHttpTransport(client, owned = false)

        /** Returns a fresh [Builder] for SDK-managed [OkHttpClient] construction. */
        @JvmStatic
        public fun builder(): Builder = Builder()
    }

    /**
     * Builder for [OkHttpTransport]. The knobs exposed here are the subset of OkHttp's own
     * configuration that the SDK pipeline understands; for anything else (custom
     * interceptors, dispatcher tuning, TLS configuration, connection pool sizing) construct
     * an [OkHttpClient] directly and pass it to [OkHttpTransport.create].
     *
     * Note: `followRedirects` defaults to `false` because the SDK has `DefaultRedirectStep`
     * — letting OkHttp follow redirects underneath would double-handle them.
     */
    public class Builder internal constructor() : SdkBuilder<OkHttpTransport> {
        private var connectTimeout: Duration? = null
        private var readTimeout: Duration? = null
        private var writeTimeout: Duration? = null
        private var callTimeout: Duration? = null
        private var proxy: ProxyOptions? = null
        private var followRedirects: Boolean = false

        /** Sets the connect timeout (TCP handshake + TLS handshake). */
        public fun connectTimeout(d: Duration): Builder =
            apply {
                this.connectTimeout = d
            }

        /** Sets the socket read timeout (longest gap between bytes arriving). */
        public fun readTimeout(d: Duration): Builder =
            apply {
                this.readTimeout = d
            }

        /** Sets the socket write timeout (longest gap between bytes being acknowledged). */
        public fun writeTimeout(d: Duration): Builder =
            apply {
                this.writeTimeout = d
            }

        /**
         * Sets the total per-call timeout (connect + write + read + handshake). OkHttp-specific.
         */
        public fun callTimeout(d: Duration): Builder =
            apply {
                this.callTimeout = d
            }

        /** Configures the proxy. Pass `null` to clear a previously-set proxy. */
        public fun proxy(p: ProxyOptions?): Builder =
            apply {
                this.proxy = p
            }

        /**
         * Whether OkHttp should follow 3xx redirects automatically. Defaults to `false`
         * because the SDK pipeline owns redirect handling via `DefaultRedirectStep`.
         */
        public fun followRedirects(enabled: Boolean): Builder =
            apply {
                this.followRedirects = enabled
            }

        /**
         * Builds an [OkHttpTransport]. The underlying [OkHttpClient] is created with the
         * knobs configured above; unconfigured knobs fall through to OkHttp's library
         * defaults. The transport owns the resulting client, so [OkHttpTransport.close]
         * will shut down its dispatcher executor and evict its connection pool.
         */
        override fun build(): OkHttpTransport {
            val okBuilder = OkHttpClient.Builder()
            connectTimeout?.let { okBuilder.connectTimeout(it.toMillis(), TimeUnit.MILLISECONDS) }
            readTimeout?.let { okBuilder.readTimeout(it.toMillis(), TimeUnit.MILLISECONDS) }
            writeTimeout?.let { okBuilder.writeTimeout(it.toMillis(), TimeUnit.MILLISECONDS) }
            callTimeout?.let { okBuilder.callTimeout(it.toMillis(), TimeUnit.MILLISECONDS) }
            proxy?.let { applyProxy(okBuilder, it) }
            okBuilder.followRedirects(followRedirects)
            okBuilder.followSslRedirects(followRedirects)
            // The SDK pipeline is the single retry authority (DefaultRetryStep / RetryStep).
            // OkHttp's built-in connection-failure retry would re-attempt the call underneath
            // it — the same double-handling the followRedirects(false) decision avoids — and,
            // because it re-writes the request body, would trip a non-replayable body's
            // consume-guard. Disable it on SDK-managed clients only; BYO clients keep their
            // own configuration.
            okBuilder.retryOnConnectionFailure(false)
            return OkHttpTransport(okBuilder.build(), owned = true)
        }

        /**
         * Wires the SDK's [ProxyOptions] into OkHttp's builder. Three concerns:
         *  1. The proxy type maps onto [java.net.Proxy.Type] (HTTP / SOCKS).
         *  2. Non-proxy hosts are honoured via a [ProxySelector] that returns
         *     `Proxy.NO_PROXY` for matching hosts and the configured proxy otherwise.
         *  3. Credentials (when present) are wired through [Authenticator] —
         *     a `Proxy-Authorization: Basic …` is added on 407 challenges.
         *
         * Credentials are deliberately never logged; only the proxy host/port appears in
         * the DEBUG log.
         */
        private fun applyProxy(
            okBuilder: OkHttpClient.Builder,
            options: ProxyOptions,
        ) {
            val javaType =
                when (options.type) {
                    ProxyOptions.Type.HTTP -> Proxy.Type.HTTP
                    ProxyOptions.Type.SOCKS4, ProxyOptions.Type.SOCKS5 -> Proxy.Type.SOCKS
                }
            val proxy = Proxy(javaType, options.address)
            if (options.nonProxyHosts.isEmpty() && !options.bypassAllHosts) {
                okBuilder.proxy(proxy)
            } else {
                okBuilder.proxySelector(
                    NonProxyHostSelector(options, proxy),
                )
            }
            val user = options.username
            val pass = options.password
            if (user != null && pass != null) {
                okBuilder.proxyAuthenticator(
                    Authenticator { _, response ->
                        // Basic auth challenge handler. Returns null when the credential
                        // has already been supplied (avoids an infinite retry loop on a
                        // wrong password) — the response itself carries the prior request.
                        val priorRequest = response.request
                        if (priorRequest.header("Proxy-Authorization") != null) {
                            null
                        } else {
                            val credential = Credentials.basic(user, pass)
                            priorRequest.newBuilder()
                                .header("Proxy-Authorization", credential)
                                .build()
                        }
                    },
                )
            }
        }
    }

    /**
     * Wraps OkHttp's default [ProxySelector] semantics so non-proxy-host patterns from
     * [ProxyOptions] are honoured: hosts the [ProxyOptions] would bypass return
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
