/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import org.dexpace.sdk.core.http.common.MediaType

/**
 * Bundle of serialization / deserialization strategies for a single wire format (JSON, XML, etc.).
 *
 * Acts as a single injection point: components that need to round-trip values pull a `Serde` rather
 * than separate serializer and deserializer references, which keeps the dependency surface flat and
 * makes it easy to swap formats at the edge of the SDK. Concrete implementations live outside
 * `sdk-core` since `sdk-core` deliberately ships no embedded serializer.
 */
public interface Serde {
    /** Encoder used for writing values out as wire bytes / strings / streams. */
    public val serializer: Serializer

    /** Decoder used for reading values out of wire bytes / strings / streams. */
    public val deserializer: Deserializer

    /**
     * The media type produced by this serde's serializer. Used as the default `Content-Type` when
     * creating a [org.dexpace.sdk.core.http.request.RequestBody] via
     * [org.dexpace.sdk.core.http.request.RequestBody.Companion.create].
     *
     * Each implementation declares its own wire type (e.g. `application/json` for a JSON serde,
     * `application/xml` for an XML serde). This is intentionally not defaulted: a format-agnostic
     * default would let a non-JSON serde silently stamp the wrong `Content-Type` on every body.
     */
    public fun contentType(): MediaType
}
