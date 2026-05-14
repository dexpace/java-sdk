package org.dexpace.sdk.core.io

import java.io.Closeable
import java.io.IOException

/**
 * Primitive read contract. Reads bytes from this source into the tail of a [Buffer].
 *
 * Adapters (e.g. an Okio wrapper) implement this interface; callers usually do not interact
 * with [Source] directly — they obtain a [BufferedSource] from an [IoProvider] which adds the
 * typed read surface.
 */
public interface Source : Closeable {
    /**
     * Reads up to [byteCount] bytes from this source and appends them to [sink].
     *
     * Returns the number of bytes read (at least 1 when [byteCount] > 0 and the source is
     * not exhausted), `0` when [byteCount] is `0`, or `-1` when the source is exhausted
     * before any bytes are read.
     *
     * @throws IllegalArgumentException if [byteCount] is negative.
     * @throws IOException on underlying I/O failure.
     */
    @Throws(IOException::class)
    public fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long
}
