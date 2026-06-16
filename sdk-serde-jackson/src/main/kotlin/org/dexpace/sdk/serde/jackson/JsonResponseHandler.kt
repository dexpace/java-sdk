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
import org.dexpace.sdk.core.serde.Serde
import org.dexpace.sdk.core.serde.SerdeException
import org.dexpace.sdk.core.serde.deserialize

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
        decode(response) { stream -> serde.deserializer.deserialize(stream, type) }
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
        decode(response) { stream -> serde.deserializeAs(stream, type) }
    }

/**
 * Shared body-streaming + error-translation core for the [jsonHandler] overloads. Reads the
 * response body as an `InputStream`, hands it to [decoder], and closes the response in all cases.
 * A missing body or any decoder failure is translated into a [SerdeException].
 */
private inline fun <T> decode(
    response: Response,
    decoder: (java.io.InputStream) -> T,
): T =
    response.use { resp ->
        val body =
            resp.body
                ?: throw SerdeException(
                    "Cannot deserialize a ${resp.status.code} response that has no body.",
                )
        try {
            decoder(body.source().inputStream())
        } catch (e: SerdeException) {
            throw e
        } catch (e: Exception) {
            throw SerdeException("Failed to deserialize response body: ${e.message}", e)
        }
    }
