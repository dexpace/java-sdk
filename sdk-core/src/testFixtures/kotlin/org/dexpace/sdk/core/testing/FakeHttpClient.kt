package org.dexpace.sdk.core.testing

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.time.Duration

/**
 * Test [HttpClient] that returns a FIFO queue of canned [MockResponse]s and records every
 * request received.
 *
 * Each `@Test` should construct a fresh instance — the fake holds mutable state (the
 * response queue and the request log).
 *
 * Usage:
 * ```
 * val fake = FakeHttpClient()
 *     .enqueue { status(200).body("ok") }
 *     .enqueue { status(503).delay(Duration.ofMillis(20)) }
 *
 * val response = fake.execute(request)
 * assertEquals(1, fake.callCount)
 * ```
 *
 * Throws [IllegalStateException] from [execute] if the queue is empty when a request
 * arrives — the message names the failing URL so the missing `enqueue()` is easy to find.
 */
class FakeHttpClient : HttpClient {
    private val responses: ArrayDeque<MockResponse> = ArrayDeque()
    private val recorder: RequestRecorder = RequestRecorder()

    /** Enqueues [response] at the tail of the response queue. */
    fun enqueue(response: MockResponse): FakeHttpClient = apply { responses.addLast(response) }

    /**
     * Builder-DSL overload: configure a [MockResponse] inline.
     * `enqueue { status(200).body("ok") }`.
     */
    fun enqueue(build: MockResponse.Builder.() -> Unit): FakeHttpClient =
        enqueue(MockResponse.Builder().apply(build).build())

    /** Immutable snapshot of every request received so far. */
    val requests: List<Request> get() = recorder.snapshot()

    /** Number of requests received so far. */
    val callCount: Int get() = recorder.callCount

    /**
     * Records [request], pops the next canned [MockResponse], honors its simulated delay
     * with `Thread.sleep`, and returns the materialized [Response].
     *
     * Throws [IllegalStateException] if no response is enqueued.
     */
    override fun execute(request: Request): Response {
        recorder.record(request)
        val mock =
            responses.removeFirstOrNull()
                ?: throw IllegalStateException(
                    "FakeHttpClient: no response enqueued for request to ${request.url}",
                )
        if (mock.delay > Duration.ZERO) {
            Thread.sleep(mock.delay.toMillis())
        }
        return mock.toResponse(request)
    }
}
