package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.io.BufferedSink
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import java.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoggableRequestBodyTest {
    @BeforeTest
    fun installProvider() {
        Io.installProvider(OkioIoProvider)
    }

    @Test
    fun `mediaType is delegated to the wrapped body`() {
        val type = MediaType.parse("text/plain")
        val delegate = RequestBody.create("x".toByteArray(), type)
        val wrapper = LoggableRequestBody(delegate)
        assertSame(type, wrapper.mediaType())
    }

    @Test
    fun `mediaType is null when delegate exposes no media type`() {
        val delegate = RequestBody.create("x".toByteArray())
        val wrapper = LoggableRequestBody(delegate)
        assertNull(wrapper.mediaType())
    }

    @Test
    fun `contentLength is delegated to the wrapped body`() {
        val delegate = RequestBody.create("hello".toByteArray())
        val wrapper = LoggableRequestBody(delegate)
        assertEquals(5L, wrapper.contentLength())
    }

    @Test
    fun `isReplayable returns true when delegate is replayable`() {
        val delegate = RequestBody.create("x".toByteArray())
        val wrapper = LoggableRequestBody(delegate)
        assertTrue(wrapper.isReplayable())
    }

    @Test
    fun `isReplayable returns false when delegate is single-use`() {
        val delegate = RequestBody.create(Io.provider.source("x".toByteArray()))
        val wrapper = LoggableRequestBody(delegate)
        assertFalse(wrapper.isReplayable())
    }

    @Test
    fun `writeTo mirrors bytes through to sink and tap`() {
        val payload = "payload".toByteArray()
        val delegate = RequestBody.create(payload)
        val wrapper = LoggableRequestBody(delegate)
        val sink = Io.provider.buffer()

        wrapper.writeTo(sink)

        assertContentEquals(payload, sink.snapshot())
        assertContentEquals(payload, wrapper.snapshot())
    }

    @Test
    fun `writeTo clears tap between attempts so repeated writes do not accumulate`() {
        val delegate = RequestBody.create("abc".toByteArray())
        val wrapper = LoggableRequestBody(delegate)

        wrapper.writeTo(Io.provider.buffer())
        wrapper.writeTo(Io.provider.buffer())

        // The tap reflects only the most recent attempt — not double the bytes.
        assertContentEquals("abc".toByteArray(), wrapper.snapshot())
    }

    @Test
    fun `snapshot before writeTo returns empty array`() {
        val wrapper = LoggableRequestBody(RequestBody.create("x".toByteArray()))
        assertContentEquals(ByteArray(0), wrapper.snapshot())
    }

    @Test
    fun `snapshot with maxBytes truncates to the requested prefix`() {
        val delegate = RequestBody.create("0123456789".toByteArray())
        val wrapper = LoggableRequestBody(delegate)
        wrapper.writeTo(Io.provider.buffer())
        assertContentEquals("01234".toByteArray(), wrapper.snapshot(5))
    }

    @Test
    fun `snapshot with maxBytes larger than payload returns full payload`() {
        val delegate = RequestBody.create("abc".toByteArray())
        val wrapper = LoggableRequestBody(delegate)
        wrapper.writeTo(Io.provider.buffer())
        assertContentEquals("abc".toByteArray(), wrapper.snapshot(99))
    }

    @Test
    fun `snapshot with negative maxBytes throws`() {
        val wrapper = LoggableRequestBody(RequestBody.create("x".toByteArray()))
        assertFailsWith<IllegalArgumentException> { wrapper.snapshot(-1) }
    }

    @Test
    fun `toReplayable returns this when delegate is already replayable`() {
        val delegate = RequestBody.create("x".toByteArray())
        val wrapper = LoggableRequestBody(delegate)
        assertSame(wrapper, wrapper.toReplayable(Io.provider))
    }

    @Test
    fun `toReplayable wraps a new replayable delegate when source is single-use`() {
        val delegate = RequestBody.create(Io.provider.source("data".toByteArray()))
        val wrapper = LoggableRequestBody(delegate)
        val replayable = wrapper.toReplayable(Io.provider)

        // Must produce a new LoggableRequestBody (not the original).
        assertNotSame(wrapper, replayable)
        assertTrue(replayable.isReplayable())

        val sink1 = Io.provider.buffer()
        val sink2 = Io.provider.buffer()
        replayable.writeTo(sink1)
        replayable.writeTo(sink2)
        assertContentEquals("data".toByteArray(), sink1.snapshot())
        assertContentEquals("data".toByteArray(), sink2.snapshot())
    }

    @Test
    fun `snapshot captures bytes written before a delegate failure`() {
        val delegate =
            object : RequestBody() {
                override fun mediaType(): MediaType? = null

                override fun writeTo(sink: BufferedSink) {
                    sink.write("partial".toByteArray())
                    throw IOException("boom")
                }
            }
        val wrapper = LoggableRequestBody(delegate)
        val sink = Io.provider.buffer()
        assertFailsWith<IOException> { wrapper.writeTo(sink) }
        assertContentEquals("partial".toByteArray(), wrapper.snapshot())
    }
}
