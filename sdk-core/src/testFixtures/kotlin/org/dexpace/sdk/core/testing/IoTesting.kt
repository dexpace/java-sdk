package org.dexpace.sdk.core.testing

import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.core.io.IoProvider

/**
 * Test-only seam: temporarily swap [Io.provider] for the duration of [block]. Not safe for
 * parallel test execution across multiple test classes; use sequential execution if multiple
 * tests rely on this. Restored even on exception.
 */
fun <T> withProvider(
    provider: IoProvider,
    block: () -> T,
): T {
    val previous = Io.swapProvider(provider)
    try {
        return block()
    } finally {
        Io.swapProvider(previous)
    }
}
