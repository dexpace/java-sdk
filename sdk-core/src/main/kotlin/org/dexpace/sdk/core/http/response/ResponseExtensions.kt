/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.http.response.exception.HttpExceptionFactory
import org.dexpace.sdk.core.instrumentation.ClientLogger
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.serde.Deserializer
import org.dexpace.sdk.core.serde.Serde
import org.dexpace.sdk.core.serde.TypeRef

private val logger = ClientLogger("org.dexpace.sdk.core.http.response.ResponseExtensions")

/** Maximum bytes buffered from the error body in [throwOnError]. Bodies exceeding this limit are truncated. */
private const val MAX_BUFFERED_ERROR_BODY_BYTES: Long = 1L * 1024 * 1024

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
 * released. The buffered copy is a single, in-memory byte sequence — it is not infinitely
 * replayable; once read it is consumed.
 *
 * @throws HttpException for any 4xx or 5xx response.
 */
public fun Response.throwOnError(): Response {
    if (!status.isError) return this
    val buffer = Io.provider.buffer()
    val originalBody = body
    if (originalBody != null) {
        originalBody.use { b ->
            val src = b.source()
            var remaining = MAX_BUFFERED_ERROR_BODY_BYTES
            while (remaining > 0L) {
                val n = src.read(buffer, remaining)
                if (n < 0L) break
                remaining -= n
            }
        }
    }
    val bufferedBody = originalBody?.let { ResponseBody.create(buffer, it.mediaType(), buffer.size) }
    val bufferedResponse = newBuilder().body(bufferedBody).build()
    throw HttpExceptionFactory.fromResponse(bufferedResponse)
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
 * @throws IllegalStateException if the response body is `null`.
 */
public fun <T> Response.deserialize(
    serde: Serde,
    type: Class<T>,
): T {
    val b = body ?: error("Response body is null — cannot deserialize into $type")
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
    val b = body ?: error("Response body is null — cannot deserialize into ${ref.type}")
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
 * This is Kotlin-only sugar over [deserialize]; Java callers should use the explicit `Class`
 * overload.
 *
 * @param serde The [Serde] bundle whose deserializer is used for decoding.
 * @return The decoded value.
 * @throws IllegalStateException if the response body is `null`.
 */
public inline fun <reified T> Response.deserialize(serde: Serde): T = deserialize(serde, T::class.java)
