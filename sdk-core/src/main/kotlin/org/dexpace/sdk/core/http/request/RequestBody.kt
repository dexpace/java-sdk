package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.CommonMediaTypes
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.Buffer
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.io.IoProvider
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The payload portion of an HTTP request.
 *
 * A body produces bytes on demand via [writeTo]; the transport drives the write. Concrete
 * implementations differ on whether they can be replayed (see [isReplayable]) — that
 * property is what retry logic queries before deciding whether to buffer the body in memory.
 *
 * ## Thread-safety
 *
 * Implementations are not required to be thread-safe. Concurrent [writeTo] on the same
 * instance is undefined; the built-in stream-backed bodies use atomic guards to fail loudly
 * rather than silently emitting corrupt bytes.
 */
public abstract class RequestBody {
    /** Returns the media type of the request body, or `null` when unspecified. */
    public abstract fun mediaType(): MediaType?

    /** Returns the number of bytes that will be written to [writeTo], or `-1` if unknown. */
    public open fun contentLength(): Long = -1

    /**
     * Writes the body to [sink]. Called once per send attempt; replayable bodies may have
     * this method invoked multiple times across retries.
     *
     * @throws IOException if an I/O error occurs while writing.
     */
    @Throws(IOException::class)
    public abstract fun writeTo(sink: BufferedSink)

    /**
     * Returns true when [writeTo] can be invoked more than once and produces the same bytes
     * on every call. Used by retry logic and body-logging to decide whether the body needs
     * to be buffered before sending.
     *
     * Default: false. Subclasses backed by replayable sources (byte arrays, strings, files,
     * already-buffered content) override to return true.
     */
    public open fun isReplayable(): Boolean = false

    /**
     * Returns a replayable equivalent of this body. If [isReplayable] is already true,
     * returns `this`. Otherwise drains [writeTo] once into an in-memory [Buffer] obtained
     * from [provider] and returns a buffer-backed body.
     *
     * **The original body must be considered consumed after this method returns** — for
     * stream-backed bodies the underlying stream is exhausted by the buffering step.
     * Continue using the returned value, not `this`.
     *
     * **Partial-write failure.** If `writeTo(buffer)` throws, the original body has been
     * partially consumed; the caller has no way to recover from this state. Callers should
     * use `toReplayable` only when they trust the body's `writeTo` not to fail mid-write.
     */
    @JvmOverloads
    public open fun toReplayable(provider: IoProvider = Io.provider): RequestBody {
        if (isReplayable()) return this
        val buffer = provider.buffer()
        writeTo(buffer)
        return BufferRequestBody(buffer, mediaType(), buffer.size)
    }

    /**
     * Factory entry points for the built-in body shapes. Java callers use `RequestBody.create(...)`.
     */
    public companion object {
        /**
         * Creates a request body backed by [source]. The body is **single-use** — [writeTo]
         * drains and closes the source.
         *
         * A second call to [writeTo] (or to [toReplayable] after [writeTo]) throws
         * [IllegalStateException] — it does NOT silently send zero bytes. Call
         * [toReplayable] **before** [writeTo] to obtain a buffered copy for retries.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            source: BufferedSource,
            mediaType: MediaType? = null,
            contentLength: Long = -1,
        ): RequestBody = BufferedSourceRequestBody(source, mediaType, contentLength)

        /**
         * Creates a replayable request body backed by an in-memory [Buffer]. The buffer's
         * contents are read via a non-consuming `peek()` on every [writeTo], so the body
         * can be sent any number of times.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            buffer: Buffer,
            mediaType: MediaType? = null,
            contentLength: Long = buffer.size,
        ): RequestBody = BufferRequestBody(buffer, mediaType, contentLength)

        /**
         * Creates a replayable request body backed by the given byte array. Every [writeTo]
         * call writes the same bytes; safe for retries.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            bytes: ByteArray,
            mediaType: MediaType? = null,
        ): RequestBody = ByteArrayRequestBody(bytes, mediaType)

        /**
         * Creates a replayable request body backed by [content] encoded with [charset].
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            content: String,
            mediaType: MediaType? = null,
            charset: Charset = Charsets.UTF_8,
        ): RequestBody = ByteArrayRequestBody(content.toByteArray(charset), mediaType)

        /**
         * Creates a request body backed by [input]. The body is **replayable** when the
         * stream supports `mark/reset` AND [length] fits in a `mark` readLimit — this
         * captures `BufferedInputStream`, `ByteArrayInputStream`, and similar zero-copy
         * cases. Otherwise it is single-use; call [toReplayable] for an in-memory copy.
         *
         * ## Stream ownership
         *
         * The returned body **owns** [input] for its lifetime. Do not close [input]
         * externally — the body either reads from it directly (single-use case) or relies
         * on `mark/reset` to replay. If the stream is closed externally a subsequent
         * `writeTo` will throw an `IOException` from the underlying read.
         *
         * For mark/reset replay, the `readLimit` is set to [length] — if a caller somehow
         * reads more than [length] bytes from [input] between `writeTo` calls, the mark may
         * be invalidated and `reset()` will throw.
         *
         * @param length the exact number of bytes that will be read from [input]; must be
         *               non-negative and not exceed [Buffer.MAX_BYTE_ARRAY_SIZE] when
         *               mark/reset is used (otherwise the body falls back to single-use).
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            input: InputStream,
            length: Long,
            mediaType: MediaType? = null,
        ): RequestBody {
            require(length >= 0) { "length must be non-negative" }
            return if (input.markSupported() && length <= Buffer.MAX_BYTE_ARRAY_SIZE.toLong()) {
                input.mark(length.toInt())
                ResettableInputStreamRequestBody(input, length, mediaType)
            } else {
                OneShotInputStreamRequestBody(input, length, mediaType)
            }
        }

        /**
         * Creates a replayable file-backed request body. Equivalent to constructing
         * [FileRequestBody] directly. Transports may recognize [FileRequestBody] to
         * dispatch a zero-copy `sendfile(2)` syscall.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            file: Path,
            mediaType: MediaType? = null,
            position: Long = 0,
            count: Long = -1,
        ): RequestBody = FileRequestBody(file, mediaType, position, count)

        /**
         * Creates a replayable `application/x-www-form-urlencoded` body from [formData].
         * The encoded byte array is computed once at construction and reused for every
         * write — no provider lookup is needed at send time.
         *
         * The [charset] parameter affects BOTH the byte encoding of form values AND the
         * percent-encoding character set used by `URLEncoder.encode`. Non-UTF-8 charsets
         * are rarely needed for `application/x-www-form-urlencoded` and may produce body
         * bytes that are not interoperable with all servers; UTF-8 is the safe default.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            formData: Map<String, String>,
            charset: Charset = Charsets.UTF_8,
        ): RequestBody {
            val encoded =
                formData.entries.joinToString("&") { (key, value) ->
                    "${URLEncoder.encode(key, charset.name())}=${URLEncoder.encode(value, charset.name())}"
                }
            return ByteArrayRequestBody(encoded.toByteArray(charset), CommonMediaTypes.APPLICATION_FORM_URLENCODED)
        }
    }
}

/** Replayable body backed by an in-memory [Buffer]. Reads via non-consuming `peek()`. */
private class BufferRequestBody(
    private val buffer: Buffer,
    private val mediaType: MediaType?,
    private val length: Long,
) : RequestBody() {
    override fun mediaType(): MediaType? = mediaType

    override fun contentLength(): Long = length

    override fun isReplayable(): Boolean = true

    override fun toReplayable(provider: IoProvider): RequestBody = this

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        sink.writeAll(buffer.peek())
    }
}

/** Replayable body backed by a byte array. */
private class ByteArrayRequestBody(
    private val bytes: ByteArray,
    private val mediaType: MediaType?,
) : RequestBody() {
    override fun mediaType(): MediaType? = mediaType

    override fun contentLength(): Long = bytes.size.toLong()

    override fun isReplayable(): Boolean = true

    override fun toReplayable(provider: IoProvider): RequestBody = this

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        sink.write(bytes)
    }
}

/**
 * Replayable body backed by an [InputStream] that supports `mark/reset`. The stream is
 * marked at construction time; each [writeTo] after the first calls `reset()` to rewind
 * before reading.
 *
 * The `firstWrite` toggle is an [AtomicBoolean] so concurrent `writeTo` calls cannot both
 * skip (or both perform) the `reset()` — at most one transitions the flag, ensuring exactly
 * one reset between any two writes.
 */
private class ResettableInputStreamRequestBody(
    private val input: InputStream,
    private val length: Long,
    private val mediaType: MediaType?,
) : RequestBody() {
    private val hasWritten = AtomicBoolean(false)

    override fun mediaType(): MediaType? = mediaType

    override fun contentLength(): Long = length

    override fun isReplayable(): Boolean = true

    override fun toReplayable(provider: IoProvider): RequestBody = this

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        if (hasWritten.getAndSet(true)) {
            input.reset()
        }
        copyExactly(input, sink, length)
    }
}

/**
 * Non-replayable body — reads [input] once, then the stream is exhausted. The `consumed`
 * flag is an [AtomicBoolean] so concurrent `writeTo` calls cannot both pass the guard;
 * the loser sees [IllegalStateException].
 */
private class OneShotInputStreamRequestBody(
    private val input: InputStream,
    private val length: Long,
    private val mediaType: MediaType?,
) : RequestBody() {
    private val consumed = AtomicBoolean(false)

    override fun mediaType(): MediaType? = mediaType

    override fun contentLength(): Long = length
    // isReplayable defaults to false; toReplayable defaults to draining into a buffer.

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        check(consumed.compareAndSet(false, true)) {
            "OneShotInputStreamRequestBody.writeTo was already called — the underlying stream " +
                "is exhausted. Call toReplayable() BEFORE writeTo if retries may be needed."
        }
        copyExactly(input, sink, length)
    }
}

/**
 * Non-replayable body backed by a [BufferedSource]. The source is drained and closed on the
 * first [writeTo]; a second call fails loudly rather than silently emitting zero bytes.
 *
 * The `consumed` guard is an [AtomicBoolean] CAS so the check is race-free even under
 * concurrent (mis)use.
 */
private class BufferedSourceRequestBody(
    private val source: BufferedSource,
    private val mediaType: MediaType?,
    private val length: Long,
) : RequestBody() {
    private val consumed = AtomicBoolean(false)

    override fun mediaType(): MediaType? = mediaType

    override fun contentLength(): Long = length

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        check(consumed.compareAndSet(false, true)) {
            "BufferedSourceRequestBody.writeTo was already called — the underlying source is " +
                "exhausted/closed. Call toReplayable() BEFORE writeTo if retries may be needed."
        }
        source.use { sink.writeAll(it) }
    }
}

// Scratch buffer size for InputStream → BufferedSink copies; 8 KiB matches the historical
// Okio segment size and keeps per-call allocation predictable on hot paths.
private const val COPY_CHUNK_BYTES = 8 * 1024

/**
 * Reads exactly [length] bytes from [input] and writes them to [sink]. Uses an 8 KiB scratch
 * array — small enough to stay in L1 cache, large enough to avoid per-byte syscall cost.
 *
 * Treats a `read` return value of `0` as a stream contract violation rather than spinning —
 * `InputStream.read(byte[], off, len)` is required to read at least one byte when `len > 0`,
 * so a zero-return means the stream is broken.
 */
@Throws(IOException::class)
private fun copyExactly(
    input: InputStream,
    sink: BufferedSink,
    length: Long,
) {
    if (length == 0L) return // legitimate empty body — avoid the scratch allocation
    val chunk = ByteArray(COPY_CHUNK_BYTES)
    var remaining = length
    while (remaining > 0) {
        val toRead = minOf(chunk.size.toLong(), remaining).toInt()
        val read = input.read(chunk, 0, toRead)
        if (read <= 0) {
            throw EOFException("Stream ended after ${length - remaining} of $length bytes (read=$read)")
        }
        sink.write(chunk, 0, read)
        remaining -= read
    }
}
