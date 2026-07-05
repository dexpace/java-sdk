/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.serde.jackson

import com.fasterxml.jackson.core.type.TypeReference
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.ResponseHandler
import org.dexpace.sdk.core.http.response.exception.HttpException
import org.dexpace.sdk.core.http.response.exception.HttpExceptionFactory
import org.dexpace.sdk.core.http.response.throwOnError
import org.dexpace.sdk.core.serde.Serde
import org.dexpace.sdk.core.serde.SerdeException

/**
 * A [ResponseHandler] that streams the response body through [serde]'s deserializer into a value
 * of [type], then closes the response.
 *
 * Deserialization runs against the body's `InputStream`, so the payload is never first
 * materialized as a `String` or `ByteArray`. The handler **consumes and closes the body**, so any
 * raw header / status inspection must happen before the handler runs — pair it with a
 * `ParsedResponse` to keep raw access available and to parse lazily exactly once.
 *
 * A missing body (e.g. a `204 No Content`) or any failure from the deserializer is surfaced as a
 * [SerdeException], with the original codec exception preserved as the cause; the response is
 * still closed on the failure path.
 *
 * This overload takes a raw [Class] token, which is sufficient for non-parametric targets. For
 * parametric targets (`List<Dto>`, `Map<String, Dto>`) the raw class erases the element type — use
 * the [TypeReference] overload backed by [JacksonSerde].
 *
 * @param serde The serde whose deserializer decodes the body.
 * @param type The non-parametric target type.
 * @return A [ResponseHandler] that yields a value of [type].
 */
public fun <T> jsonHandler(
    serde: Serde,
    type: Class<T>,
): ResponseHandler<T> =
    ResponseHandler { response ->
        decode(response, type.typeName) { stream -> serde.deserializer.deserialize(stream, type) }
    }

/**
 * A [ResponseHandler] that streams the response body through [serde] into the parametric type
 * captured by [type], then closes the response.
 *
 * Use this overload for generic targets (`List<Dto>`, `Map<String, Dto>`) where a raw [Class]
 * token would lose the element type. Behaves like the [Class] overload otherwise: it consumes and
 * closes the body and surfaces deserialization failures (and a missing body) as a [SerdeException].
 *
 * @param serde The Jackson serde whose mapper decodes the body.
 * @param type The parametric target type.
 * @return A [ResponseHandler] that yields a value of the captured type.
 */
public fun <T> jsonHandler(
    serde: JacksonSerde,
    type: TypeReference<T>,
): ResponseHandler<T> =
    ResponseHandler { response ->
        decode(response, type.type.typeName) { stream -> serde.deserializeAs(stream, type) }
    }

/**
 * Status-aware variant of [jsonHandler]: on a 2xx response it decodes the body into [type]; on any
 * non-2xx response it throws instead of attempting to deserialize an error payload as [type].
 *
 * A 4xx / 5xx response is mapped through [Response.throwOnError] to the matching
 * [org.dexpace.sdk.core.http.response.exception.HttpException] subclass, whose error
 * [org.dexpace.sdk.core.http.response.ResponseBody] is a **buffered** in-memory copy (bounded to
 * 1 MiB) — so it stays readable from the caught exception (e.g. via `bodySnapshot()` or typed
 * error-body deserialization) even after this handler runs inside a `response.use { … }` block that
 * closes the live response. Any other non-success status (1xx / 3xx reaching a terminal handler)
 * closes the response and fails with a [SerdeException]. On the success path the body is consumed
 * and the response closed exactly as the plain [jsonHandler].
 *
 * @param serde The serde whose deserializer decodes the body.
 * @param type The non-parametric success target type.
 * @return A [ResponseHandler] that yields a value of [type] on 2xx, and throws on non-2xx.
 */
public fun <T> jsonHandlerOrThrow(
    serde: Serde,
    type: Class<T>,
): ResponseHandler<T> =
    ResponseHandler { response ->
        throwIfNotSuccess(response)
        decode(response, type.typeName) { stream -> serde.deserializer.deserialize(stream, type) }
    }

/**
 * Status-aware variant of the parametric [jsonHandler]: decodes the body into the type captured by
 * [type] on 2xx, and throws on any non-2xx response. See the [Class] overload of [jsonHandlerOrThrow]
 * for the non-success mapping contract.
 *
 * @param serde The Jackson serde whose mapper decodes the body.
 * @param type The parametric success target type.
 * @return A [ResponseHandler] that yields a value of the captured type on 2xx, and throws on non-2xx.
 */
public fun <T> jsonHandlerOrThrow(
    serde: JacksonSerde,
    type: TypeReference<T>,
): ResponseHandler<T> =
    ResponseHandler { response ->
        throwIfNotSuccess(response)
        decode(response, type.type.typeName) { stream -> serde.deserializeAs(stream, type) }
    }

/**
 * Throws when [response] is not a 2xx: a 4xx / 5xx is mapped to its [HttpException] via
 * [Response.throwOnError], which buffers a bounded (≤ 1 MiB) copy of the error body into the thrown
 * exception so it survives the live response being closed (e.g. by a surrounding `response.use { … }`);
 * any other non-success status closes the response and raises a [SerdeException]. Returns normally on
 * a 2xx so the caller proceeds to decode.
 */
private fun throwIfNotSuccess(response: Response) {
    val status = response.status
    if (status.isSuccess) return
    if (HttpExceptionFactory.isErrorStatus(status.code)) {
        // Buffers the error body (≤ 1 MiB) into the mapped HttpException and throws, so the caught
        // exception's body is readable even after the live response is closed. Always throws here.
        response.throwOnError()
    }
    response.close()
    throw SerdeException(
        "Unexpected non-success HTTP status ${status.code}; expected a 2xx response to deserialize.",
    )
}

/**
 * Shared body-streaming + error-translation core for the [jsonHandler] overloads. Reads the
 * response body as an `InputStream`, hands it to [decoder], and closes the response in all cases.
 * A missing body or any decoder failure is translated into a [SerdeException].
 */
private inline fun <T> decode(
    response: Response,
    targetType: String,
    decoder: (java.io.InputStream) -> T,
): T =
    response.use { resp ->
        val body =
            resp.body
                ?: throw SerdeException(
                    "Cannot deserialize a ${resp.status.code} response with no body into $targetType.",
                )
        try {
            decoder(body.source().inputStream())
        } catch (e: SerdeException) {
            throw e
        } catch (e: Exception) {
            throw SerdeException("Failed to deserialize response body: ${e.message}", e)
        }
    }
