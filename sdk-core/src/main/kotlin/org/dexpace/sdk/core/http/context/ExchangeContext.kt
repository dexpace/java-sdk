/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.context

import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.instrumentation.InstrumentationContext

/**
 * Terminal link in the context promotion chain. Bundles the [Request] / [Response] pair
 * with the call's [InstrumentationContext] so post-exchange observers (metrics, log
 * sinks, span finalizers) can correlate every artifact of a completed call.
 */
public data class ExchangeContext(
    override val instrumentationContext: InstrumentationContext,
    val request: Request,
    val response: Response,
) : CallContext
