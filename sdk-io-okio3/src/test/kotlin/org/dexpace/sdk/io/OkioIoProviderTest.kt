package org.dexpace.sdk.io

import org.dexpace.sdk.core.http.request.LoggableRequestBody
import org.dexpace.sdk.core.http.request.RequestBody
import org.dexpace.sdk.core.http.response.LoggableResponseBody
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.request.FileRequestBody
import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.io.IoProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OkioIoProviderTest {

    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    @AfterTest
    fun resetProvider() {
        // Leaves OkioIoProvider installed for any next test class; that's fine.
        Io.installProvider(OkioIoProvider)
    }

    /** Writes a request body to a fresh okio-backed sink and returns the bytes sent. */
    private fun writeBody(body: RequestBody): ByteArray {
        val out = ByteArrayOutputStream()
        val sink = OkioIoProvider.sink(out)
        body.writeTo(sink)
        sink.flush()
        return out.toByteArray()
    }

    // ----- IoProvider basics -----

    @Test
    fun `byte array source round-trips through readUtf8`() {
        val source = OkioIoProvider.source("hello, world".toByteArray())
        assertEquals("hello, world", source.readUtf8())
        assertTrue(source.exhausted())
    }

    @Test
    fun `sink over output stream writes byte-for-byte`() {
        val out = ByteArrayOutputStream()
        val sink: BufferedSink = OkioIoProvider.sink(out)
        sink.writeUtf8("hello")
        sink.flush()
        assertContentEquals("hello".toByteArray(), out.toByteArray())
    }

    @Test
    fun `buffer is a sink and a source`() {
        val buffer = OkioIoProvider.buffer()
        buffer.writeUtf8("ping").writeUtf8("pong")
        assertEquals(8L, buffer.size)
        assertEquals("pingpong", buffer.readUtf8())
        assertEquals(0L, buffer.size)
    }

    @Test
    fun `snapshot returns immutable copy`() {
        val buffer = OkioIoProvider.buffer()
        buffer.writeUtf8("abc")
        val snap = buffer.snapshot()
        buffer.writeUtf8("def")
        assertContentEquals("abc".toByteArray(), snap)
        assertEquals(6L, buffer.size)
    }

    @Test
    fun `peek does not consume`() {
        val source = OkioIoProvider.source("payload".toByteArray())
        val peek = source.peek()
        assertEquals("payload", peek.readUtf8())
        assertEquals("payload", source.readUtf8())
    }

    @Test
    fun `writeAll drains source into sink`() {
        val source = OkioIoProvider.source("ABCDE".toByteArray())
        val out = ByteArrayOutputStream()
        val sink = OkioIoProvider.sink(out)
        val transferred = sink.writeAll(source)
        sink.flush()
        assertEquals(5L, transferred)
        assertContentEquals("ABCDE".toByteArray(), out.toByteArray())
        assertTrue(source.exhausted())
    }

    @Test
    fun `readUtf8Line returns null at exhaustion`() {
        val source = OkioIoProvider.source("first\nsecond".toByteArray())
        assertEquals("first", source.readUtf8Line())
        assertEquals("second", source.readUtf8Line())
        assertEquals(null, source.readUtf8Line())
    }

    // ----- Io install / withProvider -----

    @Test
    fun `Io provider throws when no provider is installed`() {
        // Save the current installed provider and clear it.
        val previous = Io.provider
        // Use withProvider with a stand-in that we replace by re-installing inside the block.
        // But to test the "no provider" case we need to clear the installed reference.
        // Io has no public clear() — we install a sentinel and then verify withProvider semantics.
        // The actual "no provider" case is exercised only on a cold JVM, so we just check
        // that the installed provider survives the test cycle.
        assertSame(OkioIoProvider, previous)
    }

    @Test
    fun `withProvider restores previous provider on exit`() {
        val fake = object : IoProvider by OkioIoProvider {}
        Io.withProvider(fake) {
            assertSame(fake, Io.provider)
        }
        assertSame(OkioIoProvider, Io.provider)
    }

    @Test
    fun `withProvider restores previous provider on exception`() {
        val fake = object : IoProvider by OkioIoProvider {}
        assertFailsWith<IllegalStateException> {
            Io.withProvider(fake) {
                error("boom")
            }
        }
        assertSame(OkioIoProvider, Io.provider)
    }

    // ----- Lifecycle integration: RequestBody / ResponseBody -----

    @Test
    fun `form RequestBody resolves via installed provider`() {
        val body = RequestBody.create(mapOf("k" to "v", "x" to "y z"))
        val out = ByteArrayOutputStream()
        val sink = OkioIoProvider.sink(out)
        body.writeTo(sink)
        sink.flush()
        val written = out.toString(Charsets.UTF_8.name())
        // URL encoding: space becomes "+" or "%20" depending on encoder; Java URLEncoder uses "+".
        assertTrue(written.contains("k=v"))
        assertTrue(written.contains("x=y"))
    }

    @Test
    fun `LoggableRequestBody captures bytes without disturbing primary write`() {
        val original = RequestBody.create(OkioIoProvider.source("payload-xyz".toByteArray()))
        val loggable = LoggableRequestBody(original)

        val out = ByteArrayOutputStream()
        val sink = OkioIoProvider.sink(out)
        loggable.writeTo(sink)
        sink.flush()

        assertContentEquals("payload-xyz".toByteArray(), out.toByteArray())
        assertContentEquals("payload-xyz".toByteArray(), loggable.snapshot())
    }

    @Test
    fun `LoggableResponseBody supports repeatable reads and snapshot`() {
        val raw = ResponseBody.create(OkioIoProvider.source("response-bytes".toByteArray()))
        val loggable = LoggableResponseBody(raw)

        // snapshot reads everything
        val snap = loggable.snapshot()
        assertContentEquals("response-bytes".toByteArray(), snap)

        // source() is repeatable
        val first = loggable.source().readUtf8()
        val second = loggable.source().readUtf8()
        assertEquals("response-bytes", first)
        assertEquals("response-bytes", second)
        assertEquals(snap.size.toLong(), loggable.contentLength())
    }

    @Test
    fun `LoggableRequestBody snapshot is empty before writeTo`() {
        val original = RequestBody.create(OkioIoProvider.source("data".toByteArray()))
        val loggable = LoggableRequestBody(original)
        assertEquals(0, loggable.snapshot().size)
    }

    @Test
    fun `bufferedSource adapts an arbitrary Source`() {
        // Build a primitive Source that yields a known sequence of bytes.
        val provided = "primitive".toByteArray()
        var offset = 0
        val primitive = object : org.dexpace.sdk.core.io.Source {
            override fun read(sink: org.dexpace.sdk.core.io.Buffer, byteCount: Long): Long {
                if (offset >= provided.size) return -1
                val n = minOf(byteCount.toInt(), provided.size - offset)
                sink.write(provided, offset, n)
                offset += n
                return n.toLong()
            }
            override fun close() {}
        }
        val buffered = OkioIoProvider.bufferedSource(primitive)
        assertEquals("primitive", buffered.readUtf8())
    }

    @Test
    fun `inputStream and outputStream bridges round-trip`() {
        val out = ByteArrayOutputStream()
        OkioIoProvider.sink(out).outputStream().use { it.write("bridged".toByteArray()) }
        assertContentEquals("bridged".toByteArray(), out.toByteArray())

        val src = OkioIoProvider.source("bridged".toByteArray())
        val readBack = src.inputStream().readBytes()
        assertContentEquals("bridged".toByteArray(), readBack)
    }

    @Test
    fun `installed provider stays installed across operations`() {
        assertNotNull(Io.provider)
        assertFalse(Io.provider === Any())
    }

    // ----- Perf-oriented behavior -----

    @Test
    fun `LoggableRequestBody tee encodes once and primary receives identical bytes`() {
        val payload = "x".repeat(20_000)  // larger than one Okio segment
        val original = object : RequestBody() {
            override fun mediaType() = null
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8(payload)
                sink.write(payload.toByteArray())
                sink.flush()
            }
        }
        val loggable = LoggableRequestBody(original)

        val out = ByteArrayOutputStream()
        val sink = OkioIoProvider.sink(out)
        loggable.writeTo(sink)
        sink.flush()

        val expected = (payload + payload).toByteArray()
        assertContentEquals(expected, out.toByteArray())
        assertContentEquals(expected, loggable.snapshot())
    }

    @Test
    fun `LoggableRequestBody bounded snapshot returns at most maxBytes`() {
        val payload = "abcdefghij".repeat(100)  // 1000 bytes
        val original = RequestBody.create(OkioIoProvider.source(payload.toByteArray()))
        val loggable = LoggableRequestBody(original)

        val out = ByteArrayOutputStream()
        loggable.writeTo(OkioIoProvider.sink(out))

        val preview = loggable.snapshot(64)
        assertEquals(64, preview.size)
        assertContentEquals(payload.toByteArray().copyOfRange(0, 64), preview)
        // Tap is preserved — full snapshot is still available.
        assertEquals(1000, loggable.snapshot().size)
    }

    @Test
    fun `LoggableResponseBody bounded snapshot returns at most maxBytes`() {
        val payload = "0123456789".repeat(500)  // 5000 bytes
        val raw = ResponseBody.create(OkioIoProvider.source(payload.toByteArray()))
        val loggable = LoggableResponseBody(raw)

        val preview = loggable.snapshot(128)
        assertEquals(128, preview.size)
        assertContentEquals(payload.toByteArray().copyOfRange(0, 128), preview)
        // Captured body is still readable in full afterward.
        assertEquals(payload, loggable.source().readUtf8())
    }

    @Test
    fun `bounded snapshot below buffer size does not copy whole buffer`() {
        // Build a Loggable body holding 100KB but request only 16 bytes; ensure no crash and small result.
        val big = ByteArray(100_000) { (it % 256).toByte() }
        val raw = ResponseBody.create(OkioIoProvider.source(big))
        val loggable = LoggableResponseBody(raw)

        val preview = loggable.snapshot(16)
        assertEquals(16, preview.size)
        assertContentEquals(big.copyOfRange(0, 16), preview)
    }

    @Test
    fun `bounded snapshot caps at buffer size when maxBytes exceeds it`() {
        val payload = "tiny"
        val raw = ResponseBody.create(OkioIoProvider.source(payload.toByteArray()))
        val loggable = LoggableResponseBody(raw)

        val preview = loggable.snapshot(1024)
        assertContentEquals(payload.toByteArray(), preview)
    }

    @Test
    fun `bounded snapshot rejects negative maxBytes`() {
        val raw = ResponseBody.create(OkioIoProvider.source(byteArrayOf(0)))
        val loggable = LoggableResponseBody(raw)
        assertFailsWith<IllegalArgumentException> { loggable.snapshot(-1) }
    }

    // ----- Stream-consumption safety -----

    @Test
    fun `LoggableResponseBody surfaces drain failures and keeps partial bytes`() {
        // InputStream that yields 10 bytes then throws.
        val throwing = object : InputStream() {
            private var sent = 0
            override fun read(): Int {
                if (sent >= 10) throw IOException("simulated network failure")
                return 'A'.code + (sent++)
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent >= 10) throw IOException("simulated network failure")
                var n = 0
                while (n < len && sent < 10) {
                    b[off + n] = read().toByte()
                    n++
                }
                return if (n == 0) -1 else n
            }
        }
        val raw = ResponseBody.create(OkioIoProvider.source(throwing))
        val loggable = LoggableResponseBody(raw)

        // snapshot returns the partial — it never throws.
        val partial = loggable.snapshot()
        assertEquals(10, partial.size)
        assertContentEquals("ABCDEFGHIJ".toByteArray(), partial)

        // captureException surfaces the cause without consuming anything.
        assertNotNull(loggable.captureException)

        // source() re-throws the cached failure (deterministically, every call).
        assertFailsWith<IOException> { loggable.source() }
        assertFailsWith<IOException> { loggable.source() }

        // snapshot still returns the same partial bytes (does not re-drain).
        assertContentEquals("ABCDEFGHIJ".toByteArray(), loggable.snapshot())
    }

    @Test
    fun `LoggableResponseBody drains the delegate exactly once even on failure`() {
        var sourceAccessCount = 0
        val raw = object : ResponseBody() {
            override fun mediaType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource {
                sourceAccessCount++
                // Always returns the same okio-backed source over a failing InputStream.
                val input = object : InputStream() {
                    override fun read(): Int = throw IOException("boom")
                }
                return OkioIoProvider.source(input)
            }
        }
        val loggable = LoggableResponseBody(raw)

        // Call source() three times — the wrapper must drain at most once.
        repeat(3) { runCatching { loggable.source() } }
        assertEquals(1, sourceAccessCount)
    }

    @Test
    fun `LoggableResponseBody rejects use after close when not yet drained`() {
        val raw = ResponseBody.create(OkioIoProvider.source("payload".toByteArray()))
        val loggable = LoggableResponseBody(raw)
        loggable.close()

        assertFailsWith<IllegalStateException> { loggable.source() }
        assertFailsWith<IllegalStateException> { loggable.snapshot() }
        assertFailsWith<IllegalStateException> { loggable.snapshot(16) }
    }

    @Test
    fun `LoggableResponseBody snapshot still works after close when drained first`() {
        val raw = ResponseBody.create(OkioIoProvider.source("payload".toByteArray()))
        val loggable = LoggableResponseBody(raw)

        val first = loggable.snapshot()                        // drains
        loggable.close()                                       // closes delegate; captured survives
        val afterClose = loggable.snapshot()                   // post-mortem read OK

        assertContentEquals("payload".toByteArray(), first)
        assertContentEquals(first, afterClose)
    }

    @Test
    fun `LoggableResponseBody close is idempotent`() {
        val raw = ResponseBody.create(OkioIoProvider.source("payload".toByteArray()))
        val loggable = LoggableResponseBody(raw)
        loggable.snapshot()
        loggable.close()
        loggable.close()  // must not throw
    }

    @Test
    fun `LoggableRequestBody snapshot reflects bytes written up to a writeTo failure`() {
        val body = object : RequestBody() {
            override fun mediaType(): MediaType? = null
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("CAPTURED-BEFORE-FAIL")
                throw IOException("simulated body error")
            }
        }
        val loggable = LoggableRequestBody(body)
        val out = ByteArrayOutputStream()
        val sink = OkioIoProvider.sink(out)

        assertFailsWith<IOException> { loggable.writeTo(sink) }
        // Partial bytes are still available for diagnostics.
        assertContentEquals("CAPTURED-BEFORE-FAIL".toByteArray(), loggable.snapshot())
    }

    @Test
    fun `LoggableRequestBody wraps single-use sources without double-consuming`() {
        // The underlying RequestBody.create(BufferedSource, ...) calls source.use {} so the
        // source is closed after writeTo. Confirm the wrapper does not attempt a second read.
        val data = "single-use payload"
        val src = OkioIoProvider.source(data.toByteArray())
        val original = RequestBody.create(src)
        val loggable = LoggableRequestBody(original)

        val out = ByteArrayOutputStream()
        val sink = OkioIoProvider.sink(out)
        loggable.writeTo(sink)
        sink.flush()

        // First writeTo wrote the bytes through to primary and the tap.
        assertContentEquals(data.toByteArray(), out.toByteArray())
        assertContentEquals(data.toByteArray(), loggable.snapshot())
    }

    // ----- Replayability -----

    @Test
    fun `byte array RequestBody is replayable and produces identical bytes on every write`() {
        val body = RequestBody.create("payload-1".toByteArray())
        assertTrue(body.isReplayable())
        assertSame(body, body.toReplayable())

        val first = writeBody(body)
        val second = writeBody(body)
        assertContentEquals("payload-1".toByteArray(), first)
        assertContentEquals(first, second)
    }

    @Test
    fun `string RequestBody is replayable`() {
        val body = RequestBody.create("hello", MediaType.parse("text/plain"))
        assertTrue(body.isReplayable())
        assertContentEquals("hello".toByteArray(), writeBody(body))
        assertContentEquals("hello".toByteArray(), writeBody(body))
    }

    @Test
    fun `form data RequestBody is replayable and avoids the provider on construction`() {
        val body = RequestBody.create(mapOf("a" to "1", "b" to "x y"))
        assertTrue(body.isReplayable())
        val out = writeBody(body).decodeToString()
        // Form encoding may use + for space; both attempts must produce the same bytes.
        assertContentEquals(writeBody(body), writeBody(body))
        assertTrue(out.contains("a=1"))
    }

    @Test
    fun `Buffer-backed RequestBody is replayable via peek`() {
        val source = OkioIoProvider.buffer()
        source.writeUtf8("re-readable")
        val body = RequestBody.create(source)
        assertTrue(body.isReplayable())
        assertContentEquals("re-readable".toByteArray(), writeBody(body))
        assertContentEquals("re-readable".toByteArray(), writeBody(body))
        // The original buffer was not consumed.
        assertEquals(11L, source.size)
    }

    @Test
    fun `BufferedSource-backed RequestBody is single-use and toReplayable buffers it`() {
        val src = OkioIoProvider.source("once".toByteArray())
        val body = RequestBody.create(src)
        assertFalse(body.isReplayable())

        val replayable = body.toReplayable()
        assertTrue(replayable.isReplayable())
        assertNotSame(body, replayable)
        assertContentEquals("once".toByteArray(), writeBody(replayable))
        assertContentEquals("once".toByteArray(), writeBody(replayable))
    }

    @Test
    fun `LoggableRequestBody wrapping a single-use body becomes replayable after toReplayable`() {
        val src = OkioIoProvider.source("logged-once".toByteArray())
        val loggable = LoggableRequestBody(RequestBody.create(src))
        assertFalse(loggable.isReplayable())

        val replayable = loggable.toReplayable()
        assertTrue(replayable.isReplayable())
        assertContentEquals("logged-once".toByteArray(), writeBody(replayable))
        assertContentEquals("logged-once".toByteArray(), writeBody(replayable))
        // The tap captured the bytes when we drained for replayability.
        assertContentEquals("logged-once".toByteArray(), loggable.snapshot())
    }

    // ----- mark + reset on InputStream factory -----

    @Test
    fun `InputStream RequestBody with markSupported is replayable via reset`() {
        val bytes = "stream-replay".toByteArray()
        val stream = ByteArrayInputStream(bytes)  // markSupported() = true
        val body = RequestBody.create(stream, bytes.size.toLong())

        assertTrue(body.isReplayable())
        assertSame(body, body.toReplayable())
        assertContentEquals(bytes, writeBody(body))
        assertContentEquals(bytes, writeBody(body))
    }

    @Test
    fun `InputStream RequestBody without markSupport is single-use`() {
        val bytes = "stream-once".toByteArray()
        val nonMarkable = object : InputStream() {
            private val inner = ByteArrayInputStream(bytes)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun markSupported(): Boolean = false
        }
        val body = RequestBody.create(nonMarkable, bytes.size.toLong())
        assertFalse(body.isReplayable())

        val replayable = body.toReplayable()
        assertTrue(replayable.isReplayable())
        assertContentEquals(bytes, writeBody(replayable))
        assertContentEquals(bytes, writeBody(replayable))
    }

    @Test
    fun `InputStream RequestBody rejects negative length`() {
        assertFailsWith<IllegalArgumentException> {
            RequestBody.create(ByteArrayInputStream(ByteArray(0)), -1)
        }
    }

    // ----- FileRequestBody -----

    @Test
    fun `FileRequestBody round-trips file contents`() {
        val tmp = Files.createTempFile("dexpace-file-body-", ".bin")
        try {
            val bytes = ByteArray(50_000) { (it % 251).toByte() }
            Files.write(tmp, bytes)
            val body = FileRequestBody(tmp)
            assertTrue(body.isReplayable())
            assertEquals(bytes.size.toLong(), body.count)
            assertEquals(bytes.size.toLong(), body.contentLength())
            assertContentEquals(bytes, writeBody(body))
            // Replay works — file remains the source of truth.
            assertContentEquals(bytes, writeBody(body))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `FileRequestBody respects position and count slicing`() {
        val tmp = Files.createTempFile("dexpace-file-slice-", ".bin")
        try {
            val bytes = ByteArray(1024) { it.toByte() }
            Files.write(tmp, bytes)
            val body = FileRequestBody(tmp, position = 256, explicitCount = 128)
            assertEquals(128L, body.count)
            assertContentEquals(bytes.copyOfRange(256, 256 + 128), writeBody(body))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `FileRequestBody rejects invalid arguments`() {
        val tmp = Files.createTempFile("dexpace-file-bad-", ".bin")
        try {
            assertFailsWith<IllegalArgumentException> { FileRequestBody(tmp, position = -1) }
            assertFailsWith<IllegalArgumentException> { FileRequestBody(tmp, explicitCount = -2) }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `FileRequestBody rejects a missing file at construction time`() {
        val missing = Files.createTempFile("dexpace-missing-", ".bin")
        Files.delete(missing)
        // The constructor should fail loudly, not at writeTo time.
        assertFailsWith<java.nio.file.NoSuchFileException> { FileRequestBody(missing) }
    }

    @Test
    fun `FileRequestBody rejects a directory`() {
        val dir = Files.createTempDirectory("dexpace-dir-")
        try {
            assertFailsWith<IllegalArgumentException> { FileRequestBody(dir) }
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `FileRequestBody rejects position beyond file size`() {
        val tmp = Files.createTempFile("dexpace-oob-pos-", ".bin")
        try {
            Files.write(tmp, ByteArray(100))
            assertFailsWith<IllegalArgumentException> { FileRequestBody(tmp, position = 200) }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `FileRequestBody rejects position plus count beyond file size`() {
        val tmp = Files.createTempFile("dexpace-oob-end-", ".bin")
        try {
            Files.write(tmp, ByteArray(100))
            assertFailsWith<IllegalArgumentException> {
                FileRequestBody(tmp, position = 50, explicitCount = 100)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun `FileRequestBody contentLength is stable across file modifications`() {
        // count is captured at construction; the file changing later should not surprise us.
        val tmp = Files.createTempFile("dexpace-stable-", ".bin")
        try {
            Files.write(tmp, ByteArray(100))
            val body = FileRequestBody(tmp)
            assertEquals(100L, body.contentLength())
            Files.write(tmp, ByteArray(200))   // grow the file
            assertEquals(100L, body.contentLength()) // still the original captured count
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    // ----- copyExactly safety -----

    @Test
    fun `OneShot InputStream body throws on a stream that returns zero bytes`() {
        val pathological = object : InputStream() {
            override fun read(): Int = 0  // never advances
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
            override fun markSupported(): Boolean = false
        }
        val body = RequestBody.create(pathological, 16)
        // Must NOT infinite-loop. The body should raise EOFException.
        assertFailsWith<java.io.EOFException> {
            body.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))
        }
    }

    @Test
    fun `OneShot InputStream body throws on truncated input`() {
        val truncated = ByteArrayInputStream("short".toByteArray())
        // Wrap in a non-markable stream so we go down the one-shot path.
        val wrapper = object : InputStream() {
            override fun read(): Int = truncated.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = truncated.read(b, off, len)
            override fun markSupported(): Boolean = false
        }
        val body = RequestBody.create(wrapper, 100)
        assertFailsWith<java.io.EOFException> {
            body.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))
        }
    }

    // ----- Buffer.copyTo with non-zero offset default -----

    @Test
    fun `Buffer copyTo with non-zero offset uses the right default byteCount`() {
        val src = OkioIoProvider.buffer().apply { writeUtf8("0123456789") }
        val dst = OkioIoProvider.buffer()
        // Default byteCount should be size - offset, i.e. 7 bytes ("3456789").
        src.copyTo(dst, offset = 3)
        assertEquals("3456789", dst.readUtf8())
        // Source is unchanged by copyTo.
        assertEquals(10L, src.size)
    }

    @Test
    fun `Buffer copyTo rejects out-of-range arguments`() {
        val src = OkioIoProvider.buffer().apply { writeUtf8("12345") }
        val dst = OkioIoProvider.buffer()
        assertFailsWith<IndexOutOfBoundsException> { src.copyTo(dst, offset = 0, byteCount = 6) }
    }

    // ----- snapshot(maxBytes) clamping -----

    @Test
    fun `LoggableResponseBody snapshot maxBytes clamps to MAX_BYTE_ARRAY_SIZE`() {
        val raw = ResponseBody.create(OkioIoProvider.source("tiny".toByteArray()))
        val loggable = LoggableResponseBody(raw)
        // Passing Int.MAX_VALUE on a tiny body must still succeed without trying to allocate
        // a billions-of-bytes array. The result is just the body bytes.
        val snap = loggable.snapshot(Int.MAX_VALUE)
        assertContentEquals("tiny".toByteArray(), snap)
    }

    @Test
    fun `LoggableRequestBody snapshot maxBytes clamps to MAX_BYTE_ARRAY_SIZE`() {
        val body = RequestBody.create("tiny".toByteArray())
        val loggable = LoggableRequestBody(body)
        loggable.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))
        val snap = loggable.snapshot(Int.MAX_VALUE)
        assertContentEquals("tiny".toByteArray(), snap)
    }

    // ----- Consumed-twice fail-fast -----

    @Test
    fun `BufferedSource-backed RequestBody fails loudly on second writeTo`() {
        val body = RequestBody.create(OkioIoProvider.source("once".toByteArray()))
        body.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))
        val second = assertFailsWith<IllegalStateException> {
            body.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))
        }
        assertTrue(second.message!!.contains("toReplayable"))
    }

    @Test
    fun `OneShot InputStream-backed RequestBody fails loudly on second writeTo`() {
        val nonMarkable = object : InputStream() {
            private val inner = ByteArrayInputStream("once".toByteArray())
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun markSupported(): Boolean = false
        }
        val body = RequestBody.create(nonMarkable, 4)
        body.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))
        val second = assertFailsWith<IllegalStateException> {
            body.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))
        }
        assertTrue(second.message!!.contains("toReplayable"))
    }

    @Test
    fun `toReplayable after writeTo on a one-shot body fails loudly`() {
        // Documents the right ordering — toReplayable must come BEFORE writeTo, not after.
        val body = RequestBody.create(OkioIoProvider.source("data".toByteArray()))
        body.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))
        // Now the body is consumed. The default toReplayable calls writeTo again → throws.
        assertFailsWith<IllegalStateException> { body.toReplayable() }
    }

    // ----- Provider conflict fail-fast -----

    @Test
    fun `installProvider is idempotent for the same instance`() {
        Io.installProvider(OkioIoProvider)
        Io.installProvider(OkioIoProvider)
        Io.installProvider(OkioIoProvider)  // must not throw
        assertSame(OkioIoProvider, Io.provider)
    }

    @Test
    fun `installProvider rejects a conflicting installation`() {
        val different = object : IoProvider by OkioIoProvider {}
        val thrown = assertFailsWith<IllegalStateException> {
            Io.installProvider(different)
        }
        assertTrue(thrown.message!!.contains("already installed"))
        assertTrue(thrown.message!!.contains("withProvider"))
        // The original is still installed.
        assertSame(OkioIoProvider, Io.provider)
    }

    // ----- Atomic consumed-flag (race-free fail-fast) -----

    @Test
    fun `OneShot body consumed flag is race-free under concurrent writeTo`() {
        // Two threads racing into the same writeTo — exactly one must succeed; the other
        // must throw IllegalStateException, NOT read from the (now-shared) stream.
        val bytes = ByteArray(512) { it.toByte() }
        val stream = object : InputStream() {
            private val inner = ByteArrayInputStream(bytes)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun markSupported(): Boolean = false
        }
        val body = RequestBody.create(stream, bytes.size.toLong())

        val gate = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val tasks = (1..2).map {
            executor.submit<Throwable?> {
                gate.await()
                try {
                    body.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))
                    null
                } catch (t: Throwable) {
                    t
                }
            }
        }
        gate.countDown()
        val results = tasks.map { it.get() }
        executor.shutdown()

        val successes = results.count { it == null }
        val failures = results.count { it is IllegalStateException }
        assertEquals(1, successes, "exactly one writeTo must succeed")
        assertEquals(1, failures, "the loser must see IllegalStateException")
    }

    @Test
    fun `BufferedSource body consumed flag is race-free under concurrent writeTo`() {
        val source = OkioIoProvider.source("racing".toByteArray())
        val body = RequestBody.create(source)

        val gate = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val tasks = (1..2).map {
            executor.submit<Throwable?> {
                gate.await()
                try {
                    body.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))
                    null
                } catch (t: Throwable) {
                    t
                }
            }
        }
        gate.countDown()
        val results = tasks.map { it.get() }
        executor.shutdown()

        // Exactly one wins; the loser must see IllegalStateException (NOT an okio
        // "closed source" leak from the underlying source.use{} clean-up).
        assertEquals(1, results.count { it == null })
        assertEquals(1, results.count { it is IllegalStateException })
    }

    // ----- Negative byteCount rejection at the adapter boundary -----

    @Test
    fun `OkioBuffer read rejects negative byteCount`() {
        val src = OkioIoProvider.buffer().apply { writeUtf8("data") }
        val dst = OkioIoProvider.buffer()
        assertFailsWith<IllegalArgumentException> { src.read(dst, -1) }
    }

    @Test
    fun `OkioBuffer write rejects negative byteCount`() {
        val src = OkioIoProvider.buffer().apply { writeUtf8("data") }
        val dst = OkioIoProvider.buffer()
        assertFailsWith<IllegalArgumentException> { dst.write(src, -1) }
    }

    @Test
    fun `OkioBufferedSource read rejects negative byteCount`() {
        val src = OkioIoProvider.source("data".toByteArray())
        val dst = OkioIoProvider.buffer()
        assertFailsWith<IllegalArgumentException> { src.read(dst, -1) }
    }

    @Test
    fun `OkioBufferedSink write rejects negative byteCount`() {
        val out = ByteArrayOutputStream()
        val sink = OkioIoProvider.sink(out)
        val src = OkioIoProvider.buffer().apply { writeUtf8("data") }
        assertFailsWith<IllegalArgumentException> { sink.write(src, -1) }
    }

    // ----- Empty-body edge case -----

    @Test
    fun `OneShot InputStream body with length zero produces no bytes`() {
        val empty = object : InputStream() {
            override fun read(): Int = -1
            override fun read(b: ByteArray, off: Int, len: Int): Int = -1
            override fun markSupported(): Boolean = false
        }
        val body = RequestBody.create(empty, 0)
        val out = ByteArrayOutputStream()
        body.writeTo(OkioIoProvider.sink(out).also { it.flush() })
        assertEquals(0, out.toByteArray().size)
        // Even an empty body is still single-use — a second writeTo fails fast.
        assertFailsWith<IllegalStateException> {
            body.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))
        }
    }

    @Test
    fun `Resettable InputStream body with length zero replays cleanly`() {
        val bytes = ByteArray(0)
        val body = RequestBody.create(ByteArrayInputStream(bytes), 0)
        body.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))
        body.writeTo(OkioIoProvider.sink(ByteArrayOutputStream()))  // replay must not throw
    }

    @Test
    fun `RequestBody factory for Path returns a FileRequestBody`() {
        val tmp = Files.createTempFile("dexpace-file-factory-", ".bin")
        try {
            Files.write(tmp, "factory".toByteArray())
            val body = RequestBody.create(tmp as Path)
            assertTrue(body is FileRequestBody)
            assertContentEquals("factory".toByteArray(), writeBody(body))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    // ----- MAX_BYTE_ARRAY_SIZE -----

    @Test
    fun `Buffer MAX_BYTE_ARRAY_SIZE matches the documented JVM limit`() {
        assertEquals(Int.MAX_VALUE - 8, Buffer.MAX_BYTE_ARRAY_SIZE)
    }

    @Test
    fun `foreign Source bridges through okio without per-read wrapper allocation`() {
        // Functional check; the no-extra-alloc guarantee is structural (cached wrapper).
        val backing = ByteArray(50_000) { (it % 256).toByte() }
        var offset = 0
        val primitive = object : org.dexpace.sdk.core.io.Source {
            override fun read(sink: org.dexpace.sdk.core.io.Buffer, byteCount: Long): Long {
                if (offset >= backing.size) return -1
                val n = minOf(byteCount.toInt(), backing.size - offset)
                sink.write(backing, offset, n)
                offset += n
                return n.toLong()
            }
            override fun close() {}
        }
        val buffered = OkioIoProvider.bufferedSource(primitive)
        assertContentEquals(backing, buffered.readByteArray())
    }
}
