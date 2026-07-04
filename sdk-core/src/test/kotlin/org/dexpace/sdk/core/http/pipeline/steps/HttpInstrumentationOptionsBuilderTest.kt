/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline.steps

import kotlin.test.Test
import kotlin.test.assertEquals

class HttpInstrumentationOptionsBuilderTest {
    @Test
    fun `builder sets logLevel and leaves other fields at defaults`() {
        val defaults = HttpInstrumentationOptions()
        val opts = HttpInstrumentationOptions.builder().logLevel(HttpLogLevel.HEADERS).build()

        assertEquals(HttpLogLevel.HEADERS, opts.logLevel)
        assertEquals(defaults.allowedHeaderNames, opts.allowedHeaderNames)
        assertEquals(defaults.allowedQueryParamNames, opts.allowedQueryParamNames)
        assertEquals(defaults.isRedactedHeaderNamesLoggingEnabled, opts.isRedactedHeaderNamesLoggingEnabled)
        assertEquals(defaults.bodyPreviewMaxBytes, opts.bodyPreviewMaxBytes)
    }

    @Test
    fun `newBuilder round-trips scalar fields`() {
        val original =
            HttpInstrumentationOptions
                .builder()
                .logLevel(HttpLogLevel.BODY_AND_HEADERS)
                .bodyPreviewMaxBytes(4096)
                .isRedactedHeaderNamesLoggingEnabled(false)
                .build()
        val copy = original.newBuilder().build()

        assertEquals(HttpLogLevel.BODY_AND_HEADERS, copy.logLevel)
        assertEquals(4096, copy.bodyPreviewMaxBytes)
        assertEquals(false, copy.isRedactedHeaderNamesLoggingEnabled)
    }
}
