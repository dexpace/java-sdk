/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

import org.dexpace.sdk.core.http.common.Headers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the [HttpTracer] vocabulary. Three angles:
 *
 *  1. [NoopHttpTracer] accepts every callback and never throws — covers the
 *     "tracing disabled" path so adding new events doesn't regress that case.
 *  2. A hand-rolled recording tracer captures each event into a list; the test
 *     asserts the canonical happy-path sequence
 *     (`operationStarted -> attemptStarted -> requestUrlResolved -> requestSent
 *     -> responseHeadersReceived -> responseReceived -> operationSucceeded`).
 *  3. The failure path captures [HttpTracer.attemptFailed] and
 *     [HttpTracer.attemptRetriesExhausted].
 */
class HttpTracerTest {
    /** Compact event holder so the test can compare sequences as data. */
    private sealed class Event {
        data class OperationStarted(val operationName: String?) : Event()

        object OperationSucceeded : Event()

        data class OperationFailed(val error: Throwable) : Event()

        data class AttemptStarted(val attemptNumber: Int) : Event()

        data class AttemptFailed(
            val error: Throwable,
            val nextDelayMillis: Long?,
        ) : Event()

        data class AttemptRetriesExhausted(val error: Throwable) : Event()

        data class RequestUrlResolved(val url: String) : Event()

        data class RequestSent(val byteCount: Long?) : Event()

        data class ResponseHeadersReceived(
            val status: Int,
            val headers: Headers,
        ) : Event()

        data class ResponseReceived(val byteCount: Long?) : Event()

        data class ConnectionAcquired(
            val host: String,
            val port: Int,
        ) : Event()
    }

    /**
     * Recording tracer used by the sequence tests. Keeps every call as a typed [Event]
     * so the test asserts both order and payload.
     */
    private class RecordingTracer : HttpTracer {
        val events: MutableList<Event> = mutableListOf()

        override fun operationStarted(operationName: String?) {
            events.add(Event.OperationStarted(operationName))
        }

        override fun operationSucceeded() {
            events.add(Event.OperationSucceeded)
        }

        override fun operationFailed(error: Throwable) {
            events.add(Event.OperationFailed(error))
        }

        override fun attemptStarted(attemptNumber: Int) {
            events.add(Event.AttemptStarted(attemptNumber))
        }

        override fun attemptFailed(
            error: Throwable,
            nextDelayMillis: Long?,
        ) {
            events.add(Event.AttemptFailed(error, nextDelayMillis))
        }

        override fun attemptRetriesExhausted(error: Throwable) {
            events.add(Event.AttemptRetriesExhausted(error))
        }

        override fun requestUrlResolved(url: String) {
            events.add(Event.RequestUrlResolved(url))
        }

        override fun requestSent(byteCount: Long?) {
            events.add(Event.RequestSent(byteCount))
        }

        override fun responseHeadersReceived(
            status: Int,
            headers: Headers,
        ) {
            events.add(Event.ResponseHeadersReceived(status, headers))
        }

        override fun responseReceived(byteCount: Long?) {
            events.add(Event.ResponseReceived(byteCount))
        }

        override fun connectionAcquired(
            host: String,
            port: Int,
        ) {
            events.add(Event.ConnectionAcquired(host, port))
        }
    }

    @Test
    fun `NoopHttpTracer accepts every callback without throwing`() {
        // The whole point of the no-op: tracer events MUST be safe to call even when no
        // tracing backend is installed. Iterate the full vocabulary and assert nothing
        // throws.
        val tracer: HttpTracer = NoopHttpTracer
        tracer.operationStarted("op")
        tracer.operationStarted(null)
        tracer.attemptStarted(1)
        tracer.requestUrlResolved("https://api.dexpace.example/v1/widgets")
        tracer.connectionAcquired("api.dexpace.example", 443)
        tracer.requestSent(123L)
        tracer.requestSent(null)
        tracer.responseHeadersReceived(200, Headers.builder().add("content-type", "application/json").build())
        tracer.responseReceived(456L)
        tracer.responseReceived(null)
        tracer.attemptFailed(RuntimeException("transport"), 250L)
        tracer.attemptFailed(RuntimeException("transport"), null)
        tracer.attemptRetriesExhausted(RuntimeException("exhausted"))
        tracer.operationSucceeded()
        tracer.operationFailed(RuntimeException("final"))
    }

    @Test
    fun `recording tracer captures a typical successful request lifecycle`() {
        // Asserts the canonical event order for a one-attempt success. The retry layer
        // (WU-3) extends this sequence with attempt failures; the transport adapters wire
        // in the network events. This test pins the contract so future wiring agrees on
        // the order.
        val tracer = RecordingTracer()
        val responseHeaders = Headers.builder().add("content-type", "application/json").build()

        tracer.operationStarted("GetWidget")
        tracer.attemptStarted(1)
        tracer.requestUrlResolved("https://api.dexpace.example/v1/widgets/42")
        tracer.connectionAcquired("api.dexpace.example", 443)
        tracer.requestSent(0L)
        tracer.responseHeadersReceived(200, responseHeaders)
        tracer.responseReceived(789L)
        tracer.operationSucceeded()

        val expected =
            listOf(
                Event.OperationStarted("GetWidget"),
                Event.AttemptStarted(1),
                Event.RequestUrlResolved("https://api.dexpace.example/v1/widgets/42"),
                Event.ConnectionAcquired("api.dexpace.example", 443),
                Event.RequestSent(0L),
                Event.ResponseHeadersReceived(200, responseHeaders),
                Event.ResponseReceived(789L),
                Event.OperationSucceeded,
            )
        assertEquals(expected, tracer.events)
    }

    @Test
    fun `recording tracer captures attemptFailed and attemptRetriesExhausted on the failure path`() {
        // Asserts the retry-exhausted shape: first attempt fails with a known next-delay,
        // second attempt fails again, retries-exhausted fires, then operationFailed
        // delivers the same throwable to the caller.
        val tracer = RecordingTracer()
        val transientError = RuntimeException("transient")
        val terminalError = RuntimeException("terminal")

        tracer.operationStarted("CreateOrder")
        tracer.attemptStarted(1)
        tracer.requestUrlResolved("https://api.dexpace.example/v1/orders")
        tracer.attemptFailed(transientError, 250L)
        tracer.attemptStarted(2)
        tracer.requestUrlResolved("https://api.dexpace.example/v1/orders")
        tracer.attemptFailed(terminalError, null)
        tracer.attemptRetriesExhausted(terminalError)
        tracer.operationFailed(terminalError)

        val expected =
            listOf(
                Event.OperationStarted("CreateOrder"),
                Event.AttemptStarted(1),
                Event.RequestUrlResolved("https://api.dexpace.example/v1/orders"),
                Event.AttemptFailed(transientError, 250L),
                Event.AttemptStarted(2),
                Event.RequestUrlResolved("https://api.dexpace.example/v1/orders"),
                Event.AttemptFailed(terminalError, null),
                Event.AttemptRetriesExhausted(terminalError),
                Event.OperationFailed(terminalError),
            )
        assertEquals(expected, tracer.events)
    }

    @Test
    fun `recording tracer can override only the events it cares about`() {
        // Confirms the "default no-op" contract: implementations may override a subset of
        // methods and the rest stay as inherited no-ops. A tracer that only cares about
        // attempt counters should not be forced to implement the rest of the vocabulary.
        var attemptCount = 0
        val tracer =
            object : HttpTracer {
                override fun attemptStarted(attemptNumber: Int) {
                    attemptCount = attemptNumber
                }
            }

        // Calls to non-overridden methods must inherit the default no-op (i.e. compile and
        // return without effect).
        tracer.operationStarted("op")
        tracer.requestSent(99L)
        tracer.responseReceived(null)
        tracer.operationSucceeded()

        // The single overridden method must still fire.
        tracer.attemptStarted(3)
        assertEquals(3, attemptCount)
    }

    @Test
    fun `NoopHttpTracer is a stable singleton`() {
        // The shared instance must compare equal to itself across reads — guards against
        // a refactor that accidentally promotes the object to a class.
        val a: HttpTracer = NoopHttpTracer
        val b: HttpTracer = NoopHttpTracer
        assertSame(a, b)
        assertTrue(a === NoopHttpTracer)
    }
}
