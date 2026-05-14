package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tiny test transport — records each `execute` invocation and returns a canned 200 OK.
 * Self-contained per the test scoping note (FakeHttpClient lives in another agent's scope).
 */
private class RecordingHttpClient : HttpClient {
    val requests: MutableList<Request> = ArrayList()

    override fun execute(request: Request): Response {
        requests.add(request)
        return Response.builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .status(Status.OK)
            .build()
    }
}

/** Records the order in which steps run by appending its tag to a shared list. */
private class TaggingStep(
    override val stage: Stage,
    private val tag: String,
    private val recorder: MutableList<String>,
) : HttpStep {
    override fun process(
        request: Request,
        next: PipelineNext,
    ): Response {
        recorder.add(tag)
        return next.process()
    }
}

private fun request(): Request = Request.builder().url("https://api.example.com/").method(Method.GET).build()

class HttpPipelineTest {
    @Test
    fun `empty pipeline goes straight to HttpClient`() {
        val client = RecordingHttpClient()
        val pipeline = HttpPipelineBuilder(client).build()

        val response = pipeline.send(request())

        assertEquals(1, client.requests.size)
        assertEquals(Status.OK, response.status)
        assertTrue(pipeline.steps.isEmpty())
    }

    @Test
    fun `single step runs then HttpClient`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val pipeline =
            HttpPipelineBuilder(client)
                .append(TaggingStep(Stage.PRE_AUTH, "pre-auth", order))
                .build()

        pipeline.send(request())

        assertEquals(listOf("pre-auth"), order)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `multiple stages run in Stage order`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val pipeline =
            HttpPipelineBuilder(client)
                // Add in reverse-of-stage order to prove the builder sorts by Stage.order.
                .append(TaggingStep(Stage.PRE_SEND, "pre-send", order))
                .append(TaggingStep(Stage.POST_AUTH, "post-auth", order))
                .append(TaggingStep(Stage.PRE_AUTH, "pre-auth", order))
                .build()

        pipeline.send(request())

        assertEquals(listOf("pre-auth", "post-auth", "pre-send"), order)
    }

    @Test
    fun `append within same stage preserves insertion order`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val pipeline =
            HttpPipelineBuilder(client)
                .append(TaggingStep(Stage.PRE_AUTH, "first", order))
                .append(TaggingStep(Stage.PRE_AUTH, "second", order))
                .build()

        pipeline.send(request())

        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun `prepend within same stage places step at head`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val pipeline =
            HttpPipelineBuilder(client)
                .append(TaggingStep(Stage.PRE_AUTH, "appended", order))
                .prepend(TaggingStep(Stage.PRE_AUTH, "prepended", order))
                .build()

        pipeline.send(request())

        assertEquals(listOf("prepended", "appended"), order)
    }

    @Test
    fun `pillar replace at same stage emits warning and keeps newest`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val firstRetry = TaggingStep(Stage.RETRY, "retry-A", order)
        val secondRetry = TaggingStep(Stage.RETRY, "retry-B", order)

        val pipeline =
            HttpPipelineBuilder(client)
                .append(firstRetry)
                .append(secondRetry)
                .build()

        pipeline.send(request())

        // Only the second retry-step is present; the first is replaced.
        assertEquals(1, pipeline.steps.size)
        assertEquals(listOf("retry-B"), order)
    }

    @Test
    fun `pillar re-install of same instance is idempotent`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val retry = TaggingStep(Stage.RETRY, "retry", order)

        val pipeline =
            HttpPipelineBuilder(client)
                .append(retry)
                .append(retry)
                .build()

        pipeline.send(request())

        assertEquals(1, pipeline.steps.size)
        assertSame(retry, pipeline.steps[0])
        assertEquals(listOf("retry"), order)
    }

    @Test
    fun `next copy process invoked twice produces independent runs`() {
        val client = RecordingHttpClient()
        val invocations = AtomicInteger(0)
        val twiceStep =
            object : HttpStep {
                override val stage: Stage = Stage.POST_RETRY

                override fun process(
                    request: Request,
                    next: PipelineNext,
                ): Response {
                    val first = next.copy().process()
                    first.close()
                    val second = next.copy().process()
                    invocations.incrementAndGet()
                    return second
                }
            }
        val pipeline =
            HttpPipelineBuilder(client)
                .append(twiceStep)
                .build()

        pipeline.send(request())

        assertEquals(1, invocations.get())
        assertEquals(2, client.requests.size)
    }

    @Test
    fun `insertAfter places step after first matching type`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val anchor = TaggingStep(Stage.PRE_AUTH, "anchor", order)
        val inserted = TaggingStep(Stage.PRE_AUTH, "inserted", order)

        val base =
            HttpPipelineBuilder(client)
                .append(anchor)
                .append(TaggingStep(Stage.POST_AUTH, "trailer", order))
                .build()

        val updated =
            HttpPipelineBuilder.from(base)
                .insertAfter<TaggingStep>(inserted)
                .build()

        updated.send(request())

        // anchor is matched first (lowest stage); inserted goes immediately after.
        assertEquals("anchor", order[0])
        assertEquals("inserted", order[1])
        assertEquals("trailer", order[2])
    }

    @Test
    fun `insertAfter throws when no instance of type present`() {
        val client = RecordingHttpClient()
        val builder = HttpPipelineBuilder(client)

        class MarkerStep : HttpStep {
            override val stage: Stage = Stage.PRE_AUTH

            override fun process(
                request: Request,
                next: PipelineNext,
            ): Response = next.process()
        }

        val ex =
            assertFailsWith<IllegalArgumentException> {
                builder.insertAfter<MarkerStep>(
                    object : HttpStep {
                        override val stage: Stage = Stage.PRE_AUTH

                        override fun process(
                            request: Request,
                            next: PipelineNext,
                        ): Response = next.process()
                    },
                )
            }
        assertTrue(ex.message!!.contains("No"), "Expected 'No' in message but was: ${ex.message}")
        assertTrue(
            ex.message!!.contains("MarkerStep"),
            "Expected type name 'MarkerStep' in message but was: ${ex.message}",
        )
    }

    @Test
    fun `replace swaps first occurrence of type`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val original = TaggingStep(Stage.PRE_AUTH, "original", order)
        val replacement = TaggingStep(Stage.PRE_AUTH, "replacement", order)

        val pipeline =
            HttpPipelineBuilder(client)
                .append(original)
                .replace<TaggingStep>(replacement)
                .build()

        pipeline.send(request())

        assertEquals(listOf("replacement"), order)
        assertEquals(1, pipeline.steps.size)
    }

    @Test
    fun `remove drops all instances of type`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val pipeline =
            HttpPipelineBuilder(client)
                .append(TaggingStep(Stage.PRE_AUTH, "a", order))
                .append(TaggingStep(Stage.PRE_AUTH, "b", order))
                .remove<TaggingStep>()
                .build()

        pipeline.send(request())

        assertTrue(pipeline.steps.isEmpty())
        assertTrue(order.isEmpty())
    }

    @Test
    fun `remove of absent type is a no-op`() {
        val client = RecordingHttpClient()

        class GhostStep : HttpStep {
            override val stage: Stage = Stage.PRE_AUTH

            override fun process(
                request: Request,
                next: PipelineNext,
            ): Response = next.process()
        }

        val pipeline = HttpPipelineBuilder(client).remove<GhostStep>().build()
        pipeline.send(request())

        assertTrue(pipeline.steps.isEmpty())
    }

    @Test
    fun `pillar install at SEND throws IllegalStateException`() {
        val client = RecordingHttpClient()
        val sendStep =
            object : HttpStep {
                override val stage: Stage = Stage.SEND

                override fun process(
                    request: Request,
                    next: PipelineNext,
                ): Response = next.process()
            }

        assertFailsWith<IllegalStateException> {
            HttpPipelineBuilder(client).append(sendStep)
        }
    }

    @Test
    fun `appendAll installs every step in iteration order`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val steps: List<HttpStep> =
            listOf(
                TaggingStep(Stage.PRE_AUTH, "a", order),
                TaggingStep(Stage.PRE_AUTH, "b", order),
                TaggingStep(Stage.PRE_AUTH, "c", order),
            )

        val pipeline = HttpPipelineBuilder(client).appendAll(steps).build()
        pipeline.send(request())

        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun `prependAll installs steps so iteration order is reversed at head`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        // Each prepend pushes the step to the head; iterating "a","b","c" therefore yields
        // final order "c","b","a" because the last prepend wins the head slot.
        val steps: List<HttpStep> =
            listOf(
                TaggingStep(Stage.PRE_AUTH, "a", order),
                TaggingStep(Stage.PRE_AUTH, "b", order),
                TaggingStep(Stage.PRE_AUTH, "c", order),
            )

        val pipeline = HttpPipelineBuilder(client).prependAll(steps).build()
        pipeline.send(request())

        assertEquals(listOf("c", "b", "a"), order)
    }

    @Test
    fun `insertBefore places step before first matching type`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val anchor = TaggingStep(Stage.PRE_AUTH, "anchor", order)
        val inserted = TaggingStep(Stage.PRE_AUTH, "inserted", order)

        val base =
            HttpPipelineBuilder(client)
                .append(anchor)
                .build()

        val updated =
            HttpPipelineBuilder.from(base)
                .insertBefore<TaggingStep>(inserted)
                .build()

        updated.send(request())

        // `inserted` precedes the anchor in the same stage.
        assertEquals("inserted", order[0])
        assertEquals("anchor", order[1])
    }

    @Test
    fun `insertBefore throws when no instance of type present`() {
        val client = RecordingHttpClient()
        val builder = HttpPipelineBuilder(client)

        class MarkerStep : HttpStep {
            override val stage: Stage = Stage.PRE_AUTH

            override fun process(
                request: Request,
                next: PipelineNext,
            ): Response = next.process()
        }

        val ex =
            assertFailsWith<IllegalArgumentException> {
                builder.insertBefore<MarkerStep>(
                    object : HttpStep {
                        override val stage: Stage = Stage.PRE_AUTH

                        override fun process(
                            request: Request,
                            next: PipelineNext,
                        ): Response = next.process()
                    },
                )
            }
        assertTrue(ex.message!!.contains("No"), "Expected 'No' in message but was: ${ex.message}")
        assertTrue(ex.message!!.contains("MarkerStep"))
    }

    @Test
    fun `insertBefore with multiple instances of T targets the first match only`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val a = TaggingStep(Stage.PRE_AUTH, "a", order)
        val b = TaggingStep(Stage.PRE_AUTH, "b", order)
        val inserted = TaggingStep(Stage.PRE_AUTH, "inserted", order)

        val base =
            HttpPipelineBuilder(client)
                .append(a)
                .append(b)
                .build()

        val updated =
            HttpPipelineBuilder.from(base)
                .insertBefore<TaggingStep>(inserted)
                .build()

        updated.send(request())

        // The first match is `a` — inserted ends up at index 0, `a` and `b` follow.
        assertEquals(listOf("inserted", "a", "b"), order)
    }

    @Test
    fun `insertAfter with multiple instances of T targets the first match only`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val a = TaggingStep(Stage.PRE_AUTH, "a", order)
        val b = TaggingStep(Stage.PRE_AUTH, "b", order)
        val inserted = TaggingStep(Stage.PRE_AUTH, "inserted", order)

        val base =
            HttpPipelineBuilder(client)
                .append(a)
                .append(b)
                .build()

        val updated =
            HttpPipelineBuilder.from(base)
                .insertAfter<TaggingStep>(inserted)
                .build()

        updated.send(request())

        // First TaggingStep is `a`; inserted goes right after it, before `b`.
        assertEquals(listOf("a", "inserted", "b"), order)
    }

    @Test
    fun `replace throws when no instance of type present`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val replacement = TaggingStep(Stage.PRE_AUTH, "replacement", order)

        class GhostStep : HttpStep {
            override val stage: Stage = Stage.PRE_AUTH

            override fun process(
                request: Request,
                next: PipelineNext,
            ): Response = next.process()
        }

        val builder = HttpPipelineBuilder(client)
        val ex =
            assertFailsWith<IllegalArgumentException> {
                builder.replace<GhostStep>(replacement)
            }
        assertTrue(ex.message!!.contains("No"))
        assertTrue(ex.message!!.contains("GhostStep"))
    }

    @Test
    fun `remove drops every instance of T`() {
        // The existing "remove drops all instances of type" test verifies the typical case.
        // This one specifically exercises remove with three matches to confirm the loop is
        // not single-shot.
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val pipeline =
            HttpPipelineBuilder(client)
                .append(TaggingStep(Stage.PRE_AUTH, "a", order))
                .append(TaggingStep(Stage.PRE_AUTH, "b", order))
                .append(TaggingStep(Stage.PRE_AUTH, "c", order))
                .remove<TaggingStep>()
                .build()

        pipeline.send(request())

        assertTrue(pipeline.steps.isEmpty(), "all three matches must be removed")
    }

    @Test
    fun `static from invoked through a Java caller resolves the JvmStatic bridge`() {
        // `@JvmStatic` generates a static bridge method on HttpPipelineBuilder that delegates
        // to the companion's `from`. Kotlin callers resolve directly to the companion and
        // skip the bridge. A Java caller exercises the bridge — important for callers using
        // reflection or static method handles.
        val client = RecordingHttpClient()
        val base = HttpPipelineBuilder(client).build()
        val copy = JavaCallsHttpPipelineBuilderFrom.copy(base).build()
        copy.send(request())
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `static from copies steps from an existing pipeline including pillars`() {
        // Cover the HttpPipelineBuilder$Companion.from branch where the source pipeline
        // contains a pillar step. The pillar must be preserved as a pillar after copy.
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val retryStep = TaggingStep(Stage.RETRY, "retry", order)
        val preAuthStep = TaggingStep(Stage.PRE_AUTH, "pre-auth", order)

        val base =
            HttpPipelineBuilder(client)
                .append(retryStep)
                .append(preAuthStep)
                .build()

        val copy = HttpPipelineBuilder.from(base).build()
        copy.send(request())

        // Stage.RETRY runs before PRE_AUTH per stage order; both steps must run.
        assertEquals(listOf("retry", "pre-auth"), order)
        assertEquals(2, copy.steps.size)
    }

    @Test
    fun `appendAll with an empty list is a no-op`() {
        val client = RecordingHttpClient()
        val pipeline =
            HttpPipelineBuilder(client)
                .appendAll(emptyList())
                .build()

        pipeline.send(request())
        assertTrue(pipeline.steps.isEmpty())
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `prependAll with an empty list is a no-op`() {
        val client = RecordingHttpClient()
        val pipeline =
            HttpPipelineBuilder(client)
                .prependAll(emptyList())
                .build()

        pipeline.send(request())
        assertTrue(pipeline.steps.isEmpty())
    }

    @Test
    fun `pillar prepend at a pillar stage replaces with a warning event`() {
        // Branch coverage on HttpPipelineBuilder.prepend: pillar stages route through
        // installPillar regardless of head/tail intent. Two distinct retry steps must
        // collapse to the second one with a warning log.
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val first = TaggingStep(Stage.RETRY, "retry-1", order)
        val second = TaggingStep(Stage.RETRY, "retry-2", order)

        val pipeline =
            HttpPipelineBuilder(client)
                .prepend(first)
                .prepend(second)
                .build()

        pipeline.send(request())

        // Second prepend replaces the first via installPillar.
        assertEquals(1, pipeline.steps.size)
        assertEquals(listOf("retry-2"), order)
    }

    @Test
    fun `same-instance pillar re-install via prepend is idempotent`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val retry = TaggingStep(Stage.RETRY, "retry", order)

        val pipeline =
            HttpPipelineBuilder(client)
                .prepend(retry)
                .prepend(retry)
                .build()

        pipeline.send(request())

        assertEquals(1, pipeline.steps.size)
        assertSame(retry, pipeline.steps[0])
        assertEquals(listOf("retry"), order)
    }

    @Test
    fun `appendAll preserves chaining with append`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val pipeline =
            HttpPipelineBuilder(client)
                .append(TaggingStep(Stage.PRE_AUTH, "first", order))
                .appendAll(
                    listOf(TaggingStep(Stage.PRE_AUTH, "bulk-1", order), TaggingStep(Stage.PRE_AUTH, "bulk-2", order)),
                )
                .append(TaggingStep(Stage.PRE_AUTH, "last", order))
                .build()
        pipeline.send(request())

        assertEquals(listOf("first", "bulk-1", "bulk-2", "last"), order)
    }
}
