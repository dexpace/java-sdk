/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.util

import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * Non-blocking type-4 UUID generator.
 *
 * Uses [ThreadLocalRandom] instead of `UUID.randomUUID()` to avoid the `SecureRandom`
 * blocking trap on `/dev/random` under load. The resulting UUIDs are RFC 4122 compliant
 * (version 4, IETF variant) but are **not cryptographically secure** — use only for
 * non-secret identifiers such as request IDs and trace IDs.
 *
 * Thread-safe by virtue of [ThreadLocalRandom]: each thread has its own generator and
 * there is no shared state.
 */
internal object Uuids {
    // RFC 4122 §4.1.3: the version-4 nibble (bits 51..48 of msb) must be set to 0b0100.
    // Bitmask sets bit 14 of the msb half, producing `0x4xxx` in the version field.
    private const val VERSION_4_BITS = 0x0000000000004000L

    // RFC 4122 §4.1.1: clear bits 63..62 then set 62 to produce the IETF variant (10xx).
    // Mask preserves the lower 62 bits; the `or Long.MIN_VALUE` step sets bit 63 separately.
    private const val IETF_VARIANT_MASK = 0x3fffffffffffffffL

    /** Type-4 UUID via [ThreadLocalRandom]. Non-cryptographic but non-blocking. */
    fun random(): UUID {
        val tlr = ThreadLocalRandom.current()
        // Set version 4 nibble: clear bits 51..48, set bit 14 → 0x4xxx in the version field.
        val msb = (tlr.nextLong() and 0xffffffffffff0fffuL.toLong()) or VERSION_4_BITS
        // Set IETF variant bits: clear bit 63, set bit 62 → high two bits of clock_seq_hi become 10.
        val lsb = (tlr.nextLong() and IETF_VARIANT_MASK) or Long.MIN_VALUE
        return UUID(msb, lsb)
    }
}
