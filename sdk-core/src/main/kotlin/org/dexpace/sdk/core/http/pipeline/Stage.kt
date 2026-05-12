package org.dexpace.sdk.core.http.pipeline

/**
 * Pipeline stage. Lower [order] runs first (closer to caller entry); higher [order] runs
 * later (closer to wire send). Pillar stages hold exactly one step each; non-pillar stages
 * hold an ordered deque of user steps.
 *
 * Sparse [order] values (100s apart) leave room to insert new stages later without
 * renumbering existing ones.
 */
@Suppress("unused")
enum class Stage(val order: Int, val isPillar: Boolean) {

    // -- Wrapping steps (re-invoke downstream via next.copy()) --
    REDIRECT(100, true),         // pillar: RedirectStep singleton
    POST_REDIRECT(150, false),   // runs *inside* the redirect loop, per hop
    RETRY(200, true),            // pillar: RetryStep singleton
    POST_RETRY(250, false),      // runs *inside* the retry loop, per attempt

    // -- Auth --
    PRE_AUTH(300, false),        // request not yet authenticated
    AUTH(400, true),             // pillar: AuthStep singleton
    POST_AUTH(500, false),       // request now carries auth header

    // -- Instrumentation --
    PRE_LOGGING(600, false),     // bytes not yet captured
    LOGGING(700, true),          // pillar: InstrumentationStep singleton
    POST_LOGGING(800, false),    // body wrapped in Loggable{Request,Response}Body

    // -- Serialization / send --
    PRE_SERDE(900, false),       // body still typed object (if applicable)
    SERDE(1000, true),           // pillar: body-to-bytes (reserved; currently unused)
    POST_SERDE(1100, false),     // body now a byte stream
    PRE_SEND(1200, false),       // last hop before the wire
    SEND(1300, true),            // terminal: HttpClient.execute (not a user-step slot)
}
