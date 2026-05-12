package org.dexpace.sdk.core.testing

import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.Io
import org.dexpace.sdk.io.OkioIoProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FakeHttpClientTest {

    @BeforeTest
    fun installProvider() {
        // `MockResponse.body(String)` needs an IoProvider to materialize its body source.
        // `installProvider` is idempotent for the same instance, so re-running the suite
        // does not throw.
        Io.installProvider(OkioIoProvider)
    }

    private fun get(path: String): Request =
        Request.builder().url("https://api.example.test/$path").method(Method.GET).build()

    @Test
    fun `enqueue then execute returns the mocked response`() {
        val fake = FakeHttpClient().enqueue { status(202) }
        val response = fake.execute(get("a"))

        assertEquals(Status.ACCEPTED, response.status)
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `execute on empty queue throws IllegalStateException with FakeHttpClient in message`() {
        val fake = FakeHttpClient()
        val ex = assertFailsWith<IllegalStateException> { fake.execute(get("x")) }
        assertTrue(
            ex.message!!.contains("FakeHttpClient"),
            "Expected 'FakeHttpClient' in message, got: ${ex.message}",
        )
        assertTrue(
            ex.message!!.contains("https://api.example.test/x"),
            "Expected request URL in message, got: ${ex.message}",
        )
    }

    @Test
    fun `multiple enqueues consumed in FIFO order`() {
        val fake = FakeHttpClient()
            .enqueue { status(200) }
            .enqueue { status(404) }
            .enqueue { status(500) }

        val r1 = fake.execute(get("a"))
        val r2 = fake.execute(get("b"))
        val r3 = fake.execute(get("c"))

        assertEquals(Status.OK, r1.status)
        assertEquals(Status.NOT_FOUND, r2.status)
        assertEquals(Status.INTERNAL_SERVER_ERROR, r3.status)
    }

    @Test
    fun `requests captures every executed request`() {
        val fake = FakeHttpClient()
            .enqueue { status(200) }
            .enqueue { status(200) }

        val a = get("a")
        val b = get("b")
        fake.execute(a)
        fake.execute(b)

        assertEquals(listOf(a, b), fake.requests)
        assertEquals(2, fake.callCount)
    }

    @Test
    fun `MockResponse Builder body String produces a readable body`() {
        val payload = "hello, world"
        val fake = FakeHttpClient().enqueue {
            status(200).body(payload, MediaType.of("text", "plain"))
        }
        val response = fake.execute(get("hello"))
        val body = response.body
        assertNotNull(body, "Expected non-null body")
        assertEquals(payload.toByteArray(Charsets.UTF_8).size.toLong(), body.contentLength())

        val actual = body.source().use { it.readUtf8() }
        assertEquals(payload, actual)
        // Content-Type header is set when a MediaType is supplied.
        assertEquals("text/plain", response.headers.get("Content-Type"))
    }
}
