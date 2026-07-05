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

private val logger = ClientLogger("org.dexpace.sdk.core.http.response.ResponseExtensions")

/** Maximum bytes buffered from the error body in [throwOnError]. Bodies exceeding this limit are truncated. */
private const val MAX_BUFFERED_ERROR_BODY_BYTES: Long = 1L * 1024 * 1024

/**
 * Returns this response if [Response.isSuccessful]; otherwise buffers the body into an
 * in-memory [ResponseBody], wraps it in a new [Response], and throws the matching
 * [HttpException] produced by [HttpExceptionFactory.fromResponse].
 *
 * The error body is buffered in memory (up to 1 MiB; bytes beyond that limit are dropped)
 * so it is readable from the thrown exception after the original transport connection is
 * released. The buffered copy is a single, in-memory byte sequence — it is not infinitely
 * replayable; once read it is consumed.
 *
 * @throws HttpException for any 4xx or 5xx response.
 */
public fun Response.throwOnError(): Response {
    if (isSuccessful) return this
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
