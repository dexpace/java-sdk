/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.io

import java.util.ServiceLoader
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Holds the [IoProvider] used by the SDK's I/O layer.
 *
 * Usage:
 *
 * ```
 * // At application startup, once (optional when exactly one IoProvider is on the classpath):
 * Io.installProvider(OkioIoProvider)
 *
 * // Anywhere afterward:
 * val buffer = Io.provider.buffer()
 * ```
 *
 * ## Provider resolution order
 *
 * 1. **Explicit install** via [installProvider] — always wins.
 * 2. **ServiceLoader auto-discovery** — when no explicit install exists and exactly one
 *    [IoProvider] implementation is discoverable via [ServiceLoader] on the classpath,
 *    it is resolved and cached automatically. The result is cached after the first resolution
 *    so the ServiceLoader scan runs at most once.
 * 3. **Error** — zero discoverable providers throw with an actionable message;
 *    more than one throw listing all candidates so the ambiguity is obvious.
 *
 * ## Thread-safety
 *
 * Reads of [provider] go through `@Volatile` fields so callers see the install effect without
 * locking. Writes ([installProvider], [swapProvider]) take a [ReentrantLock] so concurrent
 * installs cannot race past the conflict check. The ServiceLoader scan runs at most once
 * through a double-checked locking pattern.
 */
public object Io {
    private val lock = ReentrantLock()

    @Volatile
    private var installed: IoProvider? = null

    @Volatile
    private var resolved: IoProvider? = null

    /**
     * Returns the active provider: explicit install first, then ServiceLoader resolution.
     *
     * Throws [IllegalStateException] when no provider is installed and either zero or more
     * than one [IoProvider] implementations are discoverable on the classpath.
     */
    public val provider: IoProvider
        get() {
            installed?.let { return it }
            resolved?.let { return it }
            return lock.withLock {
                installed?.let { return it }
                resolved?.let { return it }
                val p = selectProvider(loadCandidates())
                resolved = p
                p
            }
        }

    /**
     * Selects the single [IoProvider] from [candidates], or throws a descriptive
     * [IllegalStateException] when the list is empty or ambiguous.
     *
     * Separated from [loadCandidates] so the selection logic can be unit-tested in isolation.
     */
    internal fun selectProvider(candidates: List<IoProvider>): IoProvider =
        when (candidates.size) {
            0 ->
                error(
                    "No IoProvider installed. Call Io.installProvider(...) at application startup " +
                        "(e.g. Io.installProvider(OkioIoProvider)).",
                )
            1 -> candidates[0]
            else -> {
                val names = candidates.joinToString { it::class.qualifiedName ?: it::class.toString() }
                error(
                    "Multiple IoProvider implementations found on the classpath ($names); " +
                        "call Io.installProvider(...) explicitly to choose one.",
                )
            }
        }

    private fun loadCandidates(): List<IoProvider> =
        ServiceLoader.load(IoProvider::class.java, IoProvider::class.java.classLoader).toList()

    /**
     * Installs [provider] as the global I/O provider.
     *
     * Idempotent when called with the same instance — re-installing the exact provider that
     * is already installed is a no-op. Installing a **different** [IoProvider] when one is
     * already installed throws [IllegalStateException]; use
     * `org.dexpace.sdk.core.testing.withProvider` (test-fixtures artifact) for scoped
     * overrides instead of double-installing.
     *
     * All installs are serialized under a [ReentrantLock]: the first install succeeds
     * unconditionally; subsequent installs of the same instance are no-ops; subsequent
     * installs of a different provider throw [IllegalStateException].
     *
     * Any previously cached ServiceLoader-resolved provider is cleared so the explicit
     * install takes full effect on the next [provider] access.
     */
    public fun installProvider(provider: IoProvider) {
        lock.withLock {
            val existing = installed
            check(existing == null || existing === provider) {
                val existingName = existing!!::class.qualifiedName ?: existing::class
                val providerName = provider::class.qualifiedName ?: provider::class
                "An IoProvider ($existingName) is " +
                    "already installed; refusing to overwrite with a different provider " +
                    "($providerName). " +
                    "Use withProvider { ... } from org.dexpace.sdk.core.testing for scoped overrides."
            }
            installed = provider
            resolved = null
        }
    }

    /**
     * Swaps the installed provider without checking for conflicts. Intended as an `internal`
     * seam for the test-fixtures `org.dexpace.sdk.core.testing.withProvider` helper; not part
     * of the public API.
     *
     * Also clears any cached ServiceLoader-resolved provider so tests that reset state to null
     * trigger a fresh resolution on the next [provider] access.
     */
    internal fun swapProvider(provider: IoProvider?): IoProvider? =
        lock.withLock {
            val previous = installed
            installed = provider
            resolved = null
            previous
        }
}
