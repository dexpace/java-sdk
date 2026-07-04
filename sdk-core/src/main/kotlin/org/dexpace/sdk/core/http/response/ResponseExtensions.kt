/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.http.response.exception.HttpExceptionFactory
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.serde.Deserializer
import org.dexpace.sdk.core.serde.Serde

/**
 * Returns this response if [Response.isSuccessful]; otherwise buffers the body into an
 * in-memory [ResponseBody], wraps it in a new [Response], and throws the matching
 * [HttpException] produced by [HttpExceptionFactory.fromResponse].
 *
 * Buffering guarantees that the exception's [HttpException.body] is still readable after the
 * original transport connection is released or the response is consumed in a `use {}` block.
 *
 * @throws HttpException for any 4xx or 5xx response.
 */
public fun Response.throwOnError(): Response {
    if (isSuccessful) return this
    val buffer = Io.provider.buffer()
    val originalBody = body
    if (originalBody != null) {
        originalBody.use { b -> buffer.writeAll(b.source()) }
    }
    val bufferedBody = originalBody?.let { ResponseBody.create(buffer, it.mediaType(), buffer.size) }
    val bufferedResponse = newBuilder().body(bufferedBody).build()
    throw HttpExceptionFactory.fromResponse(bufferedResponse)
}

/**
 * Attempts to decode the exception's error [body] using [deserializer] as a [type]-typed
 * value. Returns `null` when [HttpException.body] is `null` or when the deserializer throws.
 *
 * Exceptions thrown by the deserializer are swallowed rather than propagated; this function
 * never throws.
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
        null
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
