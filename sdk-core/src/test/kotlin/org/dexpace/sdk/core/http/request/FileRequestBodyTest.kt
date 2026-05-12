package org.dexpace.sdk.core.http.request

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileRequestBodyTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        // Delete in reverse order so any held mappings on Windows aren't blocking
        // earlier files. Failure to delete is non-fatal for the test outcome.
        for (path in tempFiles.reversed()) {
            try {
                Files.deleteIfExists(path)
            } catch (_: Throwable) {
                // best effort
            }
        }
        tempFiles.clear()
    }

    private fun tempFile(prefix: String = "frb-test", suffix: String = ".bin"): Path {
        val p = Files.createTempFile(prefix, suffix)
        tempFiles.add(p)
        return p
    }

    @Test
    fun `toByteBuffer of small file has remaining equal to file size`() {
        val file = tempFile()
        val bytes = ByteArray(64) { it.toByte() }
        Files.write(file, bytes)

        val body = FileRequestBody(file)
        val mapped = body.toByteBuffer()

        assertEquals(bytes.size, mapped.remaining())
    }

    @Test
    fun `returned buffer is read-only`() {
        val file = tempFile()
        Files.write(file, ByteArray(16) { it.toByte() })

        val mapped = FileRequestBody(file).toByteBuffer()

        assertTrue(mapped.isReadOnly, "mapped buffer should report isReadOnly = true")
        assertFailsWith<ReadOnlyBufferException> {
            mapped.put(0, 0xFF.toByte())
        }
    }

    @Test
    fun `mapping a slice returns only the requested region`() {
        val file = tempFile()
        // Bytes [0, 1, 2, ..., 31].
        val bytes = ByteArray(32) { it.toByte() }
        Files.write(file, bytes)

        // Map [8, 8+16) → bytes 8..23.
        val body = FileRequestBody(file = file, position = 8L, explicitCount = 16L)
        val mapped = body.toByteBuffer()

        assertEquals(16, mapped.remaining())
        val readBack = ByteArray(16)
        mapped.get(readBack)
        for (i in 0 until 16) {
            assertEquals((8 + i).toByte(), readBack[i], "byte $i mismatch")
        }
    }

    @Test
    fun `empty file maps to a buffer with remaining zero`() {
        val file = tempFile()
        // Files.createTempFile creates a zero-byte file already, but write to be explicit.
        Files.write(file, ByteArray(0))

        val mapped = FileRequestBody(file).toByteBuffer()

        assertEquals(0, mapped.remaining())
    }

    @Test
    fun `count greater than Int MAX VALUE throws IllegalStateException`() {
        val file = tempFile()
        Files.write(file, ByteArray(1) { 0 })

        val body = FileRequestBody(file)
        // Force `count` past the Int boundary via reflection — the constructor's bounds
        // check would otherwise reject any value that exceeds the on-disk file size.
        val countField = FileRequestBody::class.java.getDeclaredField("count")
        countField.isAccessible = true
        countField.setLong(body, Integer.MAX_VALUE.toLong() + 1L)

        val ex = assertFailsWith<IllegalStateException> { body.toByteBuffer() }
        assertContains(ex.message ?: "", "count=${Integer.MAX_VALUE.toLong() + 1L}")
        assertContains(ex.message ?: "", "smaller slice")
    }

    @Test
    fun `buffer survives channel close and is still readable`() {
        val file = tempFile()
        val payload = "hello mapped file".toByteArray(Charsets.UTF_8)
        Files.write(file, payload)

        // After this call returns, the FileChannel.open(...).use { } block has closed
        // the channel. The mapping must still be readable.
        val mapped: ByteBuffer = FileRequestBody(file).toByteBuffer()

        val readBack = ByteArray(payload.size)
        mapped.get(readBack)
        assertEquals(payload.toList(), readBack.toList())
    }

    @Test
    fun `deletion between construction and toByteBuffer throws NoSuchFileException`() {
        val file = tempFile()
        Files.write(file, ByteArray(8) { it.toByte() })

        val body = FileRequestBody(file)
        Files.delete(file)
        // The temp-cleanup AfterTest hook tolerates an already-deleted path.

        assertFailsWith<NoSuchFileException> { body.toByteBuffer() }
    }

    @Test
    fun `reading the mapping returns the actual file content`() {
        val file = tempFile()
        val payload = ByteArray(256) { (it * 7 + 3).toByte() }
        Files.write(file, payload)

        val mapped = FileRequestBody(file).toByteBuffer()

        val readBack = ByteArray(payload.size)
        mapped.get(readBack)
        assertEquals(payload.toList(), readBack.toList())
    }

    @Test
    fun `mapping is independent of subsequent file truncation after read`() {
        // Once we've copied bytes out of the mapping into a heap ByteArray, mutating the
        // underlying file must not corrupt that array. This is a defensive sanity check on
        // the read path, not a guarantee about the live mapping after truncation (Linux:
        // surviving copy-on-write semantics; Windows: undefined).
        val file = tempFile()
        val payload = ByteArray(64) { it.toByte() }
        Files.write(file, payload)

        val mapped = FileRequestBody(file).toByteBuffer()
        val snapshot = ByteArray(payload.size)
        mapped.get(snapshot)

        RandomAccessFile(file.toFile(), "rw").use { raf -> raf.setLength(0L) }

        assertEquals(payload.toList(), snapshot.toList())
    }
}
