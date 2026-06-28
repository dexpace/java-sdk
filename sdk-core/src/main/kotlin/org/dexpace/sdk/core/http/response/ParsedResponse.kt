/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Request
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Pairs a raw [Response] with a [ResponseHandler] so the typed value can be parsed **lazily and
 * exactly once**, while the raw status / headers / metadata stay readable without forcing
 * deserialization.
 *
 * This is the raw-vs-parsed seam: header and status access (e.g. reading `ETag`, branching on a
 * `404`) goes straight to the underlying response and never touches the body, whereas the typed
 * value is produced on demand by the handler. Because the body is single-use, [value] memoizes
 * the handler's outcome — the first call runs the handler and every subsequent call returns the
 * same result (or re-throws the same failure) without re-invoking the handler or re-reading the
 * body.
 *
 * ## Body consumption
 *
 * The handler owns the body. Calling [value] runs the handler, which typically consumes and
 * closes the body (the built-in [ResponseHandler.string] / [ResponseHandler.empty] and adapter
 * JSON handlers do). **Read any raw headers / status before the first [value] call**, since the
 * body is gone afterwards. [close] is available for the path where the typed value is never
 * needed and the body must still be released.
 *
 * ## Thread-safety
 *
 * Raw accessors are immutable and safe to share. [value] is guarded by a [ReentrantLock]
 * (`synchronized` would pin a carrier thread under virtual threads): concurrent first calls block
 * until the single parse completes and then all observe the same memoized result. A `null` result
 * and a thrown failure are both memoized, so neither triggers a re-parse.
 *
 * @param T The typed value the handler produces.
 * @param raw The underlying raw response. Header / status / metadata access reads from here.
 * @param handler Strategy that maps [raw] to the typed value on first [value] access.
 */
public class ParsedResponse<out T> internal constructor(
    public val raw: Response,
    private val handler: ResponseHandler<T>,
) : Closeable {
    private val lock = ReentrantLock()

    // Holds the memoized outcome once the handler has run. A non-null Result means "parsed"
    // (success or failure); the wrapped value distinguishes the two. A boxed Result (rather than a
    // bare value) lets a legitimately-null success memoize without being mistaken for "unparsed".
    @Volatile
    private var outcome: Result<T>? = null

    /** The request that produced [raw]. Does not parse. */
    public val request: Request get() = raw.request

    /** The negotiated wire protocol. Does not parse. */
    public val protocol: Protocol get() = raw.protocol

    /** The HTTP status. Does not parse. */
    public val status: Status get() = raw.status

    /** The status-line reason phrase, or `null` if absent. Does not parse. */
    public val message: String? get() = raw.message

    /** The response headers. Does not parse. */
    public val headers: Headers get() = raw.headers

    /**
     * Returns the typed value, parsing it on the first call and memoizing the outcome.
     *
     * The handler runs at most once: the first call invokes [ResponseHandler.handle] (which
     * typically consumes and closes the body); subsequent calls return the same value, or
     * re-throw the same failure, without re-running the handler.
     *
     * Any failure the handler throws is memoized and re-thrown verbatim on every later call — not
     * just [IOException]. Handlers commonly throw **unchecked** exceptions (the Jackson `jsonHandler`
     * throws `SerdeException`), so callers should not assume the only escape is [IOException].
     *
     * @return The parsed value (which may be `null` if the handler is typed `ResponseHandler<T?>`
     *   and produces `null`).
     * @throws IOException If the handler failed with an [IOException] — cached and re-thrown. The
     *   `@Throws` declaration covers only the checked surface for Java callers; the handler may also
     *   propagate **unchecked** exceptions (e.g. `SerdeException` from the Jackson `jsonHandler`),
     *   which are memoized and re-thrown the same way.
     */
    @Throws(IOException::class)
    public fun value(): T {
        outcome?.let { return it.getOrThrow() }
        return lock.withLock {
            outcome?.let { return it.getOrThrow() }
            // Memoize the outcome (success or failure) so a later call neither re-runs the handler
            // nor re-reads the now-consumed body. `runCatching` catches `Throwable`, not just
            // `Exception`: re-running a handler that already drained the single-use body would read
            // a consumed stream, so even an `Error` (e.g. OOM mid-parse) is memoized and re-thrown.
            runCatching { handler.handle(raw) }.also { outcome = it }.getOrThrow()
        }
    }

    /**
     * Releases the raw response body. Idempotent (forwards to [Response.close], which is itself
     * idempotent). Safe to call whether or not [value] has run.
     *
     * @throws IOException If the underlying close fails.
     */
    @Throws(IOException::class)
    override fun close() {
        raw.close()
    }

    public companion object {
        /**
         * Creates a [ParsedResponse] that parses [response] with [handler] on first access.
         * Java-friendly factory mirroring the Kotlin [Response.parsedWith] extension.
         *
         * @param response The raw response.
         * @param handler Strategy that maps the response to the typed value.
         * @return A lazily-parsing [ParsedResponse].
         */
        @JvmStatic
        public fun <T> of(
            response: Response,
            handler: ResponseHandler<T>,
        ): ParsedResponse<T> = ParsedResponse(response, handler)
    }
}

/**
 * Wraps this response in a [ParsedResponse] bound to [handler], so the typed value parses lazily
 * and exactly once while raw status / headers stay accessible. Kotlin-ergonomic mirror of
 * [ParsedResponse.of].
 *
 * @param handler Strategy that maps this response to the typed value.
 * @return A lazily-parsing [ParsedResponse].
 */
public fun <T> Response.parsedWith(handler: ResponseHandler<T>): ParsedResponse<T> = ParsedResponse(this, handler)
