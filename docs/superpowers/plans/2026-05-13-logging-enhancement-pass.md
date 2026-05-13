# Logging Enhancement Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve the observability the SDK ships with along four axes — consistency (single facade), correlation (trace/span IDs propagate via MDC), coverage (the four async adapters get log events), and richness (existing events gain useful fields) — plus a `DefaultAsyncInstrumentationStep` that mirrors the sync counterpart for `AsyncHttpPipeline`.

**Architecture:** All logging goes through `ClientLogger` / `LoggingEvent`. Correlation is wired by extending `Span.makeCurrent()` with an MDC-aware wrapper, and `LoggingEvent.log()` folds `MDC.getCopyOfContextMap()` into the structured SLF4J event before per-event fields. Adapter modules get top-level `private val LOG = ClientLogger(...)`. The async instrumentation step is a new file mirroring the sync step.

**Tech Stack:** Kotlin 2.3.21, SLF4J 2.x (already on classpath via `slf4j-api`), `kotlin.test`, JUnit Platform.

**Spec:** `docs/superpowers/specs/2026-05-13-logging-enhancement-pass-design.md`

**Note about scope of Commit 4:** The spec listed `error.phase` as a new field on `http.instrumentation.error`. The current code distinguishes failure sites via three different event names (`http.instrumentation.emit_request_failed`, `emit_response_failed`, `emit_failure_failed`), so the `error.phase` field overlaps with the event-name signal. The plan keeps `error.phase` anyway — operators querying by structured field find it easier than parsing event-name suffixes, and the implementation cost is three trivial `.field()` calls with no cross-step plumbing.

---

## Commit 1 — Consistency

Migrate the two raw-SLF4J usages in the pipeline builders to `ClientLogger`. Zero behavior change for downstream operators; gains the NOOP fast path, `globalContext` attachment, and structured fields.

### Task 1.1: Migrate `HttpPipelineBuilder` to ClientLogger

**Files:**
- Modify: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/HttpPipelineBuilder.kt`
- Reference: `sdk-core/src/test/kotlin/org/dexpace/sdk/core/http/pipeline/HttpPipelineBuilderTest.kt` (to confirm existing tests still pass)

- [ ] **Step 1: Replace the LoggerFactory import and LOG initializer**

  Open the file. At line 4, change:
  ```kotlin
  import org.slf4j.LoggerFactory
  ```
  to:
  ```kotlin
  import org.dexpace.sdk.core.instrumentation.ClientLogger
  ```

  At line ~118, change:
  ```kotlin
  internal val LOG = LoggerFactory.getLogger(HttpPipelineBuilder::class.java)
  ```
  to:
  ```kotlin
  internal val LOG = ClientLogger(HttpPipelineBuilder::class)
  ```

- [ ] **Step 2: Rewrite the pillar-replaced warning to use the structured builder**

  At line ~26, the existing block is:
  ```kotlin
  LOG.warn(
      "pipeline.pillar.replaced stage={} previous={} replacement={}",
      stage, prev::class.simpleName, next::class.simpleName,
  )
  ```
  Replace with:
  ```kotlin
  LOG.atWarning()
      .event("pipeline.pillar.replaced")
      .field("stage", stage.name)
      .field("previous", prev::class.simpleName ?: "<anonymous>")
      .field("replacement", next::class.simpleName ?: "<anonymous>")
      .log()
  ```

- [ ] **Step 3: Run the pipeline-builder tests**

  ```bash
  ./gradlew :sdk-core:test --tests "*HttpPipelineBuilder*" --tests "*StagedSteps*" --console=plain
  ```

  Expected: PASS. The `StagedSteps reload fires onPillarReplaced when a different pillar is appended` test asserts that the callback is invoked, not the wire format, so the migration is invisible to the assertion.

### Task 1.2: Migrate `AsyncHttpPipelineBuilder` to ClientLogger

**Files:**
- Modify: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/AsyncHttpPipelineBuilder.kt`

- [ ] **Step 1: Replace the LoggerFactory import and LOG initializer**

  At line 4, change `import org.slf4j.LoggerFactory` to `import org.dexpace.sdk.core.instrumentation.ClientLogger`.

  At line ~94, change:
  ```kotlin
  internal val LOG = LoggerFactory.getLogger(AsyncHttpPipelineBuilder::class.java)
  ```
  to:
  ```kotlin
  internal val LOG = ClientLogger(AsyncHttpPipelineBuilder::class)
  ```

- [ ] **Step 2: Rewrite the pillar-replaced warning**

  At line ~17, replace:
  ```kotlin
  LOG.warn(
      "async.pipeline.pillar.replaced stage={} previous={} replacement={}",
      stage, prev::class.simpleName, next::class.simpleName,
  )
  ```
  with:
  ```kotlin
  LOG.atWarning()
      .event("async.pipeline.pillar.replaced")
      .field("stage", stage.name)
      .field("previous", prev::class.simpleName ?: "<anonymous>")
      .field("replacement", next::class.simpleName ?: "<anonymous>")
      .log()
  ```

- [ ] **Step 3: Run the async-pipeline tests**

  ```bash
  ./gradlew :sdk-core:test --tests "*AsyncHttpPipelineBuilder*" --tests "*AsyncPipeline*" --console=plain
  ```
  Expected: PASS.

### Task 1.3: Commit Commit 1

- [ ] **Step 1: Stage and commit**

  ```bash
  git add \
    sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/HttpPipelineBuilder.kt \
    sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/AsyncHttpPipelineBuilder.kt
  git commit -m "$(cat <<'EOF'
  refactor: route pipeline-builder warnings through ClientLogger

  Both HttpPipelineBuilder and AsyncHttpPipelineBuilder used LoggerFactory
  directly for the pillar-replaced warning, bypassing the structured-event
  facade and its NOOP fast path. Migrated to ClientLogger.atWarning().event()
  so the warning carries the same globalContext as the rest of the SDK's
  log events and renders structured fields rather than a formatted string.
  Behavior is otherwise unchanged; the StagedSteps onPillarReplaced test
  asserts the callback fires, not the wire format.
  EOF
  )"
  ```

  Expected: commit created. No Co-Authored-By footer.

---

## Commit 2 — Correlation via MDC

Wire `trace.id` / `span.id` onto every log event emitted while a span is current. The MDC fold is at two layers: `Span.makeCurrent()` is responsible for pushing/popping MDC entries (the contract of the new `MdcAwareTracingScope` wrapper), and `LoggingEvent.log()` folds `MDC.getCopyOfContextMap()` into the SLF4J builder so every emitted event picks up the keys. `DefaultInstrumentationStep` is updated to actually wrap its downstream pipeline call in a `span.makeCurrent()` scope so the correlation has a window to apply.

### Task 2.1: Fold MDC into `LoggingEvent.log()`

**Files:**
- Modify: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/LoggingEvent.kt`
- Test: `sdk-core/src/test/kotlin/org/dexpace/sdk/core/instrumentation/LoggingEventTest.kt`

- [ ] **Step 1: Add MDC import**

  At the top of `LoggingEvent.kt`, add:
  ```kotlin
  import org.slf4j.MDC
  ```

- [ ] **Step 2: Fold MDC entries before per-event fields**

  In `LoggingEvent.log(message: String = "")`, after the global-context loop and BEFORE the `eventName?.let { builder.addKeyValue(EVENT_KEY, it) }` line, insert:

  ```kotlin
  // Fold SLF4J MDC into the structured event so trace.id / span.id set by an
  // enclosing TracingScope (or any other MDC key set by the application) reaches
  // log backends as structured fields, not just as %X{...} pattern lookups.
  // Per-event fields (added below) override MDC entries with the same key.
  val mdcMap = MDC.getCopyOfContextMap()
  if (mdcMap != null) {
      for ((k, v) in mdcMap) {
          if (v != null) builder.addKeyValue(k, v)
      }
  }
  ```

- [ ] **Step 3: Write the failing tests for MDC fold**

  **Open `LoggingEventTest.kt` and read its top 60 lines to identify the event-capture pattern.** The file already has working tests for the disabled-NOOP, single-shot, and globalContext paths — copy the same scaffolding for the new tests (typically a test SLF4J `ListAppender` wired to the `Logger` returned by `LoggerFactory.getLogger(...)` for a specific test logger name). Append two tests near the existing globalContext tests:

  ```kotlin
  @Test
  fun `log folds MDC entries into the structured event`() {
      MDC.put("trace.id", "abc123")
      MDC.put("span.id", "def456")
      try {
          val logger = newTestLogger()      // use whatever helper the file uses
          logger.atInfo().event("test.event").log("hello")
          val event = lastCapturedEvent()   // same — match the existing helper
          val kv = event.keyValuePairs.associate { it.key to it.value }
          assertEquals("abc123", kv["trace.id"])
          assertEquals("def456", kv["span.id"])
      } finally {
          MDC.clear()
      }
  }

  @Test
  fun `per-event fields override MDC entries with the same key`() {
      MDC.put("trace.id", "mdc-trace")
      try {
          val logger = newTestLogger()
          logger.atInfo()
              .event("test.event")
              .field("trace.id", "event-trace")
              .log("hello")
          val event = lastCapturedEvent()
          val kv = event.keyValuePairs.associate { it.key to it.value }
          assertEquals("event-trace", kv["trace.id"])
      } finally {
          MDC.clear()
      }
  }
  ```

  Add `import org.slf4j.MDC` at the top of the file if not present. Replace `newTestLogger()` and `lastCapturedEvent()` with the exact helper names the existing tests use — they exist; this file is already capturing events for assertions.

- [ ] **Step 4: Run tests**

  ```bash
  ./gradlew :sdk-core:test --tests "*LoggingEventTest*" --console=plain
  ```
  Expected: PASS.

### Task 2.2: Add an MDC-aware `TracingScope` wrapper for spans

**Files:**
- Create: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/SpanLoggingExtensions.kt`
- Test: `sdk-core/src/test/kotlin/org/dexpace/sdk/core/instrumentation/SpanLoggingExtensionsTest.kt`

- [ ] **Step 1: Create the extension file**

  ```kotlin
  package org.dexpace.sdk.core.instrumentation

  import org.slf4j.MDC

  /**
   * Activates [this] span via [Span.makeCurrent] and additionally pushes `trace.id` and
   * `span.id` onto SLF4J MDC for the lifetime of the returned scope. Closing the scope
   * restores any previous MDC values for those keys (or removes them if previously unset),
   * then closes the underlying scope.
   *
   * For [non-recording spans][Span.isRecording] the MDC push is skipped — non-recording
   * spans have no trace/span identifiers worth attaching — and the call delegates straight
   * to [Span.makeCurrent].
   *
   * ## Propagation caveat
   *
   * MDC is per-thread. The keys pushed here are visible to log events emitted on **this**
   * thread between activation and close, but they do NOT automatically propagate across
   * [java.util.concurrent.CompletableFuture] continuations or coroutine suspensions. For
   * coroutine callers, use `kotlinx-coroutines-slf4j`'s `MDCContext` element. For raw
   * future chains, capture and restore manually.
   */
  fun Span.makeCurrentWithLoggingContext(): TracingScope {
      val inner = makeCurrent()
      if (!isRecording) return inner
      val ctx = context
      val traceId = ctx.traceId
      val spanId = ctx.spanId
      val prevTraceId = MDC.get(MDC_TRACE_ID)
      val prevSpanId = MDC.get(MDC_SPAN_ID)
      MDC.put(MDC_TRACE_ID, traceId)
      MDC.put(MDC_SPAN_ID, spanId)
      return TracingScope {
          try {
              if (prevTraceId == null) MDC.remove(MDC_TRACE_ID) else MDC.put(MDC_TRACE_ID, prevTraceId)
              if (prevSpanId == null) MDC.remove(MDC_SPAN_ID) else MDC.put(MDC_SPAN_ID, prevSpanId)
          } finally {
              inner.close()
          }
      }
  }

  /** SLF4J MDC key for the W3C trace id. Lowercase-dotted to match the SDK's field-naming convention. */
  internal const val MDC_TRACE_ID: String = "trace.id"

  /** SLF4J MDC key for the W3C span id. */
  internal const val MDC_SPAN_ID: String = "span.id"
  ```

  Note: `context.traceId` and `context.spanId` are the property names on `InstrumentationContext`. **Verify by reading `sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/InstrumentationContext.kt`** before assuming. If the properties are named differently (e.g., `traceIdHex`, `spanIdHex`), adjust accordingly.

- [ ] **Step 2: Write tests**

  Create `SpanLoggingExtensionsTest.kt`:

  ```kotlin
  package org.dexpace.sdk.core.instrumentation

  import org.slf4j.MDC
  import kotlin.test.AfterTest
  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertNull

  class SpanLoggingExtensionsTest {

      @AfterTest
      fun clearMdc() {
          MDC.clear()
      }

      @Test
      fun `makeCurrentWithLoggingContext pushes trace and span ids onto MDC`() {
          val span = RecordingTestSpan(traceId = "abc", spanId = "xyz")
          span.makeCurrentWithLoggingContext().use {
              assertEquals("abc", MDC.get("trace.id"))
              assertEquals("xyz", MDC.get("span.id"))
          }
          assertNull(MDC.get("trace.id"))
          assertNull(MDC.get("span.id"))
      }

      @Test
      fun `non-recording span skips MDC push`() {
          val span = RecordingTestSpan(traceId = "abc", spanId = "xyz", recording = false)
          span.makeCurrentWithLoggingContext().use {
              assertNull(MDC.get("trace.id"))
              assertNull(MDC.get("span.id"))
          }
      }

      @Test
      fun `nested scopes restore the outer MDC values on close`() {
          val outer = RecordingTestSpan(traceId = "outer-trace", spanId = "outer-span")
          val inner = RecordingTestSpan(traceId = "inner-trace", spanId = "inner-span")
          outer.makeCurrentWithLoggingContext().use {
              assertEquals("outer-trace", MDC.get("trace.id"))
              inner.makeCurrentWithLoggingContext().use {
                  assertEquals("inner-trace", MDC.get("trace.id"))
              }
              assertEquals("outer-trace", MDC.get("trace.id"))
          }
          assertNull(MDC.get("trace.id"))
      }

      private class RecordingTestSpan(
          private val traceId: String,
          private val spanId: String,
          private val recording: Boolean = true,
      ) : Span {
          override val context: InstrumentationContext = object : InstrumentationContext {
              override val traceId: String get() = this@RecordingTestSpan.traceId
              override val spanId: String get() = this@RecordingTestSpan.spanId
              // Implement other InstrumentationContext members as needed — read the
              // interface and stub the remainder with sensible defaults.
          }
          override val isRecording: Boolean = recording
          override fun setAttribute(key: String, value: Any): Span = this
          override fun setError(errorType: String): Span = this
          override fun makeCurrent(): TracingScope = TracingScope { /* no-op */ }
          override fun end() = Unit
          override fun end(throwable: Throwable) = Unit
      }
  }
  ```

  **Before running**, read `InstrumentationContext.kt` and stub the remaining members on the anonymous object. The minimum is whatever the interface requires.

- [ ] **Step 3: Run tests**

  ```bash
  ./gradlew :sdk-core:test --tests "*SpanLoggingExtensions*" --console=plain
  ```
  Expected: PASS.

### Task 2.3: Use the MDC-aware scope from `DefaultInstrumentationStep`

**Files:**
- Modify: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/DefaultInstrumentationStep.kt`

- [ ] **Step 1: Add the import**

  Near the other `instrumentation` imports, add:
  ```kotlin
  import org.dexpace.sdk.core.instrumentation.makeCurrentWithLoggingContext
  ```

- [ ] **Step 2: Wrap the downstream call in the MDC-aware scope**

  In `process(request: Request, next: PipelineNext): Response`, find the existing try/catch that calls `next.process(outgoing)` (around line ~100). Wrap it in `span.makeCurrentWithLoggingContext().use { ... }`:

  Before:
  ```kotlin
  val response: Response
  try {
      response = next.process(outgoing)
  } catch (t: Throwable) {
      val elapsedMs = elapsedMillis(startNanos)
      emitFailureEvent(outgoing, redactedUrl, t, elapsedMs, wrappedRequestBody)
      span.end(t)
      throw t
  }
  ```

  After:
  ```kotlin
  val response: Response = span.makeCurrentWithLoggingContext().use {
      try {
          next.process(outgoing)
      } catch (t: Throwable) {
          val elapsedMs = elapsedMillis(startNanos)
          emitFailureEvent(outgoing, redactedUrl, t, elapsedMs, wrappedRequestBody)
          span.end(t)
          throw t
      }
  }
  ```

  This puts every log event emitted by downstream steps (and by the response event emitted after `next.process` returns) inside the MDC scope. The response event itself is emitted AFTER the `.use{}` block today; move the response-event emission INSIDE the `.use{}` block too if the existing structure permits. Read the surrounding code carefully — the response event emission may be in an `emitResponseEvent` call after the try/catch.

- [ ] **Step 3: Run instrumentation tests**

  ```bash
  ./gradlew :sdk-core:test --tests "*InstrumentationStepTest*" --console=plain
  ```
  Expected: PASS. Existing tests don't assert anything about MDC, so they shouldn't break. The new behavior is additive.

### Task 2.4: Documentation note on TracingScope MDC contract

**Files:**
- Modify: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/TracingScope.kt`
- Modify: `docs/architecture.md` (search for the relevant tracing / observability section)

- [ ] **Step 1: Extend `TracingScope` KDoc**

  Replace the existing KDoc with:
  ```kotlin
  /**
   * Lifecycle handle for a span that has been activated via [Span.makeCurrent].
   *
   * While the scope is open, the associated span is the "current" span for the executing
   * thread; closing the scope restores the previously active span. Implementations must be
   * safe to `close()` from `try`-with-resources / Kotlin `use { … }` to guarantee cleanup
   * even when the guarded code throws.
   *
   * For log-event correlation, use [makeCurrentWithLoggingContext] instead of
   * [Span.makeCurrent] directly — that wrapper also pushes `trace.id` / `span.id` onto
   * SLF4J MDC for the lifetime of the scope. The plain [Span.makeCurrent] is appropriate
   * when only tracing-system propagation (e.g. OTel context) is needed.
   *
   * **Async propagation:** MDC is per-thread. Values pushed inside the scope do NOT
   * automatically propagate across [java.util.concurrent.CompletableFuture] continuations,
   * coroutine suspensions, or executor handoffs. For coroutines use
   * `kotlinx-coroutines-slf4j`'s `MDCContext` element.
   */
  ```

- [ ] **Step 2: Add a short note to docs/architecture.md**

  Append a brief paragraph under the existing tracing/instrumentation discussion (or create a new section "Log correlation" if none exists). Two sentences:

  > Log correlation is wired through SLF4J MDC: `Span.makeCurrentWithLoggingContext()` pushes `trace.id` / `span.id` for the scope, and `LoggingEvent.log()` folds MDC into every emitted event as structured fields. MDC is per-thread; callers using `CompletableFuture` chains or coroutines must propagate explicitly (see `TracingScope` KDoc).

- [ ] **Step 3: Run a focused compile check**

  ```bash
  ./gradlew :sdk-core:compileKotlin --console=plain
  ```
  Expected: BUILD SUCCESSFUL.

### Task 2.5: Commit Commit 2

- [ ] **Step 1: Stage and commit**

  ```bash
  git add \
    sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/LoggingEvent.kt \
    sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/SpanLoggingExtensions.kt \
    sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/TracingScope.kt \
    sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/DefaultInstrumentationStep.kt \
    sdk-core/src/test/kotlin/org/dexpace/sdk/core/instrumentation/LoggingEventTest.kt \
    sdk-core/src/test/kotlin/org/dexpace/sdk/core/instrumentation/SpanLoggingExtensionsTest.kt \
    docs/architecture.md
  git commit -m "$(cat <<'EOF'
  feat: correlate log events with active span via SLF4J MDC

  Logs emitted within a Span scope now carry trace.id and span.id as
  structured fields. Span.makeCurrentWithLoggingContext() (new extension)
  wraps Span.makeCurrent() and pushes the IDs onto SLF4J MDC for the
  scope's lifetime, restoring previous values on close. LoggingEvent.log()
  folds MDC.getCopyOfContextMap() into the SLF4J event builder before
  per-event fields, so MDC keys appear as structured fields rather than
  only %X{...} pattern lookups; per-event fields override MDC keys with
  the same name. DefaultInstrumentationStep now actually scopes the
  downstream pipeline call in the MDC-aware scope so events emitted by
  retry/redirect/auth/instrumentation are correlated automatically on
  the calling thread.

  MDC is per-thread; the limitation is documented in TracingScope KDoc
  and docs/architecture.md. Coroutine callers should pair with
  kotlinx-coroutines-slf4j's MDCContext.
  EOF
  )"
  ```

---

## Commit 3 — Coverage

Add log events at currently-silent paths in the four async adapters. Each adapter file gains a top-level `private val LOG = ClientLogger(...)` and emits at most two events.

### Task 3.1: Add logging to `sdk-async-coroutines/Coroutines.kt`

**Files:**
- Modify: `sdk-async-coroutines/src/main/kotlin/org/dexpace/sdk/async/coroutines/Coroutines.kt`

- [ ] **Step 1: Add the import and top-level logger**

  At the top of `Coroutines.kt`, after the existing imports:
  ```kotlin
  import org.dexpace.sdk.core.instrumentation.ClientLogger
  ```

  Just before the first function declaration (`suspend fun AsyncHttpClient.execute(...)`):
  ```kotlin
  private val LOG = ClientLogger("org.dexpace.sdk.async.coroutines.Coroutines")
  ```

  Use a string name rather than a `KClass` since the file has top-level extensions and no host class.

- [ ] **Step 2: Log on `asAsyncCoroutines` adaptation**

  Inside `fun HttpClient.asAsyncCoroutines(scope: CoroutineScope): AsyncHttpClient`, just before the `AsyncHttpClient { ... }` lambda is returned:

  ```kotlin
  LOG.atVerbose()
      .event("async.adapter.wrapped")
      .field("adapter.type", "coroutines")
      .field("scope.coroutineContext", scope.coroutineContext.toString())
      .log()
  ```

  Place the call before `return AsyncHttpClient { ... }` (or before the `=` if it's an expression body — adjust as needed). Each call to `asAsyncCoroutines` emits one verbose event; this is config-time, not hot-path.

- [ ] **Step 3: Run sdk-async-coroutines tests**

  ```bash
  ./gradlew :sdk-async-coroutines:test --console=plain
  ```
  Expected: PASS. Existing tests don't assert on log output.

### Task 3.2: Add logging to `sdk-async-netty/Netty.kt`

**Files:**
- Modify: `sdk-async-netty/src/main/kotlin/org/dexpace/sdk/async/netty/Netty.kt`

- [ ] **Step 1: Add the import and top-level logger**

  ```kotlin
  import org.dexpace.sdk.core.instrumentation.ClientLogger

  private val LOG = ClientLogger("org.dexpace.sdk.async.netty.Netty")
  ```

- [ ] **Step 2: Log on Netty-side cancellation propagation**

  In `bridgeToNetty`, the existing listener block is:
  ```kotlin
  promise.addListener {
      if (it.isCancelled && !source.isDone) source.cancel(true)
  }
  ```
  Update to:
  ```kotlin
  promise.addListener {
      if (it.isCancelled && !source.isDone) {
          source.cancel(true)
          LOG.atVerbose()
              .event("async.adapter.cancel_propagated")
              .field("adapter.type", "netty")
              .log()
      }
  }
  ```

- [ ] **Step 3: Run sdk-async-netty tests**

  ```bash
  ./gradlew :sdk-async-netty:test --console=plain
  ```
  Expected: PASS.

### Task 3.3: Add logging to `sdk-async-reactor/Reactor.kt`

**Files:**
- Modify: `sdk-async-reactor/src/main/kotlin/org/dexpace/sdk/async/reactor/Reactor.kt`

- [ ] **Step 1: Add the import and top-level logger**

  ```kotlin
  import org.dexpace.sdk.core.instrumentation.ClientLogger

  private val LOG = ClientLogger("org.dexpace.sdk.async.reactor.Reactor")
  ```

- [ ] **Step 2: Hook subscribe + cancel signals on the Mono**

  Replace the current bodies of `executeMono` and `sendMono` so they emit subscribe/cancel events. The current bodies are:
  ```kotlin
  fun AsyncHttpClient.executeMono(request: Request): Mono<Response> =
      Mono.fromFuture { executeAsync(request) }

  fun AsyncHttpPipeline.sendMono(request: Request): Mono<Response> =
      Mono.fromFuture { sendAsync(request) }
  ```

  Replace with:
  ```kotlin
  fun AsyncHttpClient.executeMono(request: Request): Mono<Response> =
      Mono.fromFuture { executeAsync(request) }
          .doOnSubscribe {
              LOG.atVerbose()
                  .event("async.adapter.subscribed")
                  .field("adapter.type", "reactor")
                  .log()
          }
          .doOnCancel {
              LOG.atVerbose()
                  .event("async.adapter.cancel_propagated")
                  .field("adapter.type", "reactor")
                  .log()
          }

  fun AsyncHttpPipeline.sendMono(request: Request): Mono<Response> =
      Mono.fromFuture { sendAsync(request) }
          .doOnSubscribe {
              LOG.atVerbose()
                  .event("async.adapter.subscribed")
                  .field("adapter.type", "reactor")
                  .log()
          }
          .doOnCancel {
              LOG.atVerbose()
                  .event("async.adapter.cancel_propagated")
                  .field("adapter.type", "reactor")
                  .log()
          }
  ```

- [ ] **Step 3: Run sdk-async-reactor tests**

  ```bash
  ./gradlew :sdk-async-reactor:test --console=plain
  ```
  Expected: PASS.

### Task 3.4: Add logging to `sdk-async-virtualthreads/VirtualThreads.kt`

**Files:**
- Modify: `sdk-async-virtualthreads/src/main/kotlin/org/dexpace/sdk/async/virtualthreads/VirtualThreads.kt`

- [ ] **Step 1: Add the import and top-level logger**

  ```kotlin
  import org.dexpace.sdk.core.instrumentation.ClientLogger

  private val LOG = ClientLogger("org.dexpace.sdk.async.virtualthreads.VirtualThreads")
  ```

- [ ] **Step 2: Log on executor close**

  In `VirtualThreadAsyncHttpClient.close()`:
  ```kotlin
  override fun close() {
      LOG.atInfo()
          .event("executor.closed")
          .field("adapter.type", "virtualthreads")
          .log()
      executor.close()
  }
  ```

- [ ] **Step 3: Run sdk-async-virtualthreads tests**

  ```bash
  ./gradlew :sdk-async-virtualthreads:test --console=plain
  ```
  Expected: PASS.

### Task 3.5: Commit Commit 3

- [ ] **Step 1: Stage and commit**

  ```bash
  git add \
    sdk-async-coroutines/src/main/kotlin/org/dexpace/sdk/async/coroutines/Coroutines.kt \
    sdk-async-netty/src/main/kotlin/org/dexpace/sdk/async/netty/Netty.kt \
    sdk-async-reactor/src/main/kotlin/org/dexpace/sdk/async/reactor/Reactor.kt \
    sdk-async-virtualthreads/src/main/kotlin/org/dexpace/sdk/async/virtualthreads/VirtualThreads.kt
  git commit -m "$(cat <<'EOF'
  feat: add observability log events to the four sdk-async-* adapters

  The async adapter modules shipped silently — no log output even at
  verbose level. Operators debugging cancellation propagation across
  the SDK / transport boundary had no breadcrumbs. Each adapter now
  emits at most two events through ClientLogger:

  - coroutines: async.adapter.wrapped (VERBOSE, on asAsyncCoroutines).
  - netty: async.adapter.cancel_propagated (VERBOSE, when the Netty
    promise's cancellation hop closes the SDK future).
  - reactor: async.adapter.subscribed and async.adapter.cancel_propagated
    (VERBOSE) on the Mono lifecycle hooks.
  - virtualthreads: executor.closed (INFO, on AutoCloseable.close).

  All events carry adapter.type so a single dashboard query can filter
  by adapter. The NOOP fast path on disabled levels means zero hot-path
  cost when the logger is not enabled.
  EOF
  )"
  ```

---

## Commit 4 — Richness

Add fields to existing events. No renames; only additions.

### Task 4.1: `http.redirect` — add `redirect.from_url` and `redirect.target_url`

**Files:**
- Modify: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/DefaultRedirectStep.kt`
- Modify: `sdk-core/src/test/kotlin/org/dexpace/sdk/core/http/pipeline/steps/RedirectStepTest.kt`

- [ ] **Step 1: Add the two new fields to the `http.redirect` emission**

  At line ~269 in `DefaultRedirectStep.kt`, the current `http.redirect` info-level event has a `.field(...)` chain. Open the file and locate the `logger.atInfo().event("http.redirect")` chain. Add two fields, redacted via the existing `UrlRedactor` (look for the existing redact helper in the file — likely an injected `redactor: UrlRedactor` field or a top-level `UrlRedactor.DEFAULT` instance; reuse the same pattern as the loop-detected event):

  ```kotlin
  .field("redirect.from_url", redactor.redact(currentRequestUrl.toString()))
  .field("redirect.target_url", redactor.redact(nextRequestUrl.toString()))
  ```

  Use the variable names that already exist in the surrounding code — `currentRequestUrl` / `nextRequestUrl` / `current.request.url` / etc. **Read lines 240-280 first** to find the right variable names and the redactor's call site.

- [ ] **Step 2: Update an existing test to assert the new fields**

  Find the existing `RedirectStepTest` test that asserts on the `http.redirect` event's structured fields. Add assertions:
  ```kotlin
  assertEquals("https://example.com/initial", event.field("redirect.from_url"))
  assertEquals("https://example.com/next", event.field("redirect.target_url"))
  ```

  Use the same URLs the existing test sets up so the assertions are concrete. If the existing test fixture redacts URLs, the assertion values change accordingly — match the redactor's output.

- [ ] **Step 3: Run redirect tests**

  ```bash
  ./gradlew :sdk-core:test --tests "*RedirectStepTest*" --console=plain
  ```
  Expected: PASS.

### Task 4.2: `http.retry` — add `retry.total_elapsed_ms` and `retry.cause_class`

**Files:**
- Modify: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/DefaultRetryStep.kt`
- Modify: `sdk-core/src/test/kotlin/org/dexpace/sdk/core/http/pipeline/steps/RetryStepTest.kt`

- [ ] **Step 1: Track elapsed retry time per request**

  In `DefaultRetryStep.process(...)` (or wherever the retry loop is), find where `tryCount` is incremented. Add a sibling local variable `retryStartedAtNanos` captured at the start of the retry sequence (before the first retry, so `total_elapsed_ms == 0` for attempt 0). The plan: capture `clock.monotonic()` once at the top of `process()` (or just before the loop), and compute `(clock.monotonic() - retryStartedAtNanos).toMillis()` at the log site.

  Add field after the existing field chain for `http.retry`:
  ```kotlin
  .field("retry.total_elapsed_ms", (clock.monotonic() - retryStartedAtNanos) / 1_000_000)
  ```
  (Divide by 1M if `monotonic()` returns nanoseconds; check by reading the existing `elapsedMillis` helper, if any.)

- [ ] **Step 2: Add `retry.cause_class` to the `http.retry` event**

  At the same log call (~line 391), if `cause` is non-null:
  ```kotlin
  if (cause != null) event.field("retry.cause_class", cause::class.simpleName ?: "Throwable")
  ```

- [ ] **Step 3: Update RetryStepTest assertions**

  Add a test asserting that on a retry triggered by an exception, the `http.retry` event carries `retry.cause_class` matching the exception's simple name, and `retry.total_elapsed_ms` is `>= 0`.

- [ ] **Step 4: Run retry tests**

  ```bash
  ./gradlew :sdk-core:test --tests "*RetryStepTest*" --console=plain
  ```
  Expected: PASS.

### Task 4.3: `http.instrumentation.emit_*_failed` events — add `error.phase`

**Files:**
- Modify: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/DefaultInstrumentationStep.kt`
- Modify: `sdk-core/src/test/kotlin/org/dexpace/sdk/core/http/pipeline/steps/InstrumentationStepTest.kt`

Adds a structured `error.phase` field on each of the three instrumentation-error emissions, valued `request_event`, `response_event`, or `failure_event`. Operators querying by structured field find this easier than parsing event-name suffixes.

- [ ] **Step 1: Change `emitInstrumentationError` to accept a phase**

  In `DefaultInstrumentationStep.kt`, the current helper at line ~279 is:
  ```kotlin
  private fun emitInstrumentationError(event: String, t: Throwable) {
      try {
          logger.atWarning()
              .event(event)
              .field("error.type", t.javaClass.simpleName ?: "Throwable")
              .cause(t)
              .log()
      } catch (_: Throwable) { }
  }
  ```
  Update to:
  ```kotlin
  private fun emitInstrumentationError(event: String, phase: String, t: Throwable) {
      try {
          logger.atWarning()
              .event(event)
              .field("error.phase", phase)
              .field("error.type", t.javaClass.simpleName ?: "Throwable")
              .cause(t)
              .log()
      } catch (_: Throwable) { }
  }
  ```

- [ ] **Step 2: Update the three call sites**

  - Line ~152 — `emitInstrumentationError("http.instrumentation.emit_request_failed", t)` → add `"request_event"` as the second argument.
  - Line ~191 — `emitInstrumentationError("http.instrumentation.emit_response_failed", t)` → add `"response_event"`.
  - Line ~218 — `emitInstrumentationError("http.instrumentation.emit_failure_failed", t)` → add `"failure_event"`.

- [ ] **Step 3: Update the existing test that forces a contentLength-throws path**

  In `InstrumentationStepTest.kt`, find the test named like `emit_request_failed instrumentation error is logged when contentLength on the body throws`. It currently asserts the event name. Add an assertion on the `error.phase` field:
  ```kotlin
  assertEquals("request_event", event.field("error.phase"))
  ```
  Use whatever event-field-accessor pattern that test file already uses (e.g. `event.keyValuePairs.associate { ... }["error.phase"]`).

- [ ] **Step 4: Run instrumentation tests**

  ```bash
  ./gradlew :sdk-core:test --tests "*InstrumentationStepTest*" --console=plain
  ```
  Expected: PASS.

### Task 4.4: Commit Commit 4

- [ ] **Step 1: Stage and commit**

  ```bash
  git add \
    sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/DefaultRedirectStep.kt \
    sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/DefaultRetryStep.kt \
    sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/DefaultInstrumentationStep.kt \
    sdk-core/src/test/kotlin/org/dexpace/sdk/core/http/pipeline/steps/RedirectStepTest.kt \
    sdk-core/src/test/kotlin/org/dexpace/sdk/core/http/pipeline/steps/RetryStepTest.kt \
    sdk-core/src/test/kotlin/org/dexpace/sdk/core/http/pipeline/steps/InstrumentationStepTest.kt
  git commit -m "$(cat <<'EOF'
  feat: enrich http.redirect, http.retry, and instrumentation-error events with diagnostic fields

  - http.redirect now carries redirect.from_url and redirect.target_url
    (both redacted via UrlRedactor) so operators see the chain across
    successive emissions rather than having to correlate by timestamp.
  - http.retry now carries retry.total_elapsed_ms (cumulative wall time
    since the first attempt) and retry.cause_class (exception simple
    name; absent for status-code-only retries) so non-IOException retry
    paths show up clearly in queries.
  - http.instrumentation.emit_*_failed events now carry error.phase
    valued request_event / response_event / failure_event so operators
    querying by structured field don't have to parse event-name suffixes.

  Backward-compatible: no renames, only additions. Existing dashboards
  keyed on event names or pre-existing field names continue to work.
  EOF
  )"
  ```

---

## Commit 5 — DefaultAsyncInstrumentationStep

Mirror `DefaultInstrumentationStep` for `AsyncHttpPipeline`. Same options class (`HttpInstrumentationOptions`), same events, same metric handles, adapted for `CompletableFuture<Response>`.

### Task 5.1: Read the sync `DefaultInstrumentationStep` end-to-end

**Files:**
- Read-only: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/DefaultInstrumentationStep.kt`
- Read-only: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/InstrumentationStep.kt`
- Read-only: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/HttpInstrumentationOptions.kt`
- Read-only: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/AsyncHttpStep.kt`

- [ ] **Step 1: Read each file once and write a short scratchpad**

  Take 5 minutes to read each file. Note:
  - What does `InstrumentationStep` / `AsyncHttpStep` require (abstract or `fun interface`)?
  - What signature does `DefaultInstrumentationStep.process(request, next)` return?
  - What's the constructor parameter set on `DefaultInstrumentationStep`?
  - What does the response-event emission path look like — what fields, what level, what callsites?
  - Where is the span ended (success vs failure)?
  - Where is the metric recorded (counter, histogram)?

  The goal is symmetry: `DefaultAsyncInstrumentationStep` should look like the sync step, just returning a future and using `.whenComplete` instead of try/catch.

### Task 5.2: Create `DefaultAsyncInstrumentationStep`

**Files:**
- Create: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/DefaultAsyncInstrumentationStep.kt`

- [ ] **Step 1: Sketch the class skeleton**

  ```kotlin
  package org.dexpace.sdk.core.http.pipeline.steps

  import org.dexpace.sdk.core.http.pipeline.AsyncHttpStep
  import org.dexpace.sdk.core.http.pipeline.AsyncPipelineNext
  import org.dexpace.sdk.core.http.pipeline.Stage
  import org.dexpace.sdk.core.http.request.LoggableRequestBody
  import org.dexpace.sdk.core.http.request.Request
  import org.dexpace.sdk.core.http.response.LoggableResponseBody
  import org.dexpace.sdk.core.http.response.Response
  import org.dexpace.sdk.core.instrumentation.ClientLogger
  import org.dexpace.sdk.core.instrumentation.LoggingEvent
  import org.dexpace.sdk.core.instrumentation.Span
  import org.dexpace.sdk.core.instrumentation.UrlRedactor
  import org.dexpace.sdk.core.instrumentation.makeCurrentWithLoggingContext
  import org.dexpace.sdk.core.instrumentation.metrics.DoubleHistogram
  import org.dexpace.sdk.core.instrumentation.metrics.LongCounter
  import org.dexpace.sdk.core.util.Clock
  import org.dexpace.sdk.core.util.Futures
  import java.util.concurrent.CompletableFuture

  /**
   * Async counterpart of [DefaultInstrumentationStep]. Emits the same `http.request` /
   * `http.response` / failure events, drives the same span and metric lifecycle, and is
   * configured with the same [HttpInstrumentationOptions]. The only structural difference
   * is that `processAsync` returns a [CompletableFuture] composed via `.whenComplete` so
   * the response event fires on the completion thread.
   *
   * ## Span-thread caveat
   *
   * The future's completion handler runs on whatever thread completes the underlying
   * future. The MDC push from [Span.makeCurrentWithLoggingContext] is per-thread; if the
   * completion thread differs from the calling thread, MDC won't carry. This step
   * therefore attaches `trace.id` / `span.id` as **explicit fields** on its own events,
   * matching the sync step's behaviour. Downstream steps (and the actual transport call)
   * benefit from MDC for the synchronous portion of their work; the `.whenComplete`
   * boundary is the cutoff.
   */
  class DefaultAsyncInstrumentationStep @JvmOverloads constructor(
      private val options: HttpInstrumentationOptions = HttpInstrumentationOptions(),
      private val clock: Clock = Clock.SYSTEM,
  ) : AsyncHttpStep {

      // TODO: implement — see Task 5.3 below.
      override val stage: Stage = Stage.INSTRUMENTATION

      override fun processAsync(request: Request, next: AsyncPipelineNext): CompletableFuture<Response> {
          TODO("implement in Task 5.3")
      }
  }
  ```

  Compile to make sure the class boundary fits before filling in the body.

- [ ] **Step 2: Compile-check the skeleton**

  ```bash
  ./gradlew :sdk-core:compileKotlin --console=plain
  ```
  Expected: BUILD SUCCESSFUL.

### Task 5.3: Implement `processAsync`

- [ ] **Step 1: Port the body from the sync step**

  Open `DefaultInstrumentationStep.kt` side-by-side. Mirror `process(request, next)` into `processAsync(request, next)` with these adaptations:

  - The span is started the same way.
  - The body wrapping (`LoggableRequestBody`) happens the same way.
  - The request event is emitted the same way.
  - The downstream call `next.process(outgoing)` becomes `next.processAsync(outgoing)` and returns a `CompletableFuture<Response>`.
  - Wrap the downstream future in `.whenComplete { response, error -> … }` to emit the response event (on success) or failure event (on error) and end the span.
  - The MDC scope (`span.makeCurrentWithLoggingContext()`) wraps only the **synchronous** part — the call to `next.processAsync(outgoing)`. The `.whenComplete` callback runs on the completion thread; do NOT try to extend the MDC scope across it. Instead, extract `span.context.traceId` / `spanId` once before scheduling and add them as explicit fields on the response/failure event.
  - On synchronous exception (e.g. argument-validation failure thrown by an upstream step), return `Futures.failed(e)` after emitting the failure event and ending the span.

  This is intentionally NOT a 5-minute step — budget 30-45 minutes. Reference the sync step continuously.

- [ ] **Step 2: Compile**

  ```bash
  ./gradlew :sdk-core:compileKotlin --console=plain
  ```
  Expected: BUILD SUCCESSFUL. Fix any compile errors before moving on.

### Task 5.4: Test `DefaultAsyncInstrumentationStep`

**Files:**
- Create: `sdk-core/src/test/kotlin/org/dexpace/sdk/core/http/pipeline/steps/AsyncInstrumentationStepTest.kt`

- [ ] **Step 1: Mirror the sync InstrumentationStepTest's coverage for the success case**

  Read `InstrumentationStepTest.kt` and pick 3-4 representative tests to mirror:
  - "emits a `http.request` event with the redacted URL"
  - "emits a `http.response` event with status and duration"
  - "wraps the request body when bodyAndHeaders logging is enabled"
  - "emits a failure event and rethrows on downstream exception"

  For each, port the test to async: replace `pipeline.send(request)` with `pipeline.sendAsync(request).join()` (and `.completedFuture(...)` from the fake async client). Use the same `RecordingTracer`, `RecordingMeter`, and `FakeAsyncHttpClient` test doubles — if a `FakeAsyncHttpClient` doesn't exist, write one that satisfies `AsyncHttpClient { request -> CompletableFuture.completedFuture(scriptedResponse) }`.

  Each test follows the pattern:
  ```kotlin
  @Test
  fun `name`() {
      val fakeAsync = AsyncHttpClient { request ->
          CompletableFuture.completedFuture(scriptedResponse(request))
      }
      val pipeline = AsyncHttpPipelineBuilder(fakeAsync)
          .append(DefaultAsyncInstrumentationStep(HttpInstrumentationOptions(...)))
          .build()
      val response = pipeline.sendAsync(getRequest("https://api.example.com/x")).join()
      // assertions
  }
  ```

- [ ] **Step 2: Run the tests**

  ```bash
  ./gradlew :sdk-core:test --tests "*AsyncInstrumentationStepTest*" --console=plain
  ```
  Expected: PASS.

### Task 5.5: Commit Commit 5

- [ ] **Step 1: Stage and commit**

  ```bash
  git add \
    sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/DefaultAsyncInstrumentationStep.kt \
    sdk-core/src/test/kotlin/org/dexpace/sdk/core/http/pipeline/steps/AsyncInstrumentationStepTest.kt
  git commit -m "$(cat <<'EOF'
  feat: DefaultAsyncInstrumentationStep — async counterpart to the sync step

  Mirrors DefaultInstrumentationStep for AsyncHttpPipeline: same
  HttpInstrumentationOptions, same http.request / http.response / failure
  event shapes, same span and metric lifecycle. The future returned by
  next.processAsync is wrapped in .whenComplete to emit the response or
  failure event on the completion thread and end the span there.

  Trace/span correlation: the synchronous portion (before the future is
  scheduled) uses Span.makeCurrentWithLoggingContext() so downstream
  steps and the transport call see trace.id / span.id in MDC. The async
  completion callback runs on whatever thread completes the future, so
  MDC is not relied upon there — this step adds trace.id / span.id as
  explicit fields on its own response/failure events.

  Caller contract on cancellation matches AsyncHttpClient.executeAsync:
  cancelling the returned future after the response has been delivered
  does NOT close the body — the caller still owns close per the
  HttpClient.execute contract.
  EOF
  )"
  ```

---

## Final verification

After all five commits land:

- [ ] **Run full build**

  ```bash
  ./gradlew clean build --console=plain
  ```
  Expected: BUILD SUCCESSFUL.

- [ ] **Aggregate coverage**

  ```bash
  ./gradlew koverXmlReport --console=plain
  python3 - <<'EOF'
  import xml.etree.ElementTree as ET
  root = ET.parse('build/reports/kover/report.xml').getroot()
  line_miss = sum(int(c.get('missed')) for c in root.findall('counter') if c.get('type') == 'LINE')
  line_cov  = sum(int(c.get('covered')) for c in root.findall('counter') if c.get('type') == 'LINE')
  print(f"Lines covered: {line_cov}/{line_cov+line_miss} = {100*line_cov/(line_cov+line_miss):.2f}%")
  EOF
  ```
  Expected: ≥ 97% (current baseline is 97.26%). New code in adapter modules adds covered lines; correlation/richness additions go through tested paths.

- [ ] **Push**

  ```bash
  git push origin main
  ```

---

## Risks the executor should be aware of

- **Task 2.1's `TestLogger.captor()` placeholder**: the existing `LoggingEventTest.kt` may use a different test pattern. Read the file before writing the test; copy the existing scaffolding.
- **Task 2.2's `InstrumentationContext` member set**: the anonymous class in the test stubs needs all members of `InstrumentationContext` to compile. Read the interface first; stub only what's required.
- **Task 4.3 helper signature change**: `emitInstrumentationError` gains a `phase` parameter; the three call sites must all be updated in lockstep or the compile breaks. The plan lists exact line numbers (152, 191, 218) — if the file has drifted, grep for `emitInstrumentationError(` to find them.
- **Task 5.3's scope**: porting the sync step to async is non-trivial — budget time and reference the sync step continuously. If the future composition gets tangled, fall back to a simpler skeleton: emit only request/response/failure events, no MDC propagation across the boundary, no metric histogram update on failure. Land the simpler version; future commits can polish.

## Out of scope (do NOT add)

- Log sampling / rate limiting.
- Header-level redaction beyond URLs.
- Backward-incompatible event renames or field renames.
- Wiring MDC propagation across CompletableFuture chains (documented limitation; not in this plan).
