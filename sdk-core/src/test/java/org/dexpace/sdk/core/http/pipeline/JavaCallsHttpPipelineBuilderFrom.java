/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline;

/**
 * Java-side helper that exercises the static {@code @JvmStatic} bridge method
 * {@link HttpPipelineBuilder#from(HttpPipeline)}. Kotlin callers invoke the companion
 * directly, bypassing the static bridge; a Java caller resolves the static and triggers
 * coverage for it.
 */
public final class JavaCallsHttpPipelineBuilderFrom {
    private JavaCallsHttpPipelineBuilderFrom() {}

    /** Invokes the static {@code from} bridge and returns the resulting builder. */
    public static HttpPipelineBuilder copy(HttpPipeline pipeline) {
        return HttpPipelineBuilder.from(pipeline);
    }
}
