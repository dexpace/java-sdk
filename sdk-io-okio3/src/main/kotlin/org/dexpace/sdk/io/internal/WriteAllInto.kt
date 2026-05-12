package org.dexpace.sdk.io.internal

import org.dexpace.sdk.core.io.Source

/**
 * Shared `writeAll` implementation used by both [OkioBufferedSink] and the buffer-side
 * `BufferedSink` surface of [OkioBuffer]. Uses Okio's native `writeAll` when [source] is
 * known to be Okio-backed; otherwise falls back to a pump loop through an intermediate
 * [okio.Buffer].
 *
 * `LoggableResponseBody.drainAndCache()` is the main beneficiary of the fast path: it does
 * `buf.writeAll(src)` to suck a wrapped response body into an in-memory cache; when `src` is
 * an [OkioBufferedSource] (the common case), Okio moves segments by ownership transfer
 * rather than copying bytes.
 */
internal fun writeAllInto(sink: okio.BufferedSink, source: Source): Long {
    return when (source) {
        is OkioBufferedSource -> sink.writeAll(source.delegate)
        is OkioBuffer -> sink.writeAll(source.delegate)
        else -> {
            val tmp = OkioBuffer()
            var total = 0L
            while (true) {
                val read = source.read(tmp, SEGMENT_SIZE)
                if (read == -1L) break
                sink.write(tmp.delegate, tmp.delegate.size)
                total += read
            }
            total
        }
    }
}

private const val SEGMENT_SIZE: Long = 8 * 1024
