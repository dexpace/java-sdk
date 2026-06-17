/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp.internal

import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.io.Io
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.UncheckedIOException
import java.net.http.HttpRequest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import org.dexpace.sdk.core.http.request.RequestBody as SdkRequestBody

/**
 * Bridges an SDK [SdkRequestBody] onto a `java.net.http.HttpRequest.BodyPublisher`.
 *
 * `BodyPublisher` is a `Flow.Publisher<ByteBuffer>` (Reactive Streams). The SDK body produces
 * bytes through `writeTo(BufferedSink)` — a stream-style write API. There is no zero-allocation
 * bridge between the two; the adapter picks one of two strategies based on the declared
 * `contentLength()`:
 *
 *  - **Eager path (`0 ≤ contentLength ≤ 64 KiB`)** — drain the body into an in-memory [Buffer]
 *    via the active [Io.provider], snapshot to a `ByteArray`, hand to
 *    `BodyPublishers.ofByteArray`. The JDK reads the array deterministically; no extra
 *    threads, no piped-stream coordination. Eager buffering is acceptable up to 64 KiB
 *    because the body would have to be materialised in the publisher anyway and small
 *    bodies do not benefit from streaming.
 *
 *  - **Streaming path (`contentLength > 64 KiB` or `-1L` (unknown))** — return a
 *    `BodyPublishers.ofInputStream` whose supplier creates a **fresh**
 *    [PipedOutputStream] / [PipedInputStream] pair and a **fresh** writer task on every
 *    invocation. A daemon writer thread drains `body.writeTo(...)` into the OutputStream;
 *    the publisher reads from the InputStream. See "Per-subscription pipes" below for why
 *    the pipe and writer are created lazily, per-subscription, rather than once.
 *
 * The 64 KiB threshold matches the spec; not exposed via the builder. Justification: the
 * pipe coordination overhead measurably exceeds the buffer cost below this size, and
 * holding 64 KiB in heap is uncontroversial.
 *
 * ## Per-subscription pipes and one-shot bodies
 *
 * The JDK invokes the `ofInputStream` supplier **once per subscription**, and it re-subscribes
 * the same publisher on internal resends — notably the 407 proxy-auth retry driven by the
 * `ProxyAuthenticator` this transport installs, and HTTP/2 `GOAWAY` replays. A supplier that
 * returned one shared, already-draining stream would hand the second subscription an exhausted
 * pipe, so the authenticated retry would carry a truncated or empty body. The supplier therefore
 * constructs a new pipe + writer each time.
 *
 * A **replayable** body produces the same bytes on every `writeTo`, so each subscription streams
 * the full body straight from the source — no buffering, any number of resends.
 *
 * A **non-replayable** (one-shot) body can be written only once. The streaming path therefore
 * **streams the first subscription directly from the source** — it does NOT buffer the body into
 * heap up front, which is the whole point of the streaming path (a body that is large or of
 * unknown length is exactly the body you must not materialise). A **second** subscription cannot
 * replay a consumed one-shot body, so it fails loudly with a clear "one-shot body cannot be
 * re-sent — supply a replayable body" `IOException` rather than silently shipping a truncated or
 * empty body. This matches the consume-once discipline of `OneShotInputStreamRequestBody` /
 * `BufferedSourceRequestBody` in `sdk-core`: a resend of a one-shot body is a caller error, and
 * the transport surfaces it instead of corrupting the request. Callers that need the proxy-auth /
 * `GOAWAY` resend to succeed must supply a replayable body. See [streamingPublisher].
 *
 * ## Writer thread lifecycle
 *
 * [bodyWriterExecutor] is process-wide and daemon-threaded — JVM shutdown reaps any
 * stranded writers without an explicit close hook. Threads are named
 * `dexpace-jdkhttp-body-writer` so they are identifiable in thread dumps.
 *
 * A subscription that is acquired but never drained (connect failure, cancellation before
 * the body is sent) would otherwise strand its writer thread blocked in
 * `PipedOutputStream.write`. The returned [PipedInputStream] is wrapped so that closing it
 * — which the JDK does when it abandons a subscription — cancels the writer [Future] and
 * closes the pipe's output end, unblocking the stuck writer.
 *
 * ## Thread-safety
 *
 * Each call to [adaptBody] creates a fresh `BodyPublisher`; each subscription to a streaming
 * publisher creates a fresh pipe + writer. No state is shared between concurrent invocations
 * or between subscriptions.
 */
internal object BodyPublishers {
    /**
     * Maximum body size that is eagerly buffered before publication. Bodies at or under this
     * threshold are turned into a `ByteArray` via the IoProvider; bodies above use the
     * piped-stream + daemon writer path.
     */
    private const val EAGER_THRESHOLD_BYTES: Long = 64L * 1024L

    /**
     * Capacity of the piped pipe in the streaming path. 8 KiB matches the historical Okio
     * segment size and keeps producer/consumer alignment predictable.
     */
    private const val PIPE_BUFFER_BYTES: Int = 8 * 1024

    private val log: ClientLogger = ClientLogger("org.dexpace.sdk.transport.jdkhttp.BodyPublishers")

    /**
     * Daemon-thread executor shared across all `JdkHttpTransport` instances. Used only by the
     * streaming-body path. The executor is created lazily on first use and never shut down —
     * threads are daemon, so JVM shutdown reaps any survivors.
     *
     * `newCachedThreadPool` is the right pool shape for a fan-in of bounded-life producer
     * tasks: idle threads time out after 60 s, peak concurrency is bounded only by request
     * concurrency (one thread per concurrent large body), and there is no head-of-line
     * blocking from a fixed pool.
     */
    private val bodyWriterExecutor: ExecutorService by lazy {
        Executors.newCachedThreadPool { r ->
            Thread(r, "dexpace-jdkhttp-body-writer").apply { isDaemon = true }
        }
    }

    /**
     * Adapts [body] into a [HttpRequest.BodyPublisher]. Returns
     * [HttpRequest.BodyPublishers.noBody] when [body] is `null`.
     */
    fun adaptBody(body: SdkRequestBody?): HttpRequest.BodyPublisher {
        if (body == null) {
            return HttpRequest.BodyPublishers.noBody()
        }
        val length = body.contentLength()
        return if (length in 0L..EAGER_THRESHOLD_BYTES) {
            eagerPublisher(body)
        } else {
            streamingPublisher(body)
        }
    }

    /**
     * Drains [body] into a fresh in-memory [org.dexpace.sdk.core.io.Buffer] obtained from the
     * active [Io.provider], snapshots to a byte array, and wraps in
     * [HttpRequest.BodyPublishers.ofByteArray]. The buffer is discarded after snapshot — its
     * memory is released when the array is handed off.
     */
    private fun eagerPublisher(body: SdkRequestBody): HttpRequest.BodyPublisher {
        val bytes = bufferToByteArray(body)
        return HttpRequest.BodyPublishers.ofByteArray(bytes)
    }

    /**
     * Streaming publisher for bodies larger than the eager threshold (or of unknown length).
     *
     * The JDK calls the `ofInputStream` supplier **once per subscription** and re-subscribes on
     * internal resends (407 proxy-auth retry, HTTP/2 `GOAWAY` replay). The supplier therefore
     * mints a fresh pipe + writer per subscription so each subscription streams the body from its
     * source — no up-front heap buffering. This is the whole point of the streaming path: a body
     * that is large or of unknown length is exactly the body that must not be materialised into a
     * `ByteArray`/`Buffer`.
     *
     * **Replayable body.** Every `writeTo` produces the same bytes, so every subscription (the
     * first and any resend) streams the full body straight from the source.
     *
     * **Non-replayable (one-shot) body.** The body can be written only once. The first
     * subscription streams it directly from the source. A **second** subscription cannot replay a
     * consumed one-shot body, so [oneShotSupplier] returns a stream that fails its first read with
     * a clear [IOException] ("one-shot request body cannot be re-sent — supply a replayable
     * body"). The JDK surfaces that read failure on the resend; the request fails loudly rather
     * than shipping a truncated or empty body. This deliberately does NOT buffer the body up
     * front to make resends succeed — that would re-import the OOM hazard the streaming path
     * exists to avoid (a 2 GiB upload would need 2 GiB of contiguous heap, and a body above the
     * byte-array/segment limits would fail outright). Callers that need proxy-auth / `GOAWAY`
     * resend support must supply a replayable body.
     *
     * For each (admitted) subscription the supplier:
     *  1. creates a fresh [PipedOutputStream] / [PipedInputStream] pair;
     *  2. submits a writer task that drives `body.writeTo(...)` onto the OutputStream side and
     *     captures its [Future]; and
     *  3. returns the InputStream wrapped so its `close()` cancels the writer [Future] and
     *     closes the OutputStream — unblocking a writer stranded in `PipedOutputStream.write`
     *     when the JDK abandons the subscription without draining it.
     *
     * Errors thrown from `body.writeTo` (including a one-shot body's own consume-once guard, or a
     * mid-write `IOException`) close the OutputStream prematurely (the `use { }` block exits
     * abnormally); the JDK reader then observes an `IOException` on its next read and the
     * surrounding future completes exceptionally. Thread interruption in the writer is honoured
     * per the repo convention: the flag is restored and an [InterruptedIOException] is surfaced
     * so the reader side fails loudly. The DEBUG log records the writer-side failure so it is
     * discoverable in tests / production.
     */
    private fun streamingPublisher(body: SdkRequestBody): HttpRequest.BodyPublisher {
        if (body.isReplayable()) {
            return HttpRequest.BodyPublishers.ofInputStream { newSubscriptionStream(body) }
        }
        // One-shot body: a single AtomicBoolean — created once here, captured by the supplier —
        // admits exactly the first subscription to stream the body. Every later subscription
        // (proxy-auth retry, HTTP/2 GOAWAY replay) gets a stream that fails loudly, because a
        // consumed one-shot body cannot be replayed.
        val firstSubscription = AtomicBoolean(true)
        return HttpRequest.BodyPublishers.ofInputStream {
            if (firstSubscription.compareAndSet(true, false)) {
                newSubscriptionStream(body)
            } else {
                log.atVerbose()
                    .event("transport.jdkhttp.body.oneshot.resend")
                    .log("one-shot streaming body re-subscribed; failing the resend loudly")
                ResendRefusedInputStream()
            }
        }
    }

    /**
     * Test seam: opens a single streaming subscription's InputStream directly, bypassing the
     * `ofInputStream` publisher's own reader thread so a test can hold the stream un-drained and
     * close it to exercise the [KillSwitchInputStream] writer-unblock path. The [body] must be
     * replayable (the production path guarantees this before reaching [newSubscriptionStream]).
     */
    @JvmSynthetic
    internal fun openSubscriptionStreamForTest(body: SdkRequestBody): InputStream = newSubscriptionStream(body)

    /**
     * Builds one subscription's InputStream: a fresh pipe pair plus a writer task draining
     * [body] into it. Returns the read end wrapped with a kill-switch `close()` (see
     * [KillSwitchInputStream]).
     */
    private fun newSubscriptionStream(body: SdkRequestBody): InputStream {
        val pipeIn = PipedInputStream(PIPE_BUFFER_BYTES)
        val pipeOut = PipedOutputStream(pipeIn)
        val task =
            Runnable {
                try {
                    pipeOut.use { os ->
                        Io.provider.sink(os).use { sdkSink ->
                            body.writeTo(sdkSink)
                        }
                    }
                } catch (e: InterruptedIOException) {
                    // The writer was interrupted (e.g. its Future was cancelled when the
                    // subscription was abandoned). Restore the flag per repo convention; the
                    // pipe is already closing, so the reader observes EOF / IOException.
                    Thread.currentThread().interrupt()
                    logWriterFailure(e)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    logWriterFailure(e)
                } catch (t: Throwable) {
                    logWriterFailure(t)
                }
            }
        val writer: Future<*> = bodyWriterExecutor.submit(task)
        return KillSwitchInputStream(pipeIn, pipeOut, writer)
    }

    /**
     * Stand-in stream handed to a **resent** subscription of a one-shot body. A consumed one-shot
     * body cannot be replayed, so rather than ship a truncated/empty body the stream fails its
     * first read.
     *
     * The failure is raised as an [UncheckedIOException] wrapping the explanatory [IOException],
     * **not** a bare checked [IOException]. The JDK's `ofInputStream` reader (`StreamIterator`)
     * catches checked `IOException` from `read` and — on the JDK 11 baseline this module targets —
     * swallows it as a silent end-of-stream, which would let the resend complete with a truncated
     * body. An [UncheckedIOException] is not caught there, so it propagates: on JDK 11 it surfaces
     * synchronously on the subscriber's `request` stack, and on later JDKs it is delivered through
     * the subscriber's `onError`. Either way the resend fails loudly with the original cause
     * preserved. `available()` returns a non-zero value so the JDK is driven to call `read`
     * (where the failure surfaces) rather than treating the stream as already at EOF.
     */
    private class ResendRefusedInputStream : InputStream() {
        override fun read(): Int = throw resendError()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = throw resendError()

        override fun available(): Int = 1

        private fun resendError(): UncheckedIOException =
            UncheckedIOException(
                IOException(
                    "one-shot request body cannot be re-sent (the JDK transport attempted an internal " +
                        "resend, e.g. proxy-auth retry or HTTP/2 GOAWAY replay); supply a replayable body",
                ),
            )
    }

    private fun logWriterFailure(t: Throwable) {
        log.atVerbose()
            .event("transport.jdkhttp.body.write.failed")
            .field("error.message", t.message ?: "")
            .log("piped body writer failed; reader side will see IOException")
    }

    /**
     * Drains [body] into a fresh in-memory [org.dexpace.sdk.core.io.Buffer] from the active
     * [Io.provider] and snapshots it to a byte array.
     */
    private fun bufferToByteArray(body: SdkRequestBody): ByteArray {
        val buffer = Io.provider.buffer()
        body.writeTo(buffer)
        return buffer.snapshot()
    }

    /**
     * Wraps a subscription's [PipedInputStream] so that closing the read end also cancels the
     * writer [Future] and closes the [PipedOutputStream] write end. Without this, a JDK
     * subscription that is acquired and then abandoned (connect failure, cancellation before
     * the body is sent) would leave the writer thread blocked indefinitely in
     * `PipedOutputStream.write`, pinning the writer thread and the body's resources.
     *
     * `close()` is idempotent: the JDK may close the supplied stream more than once.
     */
    private class KillSwitchInputStream(
        private val pipeIn: PipedInputStream,
        private val pipeOut: PipedOutputStream,
        private val writer: Future<*>,
    ) : InputStream() {
        override fun read(): Int = pipeIn.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = pipeIn.read(b, off, len)

        override fun available(): Int = pipeIn.available()

        override fun close() {
            // Interrupt/cancel the writer first so a thread parked in PipedOutputStream.write
            // wakes up, then close both pipe ends. Order matters: closing pipeOut alone does
            // not unblock a writer mid-write, so the cancel(true) is what frees the thread.
            writer.cancel(true)
            closeQuietly(pipeOut)
            closeQuietly(pipeIn)
        }

        private fun closeQuietly(closeable: Closeable) {
            try {
                closeable.close()
            } catch (_: IOException) {
                // Best-effort teardown — the peer end may already be closed.
            }
        }
    }
}
