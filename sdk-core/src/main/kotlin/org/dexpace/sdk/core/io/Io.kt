package org.dexpace.sdk.core.io

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Holds the [IoProvider] installed by the consuming application.
 *
 * Usage:
 *
 * ```
 * // At application startup, once:
 * Io.installProvider(OkioIoProvider)
 *
 * // Anywhere afterward:
 * val buffer = Io.provider.buffer()
 * ```
 *
 * If no provider is installed, [provider] throws an [IllegalStateException] with a message
 * naming the missing setup call. Failure is loud and immediate — there is no fallback and no
 * `ServiceLoader` magic.
 *
 * ## Thread-safety
 *
 * Reads of [provider] go through a `@Volatile` field so callers see the install effect without
 * locking. Writes ([installProvider], [swapProvider]) take a [ReentrantLock] so concurrent
 * installs cannot race past the conflict check.
 */
object Io {
    private val lock = ReentrantLock()

    @Volatile
    private var installed: IoProvider? = null

    /**
     * Returns the installed provider, or throws if none was installed.
     */
    val provider: IoProvider
        get() = installed ?: error(
            "No IoProvider installed. Call Io.installProvider(...) at application startup " +
                "(e.g. Io.installProvider(OkioIoProvider))."
        )

    /**
     * Installs [provider] as the global I/O provider.
     *
     * Idempotent when called with the same instance — re-installing the exact provider that
     * is already installed is a no-op. Installing a **different** [IoProvider] when one is
     * already installed throws [IllegalStateException]; use
     * `org.dexpace.sdk.core.testing.withProvider` (test-fixtures artifact) for scoped
     * overrides instead of double-installing.
     *
     * The first install is unconditional; subsequent installs are checked under a lock so
     * concurrent installs cannot race past each other.
     */
    fun installProvider(provider: IoProvider) {
        lock.withLock {
            val existing = installed
            if (existing != null && existing !== provider) {
                throw IllegalStateException(
                    "An IoProvider (${existing::class.qualifiedName ?: existing::class}) is " +
                        "already installed; refusing to overwrite with a different provider " +
                        "(${provider::class.qualifiedName ?: provider::class}). " +
                        "Use withProvider { ... } from org.dexpace.sdk.core.testing for scoped overrides."
                )
            }
            installed = provider
        }
    }

    /**
     * Swaps the installed provider without checking for conflicts. Intended as a
     * package-private seam for [org.dexpace.sdk.core.testing.withProvider]; not part of the
     * public API.
     */
    internal fun swapProvider(provider: IoProvider?): IoProvider? =
        lock.withLock { installed.also { installed = provider } }
}
