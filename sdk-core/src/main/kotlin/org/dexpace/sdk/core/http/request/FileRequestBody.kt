package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.IoProvider
import java.io.IOException
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
 */
class FileRequestBody @JvmOverloads constructor(
    val file: Path,
    private val mediaType: MediaType? = null,
    val position: Long = 0,
    explicitCount: Long = -1,
) : RequestBody() {

    /** The exact number of bytes this body will upload, captured at construction time. */
    val count: Long

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
}
