package org.dexpace.sdk.core.http.common

import org.dexpace.sdk.core.util.DateTimeRfc1123
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestConditionsTest {

    @Test
    fun `empty conditions write no headers`() {
        val builder = Headers.builder()
        RequestConditions.builder().build().applyTo(builder)
        val headers = builder.build()
        assertFalse(headers.contains(HttpHeaderName.IF_MATCH))
        assertFalse(headers.contains(HttpHeaderName.IF_NONE_MATCH))
        assertFalse(headers.contains(HttpHeaderName.IF_MODIFIED_SINCE))
        assertFalse(headers.contains(HttpHeaderName.IF_UNMODIFIED_SINCE))
    }

    @Test
    fun `single ifMatch emits strong validator`() {
        val builder = Headers.builder()
        RequestConditions.builder()
            .ifMatch(ETag.strong("x"))
            .build()
            .applyTo(builder)
        val headers = builder.build()
        assertEquals("\"x\"", headers.get(HttpHeaderName.IF_MATCH))
    }

    @Test
    fun `multiple ifMatch emits comma-separated list`() {
        val builder = Headers.builder()
        RequestConditions.builder()
            .ifMatch(ETag.strong("x"))
            .ifMatch(ETag.weak("y"))
            .ifMatch(ETag.strong("z"))
            .build()
            .applyTo(builder)
        val headers = builder.build()
        assertEquals("\"x\", W/\"y\", \"z\"", headers.get(HttpHeaderName.IF_MATCH))
    }

    @Test
    fun `single ifNoneMatch emits validator`() {
        val builder = Headers.builder()
        RequestConditions.builder()
            .ifNoneMatch(ETag.strong("x"))
            .build()
            .applyTo(builder)
        val headers = builder.build()
        assertEquals("\"x\"", headers.get(HttpHeaderName.IF_NONE_MATCH))
    }

    @Test
    fun `multiple ifNoneMatch emits comma-separated list`() {
        val builder = Headers.builder()
        RequestConditions.builder()
            .ifNoneMatch(ETag.strong("a"))
            .ifNoneMatch(ETag.strong("b"))
            .build()
            .applyTo(builder)
        val headers = builder.build()
        assertEquals("\"a\", \"b\"", headers.get(HttpHeaderName.IF_NONE_MATCH))
    }

    @Test
    fun `ifMatch and ifNoneMatch coexist`() {
        val builder = Headers.builder()
        RequestConditions.builder()
            .ifMatch(ETag.strong("x"))
            .ifNoneMatch(ETag.strong("y"))
            .build()
            .applyTo(builder)
        val headers = builder.build()
        assertEquals("\"x\"", headers.get(HttpHeaderName.IF_MATCH))
        assertEquals("\"y\"", headers.get(HttpHeaderName.IF_NONE_MATCH))
    }

    @Test
    fun `ifMatch with ANY emits star`() {
        val builder = Headers.builder()
        RequestConditions.builder()
            .ifMatch(ETag.ANY)
            .build()
            .applyTo(builder)
        val headers = builder.build()
        assertEquals("*", headers.get(HttpHeaderName.IF_MATCH))
    }

    @Test
    fun `ifModifiedSince formats as RFC 1123`() {
        val instant = Instant.parse("2024-01-01T00:00:00Z")
        val builder = Headers.builder()
        RequestConditions.builder()
            .ifModifiedSince(instant)
            .build()
            .applyTo(builder)
        val headers = builder.build()
        assertEquals(DateTimeRfc1123.format(instant), headers.get(HttpHeaderName.IF_MODIFIED_SINCE))
        assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", headers.get(HttpHeaderName.IF_MODIFIED_SINCE))
    }

    @Test
    fun `ifUnmodifiedSince formats as RFC 1123`() {
        val instant = Instant.parse("2024-06-15T12:30:45Z")
        val builder = Headers.builder()
        RequestConditions.builder()
            .ifUnmodifiedSince(instant)
            .build()
            .applyTo(builder)
        val headers = builder.build()
        assertEquals(DateTimeRfc1123.format(instant), headers.get(HttpHeaderName.IF_UNMODIFIED_SINCE))
    }

    @Test
    fun `both modified-since and unmodified-since are both emitted`() {
        // Contradictory but legal per RFC 7232 — both pass through.
        val modified = Instant.parse("2024-01-01T00:00:00Z")
        val unmodified = Instant.parse("2024-12-31T23:59:59Z")
        val builder = Headers.builder()
        RequestConditions.builder()
            .ifModifiedSince(modified)
            .ifUnmodifiedSince(unmodified)
            .build()
            .applyTo(builder)
        val headers = builder.build()
        assertEquals(DateTimeRfc1123.format(modified), headers.get(HttpHeaderName.IF_MODIFIED_SINCE))
        assertEquals(DateTimeRfc1123.format(unmodified), headers.get(HttpHeaderName.IF_UNMODIFIED_SINCE))
    }

    @Test
    fun `applyTo is idempotent`() {
        val conditions = RequestConditions.builder()
            .ifMatch(ETag.strong("x"))
            .ifNoneMatch(ETag.strong("y"))
            .ifModifiedSince(Instant.parse("2024-01-01T00:00:00Z"))
            .ifUnmodifiedSince(Instant.parse("2024-12-31T23:59:59Z"))
            .build()

        val builder = Headers.builder()
        conditions.applyTo(builder)
        conditions.applyTo(builder)
        val headers = builder.build()

        // Each header should still have a single value (set, not add).
        assertEquals(1, headers.values(HttpHeaderName.IF_MATCH).size)
        assertEquals(1, headers.values(HttpHeaderName.IF_NONE_MATCH).size)
        assertEquals(1, headers.values(HttpHeaderName.IF_MODIFIED_SINCE).size)
        assertEquals(1, headers.values(HttpHeaderName.IF_UNMODIFIED_SINCE).size)
        assertEquals("\"x\"", headers.get(HttpHeaderName.IF_MATCH))
        assertEquals("\"y\"", headers.get(HttpHeaderName.IF_NONE_MATCH))
    }

    @Test
    fun `applyTo returns the same builder for chaining`() {
        val builder = Headers.builder()
        val returned = RequestConditions.builder()
            .ifMatch(ETag.strong("x"))
            .build()
            .applyTo(builder)
        assertTrue(returned === builder)
    }

    @Test
    fun `builder exposes immutable lists after build`() {
        // Confirm that adding to the builder after build() does not mutate the built
        // RequestConditions — the snapshot semantics from `toList()` are intact.
        val rcBuilder = RequestConditions.builder().ifMatch(ETag.strong("x"))
        val first = rcBuilder.build()
        rcBuilder.ifMatch(ETag.strong("y"))
        val second = rcBuilder.build()
        assertEquals(1, first.ifMatch.size)
        assertEquals(2, second.ifMatch.size)
    }

    @Test
    fun `newBuilder copies state from an existing RequestConditions`() {
        val original = RequestConditions.builder()
            .ifMatch(ETag.strong("x"))
            .ifNoneMatch(ETag.strong("y"))
            .ifModifiedSince(Instant.parse("2024-01-01T00:00:00Z"))
            .ifUnmodifiedSince(Instant.parse("2024-12-31T23:59:59Z"))
            .build()
        val copy = original.newBuilder().build()
        assertEquals(original.ifMatch, copy.ifMatch)
        assertEquals(original.ifNoneMatch, copy.ifNoneMatch)
        assertEquals(original.ifModifiedSince, copy.ifModifiedSince)
        assertEquals(original.ifUnmodifiedSince, copy.ifUnmodifiedSince)
    }

    @Test
    fun `newBuilder allows derived modification without touching the source`() {
        val original = RequestConditions.builder()
            .ifMatch(ETag.strong("x"))
            .build()
        val derived = original.newBuilder()
            .ifMatch(ETag.strong("y"))
            .build()
        assertEquals(1, original.ifMatch.size)
        assertEquals(2, derived.ifMatch.size)
    }

    @Test
    fun `builder ifMatch chain accumulates across multiple calls`() {
        val rc = RequestConditions.builder()
            .ifMatch(ETag.strong("a"))
            .ifMatch(ETag.strong("b"))
            .ifMatch(ETag.weak("c"))
            .build()
        assertEquals(3, rc.ifMatch.size)
        assertEquals(ETag.strong("a"), rc.ifMatch[0])
        assertEquals(ETag.strong("b"), rc.ifMatch[1])
        assertEquals(ETag.weak("c"), rc.ifMatch[2])
    }

    @Test
    fun `builder ifNoneMatch chain accumulates across multiple calls`() {
        val rc = RequestConditions.builder()
            .ifNoneMatch(ETag.strong("a"))
            .ifNoneMatch(ETag.strong("b"))
            .build()
        assertEquals(2, rc.ifNoneMatch.size)
    }

    @Test
    fun `builder ifModifiedSince call overwrites previous value`() {
        val first = Instant.parse("2024-01-01T00:00:00Z")
        val second = Instant.parse("2024-06-01T00:00:00Z")
        val rc = RequestConditions.builder()
            .ifModifiedSince(first)
            .ifModifiedSince(second)
            .build()
        assertEquals(second, rc.ifModifiedSince)
    }

    @Test
    fun `builder ifUnmodifiedSince call overwrites previous value`() {
        val first = Instant.parse("2024-01-01T00:00:00Z")
        val second = Instant.parse("2024-06-01T00:00:00Z")
        val rc = RequestConditions.builder()
            .ifUnmodifiedSince(first)
            .ifUnmodifiedSince(second)
            .build()
        assertEquals(second, rc.ifUnmodifiedSince)
    }

    @Test
    fun `companion builder returns an empty Builder`() {
        val rc = RequestConditions.builder().build()
        assertTrue(rc.ifMatch.isEmpty())
        assertTrue(rc.ifNoneMatch.isEmpty())
        assertEquals(null, rc.ifModifiedSince)
        assertEquals(null, rc.ifUnmodifiedSince)
    }
}
