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
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * Maps a JDK transport-level failure onto the SDK's [NetworkException] contract.
 *
 * `HttpClient.sendAsync` reports a transport failure by completing its exchange future
 * exceptionally; the failure may arrive bare or wrapped in a [CompletionException] /
 * [ExecutionException]. This strips those envelopes and then classifies the root cause:
 *
 *  - An interrupt-derived failure ([InterruptedIOException]) is returned untouched — as the
 *    ORIGINAL [error] with any envelopes intact — so the caller keeps observing it as an
 *    interrupt.
 *  - Any other [IOException] — connect refused, DNS lookup failure, peer reset, TLS handshake
 *    failure, a non-interrupt timeout — is wrapped in a [NetworkException]. Because that is itself
 *    an [IOException], existing `catch (IOException)` sites keep matching and the retry classifier
 *    sees the retryable transport-failure contract.
 *  - Anything else is returned untouched.
 */
internal fun mapTransportFailure(error: Throwable): Throwable =
    when (val cause = unwrapCompletion(error)) {
        is InterruptedIOException -> error
        is IOException -> NetworkException(cause.message, cause)
        else -> error
    }

/** Strips [CompletionException] / [ExecutionException] envelopes to reach the underlying cause. */
private fun unwrapCompletion(error: Throwable): Throwable {
    var current = error
    while (current is CompletionException || current is ExecutionException) {
        val cause = current.cause ?: break
        if (cause === current) break
        current = cause
    }
    return current
}
