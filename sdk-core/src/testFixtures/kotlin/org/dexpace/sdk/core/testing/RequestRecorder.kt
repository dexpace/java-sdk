/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.testing

import org.dexpace.sdk.core.http.request.Request
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe captured-request log used by [FakeHttpClient] and any other test fixture
 * that needs to observe the exact request a pipeline produced.
 *
 * Backed by a `mutableListOf` guarded with a [ReentrantLock] — adequate for the modest
 * concurrency seen in test scenarios. [snapshot] returns an immutable copy so callers
 * can iterate without worrying about concurrent mutation.
 */
class RequestRecorder {
    private val lock: ReentrantLock = ReentrantLock()
    private val list: MutableList<Request> = mutableListOf()

    /** Appends [request] to the log. Safe to call concurrently. */
    fun record(request: Request) {
        lock.withLock { list.add(request) }
    }

    /** Returns an immutable snapshot of the captured requests in insertion order. */
    fun snapshot(): List<Request> = lock.withLock { list.toList() }

    /** Number of requests captured so far. */
    val callCount: Int get() = lock.withLock { list.size }
}
