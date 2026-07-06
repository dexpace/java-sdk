/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.pipeline

import org.dexpace.sdk.core.client.HttpClient
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.pipeline.steps.DefaultRetryStep
import org.dexpace.sdk.core.http.pipeline.steps.RetryStep
import org.dexpace.sdk.core.http.request.Method
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.request.RequestOptions
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.pagination.PageInfo
import org.dexpace.sdk.core.pagination.PaginationStrategy
import org.dexpace.sdk.core.pagination.Paginator
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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

/**
 * Transport that records the [RequestOptions] threaded into the per-call `execute` overload — the
 * seam a pipeline uses to hand a caller's options to the transport.
 */
private class OptionsRecordingHttpClient : HttpClient {
    val seenOptions: MutableList<RequestOptions> = ArrayList()

    override fun execute(request: Request): Response = execute(request, RequestOptions.EMPTY)

    override fun execute(
        request: Request,
        options: RequestOptions,
    ): Response {
        seenOptions.add(options)
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
    fun `empty pipeline threads RequestOptions to the transport per-call overload`() {
        val client = OptionsRecordingHttpClient()
        val pipeline = HttpPipelineBuilder(client).build()
        val options = RequestOptions.builder().timeout(Duration.ofSeconds(2)).maxRetries(1).build()

        pipeline.send(request(), options)

        assertEquals(1, client.seenOptions.size)
        assertSame(options, client.seenOptions[0], "empty pipeline must pass options straight through")
    }

    @Test
    fun `send without options threads EMPTY to the transport`() {
        val client = OptionsRecordingHttpClient()
        val pipeline = HttpPipelineBuilder(client).build()

        pipeline.send(request())

        assertEquals(RequestOptions.EMPTY, client.seenOptions.single())
    }

    @Test
    fun `steps see the caller's options and the terminal dispatch threads them through`() {
        val client = OptionsRecordingHttpClient()
        val seenByStep = AtomicReference<RequestOptions?>(null)
        val readingStep =
            object : HttpStep {
                override val stage: Stage = Stage.PRE_SEND

                override fun process(
                    request: Request,
                    next: PipelineNext,
                ): Response {
                    seenByStep.set(next.options)
                    return next.process()
                }
            }
        val pipeline = HttpPipelineBuilder(client).append(readingStep).build()
        val options = RequestOptions.builder().maxRetries(0).tag("k", "v").build()

        pipeline.send(request(), options)

        assertSame(options, seenByStep.get(), "the step must read the caller's options via next.options")
        assertSame(options, client.seenOptions.single(), "options must reach the terminal dispatch")
    }

    @Test
    fun `execute request through the HttpClient SPI threads EMPTY options`() {
        val client = OptionsRecordingHttpClient()
        val transport: HttpClient = HttpPipelineBuilder(client).build()

        transport.execute(request())

        assertNull(client.seenOptions.single().maxRetries)
        assertEquals(RequestOptions.EMPTY, client.seenOptions.single())
    }

    @Test
    fun `execute conforms to the HttpClient SPI and routes through the pipeline steps`() {
        val client = RecordingHttpClient()
        val headerStep =
            object : HttpStep {
                override val stage: Stage = Stage.PRE_SEND

                override fun process(
                    request: Request,
                    next: PipelineNext,
                ): Response = next.process(request.newBuilder().setHeader("X-Pipeline", "on").build())
            }
        // Typed as the transport SPI to prove HttpPipeline conforms to HttpClient.
        val transport: HttpClient = HttpPipelineBuilder(client).append(headerStep).build()

        val response = transport.execute(request())

        assertEquals(Status.OK, response.status)
        assertEquals(1, client.requests.size)
        assertEquals("on", client.requests[0].headers.get("X-Pipeline"))
    }

    @Test
    fun `Paginator over the pipeline as HttpClient walks two pages through the steps`() {
        val client = RecordingHttpClient()
        val stepRuns = AtomicInteger(0)
        val countingStep =
            object : HttpStep {
                override val stage: Stage = Stage.PRE_SEND

                override fun process(
                    request: Request,
                    next: PipelineNext,
                ): Response {
                    stepRuns.incrementAndGet()
                    return next.process()
                }
            }
        val transport: HttpClient = HttpPipelineBuilder(client).append(countingStep).build()

        val pageCount = AtomicInteger(0)
        val strategy =
            PaginationStrategy<String> { _, initial ->
                val n = pageCount.incrementAndGet()
                PageInfo(listOf("item-$n"), if (n < 2) initial else null)
            }

        val items = Paginator(transport, request(), strategy).iterateAll().toList()

        assertEquals(listOf("item-1", "item-2"), items)
        assertEquals(2, client.requests.size, "two page fetches routed through the pipeline")
        assertEquals(2, stepRuns.get(), "the pipeline step ran on each page")
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
                // Add in reverse declaration order to prove the builder emits in Stage declaration order.
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
    fun `appending a distinct second pillar step throws IllegalStateException`() {
        val client = RecordingHttpClient()
        val builder =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep())

        val ex = assertFailsWith<IllegalStateException> { builder.append(DefaultRetryStep()) }
        assertTrue(ex.message!!.contains("RETRY"), "message names the pillar stage: ${ex.message}")
        assertTrue(ex.message!!.contains("replace"), "message points at replace<T>(): ${ex.message}")
    }

    @Test
    fun `replace swaps a pillar step in place`() {
        val client = RecordingHttpClient()
        val replacement = DefaultRetryStep()

        val pipeline =
            HttpPipelineBuilder(client)
                .append(DefaultRetryStep())
                .replace<RetryStep>(replacement)
                .build()

        assertEquals(1, pipeline.steps.size)
        assertSame(replacement, pipeline.steps[0])
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
    fun `insertAfter rejects a cross-stage step instead of silently relocating it`() {
        // The anchor is in PRE_AUTH; the inserted step declares POST_AUTH. Because the storage
        // re-buckets by Stage on every edit, an unchecked insert would silently land the step
        // wherever POST_AUTH falls — not "after the anchor". The builder must reject it.
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val anchor = TaggingStep(Stage.PRE_AUTH, "anchor", order)
        val crossStage = TaggingStep(Stage.POST_AUTH, "cross", order)

        val base = HttpPipelineBuilder(client).append(anchor).build()

        val ex =
            assertFailsWith<IllegalArgumentException> {
                HttpPipelineBuilder.from(base).insertAfter<TaggingStep>(crossStage)
            }
        assertTrue(ex.message!!.contains("PRE_AUTH"), "message names the anchor stage: ${ex.message}")
        assertTrue(ex.message!!.contains("POST_AUTH"), "message names the step stage: ${ex.message}")
    }

    @Test
    fun `insertBefore rejects a cross-stage step`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val anchor = TaggingStep(Stage.PRE_AUTH, "anchor", order)
        val crossStage = TaggingStep(Stage.POST_AUTH, "cross", order)

        val base = HttpPipelineBuilder(client).append(anchor).build()

        assertFailsWith<IllegalArgumentException> {
            HttpPipelineBuilder.from(base).insertBefore<TaggingStep>(crossStage)
        }
    }

    @Test
    fun `replace rejects a cross-stage replacement`() {
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val original = TaggingStep(Stage.PRE_AUTH, "original", order)
        val crossStage = TaggingStep(Stage.POST_AUTH, "cross", order)

        val base = HttpPipelineBuilder(client).append(original).build()

        assertFailsWith<IllegalArgumentException> {
            HttpPipelineBuilder.from(base).replace<TaggingStep>(crossStage)
        }
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
    fun `surgical insert that would duplicate a pillar throws and leaves the builder intact`() {
        // insertAfter re-buckets by reloading the flattened step list. A second distinct RETRY-stage
        // step would create a duplicate RETRY pillar; the reload is atomic, so the collision throws
        // and the builder's prior steps survive — build() still yields exactly the original pillar.
        val client = RecordingHttpClient()
        val originalRetry = DefaultRetryStep()
        val builder = HttpPipelineBuilder(client).append(originalRetry)

        assertFailsWith<IllegalStateException> {
            builder.insertAfter<DefaultRetryStep>(DefaultRetryStep())
        }

        val pipeline = builder.build()
        assertEquals(1, pipeline.steps.size, "the rejected insert must not leave a duplicate pillar behind")
        assertSame(originalRetry, pipeline.steps[0], "the original retry pillar must remain intact")
    }

    @Test
    fun `HttpPipelineBuilder of an HttpPipeline flattens rather than nesting`() {
        // A statically-typed HttpPipeline argument resolves to the flattening constructor: the
        // source pipeline's steps are copied into the new builder (same count/order as from()), NOT
        // nested as an opaque transport — which would leave the outer builder with zero steps.
        val client = RecordingHttpClient()
        val order = mutableListOf<String>()
        val retryStep = TaggingStep(Stage.RETRY, "retry", order)
        val preAuthStep = TaggingStep(Stage.PRE_AUTH, "pre-auth", order)
        val base = HttpPipelineBuilder(client).append(retryStep).append(preAuthStep).build()

        val viaConstructor = HttpPipelineBuilder(base).build()
        val viaFrom = HttpPipelineBuilder.from(base).build()

        // Same step count/order as from() — proving flatten, not nest.
        assertEquals(viaFrom.steps.size, viaConstructor.steps.size)
        assertEquals(2, viaConstructor.steps.size)
        assertSame(retryStep, viaConstructor.steps[0])
        assertSame(preAuthStep, viaConstructor.steps[1])
        // The transport is the base's transport, not the base pipeline nested as a transport.
        assertSame(client, viaConstructor.httpClient)

        // Both steps actually run when sent (a nested pipeline would run zero outer steps).
        viaConstructor.send(request())
        assertEquals(listOf("retry", "pre-auth"), order)
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
    fun `prepending a distinct second pillar step throws IllegalStateException`() {
        // Branch coverage on HttpPipelineBuilder.prepend: pillar stages route through
        // installPillar regardless of head/tail intent, so a distinct second retry step
        // must fail fast rather than silently collapse.
        val client = RecordingHttpClient()
        val builder =
            HttpPipelineBuilder(client)
                .prepend(DefaultRetryStep())

        val ex = assertFailsWith<IllegalStateException> { builder.prepend(DefaultRetryStep()) }
        assertTrue(ex.message!!.contains("RETRY"), "message names the pillar stage: ${ex.message}")
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
