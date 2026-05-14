package org.dexpace.sdk.core.instrumentation

import org.slf4j.MDC
import org.slf4j.spi.MDCAdapter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MdcSnapshotTest {
    private var originalAdapter: MDCAdapter? = null

    @BeforeTest
    fun installAdapter() {
        originalAdapter = MDC.getMDCAdapter()
        installBasicMdcAdapter()
    }

    @AfterTest
    fun restoreAdapter() {
        MDC.clear()
        restoreMdcAdapter(originalAdapter)
    }

    @Test
    fun `capture stores an empty map when no MDC entries are set`() {
        val snap = MdcSnapshot.capture()
        MDC.put("trace.id", "after-capture")
        snap.restore()
        assertNull(MDC.get("trace.id"))
    }

    @Test
    fun `capture stores a non-empty map and restore brings it back`() {
        MDC.put("trace.id", "abc")
        MDC.put("span.id", "xyz")
        val snap = MdcSnapshot.capture()
        MDC.clear()
        snap.restore()
        assertEquals("abc", MDC.get("trace.id"))
        assertEquals("xyz", MDC.get("span.id"))
    }

    @Test
    fun `withMdc installs snapshot for the block and restores previous afterwards`() {
        MDC.put("trace.id", "captured")
        val snap = MdcSnapshot.capture()
        MDC.put("trace.id", "outer")
        var seenInBlock: String? = null
        snap.withMdc { seenInBlock = MDC.get("trace.id") }
        assertEquals("captured", seenInBlock)
        assertEquals("outer", MDC.get("trace.id"))
    }

    @Test
    fun `withMdc restores previous MDC even when the block throws`() {
        MDC.put("trace.id", "outer")
        val snap = MdcSnapshot.capture()
        MDC.put("trace.id", "before-call")
        assertFailsWith<IllegalStateException> {
            snap.withMdc { throw IllegalStateException("boom") }
        }
        assertEquals("before-call", MDC.get("trace.id"))
    }

    @Test
    fun `withMdc returns the block's result`() {
        MDC.put("trace.id", "captured")
        val snap = MdcSnapshot.capture()
        val result = snap.withMdc { 42 }
        assertEquals(42, result)
    }
}

private fun restoreMdcAdapter(adapter: MDCAdapter?) {
    val field = MDC::class.java.getDeclaredField("MDC_ADAPTER")
    field.isAccessible = true
    field.set(null, adapter)
}
