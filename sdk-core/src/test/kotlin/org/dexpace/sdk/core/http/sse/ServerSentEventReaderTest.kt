package org.dexpace.sdk.core.http.sse

import org.dexpace.sdk.core.io.BufferedSource
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerSentEventReaderTest {

    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    private fun source(text: String): BufferedSource =
        Io.provider.source(text.toByteArray(Charsets.UTF_8))

    private fun source(bytes: ByteArray): BufferedSource =
        Io.provider.source(bytes)

    @Test
    fun `single simple event with data field`() {
        val src = source("data: hello\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals(ServerSentEvent(data = listOf("hello")), event)
    }

    @Test
    fun `multi-line data accumulates into list`() {
        val src = source("data: line1\ndata: line2\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals(listOf("line1", "line2"), event?.data)
    }

    @Test
    fun `event with id field`() {
        val src = source("id: 1\ndata: x\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals("1", event?.id)
        assertEquals(listOf("x"), event?.data)
    }

    @Test
    fun `event with event-type field`() {
        val src = source("event: foo\ndata: x\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals("foo", event?.event)
        assertEquals(listOf("x"), event?.data)
    }

    @Test
    fun `retry parses as Duration of millis`() {
        val src = source("retry: 5000\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals(Duration.ofMillis(5000), event?.retry)
    }

    @Test
    fun `retry with non-integer value is ignored`() {
        val src = source("retry: bad\ndata: x\n\n")
        val event = ServerSentEventReader(src).next()
        assertNull(event?.retry)
        // data field still emitted so the event isn't lost
        assertEquals(listOf("x"), event?.data)
    }

    @Test
    fun `retry with embedded non-digit is ignored`() {
        val src = source("retry: 12a3\ndata: x\n\n")
        val event = ServerSentEventReader(src).next()
        assertNull(event?.retry)
    }

    @Test
    fun `retry with negative sign is ignored per spec`() {
        val src = source("retry: -100\ndata: x\n\n")
        val event = ServerSentEventReader(src).next()
        assertNull(event?.retry)
    }

    @Test
    fun `retry with empty value is ignored`() {
        val src = source("retry:\ndata: x\n\n")
        val event = ServerSentEventReader(src).next()
        assertNull(event?.retry)
    }

    @Test
    fun `comment line is captured into comment field`() {
        val src = source(":keep-alive\ndata: x\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals("keep-alive", event?.comment)
        assertEquals(listOf("x"), event?.data)
    }

    @Test
    fun `comment-only event is emitted because comment counts as a field`() {
        // Per implementation guardrail: comment lines DO count as 'hasField' so that
        // server keep-alives are visible. The spec is silent on whether to expose
        // them; we expose them for observability.
        val src = source(":keep-alive\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals("keep-alive", event?.comment)
        assertEquals(emptyList(), event?.data)
    }

    @Test
    fun `blank-only event is not emitted`() {
        // Single \n then EOF — no fields seen, should return null
        val src = source("\n")
        val event = ServerSentEventReader(src).next()
        assertNull(event)
    }

    @Test
    fun `multiple blank lines do not emit empty events`() {
        val src = source("\n\n\ndata: x\n\n")
        val reader = ServerSentEventReader(src)
        val event = reader.next()
        assertEquals(listOf("x"), event?.data)
        // No follow-up event
        assertNull(reader.next())
    }

    @Test
    fun `unknown field is silently discarded`() {
        val src = source("foo: bar\ndata: x\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals(listOf("x"), event?.data)
        // No leakage into other fields
        assertNull(event?.event)
        assertNull(event?.id)
    }

    @Test
    fun `BOM at stream start is consumed`() {
        // UTF-8 BOM is EF BB BF. The reader must strip it once at start.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val rest = "data: x\n\n".toByteArray(Charsets.UTF_8)
        val src = source(bom + rest)
        val event = ServerSentEventReader(src).next()
        assertEquals(listOf("x"), event?.data)
        // BOM did NOT end up in any field
        assertNull(event?.id)
        assertNull(event?.event)
    }

    @Test
    fun `BOM is only consumed once at stream start`() {
        // A BOM in the middle of the stream should be treated as data, not stripped.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val first = "data: x\n\n".toByteArray(Charsets.UTF_8)
        val second = "data: ".toByteArray(Charsets.UTF_8) + bom + "y\n\n".toByteArray(Charsets.UTF_8)
        val src = source(first + second)
        val reader = ServerSentEventReader(src)
        val e1 = reader.next()
        val e2 = reader.next()
        assertEquals(listOf("x"), e1?.data)
        // Second event's data still contains the BOM bytes as text
        assertNotNull(e2)
        assertEquals(1, e2.data.size)
        // The BOM character (U+FEFF) is present as part of the data string
        assertTrue(
            e2.data[0].contains('﻿'),
            "expected mid-stream BOM to be preserved as data, got: ${e2.data[0]}",
        )
    }

    @Test
    fun `multiple events emitted in sequence then null`() {
        val src = source("data: 1\n\ndata: 2\n\n")
        val reader = ServerSentEventReader(src)
        assertEquals(listOf("1"), reader.next()?.data)
        assertEquals(listOf("2"), reader.next()?.data)
        assertNull(reader.next())
    }

    @Test
    fun `stream end without trailing blank line emits partial event`() {
        val src = source("data: hello")
        val reader = ServerSentEventReader(src)
        val event = reader.next()
        assertEquals(listOf("hello"), event?.data)
        assertNull(reader.next())
    }

    @Test
    fun `stream end mid-event after newline emits partial event`() {
        val src = source("data: a\ndata: b\n")
        val reader = ServerSentEventReader(src)
        val event = reader.next()
        assertEquals(listOf("a", "b"), event?.data)
        assertNull(reader.next())
    }

    @Test
    fun `CRLF line endings work`() {
        val src = source("data: a\r\ndata: b\r\n\r\n")
        val event = ServerSentEventReader(src).next()
        assertEquals(listOf("a", "b"), event?.data)
    }

    @Test
    fun `bare CR line endings work`() {
        val src = source("data: a\rdata: b\r\r")
        val event = ServerSentEventReader(src).next()
        assertEquals(listOf("a", "b"), event?.data)
    }

    @Test
    fun `bare LF line endings work`() {
        val src = source("data: a\ndata: b\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals(listOf("a", "b"), event?.data)
    }

    @Test
    fun `mixed line endings within a stream work`() {
        val src = source("data: a\r\ndata: b\rdata: c\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals(listOf("a", "b", "c"), event?.data)
    }

    @Test
    fun `single space after colon is consumed`() {
        val src = source("data: hello\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals("hello", event?.data?.first())
    }

    @Test
    fun `further spaces after colon are preserved`() {
        val src = source("data:   hello\n\n")
        val event = ServerSentEventReader(src).next()
        // Only ONE space is consumed; the other two remain.
        assertEquals("  hello", event?.data?.first())
    }

    @Test
    fun `field with no space after colon retains value as-is`() {
        val src = source("data:hello\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals("hello", event?.data?.first())
    }

    @Test
    fun `field with no colon is treated as field with empty value`() {
        // "data" alone (no colon) should add an empty entry to data.
        val src = source("data\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals(listOf(""), event?.data)
    }

    @Test
    fun `id with null byte is replaced with replacement char`() {
        // The id "a b" must become "a�b".
        val src = source("id: a b\ndata: x\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals("a�b", event?.id)
    }

    @Test
    fun `id without null bytes is preserved verbatim`() {
        val src = source("id: abc123\ndata: x\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals("abc123", event?.id)
    }

    @Test
    fun `id-only event emits with empty data list`() {
        val src = source("id: 42\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals("42", event?.id)
        assertEquals(emptyList(), event?.data)
    }

    @Test
    fun `latest comment in event wins`() {
        val src = source(":first\n:second\ndata: x\n\n")
        val event = ServerSentEventReader(src).next()
        assertEquals("second", event?.comment)
    }

    @Test
    fun `readServerSentEvents extension returns all events`() {
        val src = source("data: 1\n\ndata: 2\n\ndata: 3\n\n")
        val events = src.readServerSentEvents().toList()
        assertEquals(3, events.size)
        assertEquals(listOf("1"), events[0].data)
        assertEquals(listOf("2"), events[1].data)
        assertEquals(listOf("3"), events[2].data)
    }

    @Test
    fun `readServerSentEvents handles empty stream`() {
        val src = source("")
        val events = src.readServerSentEvents().toList()
        assertTrue(events.isEmpty())
    }

    @Test
    fun `listener pattern dispatches events through callback`() {
        val src = source("data: a\n\ndata: b\n\ndata: c\n\n")
        val received = mutableListOf<ServerSentEvent>()
        var closed = false
        val listener = object : ServerSentEventListener {
            override fun onEvent(event: ServerSentEvent) {
                received.add(event)
            }
            override fun onClose() {
                closed = true
            }
        }

        val reader = ServerSentEventReader(src)
        try {
            while (true) {
                val event = reader.next() ?: break
                listener.onEvent(event)
            }
        } finally {
            listener.onClose()
        }

        assertEquals(3, received.size)
        assertEquals(listOf("a"), received[0].data)
        assertEquals(listOf("b"), received[1].data)
        assertEquals(listOf("c"), received[2].data)
        assertTrue(closed)
    }

    @Test
    fun `listener default error and close methods are no-op`() {
        // Verify the default implementations don't throw — fundamental sanity check.
        val listener = object : ServerSentEventListener {
            override fun onEvent(event: ServerSentEvent) {}
        }
        listener.onError(RuntimeException("boom"))
        listener.onClose()
    }

    @Test
    fun `very long data line over 64KB succeeds`() {
        // 100 KB of digits — well above the 64 KB threshold we want to cover.
        val payload = buildString(100_000) {
            for (i in 0 until 100_000) append((i % 10).toString())
        }
        val src = source("data: $payload\n\n")
        val event = ServerSentEventReader(src).next()
        assertNotNull(event)
        assertEquals(1, event.data.size)
        assertEquals(payload.length, event.data[0].length)
        assertEquals(payload, event.data[0])
    }

    @Test
    fun `id field is reset between events`() {
        // Per spec, "id" is the only field that persists across events (in the
        // browser's "last event id"), but our parser surfaces what the wire sent
        // for each event independently. The second event should NOT carry the
        // first event's id.
        val src = source("id: 1\ndata: a\n\ndata: b\n\n")
        val reader = ServerSentEventReader(src)
        val e1 = reader.next()
        val e2 = reader.next()
        assertEquals("1", e1?.id)
        assertNull(e2?.id)
    }

    @Test
    fun `multi-event stream with mixed terminators and fields`() {
        val sse = buildString {
            append(":welcome\r\n")
            append("retry: 1500\r\n")
            append("\r\n")
            append("id: m1\n")
            append("event: message\n")
            append("data: hello\n")
            append("data: world\n")
            append("\n")
            append("event: close\r")
            append("\r")
        }
        val src = source(sse)
        val reader = ServerSentEventReader(src)

        val e1 = reader.next()
        assertNotNull(e1)
        assertEquals("welcome", e1.comment)
        assertEquals(Duration.ofMillis(1500), e1.retry)

        val e2 = reader.next()
        assertNotNull(e2)
        assertEquals("m1", e2.id)
        assertEquals("message", e2.event)
        assertEquals(listOf("hello", "world"), e2.data)

        val e3 = reader.next()
        assertNotNull(e3)
        assertEquals("close", e3.event)

        assertNull(reader.next())
    }

    @Test
    fun `isEmpty flag works as expected`() {
        assertTrue(ServerSentEvent().isEmpty)
        assertFalse(ServerSentEvent(data = listOf("x")).isEmpty)
        assertFalse(ServerSentEvent(id = "1").isEmpty)
        assertFalse(ServerSentEvent(event = "type").isEmpty)
        assertFalse(ServerSentEvent(comment = "c").isEmpty)
        assertFalse(ServerSentEvent(retry = Duration.ofMillis(100)).isEmpty)
    }
}
