/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.lifecycle

/**
 * A single detected leak: a tracked resource that became phantom-reachable without ever being
 * closed.
 *
 * The report is the payload handed to a [LeakListener]. It intentionally carries no reference
 * to the leaked object itself (by the time a leak is detected the object is already gone);
 * instead it carries the human-readable [description] that was supplied at registration and,
 * when stack capture was enabled, the [creationStack] showing where the resource was created.
 *
 * @property description the label supplied to [LeakDetector.track], e.g. `"ResponseBody"` or
 *   a more specific `"ResponseBody(GET /v1/widgets)"`. Never blank.
 * @property creationStack the stack trace captured at registration time, or `null` when stack
 *   capture was disabled (the default). Capturing a stack on every registration has a real
 *   allocation cost, so it is opt-in via [LeakDetector.Builder.captureCreationStack].
 */
public class LeakReport private constructor(
    public val description: String,
    public val creationStack: Array<StackTraceElement>?,
) {
    /**
     * Renders a single-line summary suitable for a log message. When a [creationStack] is
     * present the caller is responsible for rendering it separately (e.g. as a `Throwable`
     * cause); this method only returns the headline.
     */
    public fun summary(): String = "Resource leak detected: $description was not closed before being reclaimed"

    internal companion object {
        internal fun create(
            description: String,
            creationStack: Array<StackTraceElement>?,
        ): LeakReport = LeakReport(description, creationStack)
    }
}
