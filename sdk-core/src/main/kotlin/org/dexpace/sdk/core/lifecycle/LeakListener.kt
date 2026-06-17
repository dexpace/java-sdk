/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.lifecycle

/**
 * Sink for leak reports produced by a [LeakDetector].
 *
 * The default detector logs each report at `WARN` through SLF4J (see
 * [LeakDetector.loggingListener]). Applications that want to forward leaks to metrics, fail a
 * test suite, or aggregate counts can supply their own implementation via
 * [LeakDetector.Builder.listener].
 *
 * ## Threading
 *
 * [onLeak] is invoked from the detector's internal reaper thread (or from the calling thread
 * when [LeakDetector.drainManually] is used in tests). Implementations must be thread-safe and
 * should not block; a slow listener stalls detection of subsequent leaks. Exceptions thrown
 * from [onLeak] are caught by the detector and never propagate to the JVM.
 */
public fun interface LeakListener {
    /**
     * Called once per detected leak. Must not throw; any thrown exception is swallowed by the
     * detector to keep the reaper alive.
     */
    public fun onLeak(report: LeakReport)
}
