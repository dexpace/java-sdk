/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileRequestBodyTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

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

    private fun tempFile(
        prefix: String = "frb-test",
        suffix: String = ".bin",
    ): Path {
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

    // ---------------------------------------------------------------------
    // mediaType / contentLength / writeTo / isReplayable / toReplayable
    // ---------------------------------------------------------------------

    @Test
    fun `mediaType returns the value supplied to the constructor`() {
        val file = tempFile()
        Files.write(file, "x".toByteArray())
        val type = MediaType.parse("application/octet-stream")
        val body = FileRequestBody(file, type)
        assertSame(type, body.mediaType())
    }

    @Test
    fun `mediaType is null when not supplied`() {
        val file = tempFile()
        Files.write(file, "x".toByteArray())
        assertNull(FileRequestBody(file).mediaType())
    }

    @Test
    fun `contentLength equals file size for full file`() {
        val file = tempFile()
        Files.write(file, ByteArray(123) { it.toByte() })
        val body = FileRequestBody(file)
        assertEquals(123L, body.contentLength())
        assertEquals(123L, body.count)
    }

    @Test
    fun `contentLength equals explicit count when slice is specified`() {
        val file = tempFile()
        Files.write(file, ByteArray(100) { it.toByte() })
        val body = FileRequestBody(file = file, position = 10, explicitCount = 50)
        assertEquals(50L, body.contentLength())
    }

    @Test
    fun `writeTo emits the full file contents via the buffered sink`() {
        val file = tempFile()
        val payload = ByteArray(256) { it.toByte() }
        Files.write(file, payload)

        val sink = Io.provider.buffer()
        FileRequestBody(file).writeTo(sink)

        assertContentEquals(payload, sink.snapshot())
    }

    @Test
    fun `writeTo emits only the requested slice when position and count are set`() {
        val file = tempFile()
        val payload = ByteArray(64) { it.toByte() }
        Files.write(file, payload)

        val body = FileRequestBody(file = file, position = 8L, explicitCount = 16L)
        val sink = Io.provider.buffer()
        body.writeTo(sink)

        val emitted = sink.snapshot()
        assertEquals(16, emitted.size)
        for (i in 0 until 16) {
            assertEquals((8 + i).toByte(), emitted[i])
        }
    }

    @Test
    fun `isReplayable returns true and supports repeated writes`() {
        val file = tempFile()
        val payload = "replayable".toByteArray()
        Files.write(file, payload)

        val body = FileRequestBody(file)
        assertTrue(body.isReplayable())

        val firstSink = Io.provider.buffer()
        body.writeTo(firstSink)
        val secondSink = Io.provider.buffer()
        body.writeTo(secondSink)

        assertContentEquals(payload, firstSink.snapshot())
        assertContentEquals(payload, secondSink.snapshot())
    }

    @Test
    fun `toReplayable returns this because the file is the source of truth`() {
        val file = tempFile()
        Files.write(file, "x".toByteArray())
        val body = FileRequestBody(file)
        assertSame(body, body.toReplayable(Io.provider))
    }

    @Test
    fun `constructor rejects negative position`() {
        val file = tempFile()
        Files.write(file, "x".toByteArray())
        assertFailsWith<IllegalArgumentException> {
            FileRequestBody(file = file, position = -1L)
        }
    }

    @Test
    fun `constructor rejects negative explicit count except minus one`() {
        val file = tempFile()
        Files.write(file, "x".toByteArray())
        assertFailsWith<IllegalArgumentException> {
            FileRequestBody(file = file, explicitCount = -2L)
        }
    }

    @Test
    fun `constructor rejects position larger than file size`() {
        val file = tempFile()
        Files.write(file, ByteArray(4) { it.toByte() })
        assertFailsWith<IllegalArgumentException> {
            FileRequestBody(file = file, position = 99L)
        }
    }

    @Test
    fun `constructor rejects position plus count larger than file size`() {
        val file = tempFile()
        Files.write(file, ByteArray(4) { it.toByte() })
        assertFailsWith<IllegalArgumentException> {
            FileRequestBody(file = file, position = 2L, explicitCount = 10L)
        }
    }

    @Test
    fun `constructor rejects missing file`() {
        val dir = Files.createTempDirectory("frb-missing")
        val missing = dir.resolve("does-not-exist.bin")
        tempFiles.add(dir)
        assertFailsWith<NoSuchFileException> {
            FileRequestBody(missing)
        }
    }

    @Test
    fun `constructor rejects directory paths`() {
        val dir = Files.createTempDirectory("frb-dir")
        tempFiles.add(dir)
        assertFailsWith<IllegalArgumentException> {
            FileRequestBody(dir)
        }
    }
}
