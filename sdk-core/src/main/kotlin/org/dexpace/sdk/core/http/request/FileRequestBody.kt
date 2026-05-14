package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.IoProvider
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A [RequestBody] backed by a file on disk. Inherently replayable — the file is the source
 * of truth — and supports byte-range slicing via [position] and [count].
 *
 * ## Validation
 *
 * The constructor validates fail-fast: the file must exist, must be a regular file (not a
 * directory or special file), and `position + count` must not exceed the file size at
 * construction time. The file's size is captured once and used for the body's lifetime;
 * callers must not modify the file while it is being uploaded.
 *
 * ## Performance hint to transports
 *
 * Transports may type-check for [FileRequestBody] to dispatch a zero-copy `sendfile(2)`
 * syscall:
 *
 * ```
 * if (request.body is FileRequestBody) {
 *     FileChannel.open(body.file, READ).use { fc ->
 *         fc.transferTo(body.position, body.count, socketChannel)
 *     }
 * }
 * ```
 *
 * The default [writeTo] uses `FileChannel.transferTo` to a
 * `Channels.newChannel(sink.outputStream())` target — this skips at least one user-space
 * buffer copy and lets the JVM use `transferTo`'s internal fast paths where it can detect
 * them. Without the transport-side check the kernel sendfile is unreachable through the
 * generic [BufferedSink] surface, but the contract still beats a manual byte loop.
 *
 * ## Thread-safety
 *
 * Immutable after construction. The file handle is opened fresh on each [writeTo], so
 * concurrent sends are safe at the body level (the file itself must not be mutated during).
 *
 * @param file Path to the file. Must exist and be a regular file at construction time.
 * @param mediaType Optional Content-Type for the body.
 * @param position Starting byte offset within the file.
 * @param explicitCount Number of bytes to send starting at [position], or `-1` to send the
 *   rest of the file. Stored after resolution in [count].
 */
public class FileRequestBody @JvmOverloads constructor(
    public val file: Path,
    private val mediaType: MediaType? = null,
    public val position: Long = 0,
    explicitCount: Long = -1,
) : RequestBody() {

    /** The exact number of bytes this body will upload, captured at construction time. */
    public val count: Long

    init {
        require(position >= 0) { "position must be non-negative (got $position)" }
        require(explicitCount == -1L || explicitCount >= 0) {
            "count must be non-negative or -1 to mean 'rest of file' (got $explicitCount)"
        }
        if (!Files.exists(file)) {
            throw NoSuchFileException(file.toString()).apply {
                initCause(IllegalArgumentException("File does not exist: $file"))
            }
        }
        require(Files.isRegularFile(file)) {
            "File must be a regular file (not a directory or special file): $file"
        }
        val fileSize = Files.size(file)
        require(position <= fileSize) {
            "position $position exceeds file size $fileSize for $file"
        }
        count = if (explicitCount >= 0) explicitCount else fileSize - position
        require(position + count <= fileSize) {
            "position + count (${position + count}) exceeds file size $fileSize for $file"
        }
    }

    override fun mediaType(): MediaType? = mediaType
    override fun contentLength(): Long = count
    override fun isReplayable(): Boolean = true
    override fun toReplayable(provider: IoProvider): RequestBody = this

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            // `Channels.newChannel(OutputStream)` returns a WritableByteChannel whose
            // `close()` ALSO closes the underlying OutputStream (per JDK docs). We do NOT
            // close `target` explicitly here because doing so would close `sink.outputStream()`,
            // which is owned by the caller. The wrapper is not AutoCloseable-managed; it will
            // be reclaimed by GC normally.
            val target = Channels.newChannel(sink.outputStream())
            var transferred = 0L
            while (transferred < count) {
                val n = channel.transferTo(position + transferred, count - transferred, target)
                if (n <= 0) break
                transferred += n
            }
            if (transferred < count) {
                throw IOException(
                    "FileRequestBody short-write for $file: transferred $transferred of $count bytes"
                )
            }
        }
    }

    /**
     * Returns a read-only memory-mapped view of the file region `[position, position + count)`.
     * Useful for local hashing / signature computation without copying bytes onto the heap.
     *
     * The returned [ByteBuffer] survives the underlying [FileChannel] close — its lifetime is
     * tied to GC of the buffer itself, not to any channel. On Linux the unmap is
     * non-deterministic (it depends on the GC observing the buffer becoming unreachable); on
     * Windows the mapping is invalidated when the underlying file is deleted while the channel
     * is open. Hold the buffer reference for as long as you need to read from it, and drop it
     * promptly to allow the OS to release the file/page mapping.
     *
     * **Windows note.** On Windows, the file-backed mapping prevents exclusive open of the
     * file (e.g. by other processes expecting exclusive access) while the mapping exists.
     * Hold the returned [ByteBuffer] no longer than needed and allow it to become unreachable
     * so the GC can release the mapping promptly.
     *
     * @throws IllegalStateException if [count] exceeds [Int.MAX_VALUE] (a single ByteBuffer
     *   cannot address more than ~2 GiB). Map smaller slices manually for larger files.
     * @throws java.nio.file.NoSuchFileException if the file no longer exists.
     * @throws java.nio.file.AccessDeniedException if the file is unreadable.
     * @throws IOException if mapping fails (for example the file was truncated after this body
     *   was constructed so `position + count` now exceeds the on-disk size).
     */
    @Throws(IOException::class)
    public fun toByteBuffer(): ByteBuffer {
        check(count <= Integer.MAX_VALUE.toLong()) {
            "FileRequestBody.toByteBuffer(): mapped region must fit in Int range " +
                "(count=$count > ${Integer.MAX_VALUE}). Map a smaller slice manually for larger files."
        }
        return FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, position, count).asReadOnlyBuffer()
        }
    }
}
