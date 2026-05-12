package org.dexpace.sdk.core.http.sse

import org.dexpace.sdk.core.io.BufferedSource
import java.io.IOException
import java.time.Duration

/**
 * Parses a Server-Sent Event stream from a [BufferedSource], per the WHATWG spec
 * (https://html.spec.whatwg.org/multipage/server-sent-events.html).
 *
 * Each call to [next] returns one event, or `null` at stream end. The reader is
 * stateful — only the BOM consumption flag persists across calls; per-event field
 * accumulators are reset at each blank-line dispatch.
 *
 * The reader does not own the underlying [source] and does not close it. Callers
 * are responsible for the source's lifecycle.
 *
 * **Threading**: not thread-safe. Drive it from one thread.
 */
class ServerSentEventReader(private val source: BufferedSource) {

    private var bomConsumed: Boolean = false

    /**
     * Returns the next event from the stream, or `null` once the stream is exhausted.
     *
     * A "dispatch" happens at a blank line per the spec: the accumulated `id`, `event`,
     * `data`, `comment`, and `retry` fields collapse into a single [ServerSentEvent].
     * The spec rule "if data buffer and event type buffer are both empty, skip"
     * is interpreted permissively here: an event is emitted if **any** of the five
     * fields was set, so `id`-only or `retry`-only events are visible. Pure blank
     * lines (no fields seen) are skipped.
     */
    @Throws(IOException::class)
    fun next(): ServerSentEvent? {
        consumeBomIfNeeded()

        var id: String? = null
        var event: String? = null
        var comment: String? = null
        var retry: Duration? = null
        var data: MutableList<String>? = null
        var hasField = false

        while (true) {
            val line = readLine() ?: run {
                // End of stream: emit whatever accumulated state remains (if any),
                // else signal termination by returning null.
                return if (hasField) {
                    ServerSentEvent(
                        id = id,
                        event = event,
                        data = data ?: emptyList(),
                        comment = comment,
                        retry = retry,
                    )
                } else {
                    null
                }
            }

            if (line.isEmpty()) {
                // Blank line: dispatch the accumulated event, or skip if nothing was set.
                if (hasField) {
                    return ServerSentEvent(
                        id = id,
                        event = event,
                        data = data ?: emptyList(),
                        comment = comment,
                        retry = retry,
                    )
                }
                // No fields seen — keep reading for the next event.
                continue
            }

            // Comment line: latest wins.
            if (line[0] == ':') {
                comment = line.substring(1)
                hasField = true
                continue
            }

            val colon = line.indexOf(':')
            val field: String
            val rawValue: String
            if (colon < 0) {
                // No colon at all — entire line is the field name; value is empty.
                field = line
                rawValue = ""
            } else {
                field = line.substring(0, colon)
                // Per spec: if the value starts with U+0020 SPACE, drop one.
                val afterColon = colon + 1
                rawValue = if (afterColon < line.length && line[afterColon] == ' ') {
                    line.substring(afterColon + 1)
                } else {
                    line.substring(afterColon)
                }
            }

            when (field) {
                "id" -> {
                    // Per spec: null bytes (U+0000) in the id are invalid. Substituting
                    // the Unicode replacement character keeps the field visible to the
                    // caller (so they can detect / log the bad data) without producing
                    // a string that downstream JSON or logging code might mishandle.
                    id = if (rawValue.indexOf(NULL_CHAR) < 0) {
                        rawValue
                    } else {
                        rawValue.replace(NULL_CHAR, REPLACEMENT_CHAR)
                    }
                    hasField = true
                }
                "event" -> {
                    event = rawValue
                    hasField = true
                }
                "data" -> {
                    val accumulator = data ?: ArrayList<String>(4).also { data = it }
                    accumulator.add(rawValue)
                    hasField = true
                }
                "retry" -> {
                    val parsed = parseRetryMillis(rawValue)
                    if (parsed != null) {
                        retry = Duration.ofMillis(parsed)
                        hasField = true
                    }
                    // Non-numeric retry values are ignored per spec.
                }
                // Unknown field: silently discard.
            }
        }
    }

    /**
     * Consumes the optional leading BOM (U+FEFF, UTF-8 `EF BB BF`) at stream start.
     * Per the WHATWG SSE spec, exactly one BOM at the head of the stream is stripped;
     * any later occurrence is preserved as data.
     */
    @Throws(IOException::class)
    private fun consumeBomIfNeeded() {
        if (bomConsumed) return
        bomConsumed = true
        // peek() returns a non-consuming view, so we can inspect the first three bytes
        // without advancing the real cursor if they aren't a BOM.
        val peek = source.peek()
        val b1 = if (!peek.exhausted()) peek.readByte() else return
        if (b1 != 0xEF.toByte()) return
        val b2 = if (!peek.exhausted()) peek.readByte() else return
        if (b2 != 0xBB.toByte()) return
        val b3 = if (!peek.exhausted()) peek.readByte() else return
        if (b3 != 0xBF.toByte()) return
        // Confirmed BOM — advance the real source past it.
        source.skip(3)
    }

    /**
     * Reads the next line from [source], handling all three SSE-legal terminators:
     * `\n`, `\r`, and `\r\n`. Returns the line contents (without the terminator),
     * or `null` when the source is exhausted before any byte is read.
     *
     * A line without a terminator at end of stream is returned as-is.
     *
     * The okio implementation of [BufferedSource.readUtf8Line] handles `\n` and `\r\n`
     * but not bare `\r`, so this reader does line-splitting itself byte-by-byte. ASCII
     * `\r` (0x0D) and `\n` (0x0A) never appear as UTF-8 continuation bytes, so a
     * byte-level scan is safe.
     */
    @Throws(IOException::class)
    private fun readLine(): String? {
        if (source.exhausted()) return null

        // Accumulate raw bytes until we see a terminator or EOF, then decode once.
        val out = ByteArrayBuilder()
        while (!source.exhausted()) {
            val b = source.readByte()
            when (b) {
                LF -> return out.toUtf8()
                CR -> {
                    // Consume a following \n if present, so \r\n behaves as one terminator.
                    if (!source.exhausted()) {
                        val peek = source.peek()
                        if (!peek.exhausted() && peek.readByte() == LF) {
                            source.skip(1)
                        }
                    }
                    return out.toUtf8()
                }
                else -> out.append(b)
            }
        }
        return out.toUtf8()
    }

    /**
     * Parses [value] as a non-negative integer number of milliseconds. The SSE spec
     * mandates ASCII digit characters only — leading sign, whitespace, or any
     * non-digit byte causes the field to be ignored. Returns `null` for any
     * non-matching input, including an empty string.
     *
     * Numbers larger than `Long.MAX_VALUE` are ignored to stay within `Duration`'s
     * representable range. The spec is silent on overflow; ignoring is conservative.
     */
    private fun parseRetryMillis(value: String): Long? {
        if (value.isEmpty()) return null
        var result = 0L
        for (i in 0 until value.length) {
            val c = value[i]
            if (c < '0' || c > '9') return null
            val digit = (c - '0').toLong()
            // Detect overflow before it happens so we don't wrap around.
            if (result > (Long.MAX_VALUE - digit) / 10L) return null
            result = result * 10L + digit
        }
        return result
    }

    private companion object {
        private const val LF: Byte = 0x0A
        private const val CR: Byte = 0x0D
        private const val NULL_CHAR: Char = ' '
        private const val REPLACEMENT_CHAR: Char = '�'
    }

    /**
     * Minimal append-only byte accumulator. Avoids the synchronization overhead of
     * `java.io.ByteArrayOutputStream` (we are single-threaded by contract) and the
     * intermediate `Buffer` allocation an Io-provider call would imply. Per-line
     * allocation is unavoidable; this just keeps the per-allocation cost low.
     */
    private class ByteArrayBuilder {
        private var bytes: ByteArray = EMPTY
        private var count: Int = 0

        fun append(b: Byte) {
            if (count == bytes.size) grow(count + 1)
            bytes[count++] = b
        }

        fun toUtf8(): String =
            if (count == 0) "" else String(bytes, 0, count, Charsets.UTF_8)

        private fun grow(minCapacity: Int) {
            val oldCap = bytes.size
            val newCap = when {
                oldCap == 0 -> 64
                oldCap < minCapacity -> maxOf(oldCap * 2, minCapacity)
                else -> oldCap
            }
            bytes = bytes.copyOf(newCap)
        }

        companion object {
            private val EMPTY = ByteArray(0)
        }
    }
}
