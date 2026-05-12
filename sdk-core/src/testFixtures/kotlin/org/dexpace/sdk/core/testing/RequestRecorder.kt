package org.dexpace.sdk.core.testing

import org.dexpace.sdk.core.http.request.Request

/**
 * Thread-safe captured-request log used by [FakeHttpClient] and any other test fixture
 * that needs to observe the exact request a pipeline produced.
 *
 * Backed by a `mutableListOf` guarded with `synchronized` — adequate for the modest
 * concurrency seen in test scenarios. [snapshot] returns an immutable copy so callers
 * can iterate without worrying about concurrent mutation.
 */
class RequestRecorder {
    private val list: MutableList<Request> = mutableListOf()

    /** Appends [request] to the log. Safe to call concurrently. */
    fun record(request: Request) {
        synchronized(list) { list.add(request) }
    }

    /** Returns an immutable snapshot of the captured requests in insertion order. */
    fun snapshot(): List<Request> = synchronized(list) { list.toList() }

    /** Number of requests captured so far. */
    val callCount: Int get() = synchronized(list) { list.size }
}
