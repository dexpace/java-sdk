/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.io.IoProvider
import org.dexpace.sdk.core.io.Source
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Wraps a [ResponseBody] to provide repeatable reads of a bounded capture prefix.
 *
 * On the first call to [source], [snapshot], or [captureException], up to `maxCaptureBytes`
 * bytes of the wrapped body are drained into an internal [Buffer]. Two regimes follow:
 *
 * - **Body fits within the cap** (the default, unlimited path): the whole body is captured,
 *   the delegate is closed, and every subsequent [source] call returns a fresh non-consuming
 *   view (`peek()`) of the same bytes — fully repeatable, exactly as before.
 * - **Body exceeds the cap**: only the prefix is buffered and the delegate is **left open**.
 *   [source] then returns a **one-shot** stream that first replays the captured prefix and
 *   then continues from the still-live delegate tail, so the caller receives the complete
 *   body. Because the tail can be read only once, calling [source] a second time on an
 *   over-cap body throws [IllegalStateException].
 *
 * The default `maxCaptureBytes` is [Long.MAX_VALUE], so the unlimited (fully-repeatable)
 * behavior is preserved for callers that construct the wrapper directly. The instrumentation
 * steps construct a [bounded] wrapper capped at the configured body-preview size so logging
 * never buffers more than a preview into memory.
 *
 * ## Failure semantics
 *
 * A network error during drain does not silently truncate. The wrapper retains whatever
 * bytes were read before the failure and caches the exception:
 *
 * - [source] **re-throws** the cached exception every time it is called, so callers see the
 *   failure deterministically (no silent zero-byte body).
 * - [snapshot] **always returns** the partial bytes — useful for post-mortem logging that
 *   wants to record "what we got" alongside the exception.
 * - [captureException] surfaces the cached exception (or null) for callers that want to
 *   branch on it without consuming the body.
 *
 * ## Memory
 *
 * At most `maxCaptureBytes` bytes are buffered in memory. The default unlimited cap buffers
 * the whole body, so the default path is only suitable for bodies that fit comfortably in
 * RAM. For multi-MB or streaming bodies whose only consumer is logging, use [bounded] (the
 * instrumentation steps do) so memory stays bounded while the caller still streams the rest.
 *
 * ## Thread safety
 *
 * The drain itself is single-fire — concurrent first calls to [source]/[snapshot] are
 * serialized on an internal lock so the upstream is read exactly once. After a full drain the
 * captured buffer is read-only from this wrapper's perspective; concurrent readers each
 * receive a separate `peek()` view, which is safe as long as no external code mutates the
 * captured buffer. The one-shot over-cap [source] is single-consumer by contract.
 *
 * @param delegate Body whose bytes will be captured and re-served.
 * @param provider I/O provider used to allocate the capture [Buffer]. Defaults to the
 *   globally installed provider.
 */
public class LoggableResponseBody
    internal constructor(
        private val delegate: ResponseBody,
        private val provider: IoProvider,
        private val maxCaptureBytes: Long,
    ) : ResponseBody() {
        /**
         * Public surface: an unbounded wrapper that captures the entire body (fully repeatable).
         * Equivalent to `bounded(delegate, provider, Long.MAX_VALUE)`.
         */
        @JvmOverloads
        public constructor(
            delegate: ResponseBody,
            provider: IoProvider = Io.provider,
        ) : this(delegate, provider, Long.MAX_VALUE)

        private val lock = ReentrantLock()

        @Volatile
        private var captured: Buffer? = null

        @Volatile
        private var drainError: Throwable? = null

        @Volatile
        private var closed = false

        // True once the whole body fit within the cap and was drained into [captured]; the drain
        // path closes the source via `.use {}` in that case, so [source] can hand out repeatable
        // peek() views and close() must not double-close the delegate.
        @Volatile
        private var fullyCaptured = false

        // Set when the cap was hit with bytes still pending: the live delegate source is retained
        // so the one-shot [source] can replay the captured prefix then continue from this tail.
        @Volatile
        private var liveTail: BufferedSource? = null

        // Guards the single-consumer contract for the over-cap one-shot source.
        private val tailHandedOut = AtomicBoolean(false)

        // Set after the delegate (and, by ownership, its source) has been closed, so a subsequent
        // close() must not close it again — some sockets / streams throw on double-close.
        @Volatile
        private var delegateClosed = false

        override fun mediaType(): MediaType? = delegate.mediaType()

        /**
         * Returns the captured size only when the body was fully captured within the cap;
         * otherwise the delegate's reported length (the true length), since the capture is just
         * a bounded prefix.
         */
        override fun contentLength(): Long =
            if (fullyCaptured) captured?.size ?: delegate.contentLength() else delegate.contentLength()

        /**
         * Returns a view of the captured body. Drains (up to the cap) on first call. If the drain
         * failed (partial capture), this method re-throws the captured exception every time —
         * callers see the failure rather than a silent empty source.
         *
         * When the body fit within the cap the returned source is a fresh non-consuming `peek()`
         * view and may be requested repeatedly. When the body exceeded the cap the returned source
         * is a **one-shot** stream that replays the captured prefix then continues from the live
         * delegate tail; a second call on an over-cap body throws [IllegalStateException].
         */
        override fun source(): BufferedSource {
            val buf = ensureCaptured()
            drainError?.let { throw it.asIOException() }
            if (fullyCaptured) return buf.peek()
            check(tailHandedOut.compareAndSet(false, true)) {
                "LoggableResponseBody capture exceeded maxCaptureBytes; source() over the live tail " +
                    "is single-use and was already consumed."
            }
            // On the over-cap path the live tail is always set by the time control reaches here:
            // drainError throws above and fullyCaptured returns above, so the only remaining regime
            // is "cap hit with bytes pending", which always assigns liveTail.
            val tail = checkNotNull(liveTail) { "over-cap source() reached with no live tail to replay" }
            return provider.bufferedSource(PrefixThenTailSource(buf.peek(), tail))
        }

        /**
         * Returns the captured (bounded) body bytes. Returns whatever was read before any drain
         * failure — a drain error does not prevent this method from returning the partial capture.
         * Use [captureException] alongside this method when you need to know whether the capture
         * was complete.
         *
         * @throws IllegalStateException if the captured body exceeds [Buffer.MAX_BYTE_ARRAY_SIZE].
         *         Use [snapshot] with a `maxBytes` cap for bodies that may be unbounded.
         */
        public fun snapshot(): ByteArray = ensureCaptured().snapshot()

        /**
         * Returns up to [maxBytes] bytes of the captured prefix. Use for log previews of large
         * bodies — avoids materializing the entire body as a `ByteArray`. The captured buffer
         * itself remains intact. Returns the partial prefix on failure (does not throw).
         *
         * [maxBytes] is silently clamped to [Buffer.MAX_BYTE_ARRAY_SIZE] so callers that pass
         * `Int.MAX_VALUE` on a multi-gigabyte body still receive a safely bounded array.
         */
        public fun snapshot(maxBytes: Int): ByteArray {
            require(maxBytes >= 0) { "maxBytes must be non-negative" }
            val cap = minOf(maxBytes, Buffer.MAX_BYTE_ARRAY_SIZE)
            val buf = ensureCaptured()
            if (buf.size <= cap) return buf.snapshot()
            return buf.peek().readByteArray(cap.toLong())
        }

        /**
         * Returns the exception that aborted the drain, or null if the body was captured
         * successfully (or has not been read yet). Calling this method does not trigger a drain.
         */
        public val captureException: Throwable? get() = drainError

        @Throws(IOException::class)
        override fun close() {
            lock.withLock {
                if (closed) return
                closed = true
                // The drain path closes the underlying source on a full capture; in that case we
                // must not close the delegate again — some sockets / streams throw on double-close.
                // On an over-cap capture the delegate is still open and must be closed here; the
                // delegate owns the live-tail source, so closing the delegate releases it too (we
                // must NOT close the live tail separately, or the source would be closed twice).
                // Both this path and the over-cap one-shot source funnel through the same guard so
                // whichever fires first wins and the second is a no-op.
                closeDelegateOnce()
                // The captured buffer intentionally survives close — it holds in-memory bytes
                // and no network resources, and is needed for post-mortem snapshot logging.
            }
        }

        /**
         * Closes the delegate (and, by ownership, its source) at most once. Both [close] and the
         * over-cap one-shot source close the same underlying source on the over-cap path; routing
         * both through this single guard prevents a double-close on a delegate whose [source]
         * returns the same instance and whose [close] closes it (some sockets / streams throw on
         * double-close). Callers must hold [lock].
         *
         * The guard flips in a `finally`, so a delegate whose `close()` throws is still marked
         * closed: the failing close propagates to the caller, but the delegate is never closed a
         * second time. This matches the drain path, which likewise marks the delegate closed after
         * a best-effort source close so a later [close] is a no-op.
         */
        @Throws(IOException::class)
        private fun closeDelegateOnce() {
            if (delegateClosed) return
            try {
                delegate.close()
            } finally {
                delegateClosed = true
            }
        }

        private fun ensureCaptured(): Buffer {
            captured?.let { return it }
            return lock.withLock {
                captured?.let { return it }
                check(!closed) {
                    "LoggableResponseBody was closed before the body was read; nothing to capture."
                }
                drainAndCache()
            }
        }

        /**
         * Drains at most [maxCaptureBytes] bytes of the delegate body into an in-memory [Buffer]
         * and caches the result.
         *
         * The capture buffer is allocated inside the try block so that a provider failure is also
         * caught and cached in [drainError]. When `delegate.source()` throws before the read loop
         * the source was never obtained, so the delegate remains open and a subsequent [close]
         * will still close it.
         *
         * If EOF is reached within the cap the body is fully captured: the source is closed
         * (best-effort — a close failure does not become a [drainError], since the capture already
         * succeeded) and [fullyCaptured] is set, preserving the fully-repeatable behavior. If the
         * cap is hit with bytes still pending the delegate is **left open** and retained as
         * [liveTail] so the consumer still receives the rest of the body via [source].
         *
         * If `provider.buffer()` throws (rare; would indicate a misconfigured provider), the error
         * is cached in [drainError] and an empty buffer is used as the fallback capture so
         * [captured] is never left null.
         */
        private fun drainAndCache(): Buffer {
            var buf: Buffer? = null
            var src: BufferedSource? = null
            try {
                buf = provider.buffer()
                src = delegate.source() // may throw — if it does, delegate is NOT yet closed
                val capturedSource = src
                var remaining = maxCaptureBytes
                while (remaining > 0L) {
                    val chunk = if (remaining < READ_CHUNK_BYTES) remaining else READ_CHUNK_BYTES
                    val n = capturedSource.read(buf, chunk)
                    if (n == -1L) {
                        // EOF within the cap — fully captured.
                        fullyCaptured = true
                        break
                    }
                    // A 0-read for a positive byteCount violates the Source.read contract; treating
                    // it as a no-op would spin this loop forever. Fail fast (the catch below caches
                    // it and source() re-throws it). Do NOT treat 0 as EOF — that would truncate.
                    if (n == 0L) {
                        throw IOException(
                            "Source returned 0 for byteCount=$chunk which violates the Source.read contract",
                        )
                    }
                    remaining -= n
                }
                if (fullyCaptured) {
                    // Whole body captured: close the source (and via ownership the delegate). The
                    // capture already succeeded, so a close failure here is best-effort cleanup, not
                    // a drain failure — swallow it (as the read-failure handler below does) so the
                    // complete captured body stays readable and is never reported as a [drainError].
                    // Mark the delegate closed whether or not close() throws, so a later close() does
                    // not close the same source a second time (some sockets / streams throw on
                    // double-close).
                    try {
                        capturedSource.close()
                    } catch (_: Throwable) {
                        // Best-effort: the body is already fully captured; a close failure must not
                        // poison it or leave the single-close guard unset.
                    }
                    delegateClosed = true
                } else {
                    // The cap was hit (or maxCaptureBytes was <= 0) with bytes still pending: retain
                    // the live tail so the consumer still gets them. Do NOT close the delegate.
                    liveTail = capturedSource
                }
            } catch (t: Throwable) {
                // Keep whatever was read before the failure so logging can still inspect partial bytes.
                drainError = t
                // If the source was obtained but a read failed, close it here so the network
                // resource is not leaked, and mark the delegate closed so a later close() does not
                // double-close. If `delegate.source()` itself threw, `src` is null and the delegate
                // remains the caller's to close via close().
                if (src != null) {
                    try {
                        src.close()
                    } catch (_: Throwable) {
                        // Best-effort: the drain already failed; a close failure must not mask it.
                    }
                    delegateClosed = true
                }
            }
            // Ensure captured is always non-null even when buf allocation itself failed.
            // The second provider.buffer() call here is only reached when the first one threw
            // (extremely rare), so if it also throws the exception propagates to the caller —
            // acceptable since the SDK invariant "provider is well-behaved" is violated.
            val result = buf ?: provider.buffer()
            captured = result
            return result
        }

        private fun Throwable.asIOException(): IOException =
            if (this is IOException) this else IOException("Body capture failed", this)

        /**
         * A one-shot [Source] that yields the captured [prefix] bytes first, then continues from
         * the live [tail]. Used only on the over-cap path. Closing it closes the independent peek
         * [prefix] view directly, but routes the live-tail close through [closeDelegateOnce] — the
         * same single-close guard the wrapper's own [close] uses — so the underlying delegate
         * source is never closed twice when both this source and the wrapper are closed.
         */
        private inner class PrefixThenTailSource(
            private val prefix: BufferedSource,
            private val tail: BufferedSource,
        ) : Source {
            @Throws(IOException::class)
            override fun read(
                sink: Buffer,
                byteCount: Long,
            ): Long {
                if (!prefix.exhausted()) return prefix.read(sink, byteCount)
                return tail.read(sink, byteCount)
            }

            @Throws(IOException::class)
            override fun close() {
                try {
                    // The peek view is independent of the delegate source and cheap to close.
                    prefix.close()
                } finally {
                    // The tail IS the delegate's source; close it through the shared guard so the
                    // wrapper's close() and this close() cannot both close the underlying source.
                    lock.withLock { closeDelegateOnce() }
                }
            }
        }

        internal companion object {
            /** Per-read pump size for the bounded drain — matches Okio's segment size. */
            private const val READ_CHUNK_BYTES: Long = 8 * 1024

            /** Internal seam: a wrapper that caps in-memory capture at [maxCaptureBytes] bytes. */
            @JvmSynthetic
            internal fun bounded(
                delegate: ResponseBody,
                provider: IoProvider,
                maxCaptureBytes: Long,
            ): LoggableResponseBody = LoggableResponseBody(delegate, provider, maxCaptureBytes)
        }
    }
