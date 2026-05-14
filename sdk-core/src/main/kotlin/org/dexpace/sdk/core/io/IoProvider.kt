package org.dexpace.sdk.core.io

import java.io.InputStream
import java.io.OutputStream

/**
 * Single seam between [sdk-core] and a concrete I/O implementation (Okio 3.x, Okio 2.x,
 * plain `java.io`, or any custom streams library).
 *
 * A consuming library installs one implementation at startup:
 *
 * ```
 * Io.installProvider(OkioIoProvider)
 * ```
 *
 * After installation, every part of the SDK that needs to create a stream calls
 * [Io.provider] and obtains the right factory method here — without ever importing the
 * concrete implementation.
 *
 * ## Thread-safety
 *
 * Factory methods are invoked concurrently from request-processing threads and must be safe
 * to call from any thread. Individual [Buffer] / [BufferedSource] / [BufferedSink] instances
 * returned from them are not required to be thread-safe.
 */
public interface IoProvider {
    /**
     * Returns a new, empty in-memory [Buffer]. Used as the canonical storage for request/response
     * body logging snapshots and as an intermediate when bridging between source and sink.
     */
    public fun buffer(): Buffer

    /**
     * Returns a [BufferedSource] that reads from [input]. The returned source takes ownership
     * of [input] — closing the source closes the stream.
     */
    public fun source(input: InputStream): BufferedSource

    /**
     * Returns a [BufferedSource] that reads from the given [bytes].
     */
    public fun source(bytes: ByteArray): BufferedSource

    /**
     * Returns a [BufferedSink] that writes to [output]. The returned sink takes ownership of
     * [output] — closing the sink closes the stream.
     */
    public fun sink(output: OutputStream): BufferedSink

    /**
     * Wraps an existing primitive [Source] with the typed read surface of [BufferedSource].
     */
    public fun bufferedSource(source: Source): BufferedSource

    /**
     * Wraps an existing primitive [Sink] with the typed write surface of [BufferedSink].
     */
    public fun bufferedSink(sink: Sink): BufferedSink
}
