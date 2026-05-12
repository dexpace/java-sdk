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
 */
interface HttpClient {
    /**
     * Sends [request] over the underlying transport and returns the matching [Response]. The
     * response body is not pre-buffered — callers are responsible for closing it.
     */
    fun execute(request: Request): Response
}
