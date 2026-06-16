/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response

import org.dexpace.sdk.core.io.Io
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Maps a raw [Response] to a typed result of type [T].
 *
 * A `ResponseHandler` is the seam generated service code dispatches against: the transport
 * produces a raw [Response], and the handler decides how to turn it into a domain value — decode
 * a JSON body into a DTO, read a body as text, or simply discard the body for an empty result.
 *
 * ## Body ownership
 *
 * A handler that reads the body **owns consuming and closing it**. [handle] is expected to leave
 * the response closed when it returns (whether it read the body or not), so callers do not need a
 * surrounding `use {}` block once they have delegated to a handler. The built-in [string] and
 * [empty] handlers honor this. Because the body is single-use, a handler must read it at most
 * once; pair a handler with [ParsedResponse] when the typed value must be exposed lazily and
 * memoized so the body is consumed exactly once.
 *
 * ## Raw access first
 *
 * Reading the body is destructive, so any header / status inspection that must happen alongside
 * parsing should read from the raw [Response] (or [ParsedResponse]'s raw accessors) **before**
 * invoking the handler.
 *
 * ## Thread-safety
 *
 * Handlers are typically stateless and shared across requests; the built-in factories return
 * stateless instances. A stateful handler must guard its own state.
 *
 * ## Nullability
 *
 * A handler that may legitimately produce `null` (e.g. an absent-but-valid payload) should be typed
 * `ResponseHandler<T?>` so the nullability is visible to Kotlin and Java callers alike; otherwise a
 * `null` slips through a non-null `T` as a platform value. [ParsedResponse.value] memoizes a `null`
 * result correctly either way.
 *
 * @param T The typed result this handler produces.
 */
public fun interface ResponseHandler<out T> {
    /**
     * Consumes [response] and produces the typed result. Implementations that read the body must
     * also close [response] before returning.
     *
     * @param response The raw response to map.
     * @return The typed result.
     * @throws IOException If reading the body fails.
     */
    @Throws(IOException::class)
    public fun handle(response: Response): T

    public companion object {
        /**
         * A handler that reads the entire response body as a UTF-8 [String] and closes the
         * response. A bodyless response (e.g. `204 No Content`) yields an empty string.
         *
         * **Unbounded.** This reads the whole body into a single in-memory [String] with no size
         * cap, so it is an unbounded-allocation vector against a hostile or misbehaving server.
         * Unlike the bounded body-logging path elsewhere in the SDK, it applies no limit — use it
         * only for trusted endpoints with bounded payloads, not for untrusted or large bodies.
         *
         * @return A stateless [String] handler.
         */
        @JvmStatic
        public fun string(): ResponseHandler<String> =
            ResponseHandler { response ->
                response.use {
                    val body = it.body ?: return@use ""
                    body.source().readString(StandardCharsets.UTF_8)
                }
            }

        /**
         * A handler that fully drains and closes the body, discarding its bytes, and returns
         * [Unit]. Use for endpoints whose payload is irrelevant (e.g. a `DELETE` returning a
         * status only) but whose connection must still be released.
         *
         * @return A stateless discarding handler.
         */
        @JvmStatic
        public fun empty(): ResponseHandler<Unit> =
            ResponseHandler { response ->
                response.use {
                    val body = it.body ?: return@use
                    val source = body.source()
                    // Pump into a throwaway scratch buffer (cleared each round) so the connection
                    // is released without materializing the whole body in memory. The buffer is
                    // closed deterministically so its segments are recycled even if the drain
                    // throws mid-stream, rather than leaning on the GC.
                    Io.provider.buffer().use { scratch ->
                        while (source.read(scratch, DRAIN_CHUNK_BYTES) != -1L) {
                            scratch.clear()
                        }
                    }
                }
            }

        /**
         * Per-read pump size for the discarding drain — a reasonable chunk size. `read` treats it
         * as an upper bound, so the exact value is not load-bearing for correctness.
         */
        private const val DRAIN_CHUNK_BYTES: Long = 8 * 1024
    }
}
