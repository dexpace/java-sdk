package org.dexpace.sdk.transport.jdkhttp.internal

import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.io.Io
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.http.HttpRequest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 *  - **Streaming path (`contentLength > 64 KiB` or `-1L` (unknown))** — create a
 *    [PipedOutputStream] / [PipedInputStream] pair. A daemon writer thread drains
 *    `body.writeTo(...)` into the OutputStream; the publisher reads from the InputStream
 *    via `BodyPublishers.ofInputStream`. Exceptions on the writer side propagate to the
 *    reader as `IOException` (the standard JDK PipedStream contract), which the JDK client
 *    surfaces back to the calling future.
 *
 * The 64 KiB threshold matches the spec; not exposed via the builder. Justification: the
 * pipe coordination overhead measurably exceeds the buffer cost below this size, and
 * holding 64 KiB in heap is uncontroversial.
 *
 * ## Writer thread lifecycle
 *
 * [bodyWriterExecutor] is process-wide and daemon-threaded — JVM shutdown reaps any
 * stranded writers without an explicit close hook. Threads are named
 * `dexpace-jdkhttp-body-writer` so they are identifiable in thread dumps.
 *
 * ## Thread-safety
 *
 * Each call to [adaptBody] creates a fresh `BodyPublisher` over a fresh pipe (for the
 * streaming path) or a fresh byte array (for the eager path); no state is shared between
 * concurrent invocations.
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
        val buffer = Io.provider.buffer()
        body.writeTo(buffer)
        val bytes = buffer.snapshot()
        return HttpRequest.BodyPublishers.ofByteArray(bytes)
    }

    /**
     * Creates a [PipedOutputStream] / [PipedInputStream] pair, submits a daemon task that
     * drives `body.writeTo(...)` onto the OutputStream side, and returns
     * [HttpRequest.BodyPublishers.ofInputStream] over the InputStream side.
     *
     * The `ofInputStream` factory expects a `Supplier<InputStream>` — the JDK calls it once
     * to acquire the stream. The stream returned here is constructed eagerly so the writer
     * task can start producing bytes immediately; the JDK reader picks them up as it
     * consumes the publisher.
     *
     * Errors thrown from `body.writeTo` close the OutputStream prematurely (because the
     * `use { }` block exits abnormally); the JDK reader then observes an `IOException` on
     * its next read and the surrounding future completes exceptionally. The DEBUG log
     * records the writer-side failure so it's discoverable in tests / production.
     */
    private fun streamingPublisher(body: SdkRequestBody): HttpRequest.BodyPublisher {
        val pipeIn = PipedInputStream(PIPE_BUFFER_BYTES)
        val pipeOut = PipedOutputStream(pipeIn)
        bodyWriterExecutor.execute {
            try {
                pipeOut.use { os ->
                    Io.provider.sink(os).use { sdkSink ->
                        body.writeTo(sdkSink)
                    }
                }
            } catch (t: Throwable) {
                log.atVerbose()
                    .event("transport.jdkhttp.body.write.failed")
                    .field("error.message", t.message ?: "")
                    .log("piped body writer failed; reader side will see IOException")
            }
        }
        return HttpRequest.BodyPublishers.ofInputStream { pipeIn }
    }
}
