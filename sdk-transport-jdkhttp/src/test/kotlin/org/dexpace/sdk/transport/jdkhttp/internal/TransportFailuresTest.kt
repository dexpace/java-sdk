/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp.internal

import org.dexpace.sdk.core.http.response.exception.NetworkException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Unit coverage for [mapTransportFailure]'s classification of async-delivered exchange failures. */
class TransportFailuresTest {
    @Test
    fun `a bare transport IOException is wrapped as a retryable NetworkException`() {
        val cause = ConnectException("connection refused")
        val mapped = mapTransportFailure(cause)
        assertTrue(mapped is NetworkException, "expected NetworkException, got ${mapped::class}")
        assertSame(cause, mapped.cause, "the original IOException must be preserved as the cause")
        assertTrue(mapped.isRetryable, "a transport failure must be retryable")
    }

    @Test
    fun `a CompletionException-wrapped IOException is unwrapped then mapped`() {
        val cause = IOException("reset by peer")
        val mapped = mapTransportFailure(CompletionException(cause))
        assertTrue(mapped is NetworkException, "expected NetworkException, got ${mapped::class}")
        assertSame(cause, mapped.cause, "the inner IOException must become the NetworkException cause")
    }

    @Test
    fun `an interrupt-derived failure is returned untouched`() {
        val interrupt = InterruptedIOException("interrupted")
        assertSame(interrupt, mapTransportFailure(interrupt), "an interrupt must not be wrapped")

        val wrapped = CompletionException(interrupt)
        assertSame(wrapped, mapTransportFailure(wrapped), "an enveloped interrupt must pass through verbatim")
    }

    @Test
    fun `a non-IO failure is returned untouched`() {
        val boom = IllegalStateException("not a transport failure")
        assertSame(boom, mapTransportFailure(boom), "a non-IO failure must not be repackaged")
    }

    @Test
    fun `nested envelopes are stripped down to the root IOException`() {
        val cause = IOException("tls handshake failure")
        val nested = CompletionException(ExecutionException("execution", cause))
        val mapped = mapTransportFailure(nested)
        assertTrue(mapped is NetworkException, "expected NetworkException, got ${mapped::class}")
        assertSame(cause, mapped.cause, "the root IOException must be reached through both envelopes")
    }

    @Test
    fun `an envelope with no cause is returned untouched`() {
        // A CompletionException carrying no cause has no transport IOException to surface, so the
        // envelope itself is returned rather than being mapped or looping in the unwrap.
        val empty = CompletionException("no cause", null)
        assertSame(empty, mapTransportFailure(empty))
    }
}
