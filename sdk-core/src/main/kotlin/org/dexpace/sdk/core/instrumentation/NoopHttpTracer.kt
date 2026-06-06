/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

/**
 * Default [HttpTracer] implementation that records nothing and allocates nothing.
 *
 * Every callback inherits the no-op default body declared on [HttpTracer] itself, so this
 * singleton has no overhead beyond the cost of the (eliminable) virtual dispatch — there
 * is no instance state, no map of recorded events, and no allocation per call.
 *
 * Shipped as a Kotlin `object` so the instance is shared process-wide. Use when no real
 * tracer is installed; the default [HttpTracerFactory] returns this singleton.
 */
public object NoopHttpTracer : HttpTracer
