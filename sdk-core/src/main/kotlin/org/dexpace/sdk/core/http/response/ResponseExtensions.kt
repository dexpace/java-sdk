/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

@file:JvmName("ResponseExtensions")

package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.http.response.exception.HttpExceptionFactory
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.serde.Deserializer
import org.dexpace.sdk.core.serde.Serde
import org.dexpace.sdk.core.serde.SerdeException
import org.dexpace.sdk.core.serde.TypeRef

private val logger = ClientLogger("org.dexpace.sdk.core.http.response.ResponseExtensions")

/**
 * Maximum bytes buffered from an error body by [throwOnError] and the pipeline's error-mapping
 * recovery so the two share one bound. Bodies exceeding this limit are truncated.
 */
internal const val MAX_BUFFERED_ERROR_BODY_BYTES: Long = 1L * 1024 * 1024

/**
 * Returns this response unchanged when its status is not an error ([Status.isError] is `false` —
 * any 1xx, 2xx, or 3xx); otherwise buffers the body into an in-memory [ResponseBody], wraps it in
 * a new [Response], and throws the matching [HttpException] produced by
 * [HttpExceptionFactory.fromResponse].
 *
 * Only 4xx/5xx responses are mapped, so a non-error, non-2xx response (e.g. a `304 Not Modified`
 * from a conditional request, or a `3xx` that no redirect step followed) is returned with its body
 * intact rather than having it consumed and an exception raised.
 *
 * The error body is buffered in memory (up to 1 MiB; bytes beyond that limit are dropped)
 * so it is readable from the thrown exception after the original transport connection is
 * released. The buffered copy is replayable: [HttpException.bodyAs], `body.string()`, and
 * `bodySnapshot()` each read the full buffered bytes independently.
 *
 * @throws HttpException for any 4xx or 5xx response.
 */
public fun Response.throwOnError(): Response {
    if (!status.isError) return this
    throw HttpExceptionFactory.fromResponse(bufferErrorBody(this, MAX_BUFFERED_ERROR_BODY_BYTES))
}

/**
 * Returns a copy of [response] whose body has been drained (up to [maxBytes]; further bytes are
 * dropped) into memory and made **replayable** — every [ResponseBody.source] call reads the full
 * buffered bytes again — so the body survives the original transport connection being released and
 * can be read more than once (e.g. decode via [HttpException.bodyAs], then log a snapshot). Returns
 * [response] unchanged when it has no body.
 *
 * The in-memory buffer is acquired inside the original body's `use` block, so a failure to resolve
 * an [Io] provider still closes the original body rather than leaking the connection. Shared by
 * [throwOnError] and the pipeline's error-mapping recovery step so the two cannot drift.
 */
internal fun bufferErrorBody(
    response: Response,
    maxBytes: Long,
): Response {
    val original = response.body ?: return response
    val mediaType = original.mediaType()
    val bytes =
        original.use { b ->
            val buffer = Io.provider.buffer()
            val src = b.source()
            var remaining = maxBytes
            while (remaining > 0L) {
                val n = src.read(buffer, remaining)
                if (n < 0L) break
                remaining -= n
            }
            buffer.readByteArray()
        }
    val replayable =
        object : ResponseBody() {
            override fun mediaType(): MediaType? = mediaType

            override fun contentLength(): Long = bytes.size.toLong()

            override fun source(): BufferedSource = Io.provider.source(bytes)

            override fun close() {
                // In-memory copy; no transport resource to release.
            }
        }
    return response.newBuilder().body(replayable).build()
}

/**
 * Attempts to decode the exception's error [body] using [deserializer] as a [type]-typed
 * value. Returns `null` when [HttpException.body] is `null` or when the deserializer throws.
 *
 * The body is closed in a `finally` block whether decoding succeeds or fails. Exceptions
 * thrown by the deserializer are logged at DEBUG and swallowed rather than propagated; this
 * function never throws.
 *
 * @param deserializer The deserializer to apply.
 * @param type The target class token required for runtime type dispatch.
 * @return The decoded payload, or `null` if the body is absent or decoding fails.
 */
public fun <T> HttpException.bodyAs(
    deserializer: Deserializer,
    type: Class<T>,
): T? {
    val b = body ?: return null
    return try {
        deserializer.deserialize(b.source().inputStream(), type)
    } catch (e: Exception) {
        logger.atVerbose()
            .event("bodyAs.deserialize.failure")
            .cause(e)
            .log("HttpException.bodyAs: deserializer threw; returning null")
        null
    } finally {
        b.close()
    }
}

/**
 * Decodes the response body as a [type]-typed value using [serde]'s deserializer, then closes
 * the response in a `finally` block.
 *
 * @param serde The [Serde] bundle whose [Serde.deserializer] is used for decoding.
 * @param type The target class token required for runtime type dispatch.
 * @return The decoded value.
 * @throws SerdeException if the response body is `null`.
 */
public fun <T> Response.deserialize(
    serde: Serde,
    type: Class<T>,
): T {
    val b = body ?: throw SerdeException("Response body is null — cannot deserialize into $type")
    return try {
        serde.deserializer.deserialize(b.source().inputStream(), type)
    } finally {
        close()
    }
}

/**
 * Decodes the response body as the parametric type captured by [ref] using [serde]'s deserializer,
 * then closes the response in a `finally` block.
 *
 * This is the entry point for generic targets (`List<User>`, `Map<String, User>`) whose element
 * types a bare [Class] token would erase, and the parametric overload Java callers reach for (build
 * a `TypeRef` as an anonymous subclass, e.g. `new TypeRef<List<User>>() {}`). It requires a [serde]
 * whose [Serde.deserializer] resolves generics (e.g. `JacksonSerde`); a format-agnostic deserializer
 * throws [org.dexpace.sdk.core.serde.SerdeException] for a genuinely parametric [ref].
 *
 * @param serde The [Serde] bundle whose [Serde.deserializer] is used for decoding.
 * @param ref The captured generic target type.
 * @return The decoded value.
 * @throws IllegalStateException if the response body is `null`.
 */
public fun <T> Response.deserialize(
    serde: Serde,
    ref: TypeRef<T>,
): T {
    val b = body ?: throw SerdeException("Response body is null — cannot deserialize into ${ref.type}")
    return try {
        serde.deserializer.deserialize(b.source().inputStream(), ref)
    } finally {
        close()
    }
}

/**
 * Decodes the response body as a reified type [T] using [serde]'s deserializer, then closes
 * the response.
 *
 * This is Kotlin-only sugar over [deserialize]; Java callers should use the explicit `Class` or
 * `TypeRef` overload. It captures `T` as a [TypeRef], so a parametric target such as
 * `response.deserialize<List<User>>(serde)` reifies its full type and decodes correctly instead of
 * erasing to the raw class (which would heap-pollute). This routes through the `TypeRef` overload,
 * so it requires a [serde] that resolves generics for a parametric `T`.
 *
 * @param serde The [Serde] bundle whose deserializer is used for decoding.
 * @return The decoded value.
 * @throws SerdeException if the response body is `null`.
 */
public inline fun <reified T> Response.deserialize(serde: Serde): T = deserialize(serde, object : TypeRef<T>() {})
