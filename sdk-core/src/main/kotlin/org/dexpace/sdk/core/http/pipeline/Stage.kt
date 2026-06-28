/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

// Public API surface — not every pipeline-stage entry is referenced within this module; SDK consumers may use any.

/**
 * Pipeline stage. Lower [order] runs first (closer to caller entry); higher [order] runs
 * later (closer to wire send). Pillar stages hold exactly one step each; non-pillar stages
 * hold an ordered deque of user steps.
 *
 * Sparse [order] values (100s apart) leave room to insert new stages later without
 * renumbering existing ones.
 *
 * @property order Stable numeric identity for the stage; ascends with declaration order.
 *   The builder emits steps in declaration order (`Stage.entries`), not by reading this
 *   value — it exists as a stable, sortable inspection key for callers.
 * @property isPillar True if the stage admits at most one step (singleton). False for
 *   user-extensible stages backed by an ordered deque.
 */
@Suppress("unused")
public enum class Stage(public val order: Int, public val isPillar: Boolean) {
    // -- Wrapping steps (re-invoke downstream via next.copy()) --
    REDIRECT(100, true), // pillar: RedirectStep singleton
    POST_REDIRECT(150, false), // runs *inside* the redirect loop, per hop
    RETRY(200, true), // pillar: RetryStep singleton
    POST_RETRY(250, false), // runs *inside* the retry loop, per attempt

    // -- Auth --
    PRE_AUTH(300, false), // request not yet authenticated
    AUTH(400, true), // pillar: AuthStep singleton
    POST_AUTH(500, false), // request now carries auth header

    // -- Instrumentation --
    PRE_LOGGING(600, false), // bytes not yet captured
    LOGGING(700, true), // pillar: InstrumentationStep singleton
    POST_LOGGING(800, false), // body wrapped in Loggable{Request,Response}Body

    // -- Serialization / send --
    PRE_SERDE(900, false), // body still typed object (if applicable)
    SERDE(1000, true), // pillar: body-to-bytes (reserved; currently unused)
    POST_SERDE(1100, false), // body now a byte stream
    PRE_SEND(1200, false), // last hop before the wire
    SEND(1300, true), // terminal: HttpClient.execute (not a user-step slot)
}
