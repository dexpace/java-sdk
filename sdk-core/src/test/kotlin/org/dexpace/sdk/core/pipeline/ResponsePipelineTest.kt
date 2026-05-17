package org.dexpace.sdk.core.pipeline

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.pipeline.step.RequestPipelineStep
import org.dexpace.sdk.core.pipeline.step.ResponsePipelineStep
import org.dexpace.sdk.core.pipeline.step.ResponseRecoveryStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Covers WU-1 acceptance criteria for the recovery-aware response pipeline. Tests are
 * self-contained: a small fake [HttpClient] is defined inline rather than depending on the
 * testFixtures `FakeHttpClient` so this test file stays the canonical reference for the
 * pipeline contract.
 */
class ResponsePipelineTest {
    // region -- test fakes --

    /**
     * One entry the fake transport can yield: either a [Response] to return or a [Throwable]
     * to throw. A plain sealed pair avoids `kotlin.Result`'s vararg restriction in tests.
     */
    private sealed class TransportEntry {
        data class Ok(val response: Response) : TransportEntry()

        data class Err(val error: Throwable) : TransportEntry()
    }

    /**
     * Minimal [HttpClient] for tests — returns a queued [Response] or throws a queued
     * [Throwable]. Each call consumes one entry from the queue.
     */
    private class FakeHttpClient(
        entries: List<TransportEntry> = emptyList(),
    ) : HttpClient {
        private val queue: ArrayDeque<TransportEntry> = ArrayDeque(entries)
        val received: MutableList<Request> = ArrayList()

        override fun execute(request: Request): Response {
            received.add(request)
            val next =
                if (queue.isEmpty()) {
                    error("FakeHttpClient: no result queued for request ${request.url}")
                } else {
                    queue.removeFirst()
                }
            return when (next) {
                is TransportEntry.Ok -> next.response
                is TransportEntry.Err -> throw next.error
            }
        }
    }

    private fun request(): Request =
        Request.builder()
            .url("https://api.example.com/resource")
            .method(Method.GET)
            .build()

    private fun okResponseFor(request: Request): Response =
        Response.builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .build()

    private fun ctx(): DispatchContext = DispatchContext.default()

    // endregion

    // region -- WU-1 acceptance tests --

    @Test
    fun `all steps no-op response passes through unchanged`() {
        val req = request()
        val transportResponse = okResponseFor(req)
        val transport = FakeHttpClient(listOf(TransportEntry.Ok(transportResponse)))

        val pipeline =
            ExecutionPipeline(
                httpClient = transport,
                requestPipeline = RequestPipeline(),
                responsePipeline = ResponsePipeline(),
            )

        val out = pipeline.execute(req, ctx())

        assertSame(transportResponse, out, "Response should be the exact transport response")
        assertEquals(1, transport.received.size)
        assertSame(req, transport.received[0])
    }

    @Test
    fun `recovery step rescues a Failure into a Success`() {
        val req = request()
        val rescued = okResponseFor(req)
        val transport = FakeHttpClient(listOf(TransportEntry.Err(IOException("transport down"))))

        val rescue =
            ResponseRecoveryStep { outcome ->
                when (outcome) {
                    is ResponseOutcome.Success -> outcome
                    is ResponseOutcome.Failure -> ResponseOutcome.Success(rescued)
                }
            }

        val pipeline =
            ExecutionPipeline(
                httpClient = transport,
                responsePipeline = ResponsePipeline(recoverySteps = listOf(rescue)),
            )

        val out = pipeline.execute(req, ctx())
        assertSame(rescued, out, "Recovery step should rescue the transport failure")
    }

    @Test
    fun `recovery step passes through Failure stays Failure`() {
        val req = request()
        val transportError = IOException("transport down")
        val transport = FakeHttpClient(listOf(TransportEntry.Err(transportError)))

        // Pass-through: returns the outcome unchanged.
        val passthrough = ResponseRecoveryStep { outcome -> outcome }

        val pipeline =
            ExecutionPipeline(
                httpClient = transport,
                responsePipeline = ResponsePipeline(recoverySteps = listOf(passthrough)),
            )

        val thrown = assertThrows(IOException::class.java) { pipeline.execute(req, ctx()) }
        assertSame(transportError, thrown, "Failure must be propagated unchanged")
    }

    @Test
    fun `recovery step rethrows replaces the throwable`() {
        val req = request()
        val originalError = IOException("transport down")
        val replacementError = IllegalStateException("mapped")
        val transport = FakeHttpClient(listOf(TransportEntry.Err(originalError)))

        val replace =
            ResponseRecoveryStep { outcome ->
                when (outcome) {
                    is ResponseOutcome.Success -> outcome
                    is ResponseOutcome.Failure -> ResponseOutcome.Failure(replacementError)
                }
            }

        val pipeline =
            ExecutionPipeline(
                httpClient = transport,
                responsePipeline = ResponsePipeline(recoverySteps = listOf(replace)),
            )

        val thrown = assertThrows(IllegalStateException::class.java) { pipeline.execute(req, ctx()) }
        assertSame(replacementError, thrown, "Replacement throwable should be surfaced")
    }

    @Test
    fun `response step throws routed to subsequent recovery step`() {
        val req = request()
        val transportResponse = okResponseFor(req)
        val transport = FakeHttpClient(listOf(TransportEntry.Ok(transportResponse)))

        val responseStepError = RuntimeException("response step failed")
        val failingResponseStep =
            ResponsePipelineStep { _, _ ->
                throw responseStepError
            }

        var recoverySawError: Throwable? = null
        val recoverFromResponseStep =
            ResponseRecoveryStep { outcome ->
                when (outcome) {
                    is ResponseOutcome.Success -> outcome
                    is ResponseOutcome.Failure -> {
                        recoverySawError = outcome.error
                        ResponseOutcome.Success(transportResponse)
                    }
                }
            }

        val pipeline =
            ExecutionPipeline(
                httpClient = transport,
                responsePipeline =
                    ResponsePipeline(
                        responseSteps = listOf(failingResponseStep),
                        recoverySteps = listOf(recoverFromResponseStep),
                    ),
            )

        val out = pipeline.execute(req, ctx())

        assertSame(transportResponse, out)
        assertNotNull(recoverySawError, "Recovery step should have observed the response-step exception")
        assertSame(responseStepError, recoverySawError)
    }

    @Test
    fun `request step throws routed through recovery chain`() {
        val req = request()
        val requestStepError = IllegalArgumentException("bad request")

        val failingRequestStep =
            RequestPipelineStep { _, _ ->
                throw requestStepError
            }

        // Track whether the transport was invoked — it must NOT be, since the request step
        // failed.
        val transport = FakeHttpClient(emptyList()) // empty queue: any call would throw

        var recoverySawError: Throwable? = null
        val recoverFromRequestStep =
            ResponseRecoveryStep { outcome ->
                when (outcome) {
                    is ResponseOutcome.Success -> outcome
                    is ResponseOutcome.Failure -> {
                        recoverySawError = outcome.error
                        // Pass through as failure — we only want to assert observation.
                        outcome
                    }
                }
            }

        val pipeline =
            ExecutionPipeline(
                httpClient = transport,
                requestPipeline = RequestPipeline(listOf(failingRequestStep)),
                responsePipeline = ResponsePipeline(recoverySteps = listOf(recoverFromRequestStep)),
            )

        val thrown = assertThrows(IllegalArgumentException::class.java) { pipeline.execute(req, ctx()) }
        assertSame(requestStepError, thrown, "The original request-step error must propagate")
        assertSame(
            requestStepError,
            recoverySawError,
            "Recovery step must observe request-step exceptions — Airbyte's BeforeRequest bypass bug must not recur",
        )
        assertTrue(transport.received.isEmpty(), "Transport must not be called when a request step fails")
    }

    @Test
    fun `transport throws routed through recovery chain`() {
        val req = request()
        val transportError = IOException("connect refused")
        val transport = FakeHttpClient(listOf(TransportEntry.Err(transportError)))

        var recoverySawError: Throwable? = null
        val observeTransportFailure =
            ResponseRecoveryStep { outcome ->
                when (outcome) {
                    is ResponseOutcome.Success -> outcome
                    is ResponseOutcome.Failure -> {
                        recoverySawError = outcome.error
                        outcome
                    }
                }
            }

        val pipeline =
            ExecutionPipeline(
                httpClient = transport,
                responsePipeline = ResponsePipeline(recoverySteps = listOf(observeTransportFailure)),
            )

        val thrown = assertThrows(IOException::class.java) { pipeline.execute(req, ctx()) }
        assertSame(transportError, thrown)
        assertSame(transportError, recoverySawError, "Recovery must observe transport failures")
    }

    // endregion

    // region -- ResponseOutcome helpers (small sanity coverage) --

    @Test
    fun `ResponseOutcome helpers report success and failure correctly`() {
        val req = request()
        val resp = okResponseFor(req)
        val success: ResponseOutcome = ResponseOutcome.Success(resp)
        val error = IOException("nope")
        val failure: ResponseOutcome = ResponseOutcome.Failure(error)

        assertTrue(success.isSuccess())
        assertTrue(!success.isFailure())
        assertSame(resp, success.getOrNull())
        assertEquals(null, success.errorOrNull())

        assertTrue(failure.isFailure())
        assertTrue(!failure.isSuccess())
        assertEquals(null, failure.getOrNull())
        assertSame(error, failure.errorOrNull())

        val folded =
            failure.fold(
                onSuccess = { "ok" },
                onFailure = { "err:${it.message}" },
            )
        assertEquals("err:nope", folded)
    }

    @Test
    fun `recovery steps run in order each observing the previous outcome`() {
        val req = request()
        val rescued = okResponseFor(req)
        val transport = FakeHttpClient(listOf(TransportEntry.Err(IOException("first failure"))))

        val observed = mutableListOf<String>()

        val tag1 =
            ResponseRecoveryStep { outcome ->
                observed.add("step1:${outcome::class.simpleName}")
                outcome
            }
        val rescue =
            ResponseRecoveryStep { outcome ->
                observed.add("step2:${outcome::class.simpleName}")
                if (outcome is ResponseOutcome.Failure) ResponseOutcome.Success(rescued) else outcome
            }
        val tag3 =
            ResponseRecoveryStep { outcome ->
                observed.add("step3:${outcome::class.simpleName}")
                outcome
            }

        val pipeline =
            ExecutionPipeline(
                httpClient = transport,
                responsePipeline = ResponsePipeline(recoverySteps = listOf(tag1, rescue, tag3)),
            )

        val out = pipeline.execute(req, ctx())

        assertSame(rescued, out)
        assertEquals(listOf("step1:Failure", "step2:Failure", "step3:Success"), observed)
    }

    // endregion
}
