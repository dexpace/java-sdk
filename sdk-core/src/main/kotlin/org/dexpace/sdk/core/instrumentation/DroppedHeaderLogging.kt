/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.instrumentation

/**
 * Policy for how a transport logs a request header it drops because it cannot encode it.
 *
 * A transport drops a caller-set header when the header is valid at the SDK model layer (printable
 * ASCII, no control characters) but is rejected by the transport's stricter wire grammar — for
 * example a name carrying an interior space, which the RFC 7230 `token` rule OkHttp and the JDK
 * client enforce forbids. The header never reaches the wire, so the drop is worth surfacing; the
 * question is how loudly, since a hot path re-setting the same non-encodable header would otherwise
 * emit a log line on every request.
 *
 * This policy controls only the drop of a **model-valid but transport-rejected** header. The
 * pre-drop of a known restricted header the transport recomputes (`Host`, `Content-Length`, …)
 * always logs at verbose regardless of this setting.
 *
 * Note that the log *level* remains the operator's to filter through their SLF4J backend; this
 * policy chooses the level (and dedup) the transport emits at, which a log level alone cannot
 * express.
 */
public enum class DroppedHeaderLogging {
    /**
     * Log every dropped header at `WARN`, on every request. Loudest — a per-request audit trail of
     * exactly which requests lost which headers, at the cost of high volume on a hot path.
     */
    PER_OCCURRENCE,

    /**
     * Log the **first** drop of a given header name (per transport instance) at `WARN`, and every
     * subsequent drop of that same name at verbose. The default: a caller learns a header is being
     * dropped without the log being flooded when the same header is re-set on a hot path. A
     * different header name still gets its own one-time `WARN`.
     */
    ONCE_PER_HEADER,

    /**
     * Log every dropped header at verbose only; never at `WARN`. Quietest — the loss is off by
     * default and visible only when the transport's log category is at debug/verbose.
     */
    VERBOSE_ONLY,
}
