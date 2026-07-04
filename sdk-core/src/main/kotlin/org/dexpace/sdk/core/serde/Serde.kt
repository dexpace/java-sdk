/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.serde

import org.dexpace.sdk.core.http.common.CommonMediaTypes
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
     * [org.dexpace.sdk.core.http.request.RequestBody.Companion.create]. Implementations may
     * override to reflect a different wire format (e.g. `application/xml`).
     *
     * Default: `application/json`.
     */
    public fun contentType(): MediaType = CommonMediaTypes.APPLICATION_JSON
}
