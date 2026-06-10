/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.io.internal

import org.dexpace.sdk.core.io.Source
import java.io.IOException

/**
 * Shared `writeAll` implementation used by both [OkioBufferedSink] and the buffer-side
 * `BufferedSink` surface of [OkioBuffer]. Uses Okio's native `writeAll` when [source] is
 * known to be Okio-backed; otherwise falls back to a pump loop through an intermediate
 * [okio.Buffer].
 *
 * Source-to-sink body bridging is the main beneficiary of the fast path: when `source` is an
 * [OkioBufferedSource] (the common case), Okio moves segments by ownership transfer rather
 * than copying bytes.
 *
 * On the fallback path a `read` of `0` for a non-zero `byteCount` violates the
 * [Source.read] contract and is **rejected** with an [IOException] rather than tolerated as
 * a silent EOF — mirroring `TeeSink.writeAll`. Only a `read` of `-1` terminates the loop.
 */
@Throws(IOException::class)
internal fun writeAllInto(
    sink: okio.BufferedSink,
    source: Source,
): Long {
    return when (source) {
        is OkioBufferedSource -> sink.writeAll(source.delegate)
        is OkioBuffer -> sink.writeAll(source.delegate)
        else -> {
            val tmp = OkioBuffer()
            var total = 0L
            while (true) {
                val read = source.read(tmp, SEGMENT_SIZE)
                when {
                    read == -1L -> break // EOF — normal termination
                    read == 0L -> throw IOException(
                        "Source returned 0 for byteCount=$SEGMENT_SIZE which violates the Source.read contract",
                    )
                    else -> {
                        sink.write(tmp.delegate, tmp.delegate.size)
                        total += read
                    }
                }
            }
            total
        }
    }
}

private const val SEGMENT_SIZE: Long = 8 * 1024
