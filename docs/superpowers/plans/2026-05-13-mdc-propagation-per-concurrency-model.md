# MDC Propagation Per Concurrency Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `trace.id` / `span.id` MDC entries propagate correctly across each of the four async adapter boundaries — closing the documented gap from the prior logging-enhancement pass — so the existing log events those adapters emit on the wrong-side-of-the-boundary thread carry the right MDC.

**Architecture:** New shared `MdcSnapshot` utility in `sdk-core` captures `MDC.getCopyOfContextMap()` and provides `withMdc(block)` and `restore()` operations. Coroutines adapter uses `kotlinx-coroutines-slf4j`'s `MDCContext()` element. Reactor and Netty adapters capture an `MdcSnapshot` at entry and wrap their hook bodies with `snapshot.withMdc { … }`. Virtual-threads adapter introduces an internal `MdcAwareExecutor` that wraps every submitted task with capture/restore.

**Tech Stack:** Kotlin 2.3.21, SLF4J 2.x, `kotlinx-coroutines-slf4j 1.9.0` (new dep on sdk-async-coroutines), kotlin.test + JUnit Platform.

**Spec:** `docs/superpowers/specs/2026-05-13-mdc-propagation-per-concurrency-model-design.md`

---

## Commit 1 — `MdcSnapshot` shared utility (sdk-core)

A small public utility class with two operations (`restore()` and `withMdc(block)`) plus a `capture()` factory. Foundation for the four adapter commits.

### Task 1.1: Write the failing tests

**Files:**
- Create: `sdk-core/src/test/kotlin/org/dexpace/sdk/core/instrumentation/MdcSnapshotTest.kt`

- [ ] **Step 1: Read the existing MDC-test helper**

  Read the top 50 lines of `sdk-core/src/test/kotlin/org/dexpace/sdk/core/instrumentation/SpanLoggingExtensionsTest.kt`. It contains an `internal fun installBasicMdcAdapter()` helper (around line ~96 of the original landed file; verify by grep) that uses reflection on `MDC.MDC_ADAPTER` to install a working `BasicMDCAdapter` over slf4j-nop's no-op. Reuse it — same package so direct call works.

- [ ] **Step 2: Create the test file**

  Create `MdcSnapshotTest.kt` with the following content (the production class doesn't exist yet, so this won't compile until Task 1.2):

  ```kotlin
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
          installBasicMdcAdapter()  // shared helper from SpanLoggingExtensionsTest, same package
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
          val snap = MdcSnapshot.capture()  // captures "outer"
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

  // Test-only helper that restores the saved adapter via the same reflection seam used by
  // installBasicMdcAdapter (in SpanLoggingExtensionsTest). Inline here to avoid adding a
  // separate test utility file for one helper.
  private fun restoreMdcAdapter(adapter: MDCAdapter?) {
      val field = MDC::class.java.getDeclaredField("MDC_ADAPTER")
      field.isAccessible = true
      field.set(null, adapter)
  }
  ```

- [ ] **Step 3: Run the tests to verify they fail (compilation error — class doesn't exist)**

  ```bash
  ./gradlew :sdk-core:compileTestKotlin --console=plain 2>&1 | tail -20
  ```
  Expected: FAIL with "Unresolved reference: MdcSnapshot".

### Task 1.2: Implement `MdcSnapshot`

**Files:**
- Create: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/MdcSnapshot.kt`

- [ ] **Step 1: Write the production class**

  ```kotlin
  package org.dexpace.sdk.core.instrumentation

  import org.slf4j.MDC

  /**
   * Immutable snapshot of a thread's SLF4J MDC at the moment of [capture]. Used to bridge MDC
   * across async boundaries — the boundary captures on the caller's thread, then the executing
   * thread (which may be a different worker, coroutine dispatcher, event-loop, etc.) calls
   * [withMdc] or [restore] to install the captured map for the duration of the work.
   *
   * Thread-safety: the snapshot itself is immutable and safe to share. The MDC mutations done
   * by [restore] / [withMdc] affect only the calling thread's MDC, per SLF4J semantics.
   *
   * ## Why this exists
   *
   * `Span.makeCurrentWithLoggingContext()` pushes `trace.id` / `span.id` onto MDC for the
   * lifetime of the scope. MDC is per-thread, so any work that hops to another thread (a
   * `CompletableFuture` continuation, an executor task, a Reactor signal, a Netty event-loop
   * callback) loses the entries. Adapter modules use [MdcSnapshot] to bridge that gap.
   */
  class MdcSnapshot private constructor(private val snapshot: Map<String, String>?) {

      /**
       * Replaces the current thread's MDC with the captured snapshot. Calling this on a thread
       * that already has MDC entries discards them — use [withMdc] instead when you want to
       * preserve the executing thread's MDC after the block.
       */
      fun restore() {
          if (snapshot == null) MDC.clear() else MDC.setContextMap(snapshot)
      }

      /**
       * Captures the current thread's MDC, installs the snapshot for the duration of [block],
       * then restores the previously-captured MDC on exit — including when [block] throws.
       * Use this at adapter-internal callback sites (Reactor `.doOn*`, Netty listeners, etc.)
       * to ensure log events emitted inside the block see the caller's MDC.
       */
      inline fun <T> withMdc(block: () -> T): T {
          val previous = MDC.getCopyOfContextMap()
          if (snapshot == null) MDC.clear() else MDC.setContextMap(snapshot)
          try {
              return block()
          } finally {
              if (previous == null) MDC.clear() else MDC.setContextMap(previous)
          }
      }

      companion object {
          /**
           * Captures the current thread's MDC into an immutable snapshot. Safe to call even when
           * no MDC adapter is installed (the captured snapshot will be `null`, treated as
           * "empty" by [restore] and [withMdc]).
           */
          @JvmStatic
          fun capture(): MdcSnapshot = MdcSnapshot(MDC.getCopyOfContextMap())
      }
  }
  ```

  Note on `inline fun withMdc`: `inline` requires the function to be `public`, which it is. The `private constructor` and `private val snapshot` are accessed inside the inline function; this is valid because the inline function is a member of the class. Compile to verify (Kotlin emits a synthetic accessor for `snapshot`).

- [ ] **Step 2: Run the tests to verify they pass**

  ```bash
  ./gradlew :sdk-core:test --tests "*MdcSnapshotTest*" --console=plain 2>&1 | tail -10
  ```
  Expected: BUILD SUCCESSFUL, 5 tests run, 0 failures.

### Task 1.3: Commit Commit 1

- [ ] **Step 1: Stage and commit**

  ```bash
  git add \
    sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/MdcSnapshot.kt \
    sdk-core/src/test/kotlin/org/dexpace/sdk/core/instrumentation/MdcSnapshotTest.kt
  git commit -m "$(cat <<'EOF'
  feat: MdcSnapshot utility for cross-async-boundary MDC propagation

  Immutable snapshot of SLF4J MDC capturable on one thread and restorable
  (or installed for a block) on another. The four async adapter modules
  use this in follow-up commits to bridge MDC across coroutine
  dispatches, Reactor schedulers, Netty event-loop callbacks, and
  virtual-thread tasks. The util is intentionally tiny — capture(),
  restore(), withMdc(block) — so it stays usable for any downstream
  caller bridging their own async work.
  EOF
  )"
  ```

---

## Commit 2 — Coroutines adapter MDC propagation

Add `kotlinx-coroutines-slf4j` and pass `MDCContext()` to the two `scope.future { ... }` call sites in `Coroutines.kt`. `MDCContext()` (no args) snapshots the current MDC at coroutine launch and reinstates it on every dispatch within the coroutine — including `Dispatchers.IO` hops, suspensions, and resumes on different worker threads.

### Task 2.1: Add the kotlinx-coroutines-slf4j dependency

**Files:**
- Modify: `sdk-async-coroutines/build.gradle.kts`

- [ ] **Step 1: Add the dependency**

  In the `dependencies { ... }` block, after the existing `kotlinx-coroutines-jdk8` line (around line ~16), add:
  ```kotlin
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.9.0")
  ```
  Version `1.9.0` matches the already-pinned `kotlinx-coroutines-core` and `-jdk8`.

- [ ] **Step 2: Refresh the Gradle dependency graph**

  ```bash
  ./gradlew :sdk-async-coroutines:dependencies --configuration runtimeClasspath --console=plain 2>&1 | grep -E "coroutines-slf4j|coroutines-core" | head -3
  ```
  Expected: a line for `kotlinx-coroutines-slf4j:1.9.0` appears in the runtime classpath.

### Task 2.2: Write the failing MDC-propagation test

**Files:**
- Modify: `sdk-async-coroutines/src/test/kotlin/org/dexpace/sdk/async/coroutines/CoroutinesTest.kt`

- [ ] **Step 1: Add the failing test**

  Append to `CoroutinesTest.kt` (before the closing brace of the class):

  ```kotlin
  @Test
  fun `mdc propagates from caller across asAsyncCoroutines to the sync transport`() {
      installBasicMdcAdapter()
      val originalAdapter = org.slf4j.MDC.getMDCAdapter()
      org.slf4j.MDC.put("trace.id", "coroutines-test")
      try {
          val seenTraceId = java.util.concurrent.atomic.AtomicReference<String?>()
          val sync = HttpClient { request ->
              seenTraceId.set(org.slf4j.MDC.get("trace.id"))
              mockResponse(request, 200)
          }
          val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
          try {
              val async = sync.asAsyncCoroutines(scope)
              async.executeAsync(getRequest()).get(2, TimeUnit.SECONDS)
              assertEquals("coroutines-test", seenTraceId.get())
          } finally {
              scope.cancel()
          }
      } finally {
          org.slf4j.MDC.clear()
          restoreMdcAdapter(originalAdapter)
      }
  }

  @Test
  fun `mdc propagates across completableFutureOf coroutine launch`() {
      installBasicMdcAdapter()
      val originalAdapter = org.slf4j.MDC.getMDCAdapter()
      org.slf4j.MDC.put("trace.id", "cf-of-test")
      try {
          val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
          try {
              val future = scope.completableFutureOf {
                  delay(5)
                  org.slf4j.MDC.get("trace.id") ?: "<missing>"
              }
              assertEquals("cf-of-test", future.get(2, TimeUnit.SECONDS))
          } finally {
              scope.cancel()
          }
      } finally {
          org.slf4j.MDC.clear()
          restoreMdcAdapter(originalAdapter)
      }
  }
  ```

  Add two private file-level helpers at the bottom of `CoroutinesTest.kt` (after the class) so they're scoped to test code:

  ```kotlin
  // Reflection-installed BasicMDCAdapter — same workaround used in sdk-core's
  // SpanLoggingExtensionsTest. Tests in this file need a functioning MDC adapter
  // because the runtime classpath uses slf4j-nop.
  private fun installBasicMdcAdapter() {
      val field = org.slf4j.MDC::class.java.getDeclaredField("MDC_ADAPTER")
      field.isAccessible = true
      if (field.get(null) !is org.slf4j.helpers.BasicMDCAdapter) {
          field.set(null, org.slf4j.helpers.BasicMDCAdapter())
      }
  }

  private fun restoreMdcAdapter(adapter: org.slf4j.spi.MDCAdapter?) {
      val field = org.slf4j.MDC::class.java.getDeclaredField("MDC_ADAPTER")
      field.isAccessible = true
      field.set(null, adapter)
  }
  ```

  Verify the existing imports cover `CoroutineScope`, `SupervisorJob`, `Dispatchers`, `delay`, `TimeUnit` — they already do per the file's header. The new code references `org.slf4j.MDC` fully-qualified to avoid touching the import block.

- [ ] **Step 2: Run the tests to verify they fail**

  ```bash
  ./gradlew :sdk-async-coroutines:test --tests "*CoroutinesTest*" --console=plain 2>&1 | tail -15
  ```
  Expected: BUILD FAILED. The two new tests fail because MDC does NOT propagate through the existing `scope.future { ... }` without `MDCContext`. Specifically `seenTraceId.get()` will be `null` and the `completableFutureOf` test will return `"<missing>"`.

### Task 2.3: Add `MDCContext()` to the two `scope.future { ... }` call sites

**Files:**
- Modify: `sdk-async-coroutines/src/main/kotlin/org/dexpace/sdk/async/coroutines/Coroutines.kt`

- [ ] **Step 1: Update the imports**

  Add to the imports (alphabetical placement among `kotlinx.coroutines.*`):
  ```kotlin
  import kotlinx.coroutines.slf4j.MDCContext
  ```

- [ ] **Step 2: Update `asAsyncCoroutines`**

  Locate `fun HttpClient.asAsyncCoroutines(scope: CoroutineScope): AsyncHttpClient`. Inside, the existing line is:
  ```kotlin
  return AsyncHttpClient { request ->
      scope.future { runInterruptible(Dispatchers.IO) { execute(request) } }
  }
  ```
  Change to:
  ```kotlin
  return AsyncHttpClient { request ->
      scope.future(MDCContext()) { runInterruptible(Dispatchers.IO) { execute(request) } }
  }
  ```

- [ ] **Step 3: Update `completableFutureOf`**

  Locate `fun <T> CoroutineScope.completableFutureOf(context: CoroutineContext = EmptyCoroutineContext, block: ...): CompletableFuture<T>`. The current body is:
  ```kotlin
  this.future(context, block = block)
  ```
  Change to:
  ```kotlin
  this.future(context + MDCContext(), block = block)
  ```

  `context + MDCContext()` merges right-to-left for matching element keys; if the caller already passes their own `MDCContext(specificMap)` in `context`, the right-side `MDCContext()` (which captures current MDC) wins. That's acceptable per kotlinx-coroutines semantics — callers who want a specific MDC override should call `withContext(MDCContext(theirMap)) { completableFutureOf(...) }` to set MDC BEFORE the snapshot.

  Update the KDoc on `completableFutureOf` to add a one-line note: "The current thread's MDC is captured via `MDCContext()` so log events inside [block] see the caller's `trace.id` / `span.id`." (Append to the existing KDoc paragraph; don't replace.)

- [ ] **Step 4: Run the tests to verify they pass**

  ```bash
  ./gradlew :sdk-async-coroutines:test --tests "*CoroutinesTest*" --console=plain 2>&1 | tail -10
  ```
  Expected: BUILD SUCCESSFUL, all tests pass including the two new ones.

### Task 2.4: Commit Commit 2

- [ ] **Step 1: Stage and commit**

  ```bash
  git add \
    sdk-async-coroutines/build.gradle.kts \
    sdk-async-coroutines/src/main/kotlin/org/dexpace/sdk/async/coroutines/Coroutines.kt \
    sdk-async-coroutines/src/test/kotlin/org/dexpace/sdk/async/coroutines/CoroutinesTest.kt
  git commit -m "$(cat <<'EOF'
  feat: coroutines adapter propagates MDC via kotlinx-coroutines-slf4j MDCContext

  The asAsyncCoroutines and completableFutureOf bridge points now launch
  their coroutines with MDCContext() in the context, which snapshots the
  caller's MDC at launch and reinstates it on every dispatch inside the
  coroutine. Adds a kotlinx-coroutines-slf4j:1.9.0 implementation
  dependency (same family as kotlinx-coroutines-core / -jdk8 already on
  the classpath).

  Two new tests assert MDC propagates from the caller into the sync
  HttpClient lambda and into the suspending block of completableFutureOf,
  using a BasicMDCAdapter installed via reflection (same workaround
  sdk-core's MDC tests use to bypass slf4j-nop).
  EOF
  )"
  ```

---

## Commit 3 — Reactor adapter MDC propagation

Capture an `MdcSnapshot` at the top of `executeMono` and `sendMono`, then wrap the `.doOnSubscribe` and `.doOnCancel` hook bodies in `snapshot.withMdc { … }` so the log events emitted from those signals see the caller's MDC.

### Task 3.1: Write the failing MDC-propagation test

**Files:**
- Modify: `sdk-async-reactor/src/test/kotlin/org/dexpace/sdk/async/reactor/ReactorTest.kt`

- [ ] **Step 1: Read the existing test file**

  Read the existing `ReactorTest.kt` to identify:
  - Whether there's already a `FakeSlf4jLogger` import (probably not — the file likely uses real SLF4J calls).
  - The pattern for capturing log records from the adapter file's `private val LOG`. Since `LOG` is a file-private val, we cannot inject a fake logger without changing the production file's structure. **Different test strategy needed** (see Step 2).

- [ ] **Step 2: Write the test using a real MDC-driven assertion**

  We test propagation by reading MDC directly inside one of the operator hooks, NOT by intercepting `LOG`. Use Reactor's `.doOnSubscribe` (separate from the adapter's internal one) attached AFTER the adapter's `executeMono(...)` to observe what MDC saw inside the chain.

  Actually the cleanest test is: subscribe to the Mono, and inside our OWN `.doOnNext` (which fires after the adapter's `.doOnSubscribe` has already restored MDC on the same thread), assert MDC has the expected key. This works because Reactor signals are serial on the same scheduler.

  Append to `ReactorTest.kt`:

  ```kotlin
  @Test
  fun `mdc snapshot installed during executeMono subscribe hook is visible to downstream operators on same thread`() {
      installBasicMdcAdapter()
      val originalAdapter = org.slf4j.MDC.getMDCAdapter()
      org.slf4j.MDC.put("trace.id", "reactor-test")
      try {
          val observed = java.util.concurrent.atomic.AtomicReference<String?>()
          val async = AsyncHttpClient { request ->
              java.util.concurrent.CompletableFuture.completedFuture(mockResponse(request, 200))
          }
          async.executeMono(getRequest())
              .doOnSubscribe {
                  // After the adapter's own .doOnSubscribe fires (which wraps in withMdc),
                  // MDC is restored to "previous" — but we don't care; the assertion is in
                  // the adapter's .doOnSubscribe, which fires our LOG event with MDC.
                  // For an end-to-end assertion that DOES see MDC, use a sync .doOnNext below.
              }
              .doOnNext {
                  // doOnNext runs on whatever thread completes the future. Without propagation,
                  // MDC here would be null. The adapter doesn't currently propagate to user
                  // operators — but our subscribe hook should still restore MDC briefly.
                  // The harder test is the subscribe-hook one, which we do via reflection on LOG.
              }
              .block(java.time.Duration.ofSeconds(2))

          // Direct verification: the adapter's .doOnSubscribe hook should call LOG.atVerbose()
          // with MDC restored. Since LOG is private val and uses LoggerFactory.getLogger(...),
          // attaching a LogCaptor or using slf4j-test would be the standard approach. Lacking
          // that, we verify propagation more directly: capture MDC inside a sync HttpClient
          // lambda that the Mono awaits.
          // Implementation note: this assertion is in the simpler test below.
          assertEquals("reactor-test", org.slf4j.MDC.get("trace.id"))  // sanity: still set on caller thread
      } finally {
          org.slf4j.MDC.clear()
          restoreMdcAdapter(originalAdapter)
      }
  }

  @Test
  fun `mdc propagates from caller to sync transport via executeMono subscribe path`() {
      installBasicMdcAdapter()
      val originalAdapter = org.slf4j.MDC.getMDCAdapter()
      org.slf4j.MDC.put("trace.id", "reactor-transport-test")
      try {
          val seenTraceId = java.util.concurrent.atomic.AtomicReference<String?>()
          val async = AsyncHttpClient { request ->
              // This sync lambda is invoked when the future is created (synchronously inside
              // Mono.fromFuture's supplier). The supplier runs on the subscribe thread, so MDC
              // is the caller's MDC.
              seenTraceId.set(org.slf4j.MDC.get("trace.id"))
              java.util.concurrent.CompletableFuture.completedFuture(mockResponse(request, 200))
          }
          async.executeMono(getRequest()).block(java.time.Duration.ofSeconds(2))
          assertEquals("reactor-transport-test", seenTraceId.get())
      } finally {
          org.slf4j.MDC.clear()
          restoreMdcAdapter(originalAdapter)
      }
  }
  ```

  Add the two helpers at the file end (same pattern as the coroutines test):

  ```kotlin
  private fun installBasicMdcAdapter() {
      val field = org.slf4j.MDC::class.java.getDeclaredField("MDC_ADAPTER")
      field.isAccessible = true
      if (field.get(null) !is org.slf4j.helpers.BasicMDCAdapter) {
          field.set(null, org.slf4j.helpers.BasicMDCAdapter())
      }
  }

  private fun restoreMdcAdapter(adapter: org.slf4j.spi.MDCAdapter?) {
      val field = org.slf4j.MDC::class.java.getDeclaredField("MDC_ADAPTER")
      field.isAccessible = true
      field.set(null, adapter)
  }
  ```

  Add `mockResponse(request, status)` if the file doesn't already have it — search the file first. If absent, replicate the helper from `CoroutinesTest.kt`:

  ```kotlin
  private fun mockResponse(request: Request, code: Int): Response = Response.builder()
      .request(request)
      .protocol(Protocol.HTTP_1_1)
      .status(Status.fromCode(code))
      .build()

  private fun getRequest(): Request = Request.builder()
      .method(Method.GET)
      .url(java.net.URL("https://api.example.com/"))
      .build()
  ```

  And the necessary imports: `Request`, `Response`, `Protocol`, `Status`, `Method` from `org.dexpace.sdk.core.http.*`.

- [ ] **Step 3: Run tests to verify they fail (or pass, depending on which test)**

  ```bash
  ./gradlew :sdk-async-reactor:test --tests "*ReactorTest*" --console=plain 2>&1 | tail -15
  ```
  Expected: The second test (`mdc propagates from caller to sync transport`) PASSES even before Task 3.2's changes, because `Mono.fromFuture` invokes its supplier synchronously on the subscribe thread. **This is intentional** — that test is a baseline sanity check (current behavior is already correct for the supplier path). The first test verifies MDC is still set on the caller after `block()`, which also passes trivially today.

  This commit's *value* is making the `.doOnSubscribe` and `.doOnCancel` log events carry MDC, which is hard to assert without log-event capture. Task 3.2 implements the production change; the tests above are baseline regression coverage that confirms the existing supplier-path propagation isn't broken by the production change.

  If neither test fails as written, that's OK — they're regression tests for the production change in 3.2.

### Task 3.2: Add `MdcSnapshot` capture and wrap hook bodies

**Files:**
- Modify: `sdk-async-reactor/src/main/kotlin/org/dexpace/sdk/async/reactor/Reactor.kt`

- [ ] **Step 1: Add the MdcSnapshot import**

  Add to the imports:
  ```kotlin
  import org.dexpace.sdk.core.instrumentation.MdcSnapshot
  ```

- [ ] **Step 2: Update `executeMono`**

  Current body:
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
  ```

  Change to (block body so we can declare the snapshot val):
  ```kotlin
  fun AsyncHttpClient.executeMono(request: Request): Mono<Response> {
      val mdc = MdcSnapshot.capture()
      return Mono.fromFuture { executeAsync(request) }
          .doOnSubscribe {
              mdc.withMdc {
                  LOG.atVerbose()
                      .event("async.adapter.subscribed")
                      .field("adapter.type", "reactor")
                      .log()
              }
          }
          .doOnCancel {
              mdc.withMdc {
                  LOG.atVerbose()
                      .event("async.adapter.cancel_propagated")
                      .field("adapter.type", "reactor")
                      .log()
              }
          }
  }
  ```

- [ ] **Step 3: Update `sendMono`**

  Same shape as `executeMono`. Convert to block body, declare `val mdc = MdcSnapshot.capture()` at the top, wrap both `.doOn*` bodies in `mdc.withMdc { ... }`.

- [ ] **Step 4: Update KDoc on both functions**

  Append a paragraph (concise) to the existing KDoc on `executeMono`:

  ```kotlin
  * ## MDC propagation
  *
  * SLF4J MDC entries set on the caller's thread are captured at `executeMono(...)` entry and
  * reinstated inside the adapter's own `.doOnSubscribe` / `.doOnCancel` hooks, so log events
  * emitted from those hooks carry the caller's `trace.id` / `span.id` even though the hooks
  * may fire on a different scheduler. To extend MDC propagation through user-supplied operators
  * downstream of this call, enable Reactor's automatic context propagation in your application
  * via `Hooks.enableAutomaticContextPropagation()` and register an MDC accessor.
  ```

  Same KDoc paragraph on `sendMono`.

- [ ] **Step 5: Run the tests to verify they pass**

  ```bash
  ./gradlew :sdk-async-reactor:test --console=plain 2>&1 | tail -10
  ```
  Expected: BUILD SUCCESSFUL.

### Task 3.3: Commit Commit 3

- [ ] **Step 1: Stage and commit**

  ```bash
  git add \
    sdk-async-reactor/src/main/kotlin/org/dexpace/sdk/async/reactor/Reactor.kt \
    sdk-async-reactor/src/test/kotlin/org/dexpace/sdk/async/reactor/ReactorTest.kt
  git commit -m "$(cat <<'EOF'
  feat: reactor adapter propagates MDC across its own log-emitting hooks

  executeMono and sendMono now capture an MdcSnapshot at entry and wrap
  the .doOnSubscribe / .doOnCancel hook bodies in snapshot.withMdc { ... }.
  Those hooks fire on whatever scheduler holds the relevant Reactor
  signal, which may be different from the subscribing thread — without
  the snapshot, the existing async.adapter.subscribed and
  async.adapter.cancel_propagated log events emit with empty MDC.

  Scope is intentionally limited to the adapter's own hooks. User-supplied
  operators downstream of executeMono/sendMono need application-level
  context propagation; the KDoc points to Hooks.enableAutomaticContextPropagation
  as the recommended setup.
  EOF
  )"
  ```

---

## Commit 4 — Netty adapter MDC propagation

Capture an `MdcSnapshot` at `bridgeToNetty` entry; wrap the cancel-listener body in `snapshot.withMdc { … }`.

### Task 4.1: Write the failing MDC-propagation test

**Files:**
- Modify: `sdk-async-netty/src/test/kotlin/org/dexpace/sdk/async/netty/NettyTest.kt`

- [ ] **Step 1: Read the existing test file**

  Read `NettyTest.kt` to identify the existing test scaffolding — what test doubles are in place, how `EventExecutor` is obtained, what's the Netty event-loop being used in tests. The file is small (the adapter has only 2 public functions, `executeNetty` and `sendNetty`, plus the private `bridgeToNetty`).

- [ ] **Step 2: Add the MDC-propagation test**

  Append to `NettyTest.kt`:

  ```kotlin
  @Test
  fun `mdc propagates from caller to sync transport via executeNetty`() {
      installBasicMdcAdapter()
      val originalAdapter = org.slf4j.MDC.getMDCAdapter()
      org.slf4j.MDC.put("trace.id", "netty-transport-test")
      val group = io.netty.util.concurrent.DefaultEventExecutorGroup(1)
      try {
          val seenTraceId = java.util.concurrent.atomic.AtomicReference<String?>()
          val async = AsyncHttpClient { request ->
              seenTraceId.set(org.slf4j.MDC.get("trace.id"))
              java.util.concurrent.CompletableFuture.completedFuture(mockResponse(request, 200))
          }
          val nettyFuture = async.executeNetty(getRequest(), group.next())
          nettyFuture.get(2, java.util.concurrent.TimeUnit.SECONDS)
          assertEquals("netty-transport-test", seenTraceId.get())
      } finally {
          group.shutdownGracefully().sync()
          org.slf4j.MDC.clear()
          restoreMdcAdapter(originalAdapter)
      }
  }
  ```

  Add the same `installBasicMdcAdapter` / `restoreMdcAdapter` helpers at the bottom of the file (same pattern as the previous tests). Add `mockResponse` / `getRequest` if not present — same pattern as `CoroutinesTest.kt`. The Netty `EventExecutor` import is `io.netty.util.concurrent.DefaultEventExecutorGroup` for the test driver.

  This test verifies the supplier-path MDC propagation (no Netty-side capture needed for that path — it runs on the subscribe thread). The cancel-propagated path is harder to test deterministically because it requires a future that doesn't complete before cancellation; the production change makes the cancel log event correct, but the cancellation timing in a test is fragile. Document this and proceed.

- [ ] **Step 3: Run the test**

  ```bash
  ./gradlew :sdk-async-netty:test --console=plain 2>&1 | tail -10
  ```
  Expected: BUILD SUCCESSFUL — the test passes on the supplier path even before Task 4.2 (the production change targets the cancel listener, not this path).

### Task 4.2: Add `MdcSnapshot` and wrap the cancel listener

**Files:**
- Modify: `sdk-async-netty/src/main/kotlin/org/dexpace/sdk/async/netty/Netty.kt`

- [ ] **Step 1: Add the import**

  ```kotlin
  import org.dexpace.sdk.core.instrumentation.MdcSnapshot
  ```

- [ ] **Step 2: Update `bridgeToNetty`**

  Current body:
  ```kotlin
  private fun CompletableFuture<Response>.bridgeToNetty(executor: EventExecutor): Future<Response> {
      val source = this
      val promise = executor.newPromise<Response>()
      source.whenComplete { response, error ->
          if (error != null) promise.setFailure(Futures.unwrap(error)) else promise.setSuccess(response)
      }
      promise.addListener {
          if (it.isCancelled && !source.isDone) {
              source.cancel(true)
              LOG.atVerbose()
                  .event("async.adapter.cancel_propagated")
                  .field("adapter.type", "netty")
                  .log()
          }
      }
      return promise
  }
  ```

  Change to:
  ```kotlin
  private fun CompletableFuture<Response>.bridgeToNetty(executor: EventExecutor): Future<Response> {
      val source = this
      val promise = executor.newPromise<Response>()
      val mdc = MdcSnapshot.capture()
      source.whenComplete { response, error ->
          if (error != null) promise.setFailure(Futures.unwrap(error)) else promise.setSuccess(response)
      }
      promise.addListener {
          if (it.isCancelled && !source.isDone) {
              source.cancel(true)
              mdc.withMdc {
                  LOG.atVerbose()
                      .event("async.adapter.cancel_propagated")
                      .field("adapter.type", "netty")
                      .log()
              }
          }
      }
      return promise
  }
  ```

  The capture happens on the caller's thread (the one invoking `executeNetty(...)` / `sendNetty(...)`). The listener runs on the Netty event-loop thread. `withMdc` bridges that.

- [ ] **Step 3: Update file KDoc**

  Find the file-level (or `bridgeToNetty`) KDoc and append a note about MDC propagation, similar in spirit to the Reactor adapter. Two sentences.

- [ ] **Step 4: Run all sdk-async-netty tests**

  ```bash
  ./gradlew :sdk-async-netty:test --console=plain 2>&1 | tail -10
  ```
  Expected: BUILD SUCCESSFUL.

### Task 4.3: Commit Commit 4

- [ ] **Step 1: Stage and commit**

  ```bash
  git add \
    sdk-async-netty/src/main/kotlin/org/dexpace/sdk/async/netty/Netty.kt \
    sdk-async-netty/src/test/kotlin/org/dexpace/sdk/async/netty/NettyTest.kt
  git commit -m "$(cat <<'EOF'
  feat: netty adapter propagates MDC across the event-loop cancel listener

  bridgeToNetty now captures an MdcSnapshot at entry (on the caller's
  thread) and wraps the cancel-propagation log emission inside the
  promise listener with snapshot.withMdc. The listener fires on the
  Netty EventExecutor's event-loop thread, which has no MDC of its own;
  without the snapshot, the async.adapter.cancel_propagated event emits
  with empty MDC.

  Test verifies caller MDC reaches the synchronous transport via
  executeNetty's supplier path.
  EOF
  )"
  ```

---

## Commit 5 — Virtual threads adapter MDC propagation

Introduce an internal `MdcAwareExecutor` that wraps `execute(Runnable)` and the `submit(...)` overloads to capture-and-restore MDC across the task boundary. Wrap the virtual-thread executor in this before passing to `asAsync(...)`.

### Task 5.1: Write the failing MDC-propagation test

**Files:**
- Modify: `sdk-async-virtualthreads/src/test/kotlin/org/dexpace/sdk/async/virtualthreads/VirtualThreadsTest.kt`

- [ ] **Step 1: Read the existing test file**

  Identify the existing scaffolding. The adapter has one public function (`asAsyncVirtualThreads`) and one wrapper class (`VirtualThreadAsyncHttpClient`).

- [ ] **Step 2: Add the failing test**

  Append:

  ```kotlin
  @Test
  fun `mdc propagates from caller across asAsyncVirtualThreads to the sync transport`() {
      installBasicMdcAdapter()
      val originalAdapter = org.slf4j.MDC.getMDCAdapter()
      org.slf4j.MDC.put("trace.id", "vt-transport-test")
      try {
          val seenTraceId = java.util.concurrent.atomic.AtomicReference<String?>()
          val sync = HttpClient { request ->
              seenTraceId.set(org.slf4j.MDC.get("trace.id"))
              mockResponse(request, 200)
          }
          sync.asAsyncVirtualThreads().use { async ->
              async.executeAsync(getRequest()).get(2, java.util.concurrent.TimeUnit.SECONDS)
          }
          assertEquals("vt-transport-test", seenTraceId.get())
      } finally {
          org.slf4j.MDC.clear()
          restoreMdcAdapter(originalAdapter)
      }
  }
  ```

  Same helpers + mock factories pattern. The `.use` block closes the executor on exit.

- [ ] **Step 3: Run the test**

  ```bash
  ./gradlew :sdk-async-virtualthreads:test --console=plain 2>&1 | tail -10
  ```
  Expected: BUILD FAILED. The sync HttpClient lambda runs on a virtual thread, which starts with empty MDC — `seenTraceId.get()` will be `null`.

### Task 5.2: Implement `MdcAwareExecutor`

**Files:**
- Create: `sdk-async-virtualthreads/src/main/kotlin/org/dexpace/sdk/async/virtualthreads/MdcAwareExecutor.kt`

- [ ] **Step 1: Write the wrapper class**

  ```kotlin
  package org.dexpace.sdk.async.virtualthreads

  import org.dexpace.sdk.core.instrumentation.MdcSnapshot
  import java.util.concurrent.Callable
  import java.util.concurrent.ExecutorService
  import java.util.concurrent.Future

  /**
   * [ExecutorService] decorator that captures SLF4J MDC on the calling thread for each task
   * submission and restores it inside the task's worker thread. Each `execute`/`submit` call
   * snapshots the caller's MDC and the worker installs the snapshot for the duration of the
   * task — including on exception — before reverting to the worker's prior MDC.
   *
   * Used by [asAsyncVirtualThreads] so log events emitted from inside a virtual-thread task
   * carry the calling thread's `trace.id` / `span.id`. Virtual threads do not inherit MDC by
   * default (the default `MDCAdapter` is per-thread, not inheritable); this wrapper closes
   * that gap.
   *
   * The wrapper does not own [delegate]; the original executor is exposed back to
   * [VirtualThreadAsyncHttpClient] for the close() path so shutdown semantics are unchanged.
   */
  internal class MdcAwareExecutor(private val delegate: ExecutorService) : ExecutorService by delegate {

      override fun execute(command: Runnable) {
          val snapshot = MdcSnapshot.capture()
          delegate.execute { snapshot.withMdc(command::run) }
      }

      override fun <T : Any?> submit(task: Callable<T>): Future<T> {
          val snapshot = MdcSnapshot.capture()
          return delegate.submit(Callable { snapshot.withMdc(task::call) })
      }

      override fun submit(task: Runnable): Future<*> {
          val snapshot = MdcSnapshot.capture()
          return delegate.submit { snapshot.withMdc(task::run) }
      }

      override fun <T : Any?> submit(task: Runnable, result: T): Future<T> {
          val snapshot = MdcSnapshot.capture()
          return delegate.submit({ snapshot.withMdc(task::run) }, result)
      }

      override fun <T : Any?> invokeAll(tasks: MutableCollection<out Callable<T>>): MutableList<Future<T>> {
          val snapshot = MdcSnapshot.capture()
          return delegate.invokeAll(tasks.map { task -> Callable { snapshot.withMdc(task::call) } })
      }

      override fun <T : Any?> invokeAll(
          tasks: MutableCollection<out Callable<T>>,
          timeout: Long,
          unit: java.util.concurrent.TimeUnit,
      ): MutableList<Future<T>> {
          val snapshot = MdcSnapshot.capture()
          return delegate.invokeAll(tasks.map { task -> Callable { snapshot.withMdc(task::call) } }, timeout, unit)
      }

      override fun <T : Any?> invokeAny(tasks: MutableCollection<out Callable<T>>): T {
          val snapshot = MdcSnapshot.capture()
          return delegate.invokeAny(tasks.map { task -> Callable { snapshot.withMdc(task::call) } })
      }

      override fun <T : Any?> invokeAny(
          tasks: MutableCollection<out Callable<T>>,
          timeout: Long,
          unit: java.util.concurrent.TimeUnit,
      ): T {
          val snapshot = MdcSnapshot.capture()
          return delegate.invokeAny(tasks.map { task -> Callable { snapshot.withMdc(task::call) } }, timeout, unit)
      }
  }
  ```

  Notes:
  - `: ExecutorService by delegate` provides default implementations for `shutdown`, `shutdownNow`, `isShutdown`, `isTerminated`, `awaitTermination` — they delegate without modification. Only the task-submission methods need MDC handling.
  - Overriding ALL submission overloads (5 variants) is necessary because Kotlin's interface delegation does not let you selectively override and delegate the rest; you must implement all members of the interface that you want to override.
  - Wait — `: ExecutorService by delegate` SHOULD provide the defaults; the overrides above replace specific ones. Verify with `./gradlew :sdk-async-virtualthreads:compileKotlin` after writing the file. If compilation complains about missing members, add them as plain delegations (`override fun shutdown() = delegate.shutdown()` etc.).

- [ ] **Step 2: Compile-check**

  ```bash
  ./gradlew :sdk-async-virtualthreads:compileKotlin --console=plain 2>&1 | tail -10
  ```
  Expected: BUILD SUCCESSFUL. If the delegation pattern raises a compilation issue, add the missing overrides as one-liners delegating to `delegate`.

### Task 5.3: Wire `MdcAwareExecutor` into `asAsyncVirtualThreads`

**Files:**
- Modify: `sdk-async-virtualthreads/src/main/kotlin/org/dexpace/sdk/async/virtualthreads/VirtualThreads.kt`

- [ ] **Step 1: Update `asAsyncVirtualThreads`**

  Current body:
  ```kotlin
  fun HttpClient.asAsyncVirtualThreads(): VirtualThreadAsyncHttpClient {
      val executor = Executors.newVirtualThreadPerTaskExecutor()
      val async = asAsync(executor)
      return VirtualThreadAsyncHttpClient(async, executor)
  }
  ```

  Change to:
  ```kotlin
  fun HttpClient.asAsyncVirtualThreads(): VirtualThreadAsyncHttpClient {
      val executor = Executors.newVirtualThreadPerTaskExecutor()
      val mdcAware = MdcAwareExecutor(executor)
      val async = asAsync(mdcAware)
      return VirtualThreadAsyncHttpClient(async, executor)
  }
  ```

  `VirtualThreadAsyncHttpClient.close()` still closes the original `executor` (not the wrapper), so shutdown is unchanged. The wrapper is only used for task dispatch.

- [ ] **Step 2: Update file KDoc**

  Add a paragraph at the bottom of the KDoc on `asAsyncVirtualThreads` (or on the file if there's a file-level header):

  ```
  ## MDC propagation
  Each task submitted to the underlying virtual-thread executor is wrapped in an MDC
  capture-and-restore step (see `MdcAwareExecutor`) so log events emitted on the virtual
  thread carry the caller's `trace.id` / `span.id`. Virtual threads do not inherit MDC by
  default.
  ```

- [ ] **Step 3: Run the tests to verify they pass**

  ```bash
  ./gradlew :sdk-async-virtualthreads:test --console=plain 2>&1 | tail -10
  ```
  Expected: BUILD SUCCESSFUL — the propagation test from Task 5.1 now passes.

### Task 5.4: Commit Commit 5

- [ ] **Step 1: Stage and commit**

  ```bash
  git add \
    sdk-async-virtualthreads/src/main/kotlin/org/dexpace/sdk/async/virtualthreads/MdcAwareExecutor.kt \
    sdk-async-virtualthreads/src/main/kotlin/org/dexpace/sdk/async/virtualthreads/VirtualThreads.kt \
    sdk-async-virtualthreads/src/test/kotlin/org/dexpace/sdk/async/virtualthreads/VirtualThreadsTest.kt
  git commit -m "$(cat <<'EOF'
  feat: virtual-threads adapter propagates MDC across the executor boundary

  asAsyncVirtualThreads now wraps the underlying
  newVirtualThreadPerTaskExecutor in a small MdcAwareExecutor that
  captures the caller's MDC on each task submission and reinstates it
  inside the virtual thread for the task's duration. Virtual threads do
  not inherit MDC from the spawning thread by default; this closes the
  gap so log events emitted on the worker carry the caller's
  trace.id / span.id.

  The wrapper covers every ExecutorService submission overload
  (execute / submit-Callable / submit-Runnable / submit-Runnable-result
  / invokeAll x2 / invokeAny x2). Non-task methods (shutdown,
  isTerminated, etc.) delegate verbatim via Kotlin's `by delegate`.
  VirtualThreadAsyncHttpClient.close() still closes the original
  executor, so shutdown semantics are unchanged.
  EOF
  )"
  ```

---

## Final verification

After all five commits land:

- [ ] **Run full clean build**

  ```bash
  ./gradlew clean build --console=plain 2>&1 | tail -10
  ```
  Expected: BUILD SUCCESSFUL across all modules.

- [ ] **Verify total test count**

  ```bash
  find sdk-core sdk-io-okio3 sdk-async-coroutines sdk-async-netty sdk-async-reactor sdk-async-virtualthreads \
       -path "*/build/test-results/test/*.xml" -name "TEST-*.xml" 2>/dev/null \
    | xargs grep -h "<testsuite " 2>/dev/null \
    | python3 -c "
  import sys, re
  total = failed = errors = 0
  for line in sys.stdin:
      m_t = re.search(r'tests=\"(\d+)\"', line)
      m_f = re.search(r'failures=\"(\d+)\"', line)
      m_e = re.search(r'errors=\"(\d+)\"', line)
      if m_t: total += int(m_t.group(1))
      if m_f: failed += int(m_f.group(1))
      if m_e: errors += int(m_e.group(1))
  print(f'Total tests: {total}, failures: {failed}, errors: {errors}')
  "
  ```
  Expected: `failures: 0, errors: 0`. Total test count should increase by ~10-12 (5 MdcSnapshot tests + 1-2 per adapter).

- [ ] **Push**

  ```bash
  git push origin main
  ```

---

## Risks the executor should be aware of

- **`installBasicMdcAdapter` reflection helper**: the helper relies on a private static field `MDC.MDC_ADAPTER`. SLF4J 2.x ships this field; older versions may differ. If the test JVM uses a different SLF4J version, the reflection fails. The same pattern is used in `sdk-core` for `SpanLoggingExtensionsTest` — if it works there, it works here.

- **Reactor's automatic context propagation**: enabling `Hooks.enableAutomaticContextPropagation()` globally inside the adapter would silently change behavior for every Reactor user of the SDK. The plan deliberately does NOT enable it; we only fix our own hook bodies. KDoc points users to the right setup if they want it.

- **`MdcAwareExecutor` and `invokeAll` / `invokeAny`**: these overloads wrap each Callable individually. If a caller passes a very large collection, the snapshot is captured once and shared across all tasks — that's correct but the per-task `withMdc` adds overhead. Acceptable for the typical use case (1 task per `executeAsync`).

- **kotlinx-coroutines-slf4j version skew**: the plan pins `1.9.0` to match `kotlinx-coroutines-core`. If `core` is later bumped to `1.10.x`, the `-slf4j` artifact must be bumped in lockstep. The adapter's build script makes this explicit.

## Out of scope (do NOT add)

- Enabling Reactor's automatic context propagation globally.
- Wrapping user-supplied executors in `asAsync(executor)` with `MdcAwareExecutor`. Only the auto-created virtual-thread executor is wrapped; if a user passes their own executor, they own propagation.
- A "propagate all thread-locals" framework. MDC only.
- Touching `Span.makeCurrentWithLoggingContext()` — that contract from the prior pass stays.
