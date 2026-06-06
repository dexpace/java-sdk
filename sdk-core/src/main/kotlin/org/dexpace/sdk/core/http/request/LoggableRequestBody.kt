/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.io.IoProvider
import org.dexpace.sdk.core.io.TeeSink
import java.io.IOException

/**
 * Wraps a [RequestBody] to capture the exact bytes written during [writeTo] without
 * disturbing the streaming write to the transport sink.
 *
 * The captured bytes are mirrored into an internal [Buffer] using a tee sink — every typed
 * write call is forwarded to both the transport sink and the tap buffer in a single encode
 * step. After [writeTo] runs, [snapshot] returns the captured bytes for logging.
 *
 * ## Stream-consumption safety
 *
 * The wrapped body's `writeTo` is invoked exactly once per call to this wrapper's
 * `writeTo`; the tee splits each write so the transport gets the bytes streamingly and the
 * tap retains a copy. The upstream stream is consumed once — not twice — so a single-use
 * underlying source is safe to wrap.
 *
 * If `writeTo` throws partway, [snapshot] returns the bytes that were tee'd up to the
 * failure point (useful for diagnosing a failed request).
 *
 * ## Multi-attempt usage
 *
 * Each call to [writeTo] **clears the tap first** and then captures the bytes produced by
 * that attempt. This keeps the snapshot in lock-step with the most recent write — retries
 * against a replayable [delegate] do not accumulate doubled / tripled bytes from earlier
 * attempts. When the [delegate] is single-use a second [writeTo] will still fail at the
 * delegate (its source is exhausted), and the snapshot returned by this wrapper will
 * reflect whatever was written before the failure.
 *
 * ## Replayability
 *
 * This wrapper exposes [delegate]'s replayability verbatim: [isReplayable] delegates, and
 * [toReplayable] returns a new `LoggableRequestBody` wrapping the delegate's replayable
 * form, so retry loops continue to see captured bytes for every attempt.
 *
 * ## Thread safety
 *
 * Not thread-safe. One writer at a time.
 *
 * @param delegate Underlying body whose writes are mirrored into the tap.
 * @param provider I/O provider used to allocate the tap [Buffer]. Defaults to the globally
 *   installed provider.
 */
public class LoggableRequestBody
    @JvmOverloads
    constructor(
        private val delegate: RequestBody,
        private val provider: IoProvider = Io.provider,
    ) : RequestBody() {
        // Deferred so a wrapper that is constructed but never written/snapshot'd doesn't allocate
        // a Buffer. NONE thread-safety mode matches the documented "not thread-safe" contract.
        private val tap: Buffer by lazy(LazyThreadSafetyMode.NONE) { provider.buffer() }

        override fun mediaType(): MediaType? = delegate.mediaType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun isReplayable(): Boolean = delegate.isReplayable()

        override fun toReplayable(provider: IoProvider): RequestBody =
            if (delegate.isReplayable()) {
                this
            } else {
                LoggableRequestBody(delegate.toReplayable(provider), provider)
            }

        /**
         * Mirrors [delegate]'s `writeTo` output into the tap. The tap is **cleared first** so the
         * post-write snapshot reflects only the bytes produced by this attempt — repeated calls
         * against a replayable delegate (HTTP retries) do not accumulate stale bytes from
         * earlier attempts.
         */
        @Throws(IOException::class)
        override fun writeTo(sink: BufferedSink) {
            tap.clear()
            val tee = TeeSink(sink, tap, provider)
            delegate.writeTo(tee)
        }

        /**
         * Returns the bytes captured so far. Empty array before [writeTo] has been called.
         *
         * @throws IllegalStateException if the captured size exceeds [Buffer.MAX_BYTE_ARRAY_SIZE].
         *         Call [snapshot] with a `maxBytes` cap for unbounded captures.
         */
        public fun snapshot(): ByteArray = tap.snapshot()

        /**
         * Returns up to [maxBytes] bytes captured so far. Useful for previewing large request
         * bodies in logs without materializing the entire body as a `ByteArray`. The internal
         * tap buffer remains intact.
         *
         * [maxBytes] is silently clamped to [Buffer.MAX_BYTE_ARRAY_SIZE] so callers that pass
         * `Int.MAX_VALUE` on a multi-gigabyte body still receive a safely bounded array.
         */
        public fun snapshot(maxBytes: Int): ByteArray {
            require(maxBytes >= 0) { "maxBytes must be non-negative" }
            val cap = minOf(maxBytes, Buffer.MAX_BYTE_ARRAY_SIZE)
            if (tap.size <= cap) return tap.snapshot()
            return tap.peek().readByteArray(cap.toLong())
        }
    }
