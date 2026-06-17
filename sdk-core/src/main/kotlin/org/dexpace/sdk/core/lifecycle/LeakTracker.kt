/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.lifecycle

/**
 * Handle returned by [LeakDetector.track] for a single tracked resource.
 *
 * A `LeakTracker` is the caller's link to the detector's bookkeeping for one resource. The
 * owning resource calls [closed] from its `close()` method to mark itself cleanly released;
 * that single call is what distinguishes a clean shutdown from a leak when the resource later
 * becomes phantom-reachable.
 *
 * Calling [closed] is idempotent and cheap (a single atomic flag flip plus a map removal).
 * Resources that never close (e.g. a body the caller forgot to release) simply never call it,
 * and the detector reports them when the JVM reclaims the resource.
 *
 * ## Thread-safety
 *
 * [closed] is safe to call from any thread and any number of times.
 */
public fun interface LeakTracker {
    /**
     * Marks the tracked resource as cleanly closed.
     *
     * Idempotent: extra calls are no-ops. After this returns, the resource will not be
     * reported as a leak even once it becomes phantom-reachable. Resources should invoke
     * this from their `close()` implementation.
     */
    public fun closed()
}
