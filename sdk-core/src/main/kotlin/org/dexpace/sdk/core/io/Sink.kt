package org.dexpace.sdk.core.io

import java.io.Closeable
import java.io.IOException

/**
 * Primitive write contract. Consumes bytes from the head of a [Buffer] and writes them
 * downstream.
 *
 * Adapters (e.g. an Okio wrapper) implement this interface; callers usually do not interact
 * with [Sink] directly — they obtain a [BufferedSink] from an [IoProvider] which adds the
 * typed write surface.
 */
public interface Sink : Closeable {
    /**
     * Removes [byteCount] bytes from the head of [source] and appends them to this sink.
     *
     * @throws IllegalArgumentException if [byteCount] is negative.
     * @throws IOException on underlying I/O failure (including if [source] has fewer than
     *         [byteCount] bytes).
     */
    @Throws(IOException::class)
    public fun write(
        source: Buffer,
        byteCount: Long,
    )

    /**
     * Pushes all buffered bytes to their final destination.
     */
    @Throws(IOException::class)
    public fun flush()
}
