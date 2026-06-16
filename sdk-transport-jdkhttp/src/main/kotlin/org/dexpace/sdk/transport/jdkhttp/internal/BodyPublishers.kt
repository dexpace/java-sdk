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
import java.net.http.HttpRequest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
 * ## Per-subscription pipes
 *
 * The JDK invokes the `ofInputStream` supplier **once per subscription**, and it re-subscribes
 * the same publisher on internal resends — notably the 407 proxy-auth retry driven by the
 * `ProxyAuthenticator` this transport installs, and HTTP/2 `GOAWAY` replays. A supplier that
 * returned one shared, already-draining stream would hand the second subscription an exhausted
 * pipe, so the authenticated retry would carry a truncated or empty body. The supplier therefore
 * constructs a new pipe + writer each time, and the streaming path requires the body to be
 * **replayable** (see [streamingPublisher]) so a second `writeTo` produces the same bytes.
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
     * The JDK calls the `ofInputStream` supplier **once per subscription** and re-subscribes
     * on internal resends (407 proxy-auth retry, HTTP/2 `GOAWAY` replay). Each subscription
     * therefore re-reads the body, so the body must be replayable for the resent request to
     * carry the correct bytes. If [body] is not already replayable it is buffered once into an
     * in-memory copy via [SdkRequestBody.toReplayable]. A body that cannot be made replayable
     * (its `toReplayable` throws mid-write) has already been partially consumed and cannot be
     * recovered — the bytes drained by the failed buffering attempt are gone and `writeTo`
     * cannot be driven a second time on a consume-once body. This method therefore fails with a
     * checked [IOException] that wraps the original cause rather than masking it (a second
     * `writeTo` would trip a consume-once guard and surface an `IllegalStateException`) or
     * shipping a truncated body. Surfacing it as a checked [IOException] keeps the transport's
     * `@Throws(IOException)` contract intact and matches the eager path, which already propagates
     * a mid-write failure as an [IOException]; callers that need resilience here must supply a
     * replayable body.
     *
     * For each subscription the supplier:
     *  1. creates a fresh [PipedOutputStream] / [PipedInputStream] pair;
     *  2. submits a writer task that drives `body.writeTo(...)` onto the OutputStream side and
     *     captures its [Future]; and
     *  3. returns the InputStream wrapped so its `close()` cancels the writer [Future] and
     *     closes the OutputStream — unblocking a writer stranded in `PipedOutputStream.write`
     *     when the JDK abandons the subscription without draining it.
     *
     * Errors thrown from `body.writeTo` close the OutputStream prematurely (the `use { }` block
     * exits abnormally); the JDK reader then observes an `IOException` on its next read and the
     * surrounding future completes exceptionally. Thread interruption in the writer is honoured
     * per the repo convention: the flag is restored and an [InterruptedIOException] is surfaced
     * so the reader side fails loudly. The DEBUG log records the writer-side failure so it is
     * discoverable in tests / production.
     */
    private fun streamingPublisher(body: SdkRequestBody): HttpRequest.BodyPublisher {
        val replayable: SdkRequestBody =
            if (body.isReplayable()) {
                body
            } else {
                try {
                    body.toReplayable()
                } catch (e: IOException) {
                    // toReplayable drained the body once and failed mid-write; a consume-once
                    // body has already flipped its guard, so a second writeTo would trip that
                    // guard and surface an IllegalStateException that masks this IOException. The
                    // partially captured bytes are local to toReplayable and gone. Rethrow as a
                    // checked IOException wrapping the cause — honouring the transport's
                    // @Throws(IOException) contract and matching the eager path — rather than
                    // re-driving the body or shipping a truncated copy.
                    log.atVerbose()
                        .event("transport.jdkhttp.body.replayable.failed")
                        .field("error.message", e.message ?: "")
                        .log("could not buffer streaming body as replayable; failing the request")
                    throw IOException(
                        "streaming request body could not be buffered for the JDK transport and " +
                            "has been partially consumed; supply a replayable body",
                        e,
                    )
                }
            }
        return HttpRequest.BodyPublishers.ofInputStream { newSubscriptionStream(replayable) }
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
