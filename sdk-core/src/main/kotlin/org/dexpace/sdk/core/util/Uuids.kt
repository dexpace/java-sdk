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

    /** Type-4 UUID via [ThreadLocalRandom]. Non-cryptographic but non-blocking. */
    fun random(): UUID {
        val tlr = ThreadLocalRandom.current()
        var msb = tlr.nextLong()
        var lsb = tlr.nextLong()
        msb = (msb and 0xffffffffffff0fffuL.toLong()) or 0x0000000000004000L
        lsb = (lsb and 0x3fffffffffffffffL) or Long.MIN_VALUE
        return UUID(msb, lsb)
    }
}
