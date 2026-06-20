/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pipeline.step

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.context.DispatchContext
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseBody
import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.http.response.exception.HttpExceptionFactory
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io

/**
 * Response step that turns an HTTP error response into the matching typed [HttpException].
 *
 * This is the error path for the recovery-aware pipeline: a transport hands back a `Response`
 * for every completed exchange — including 4xx and 5xx — so a 4xx/5xx is a [Response], not a
 * thrown exception, until something maps it. Placed in a
 * [org.dexpace.sdk.core.pipeline.ResponsePipeline.responseSteps] list, this step performs that
 * mapping via [HttpExceptionFactory.fromResponse], so the typed [HttpException] family is
 * produced consistently from one seam rather than re-derived ad hoc at each call site.
 *
 * ## Behaviour
 *
 *  - **2xx (and 1xx/3xx) response** — returned unchanged; the success chain continues.
 *  - **4xx / 5xx response** — [HttpExceptionFactory.fromResponse] is invoked and the resulting
 *    [HttpException] is thrown. [org.dexpace.sdk.core.pipeline.ResponsePipeline] catches it,
 *    closes the in-hand response, and wraps it into a
 *    [org.dexpace.sdk.core.pipeline.ResponseOutcome.Failure] — which then flows through the
 *    recovery chain (e.g. [org.dexpace.sdk.core.pipeline.step.retry.RetryStep]) exactly like a
 *    transport failure. Because the thrown [HttpException] implements
 *    [org.dexpace.sdk.core.http.response.exception.Retryable], retry classification keys off it
 *    uniformly.
 *
 * Range classification is single-sourced from [HttpExceptionFactory.isErrorStatus]: only
 * 400..599 maps to an exception, so the success / informational / redirection ranges fall
 * through untouched.
 *
 * ## Error-body buffering
 *
 * The pipeline closes the in-hand response as soon as this step throws (close-before-propagate),
 * which would also close the live [ResponseBody]. To keep the error body readable on the
 * resulting [org.dexpace.sdk.core.pipeline.ResponseOutcome.Failure] — for a log-line
 * [HttpException.bodySnapshot] or downstream typed-error deserialization — this step buffers the
 * error body into a small, replayable in-memory body before constructing the exception. The
 * buffer is bounded at [HttpException.DEFAULT_SNAPSHOT_BYTES], consistent with the
 * `bodySnapshot` cap, so a multi-megabyte 5xx body cannot OOM the process. Mapping the buffered
 * body into a typed error value is still left to the generated layer (see [HttpException.value]).
 *
 * The cap is a hard truncation, not just an OOM guard: an error body larger than
 * [HttpException.DEFAULT_SNAPSHOT_BYTES] is preserved only up to that many bytes, and the excess
 * is discarded with no marker. This is harmless for a log-line snapshot, but a downstream
 * consumer that deserializes the buffered body (e.g. the generated layer parsing it into
 * [HttpException.value]) must tolerate a structurally incomplete payload — a JSON object cut
 * mid-token will not parse. Callers that need the full error body must read it from the live
 * response before this step runs rather than relying on the buffered copy.
 *
 * ## Thread-safety
 *
 * Stateless; safe to share across concurrent requests and reuse as a singleton.
 */
public object ThrowOnHttpErrorStep : ResponsePipelineStep {
    /**
     * Returns [input] unchanged for a non-error status; throws the
     * [HttpExceptionFactory.fromResponse] mapping for a 4xx / 5xx status.
     *
     * @throws HttpException when `input.status.code` is in 400..599.
     */
    override fun execute(
        input: Response,
        context: DispatchContext,
    ): Response {
        if (!HttpExceptionFactory.isErrorStatus(input.status.code)) {
            return input
        }
        throw HttpExceptionFactory.fromResponse(bufferErrorBody(input))
    }

    /**
     * Returns a copy of [response] whose body has been drained into a bounded, replayable
     * in-memory body, so it survives the pipeline closing the original response. Bodies that are
     * absent are left as-is.
     */
    private fun bufferErrorBody(response: Response): Response {
        val body = response.body ?: return response
        val buffered = replayableBody(body)
        return response.newBuilder().body(buffered).build()
    }

    /**
     * Reads up to [HttpException.DEFAULT_SNAPSHOT_BYTES] from [body] into memory and returns a
     * [ResponseBody] that can be read repeatedly from those bytes. The original [body] is read
     * here (the byte budget is bounded), so the caller must not also stream it.
     */
    private fun replayableBody(body: ResponseBody): ResponseBody {
        val cap = HttpException.DEFAULT_SNAPSHOT_BYTES
        val mediaType = body.mediaType()
        val bytes =
            body.source().use { source ->
                readUpTo(source, cap)
            }
        return object : ResponseBody() {
            override fun mediaType(): MediaType? = mediaType

            override fun contentLength(): Long = bytes.size.toLong()

            override fun source(): BufferedSource = Io.provider.source(bytes)

            override fun close() {
                // Bytes are held in memory; nothing transport-owned to release.
            }
        }
    }

    /** Reads at most [cap] bytes from [source], returning however many were available. */
    private fun readUpTo(
        source: BufferedSource,
        cap: Int,
    ): ByteArray {
        if (cap == 0) return ByteArray(0)
        val out = ByteArray(cap)
        var read = 0
        source.inputStream().use { stream ->
            while (read < cap) {
                val n = stream.read(out, read, cap - read)
                if (n < 0) break
                read += n
            }
        }
        return if (read == cap) out else out.copyOf(read)
    }
}
