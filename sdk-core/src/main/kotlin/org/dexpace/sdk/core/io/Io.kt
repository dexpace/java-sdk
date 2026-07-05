/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.io

import org.dexpace.sdk.core.instrumentation.ClientLogger
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
 * 2. **ServiceLoader auto-discovery** — when no explicit install exists, [IoProvider]
 *    implementations are discovered via [ServiceLoader], consulting the thread-context
 *    classloader before the interface's own defining loader so that a provider registered
 *    in a child loader (servlet containers, OSGi, plugin hosts) is still found. When exactly
 *    one implementation is discoverable it is resolved and cached automatically — the scan
 *    runs at most once.
 * 3. **Error** — zero discoverable providers throw with an actionable message;
 *    more than one throw listing all candidates so the ambiguity is obvious.
 *
 * ## Install after auto-resolution
 *
 * Explicit [installProvider] always wins, even after ServiceLoader auto-resolution. If a
 * provider was already auto-resolved **and handed out** to a caller before the explicit install
 * arrives, [installProvider] logs a WARN (it does not fail): objects created against the
 * previously-resolved provider may already exist, so installing a different provider late risks
 * mixed-provider state. Call [installProvider] at application startup, before any I/O, to avoid it.
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
     * `true` once a ServiceLoader-[resolved] provider has actually been returned to a caller from
     * [provider]. Lets [installProvider] warn only when a late explicit install replaces a provider
     * that was already in use, not merely resolvable.
     */
    @Volatile
    private var resolvedReturned: Boolean = false

    /**
     * Logger seam. `internal var` (not `private val`) so tests can inject a fake SLF4J-backed
     * [ClientLogger] to assert the install-after-resolve WARN. Not part of the public API.
     */
    internal var logger: ClientLogger = ClientLogger(Io::class)

    /**
     * Returns the active provider: explicit install first, then ServiceLoader resolution.
     *
     * Throws [IllegalStateException] when no provider is installed and either zero or more
     * than one [IoProvider] implementations are discoverable on the classpath.
     */
    public val provider: IoProvider
        get() {
            installed?.let { return it }
            resolved?.let {
                resolvedReturned = true
                return it
            }
            return lock.withLock {
                installed?.let { return it }
                resolved?.let {
                    resolvedReturned = true
                    return it
                }
                val p = selectProvider(loadCandidates())
                resolved = p
                resolvedReturned = true
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

    /**
     * Discovers all [IoProvider] implementations via [ServiceLoader], consulting the classloaders
     * returned by [loaderSearchOrder] (thread-context loader first, then the interface's own
     * defining loader) and de-duplicating by concrete class.
     *
     * Consulting the thread-context classloader first is the hardened SPI pattern for
     * hierarchical-classloader deployments — servlet containers (e.g. Tomcat shared lib), OSGi, and
     * plugin hosts — where sdk-core can sit in a parent loader while the provider's
     * `META-INF/services` entry sits in a child loader that only the context loader can see. A
     * provider visible through both loaders would otherwise be discovered twice (ServiceLoader
     * instantiates a fresh object per scan) and misreported as an ambiguous multi-provider setup,
     * so candidates are keyed by their concrete [Class] and the first occurrence wins.
     *
     * Internal (not private) so discovery can be exercised directly from tests.
     */
    internal fun loadCandidates(): List<IoProvider> {
        val definingLoader = IoProvider::class.java.classLoader
        val contextLoader = Thread.currentThread().contextClassLoader
        val byClass = LinkedHashMap<Class<out IoProvider>, IoProvider>()
        for (loader in loaderSearchOrder(contextLoader, definingLoader)) {
            for (candidate in ServiceLoader.load(IoProvider::class.java, loader)) {
                byClass.getOrPut(candidate.javaClass) { candidate }
            }
        }
        return byClass.values.toList()
    }

    /**
     * Ordered, duplicate-free classloader search order for SPI discovery: the [contextLoader]
     * (thread-context classloader) first when it is set and distinct from [definingLoader], then
     * the [definingLoader] (the loader that defined [IoProvider]). When the context loader is
     * absent or is the same instance as the defining loader, only the defining loader is returned
     * so the classpath is not scanned twice. The defining loader may itself be `null` (the
     * bootstrap loader), which [ServiceLoader.load] accepts.
     *
     * Internal (not private) so the ordering can be asserted directly from tests.
     */
    internal fun loaderSearchOrder(
        contextLoader: ClassLoader?,
        definingLoader: ClassLoader?,
    ): List<ClassLoader?> =
        if (contextLoader == null || contextLoader === definingLoader) {
            listOf(definingLoader)
        } else {
            listOf(contextLoader, definingLoader)
        }

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
     * A ServiceLoader-resolved provider does **not** block an explicit install (the explicit
     * install always wins), but if that resolved provider was already handed out to a caller a
     * WARN is logged, because objects may already have been created against it — see
     * [warnIfReplacingResolvedProvider]. Any previously cached ServiceLoader-resolved provider is
     * then cleared so the explicit install takes full effect on the next [provider] access.
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
            warnIfReplacingResolvedProvider(provider)
            installed = provider
            resolved = null
            resolvedReturned = false
        }
    }

    /**
     * Emits a WARN when this explicit install replaces a *different* provider that had already
     * been auto-resolved via ServiceLoader **and handed out** to a caller. The explicit install
     * still wins — the contract is intentional — but by this point live objects may have been
     * created against the previously-resolved provider, so the mismatch is worth surfacing.
     *
     * No warning fires when nothing was resolved yet, when the resolved provider was never
     * returned from [provider], or when [incoming] is the same instance that was resolved.
     *
     * Must be called while holding [lock].
     */
    private fun warnIfReplacingResolvedProvider(incoming: IoProvider) {
        if (installed != null) return
        val previouslyResolved = resolved ?: return
        if (!resolvedReturned || previouslyResolved === incoming) return
        logger.atWarning()
            .event("io.provider.install_after_resolve")
            .field("resolved", previouslyResolved::class.qualifiedName ?: previouslyResolved::class.toString())
            .field("installed", incoming::class.qualifiedName ?: incoming::class.toString())
            .field(
                "message",
                "An IoProvider was auto-resolved via ServiceLoader and already handed out before " +
                    "Io.installProvider(...) was called; objects created earlier may reference the " +
                    "previously-resolved provider. Install the provider at application startup, before " +
                    "any I/O, to avoid mixed-provider state.",
            ).log()
    }

    /**
     * Swaps the installed provider without checking for conflicts. Intended as an `internal`
     * seam for the test-fixtures `org.dexpace.sdk.core.testing.withProvider` helper; not part
     * of the public API.
     *
     * Also clears any cached ServiceLoader-resolved provider (and its handed-out flag) so tests
     * that reset state to null trigger a fresh resolution on the next [provider] access.
     */
    internal fun swapProvider(provider: IoProvider?): IoProvider? =
        lock.withLock {
            val previous = installed
            installed = provider
            resolved = null
            resolvedReturned = false
            previous
        }
}
