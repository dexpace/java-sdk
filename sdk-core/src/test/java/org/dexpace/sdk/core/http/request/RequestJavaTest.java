/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.request;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises the {@code @JvmStatic} forwarder method that Kotlin emits on the {@code Request}
 * class itself. Java callers dispatch to this stub rather than the Companion object.
 */
final class RequestJavaTest {

    @Test
    void builderViaJavaStatic() throws java.net.MalformedURLException {
        Request.RequestBuilder b = Request.builder();
        assertNotNull(b);
        Request r = b.url("https://example.test")
                .method(Method.GET)
                .build();
        assertEquals(Method.GET, r.getMethod());
    }
}
