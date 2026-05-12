package org.dexpace.sdk.core.http.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ProtocolTest {

    @Test
    fun `every enum entry produces its canonical wire form via toString`() {
        assertEquals("http/1.0", Protocol.HTTP_1_0.toString())
        assertEquals("http/1.1", Protocol.HTTP_1_1.toString())
        assertEquals("http/2", Protocol.HTTP_2.toString())
        assertEquals("h2_prior_knowledge", Protocol.H2_PRIOR_KNOWLEDGE.toString())
        assertEquals("quic", Protocol.QUIC.toString())
    }

    @Test
    fun `get parses canonical lower-case form for each entry`() {
        assertSame(Protocol.HTTP_1_0, Protocol.get("http/1.0"))
        assertSame(Protocol.HTTP_1_1, Protocol.get("http/1.1"))
        assertSame(Protocol.HTTP_2, Protocol.get("http/2"))
        assertSame(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.get("h2_prior_knowledge"))
        assertSame(Protocol.QUIC, Protocol.get("quic"))
    }

    @Test
    fun `get is case-insensitive`() {
        assertSame(Protocol.HTTP_1_1, Protocol.get("HTTP/1.1"))
        assertSame(Protocol.HTTP_1_1, Protocol.get("Http/1.1"))
        assertSame(Protocol.QUIC, Protocol.get("QUIC"))
    }

    @Test
    fun `get accepts HTTP-2 alternative spelling`() {
        // The canonical wire form is `http/2`, but `HTTP/2` and `HTTP/2.0` also map.
        assertSame(Protocol.HTTP_2, Protocol.get("HTTP/2"))
        assertSame(Protocol.HTTP_2, Protocol.get("HTTP/2.0"))
        assertSame(Protocol.HTTP_2, Protocol.get("http/2.0"))
    }

    @Test
    fun `get throws on unknown protocol`() {
        val ex = assertFailsWith<IllegalArgumentException> { Protocol.get("http/3") }
        assertEquals(true, ex.message!!.contains("http/3"))
    }

    @Test
    fun `get throws on empty input`() {
        assertFailsWith<IllegalArgumentException> { Protocol.get("") }
    }

    @Test
    fun `entries enumerates every Protocol`() {
        val expected = setOf(
            Protocol.HTTP_1_0,
            Protocol.HTTP_1_1,
            Protocol.HTTP_2,
            Protocol.H2_PRIOR_KNOWLEDGE,
            Protocol.QUIC,
        )
        assertEquals(expected, Protocol.entries.toSet())
        assertEquals(5, Protocol.values().size)
    }

    @Test
    fun `valueOf round-trips every entry`() {
        for (entry in Protocol.entries) {
            assertSame(entry, Protocol.valueOf(entry.name))
        }
    }
}
