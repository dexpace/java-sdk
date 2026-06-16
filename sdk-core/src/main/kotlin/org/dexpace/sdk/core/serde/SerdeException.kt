/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

/**
 * Stable SDK-owned failure type for the [Serde] SPI.
 *
 * The serde abstraction is format-agnostic: callers code against [Serializer] / [Deserializer]
 * without knowing whether the bytes on the wire are turned into objects by Jackson, Gson, or a
 * hand-rolled codec. If an adapter let its underlying library's exception escape (Jackson's
 * `com.fasterxml.jackson.*`, say), every consumer's `catch` clause would have to name that library
 * — re-coupling the SDK to an implementation detail the SPI exists to hide, and breaking any caller
 * the day the backing library changes.
 *
 * `SerdeException` closes that seam: adapters catch their own library's failures and rethrow as a
 * `SerdeException` (or one of its subtypes), always chaining the original as [cause] so the
 * underlying diagnostic is never lost. Consumers catch a single, stable SDK type.
 *
 * ## Why `RuntimeException` and not a checked exception
 *
 * Serialization failures are almost always programming or contract errors (an unserializable
 * object graph, a payload that does not match the target type) rather than recoverable I/O
 * conditions, and the [Serializer] / [Deserializer] signatures are deliberately unchecked so Java
 * callers are not forced to wrap every round-trip in `try`/`catch`. Making this unchecked keeps the
 * SPI ergonomic in both Kotlin and Java.
 *
 * The class is `open` so adapters and service-client codegen can introduce more specific subtypes;
 * [SerializationException] and [DeserializationException] cover the write and read directions out
 * of the box.
 *
 * @param message Human-readable description of the failure.
 * @param cause The underlying error (typically the backing library's exception), or `null`.
 */
public open class SerdeException
    @JvmOverloads
    constructor(
        message: String? = null,
        cause: Throwable? = null,
    ) : RuntimeException(message, cause)

/**
 * A [SerdeException] raised while **encoding** a value to the wire format — for example an object
 * graph the backing codec cannot represent (a reference cycle, an unmapped type).
 *
 * @param message Human-readable description of the failure.
 * @param cause The underlying error (typically the backing library's exception), or `null`.
 */
public open class SerializationException
    @JvmOverloads
    constructor(
        message: String? = null,
        cause: Throwable? = null,
    ) : SerdeException(message, cause)

/**
 * A [SerdeException] raised while **decoding** wire bytes / strings / streams into a typed value —
 * for example a malformed document or a payload whose shape does not match the target type.
 *
 * @param message Human-readable description of the failure.
 * @param cause The underlying error (typically the backing library's exception), or `null`.
 */
public open class DeserializationException
    @JvmOverloads
    constructor(
        message: String? = null,
        cause: Throwable? = null,
    ) : SerdeException(message, cause)
