package org.dexpace.sdk.core.client

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response

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
     */
    public fun execute(request: Request): Response

    /**
     * Releases any resources held by this transport. The default implementation is a no-op so
     * SAM literals and lightweight client wrappers do not need to implement [AutoCloseable.close]
     * explicitly. Transport implementations that own background threads, connection pools, or
     * executors must override this method; see the class-level "Lifecycle" KDoc for the contract.
     */
    public override fun close() {
        // No-op default. Transports with owned resources override this.
    }
}
