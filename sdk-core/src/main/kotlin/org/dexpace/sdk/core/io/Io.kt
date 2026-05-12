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
     * already installed throws [IllegalStateException]; use [withProvider] for scoped
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
                        "Use Io.withProvider { ... } for scoped overrides."
                )
            }
            installed = provider
        }
    }

    /**
     * Runs [block] with [provider] installed, then restores whatever provider was previously
     * installed (or none). Intended for tests; not designed for concurrent use.
     */
    fun <T> withProvider(provider: IoProvider, block: () -> T): T {
        val previous = lock.withLock {
            val prev = installed
            installed = provider
            prev
        }
        try {
            return block()
        } finally {
            lock.withLock { installed = previous }
        }
    }
}
