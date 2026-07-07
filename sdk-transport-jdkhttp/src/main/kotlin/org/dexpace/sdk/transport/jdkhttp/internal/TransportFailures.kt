/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.transport.jdkhttp.internal

import org.dexpace.sdk.core.http.response.exception.NetworkException
import org.dexpace.sdk.core.util.Futures
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Maps a JDK transport-level failure onto the SDK's [NetworkException] contract.
 *
 * `HttpClient.sendAsync` reports a transport failure by completing its exchange future
 * exceptionally; the failure may arrive bare or wrapped in a
 * [java.util.concurrent.CompletionException] / [java.util.concurrent.ExecutionException]. This
 * strips those envelopes via [Futures.unwrap] (which is cycle-safe) and then classifies the root
 * cause:
 *
 *  - A read timeout ([SocketTimeoutException]) is wrapped in a [NetworkException] like any other
 *    transport failure. It is matched *before* the [InterruptedIOException] branch below (which it
 *    extends) because it is a timeout, not a cancellation — mirroring the sync path and the retry
 *    steps' carve-out, so both transports classify a timeout identically and it stays retryable.
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
    when (val cause = Futures.unwrap(error)) {
        is SocketTimeoutException -> NetworkException(cause.message, cause)
        is InterruptedIOException -> error
        is IOException -> NetworkException(cause.message, cause)
        else -> error
    }
