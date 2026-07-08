/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.client

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestOptions
import org.dexpace.sdk.core.http.response.Response
import java.io.IOException

/**
 * Transport SPI: the seam between the SDK's request/response models and a concrete HTTP transport
 * (OkHttp, Apache HttpClient, the JDK HTTP client, etc.).
 *
 * The SDK itself is an HTTP-client *toolkit*, not an HTTP client — consuming libraries plug in a
 * transport by implementing this single-method interface. Pipelines, retry, auth, and logging are
 * all built on top; this interface only owns "send one [Request], get one [Response]".
 *
 * ## Thread-safety
 * Implementations are expected to be safe for concurrent calls from multiple threads. Per-request
 * state must be confined to local variables or to the returned [Response] graph.
 *
 * ## Cancellation
 * Implementations should honor `Thread.interrupt()` on blocking I/O and propagate the interrupt
 * (preserving status) per the SDK's cancellation contract (see `docs/architecture.md`).
 *
 * ## Lifecycle
 * [HttpClient] extends [AutoCloseable]. The default [close] is a no-op so SAM literals
 * (`HttpClient { request -> ... }`) and existing implementations remain valid without modification.
 *
 * Transport implementations that own background threads, connection pools, executors, or other
 * native resources should override [close] to release them. The contract is:
 *
 *  1. **Idempotent.** Calling [close] more than once must be safe.
 *  2. **Ownership-aware.** Only release resources the transport itself created. User-supplied
 *     dependencies (e.g. a caller-built `OkHttpClient` or `java.util.concurrent.Executor`) must
 *     not be touched — the caller owns their lifecycle.
 *  3. **Interrupt-safe.** If the close path blocks on `executorService.shutdown()` or similar, it
 *     must respect `Thread.interrupt()` per the SDK's cancellation contract.
 *
 * After [close] returns, calls to [execute] have undefined behaviour — implementations may throw
 * or return an error response; the SDK does not mandate a specific failure mode.
 */
public fun interface HttpClient : AutoCloseable {
    /**
     * Sends [request] over the underlying transport and returns the matching [Response]. The
     * response body is not pre-buffered — callers are responsible for closing it.
     *
     * Declares `throws IOException` (the SDK's canonical transport failure
     * `org.dexpace.sdk.core.http.response.exception.NetworkException` extends [java.io.IOException])
     * so a Java-authored transport may declare and throw it and a Java caller can
     * `catch (IOException)` at this call site.
     */
    @Throws(IOException::class)
    public fun execute(request: Request): Response

    /**
     * Sends [request] with per-call [options] applied. The default implementation ignores
     * [options] and delegates to [execute], so this stays a single-abstract-method `fun interface`
     * and existing implementations (SAM literals, transports written before per-request overrides)
     * keep working unchanged. Transports that honour per-call overrides — a timeout, a retry
     * budget, tags — override this method to read [options]; see the reference transports for the
     * per-call-timeout wiring. Pipelines thread the caller's options into this overload.
     */
    @Throws(IOException::class)
    public fun execute(
        request: Request,
        options: RequestOptions,
    ): Response = execute(request)

    /**
     * Releases any resources held by this transport. The default is a no-op (so SAM literals and
     * lightweight wrappers need not implement it); transports that own threads, pools, or executors
     * must override it — see the class-level "Lifecycle" KDoc for the contract.
     */
    public override fun close() {
        // No-op default. Transports with owned resources override this.
    }
}
