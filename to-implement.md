# To-Implement — Design Decisions & Pending Items

Working document for tracking architectural decisions and the implementation roadmap. Paired with `azure-comp-report.md` — that report is the analysis; this is the decision log.

## Status legend

- ✅ **Agreed** — design is settled; ready to implement.
- 🛠️ **Implemented** — already shipped in the codebase.
- 🔍 **Under review** — described in `azure-comp-report.md`, awaiting decision.
- ❌ **Declined** — explicitly will not adopt.
- ⏸️ **Deferred** — agreed in principle, intentionally postponed.

---

## Section 0 — Universal implementation guardrails

These conventions apply to **every** rank. Per-rank guardrails only list additions beyond these defaults.

### Codestyle (per `CLAUDE.md`)

- **`sdk-core` targets Java 8 bytecode** (`jvmTarget = JVM_1_8`). Avoid `InputStream.transferTo` (9+), `Thread.threadId` (19+), `java.net.http.HttpClient` (11+), records, sealed `permits`, switch expressions, pattern matching, `List.of` / `Map.of` factories (9+), `String.repeat`/`isBlank` (11+), `Stream.toList` (16+), text blocks.
- **`sdk-io-okio3` and every adapter module** also targets Java 8 bytecode (jvmToolchain = 8). The SDK must be installable on JDK 8 consumers.
- **`ReentrantLock` over `synchronized`** — `lock.withLock { … }`. Required for virtual-thread compatibility (synchronized pins the carrier).
- **`AtomicBoolean.compareAndSet(false, true)`** for fail-fast "consumed-once" guards. `@Volatile` alone is not sufficient.
- **`internal` visibility** for impl types in adapter modules; the public API stays narrow.
- **`@JvmOverloads`** on public constructors / factory methods that have default-argument parameters. **`@JvmStatic`** on companion-object methods called from Java.
- **`@Throws(IOException::class)`** on every public function that can throw `IOException`. Same for `EOFException`, `InterruptedIOException`, `NoSuchFileException` where applicable.
- **`kotlin.test`** for unit tests (already configured in `sdk-io-okio3`). New modules add `testImplementation(kotlin("test"))`.
- **Comments only when *why* is non-obvious.** No "what" comments. No trailing TODOs without a tracking link.
- **Commit style:** `chore:` prefix for refactors, `feat:` for new features, `fix:` for bug fixes. Body explains *why*, not *what*.

### Performance defaults

- **Iterative loops over recursion** in any code bounded by user input (retry attempts, redirect hops, paged iteration). Stack-safe and lower allocation.
- **No allocation on disabled-level / no-op paths.** Instrumentation hooks (`ClientLogger.atVerbose()`, `Meter.counter().add()`, `NoopSpan`) return shared singletons that no-op every chained method. Verified by tests that allocate-then-call on a disabled level and assert zero work happens.
- **Cache `Stage.entries`** instead of calling `Stage.values()` repeatedly. Kotlin 1.9+ provides `Stage.entries` which doesn't clone on each access.
- **Intern strings** with a bounded vocabulary — `HttpHeaderName`, `HttpMethod`. Saves allocations on repeated lookups; enables identity equality for hot paths.
- **Reuse no-op singletons** for `LoggingEvent.NOOP`, `NoopSpan`, `NoopMeter`, `NoopInstrumentationContext`.
- **Hot-path strings**: avoid `String` concatenation in pipeline / per-request paths. Use `StringBuilder` with reserved capacity, or pre-compute and cache (`"Bearer ${token}"` cached on the AuthStep).
- **Pre-compute regex Patterns** at class init (`Pattern.compile`); never per-call.

### Allocation reductions

- **No new object pools** beyond Okio's segment pool. Don't introduce per-class pools.
- **`@JvmInline value class`** for typed wrappers (`HttpRange`, `ETag`) where the value is a single primitive/String.
- **Avoid auto-boxing** in metrics paths: `LongCounter.add(Long, …)` not `add(Number, …)`. Pass `Map<String, Any>` for attributes only when non-empty; otherwise pass `emptyMap()` shared singleton.
- **`Sequence`/`Iterator` over intermediate `List`** for lazy consumers (paging, SSE).
- **Lazy initialization** for fields only used on certain paths: `by lazy(LazyThreadSafetyMode.NONE)` if the owning class is documented not-thread-safe; `SYNCHRONIZED` otherwise.
- **`Buffer.MAX_BYTE_ARRAY_SIZE` (`Int.MAX_VALUE - 8`)** is the ceiling for any single `ByteArray`. Snapshot / read APIs check this and throw `IllegalStateException` with an actionable message pointing at streaming alternatives.

### Non-happy paths (universal)

- **Every blocking call respects `Thread.interrupt()`** (per Rank 22). Catches `InterruptedException`, calls `Thread.currentThread().interrupt()` to preserve interrupt status, throws `InterruptedIOException` or the operation's natural failure exception with the interrupt added as suppressed.
- **Resource cleanup** is `try { … } finally { resource.close() }` or `resource.use { … }`. `close()` on an already-closed resource is a no-op everywhere.
- **Single-use bodies** fail loudly on second `writeTo` (`IllegalStateException` with `toReplayable()` pointer) — never silent zero-byte sends.
- **Drain errors are surfaced, not swallowed.** `LoggableResponseBody` already caches drain exceptions; new long-running drain code must do the same.
- **Exception wrapping**: don't wrap `IOException` unless reporting through a non-IO API surface; propagate directly. Use `addSuppressed` for accumulated retry exceptions, not nested wrapping.
- **HTTPS-only checks** on credential steps throw `IllegalStateException` at policy entry, before any wire write. Message names the step type and the offending scheme.
- **Bounds checks** at construction time, not first use. `FileRequestBody`, `HttpRange`, `ETag`, `HttpRange` all fail-fast.
- **Logging in error paths**: log at the level that matches the action the caller should take. `atError` for things requiring action; `atWarning` for transient / retryable; `atVerbose` for everything else. Never log the same error at multiple levels.

### Concurrency model

- **Pipelines and steps are constructed once, then immutable** during request processing. The `HttpPipelineBuilder` is mutable; `HttpPipeline` is not.
- **Per-request state** (`PipelineCallState`, `LoggableResponseBody.captured`, `BearerTokenAuthStep.cachedToken`) is guarded as documented per step.
- **Steps are NOT required to be thread-safe within a single request** (one request flows through sequentially). Steps **ARE** required to be thread-safe across requests (multiple concurrent requests share the same step instance).
- **`next.copy()` is the only safe way to re-invoke downstream more than once.** Stateful steps (`RetryStep`, `RedirectStep`) call it once per attempt; never reuse the original `PipelineNext`.
- **Step construction may not perform I/O.** `HttpPipelineBuilder.build()` returns immediately; no network calls until `pipeline.send(request)`.

### Testing conventions

- **Use `FakeHttpClient` + `RequestRecorder` (Rank 26)** for pipeline tests. Avoid `Thread.sleep` in tests — use `FixedClock` (Rank 21) and assert on expected delays via the clock advance.
- **Tests for fail-fast guards**: assert specific exception type AND message keyword (so future refactors don't accidentally change the failure mode).
- **Race tests** for atomic consumed-checks: spawn 2-N threads, gate with `CountDownLatch`, assert exactly one succeeds.
- **Allocation tests** for no-alloc paths: use JMH or `Counter`-based assertion to confirm `atVerbose().field(...).log()` allocates nothing when verbose is disabled.

---

## Session handoff (read first in a fresh session)

**Status as of last working session**: Phase A is **implemented, tested, but NOT YET COMMITTED**. Working tree has Phase A changes uncommitted alongside the planning docs (`azure-comp-report.md`, `to-implement.md`). All 106 tests across both modules pass (`./gradlew build` is green).

**Immediate next actions for a fresh session**:
1. Verify current state: `git status` should show ~4 modified files + 4 deleted placeholder files + new untracked `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/`, `util/Clock.kt`, `src/test/`, `src/testFixtures/`, plus untracked `to-implement.md` and `azure-comp-report.md`.
2. Run `./gradlew build` to confirm green (expected: 106 tests pass).
3. Commit Phase A in two commits:
   - Planning docs: `git add azure-comp-report.md to-implement.md` → `chore: add Azure SDK comparison report and Phase A-H implementation roadmap`
   - Phase A code: stage everything except `java-sdk.iml`, `pr.diff`, `sdk-core/src/main/java/`, planning docs. Commit as `chore: implement pipeline framework, Clock, and test infrastructure (Phase A)`.
4. Do **not** push — wait for user instruction.
5. Then proceed with **Phase B** (see Section 6).

**User preference**: use **local subagents (in-process Agent tool calls)** for max parallel work when implementing each phase. The Phase A implementation used 3 parallel agents (pipeline / Clock+testFixtures / docs) and that worked well.

**Phase A deviations from spec to note**:
- `HttpStep` is a regular `interface`, not a `fun interface`. Kotlin's `fun interface` cannot have abstract properties (`val stage: Stage`), so the spec was technically unrealizable. Lambda-style construction still works via object literals.
- `Clock.SYSTEM` annotated `@JvmField` for Java interop (avoids `Clock.Companion.getSYSTEM()` from Java).
- `FixedClock.sleep(duration)` advances `current` without real blocking (intentional — fast tests).
- `MockResponse.Builder.body(String, MediaType?)` resolves `Io.provider` lazily (so a Builder can be constructed before any provider is installed; tests that read the body install `OkioIoProvider` in `@BeforeTest`).
- Test infrastructure module wiring: `sdk-core/build.gradle.kts` now applies `java-test-fixtures` plugin; tests depend on `testFixtures(project(":sdk-core"))` and `project(":sdk-io-okio3")` for `OkioIoProvider`.
- Obsolete pipeline placeholders deleted: `RequestPipeline.kt`, `ResponsePipeline.kt`, `ExecutionPipeline.kt`, `BuilderPipeline.kt`. The unidirectional `PipelineStep<T,V>` family in `pipeline/step/` was kept (different abstraction).

---

## Section 1 — Already implemented (foundation)

These are committed and tested (commit `692c07c`); listed for context.

| Concern | Status | Notes |
|---|---|---|
| `IoProvider` SPI + `Io.installProvider` (no ServiceLoader) | 🛠️ | Explicit install with idempotent same-instance, throw on conflict |
| `sdk-io-okio3` adapter module on `jvmToolchain(8)` | 🛠️ | JDK 8 consumers can use the published bytecode; compile-time refuses Java 9+ APIs |
| `Source`/`Sink`/`BufferedSource`/`BufferedSink`/`Buffer` contracts | 🛠️ | HTTP-pragmatic surface; no NIO inheritance |
| `Buffer.MAX_BYTE_ARRAY_SIZE` + bounds-checked `snapshot()` | 🛠️ | |
| `RequestBody.isReplayable` + `toReplayable(provider)` | 🛠️ | Default impl drains via `writeTo` into a buffer |
| Factories: byte array, string, buffer, mark-supporting InputStream, file, form data | 🛠️ | |
| `FileRequestBody` with `FileChannel.transferTo` | 🛠️ | Transports can type-check it for `sendfile(2)` |
| `LoggableRequestBody` with `TeeSink` (single-encode, segment-share) | 🛠️ | |
| `LoggableResponseBody` with eager-drain + peek replay + cached drain error | 🛠️ | |
| `AtomicBoolean` consumed-check on one-shot bodies (race-free fail-fast) | 🛠️ | |
| Kotlin 2.3.21, modernized root `build.gradle.kts` | 🛠️ | |
| **Pipeline framework** (`HttpStep`, `HttpPipeline`, `HttpPipelineBuilder`, `PipelineNext`, `PipelineCallState`, `Stage`) | 🛠️ Phase A (uncommitted) | Hybrid stage + type-based API; 14 tests |
| **Clock abstraction** (`Clock`, `SystemClock`, `Clock.SYSTEM`) | 🛠️ Phase A (uncommitted) | 7 tests |
| **Test infrastructure** (`FakeHttpClient`, `MockResponse`, `RequestRecorder`, `FixedClock` in `testFixtures`) | 🛠️ Phase A (uncommitted) | 16 tests |
| **Cancellation convention docs** (in `docs/architecture.md`, `CLAUDE.md`, `docs/pipelines.md`) | 🛠️ Phase A (uncommitted) | Docs only |
| **java-test-fixtures plugin** on `sdk-core` | 🛠️ Phase A (uncommitted) | |
| **Deleted placeholders**: `RequestPipeline`, `ResponsePipeline`, `ExecutionPipeline`, `BuilderPipeline` | 🛠️ Phase A (uncommitted) | Unused; replaced by `HttpPipeline` |

---

## Section 2 — Agreed design decisions

### 2.0 Naming conventions & contribution guide

We diverge from Azure's `*Policy` naming and use `*Step` to match our existing codebase (`PipelineStep<T, V>`, `RequestPipelineStep`, `ResponsePipelineStep`).

#### Translation table (Azure → ours)

| Azure | Ours | Notes |
|---|---|---|
| `HttpPipelinePolicy` | `HttpStep` | Bidirectional pipeline item; distinct from the existing unidirectional `PipelineStep<T, V>` |
| `HttpPipelineNextPolicy` | `PipelineNext` | Chain accessor passed to `process()` |
| `HttpPipelineCallState` | `PipelineCallState` | Internal state cloned by `next.copy()` |
| `HttpCredentialPolicy` / `HttpAuthPolicy` | `AuthStep` | Pillar base at `Stage.AUTH` |
| `OAuthBearerTokenAuthenticationPolicy` | `BearerTokenAuthStep` | Concrete OAuth bearer impl |
| `KeyCredentialPolicy` | `KeyCredentialAuthStep` | Concrete static-key impl |
| `HttpRetryPolicy` | `RetryStep` | Pillar at `Stage.RETRY` |
| `HttpRedirectPolicy` | `RedirectStep` | Pillar at `Stage.REDIRECT` |
| `HttpInstrumentationPolicy` | `InstrumentationStep` | Pillar at `Stage.LOGGING` |
| `UserAgentPolicy` | `UserAgentStep` | |
| `RequestIdPolicy` | `RequestIdStep` | |
| `SetDatePolicy` | `SetDateStep` | |
| `AddHeadersPolicy` | `AddHeadersStep` | |
| `HttpPipeline` | `HttpPipeline` | Kept; matches our `HttpClient` naming |
| `HttpPipelineBuilder` | `HttpPipelineBuilder` | Kept |
| `ChallengeHandler` | `ChallengeHandler` | **Kept** — not a step. It's a strategy plugged into `AuthStep` and `ProxyOptions`. |
| `BasicChallengeHandler` / `DigestChallengeHandler` | unchanged | Concrete strategies |
| `HttpRetryOptions` / `HttpRedirectOptions` / `HttpInstrumentationOptions` | unchanged | Builder-style options carriers; keep `Http*Options` prefix for discoverability |
| `HttpRetryCondition` / `HttpRedirectCondition` | unchanged | Data classes describing the decision input |
| `AuthScheme` / `AuthMetadata` | unchanged | Per-request metadata; not steps |

**Narrative wording**: "step" is preferred over "policy" when describing our design. "Policy" is reserved for references to Azure's specific class names in the comp report.

#### Suffix conventions (what to name new things)

| Type of thing | Suffix / shape | Examples |
|---|---|---|
| Bidirectional pipeline item that goes in `HttpPipeline` | `*Step` | `RetryStep`, `UserAgentStep`, custom `MyCacheStep` |
| Concrete pillar implementation | `Default*Step` | `DefaultRetryStep`, `DefaultRedirectStep` |
| Specialised auth step | `*AuthStep` | `KeyCredentialAuthStep`, `BearerTokenAuthStep` |
| Strategy / handler injected into a step | `*Handler` | `ChallengeHandler`, `BasicChallengeHandler` |
| Configuration carrier for a step | `*Options` | `HttpRetryOptions`, `HttpRedirectOptions` |
| Decision input passed to a predicate | `*Condition` | `HttpRetryCondition`, `HttpRedirectCondition` |
| Typed value (HTTP semantics, single field) | bare noun, `@JvmInline value class` | `HttpRange`, `ETag` |
| Composite value (multiple fields) | bare noun, regular `class` | `RequestConditions`, `Credential` (sealed) |
| Abstract instrumentation primitive | bare noun, `interface` | `Tracer`, `Span`, `Meter`, `Clock`, `LongCounter` |
| Default no-op instrumentation primitive | `Noop*` | `NoopSpan`, `NoopMeter`, `NoopCounter` |
| Adapter module concrete class | `<Library>*` prefix | `OkioBuffer`, `OkioBufferedSink`, `OkioIoProvider` |
| Adapter module internal helper | `Foreign*` / `Wrapper*` (inside `internal/` package) | `ForeignSourceAdapter`, `ForeignSinkAdapter` |
| Utility singleton | bare noun, `object` | `Uuids`, `UrlRedactor`, `RetryUtils`, `DateTimeRfc1123` |
| Test fixture | `Fake*` / `Mock*` / `*Recorder` | `FakeHttpClient`, `MockResponse`, `RequestRecorder` |

**Don't use:**
- `*Policy` — reserved for Azure's class names in the comp report.
- `*Manager` / `*Helper` / `*Util` (singular) — too vague. Use a concrete role-based name.
- `*Impl` suffix — Kotlin doesn't need it. Use `Default*` or a descriptive name.
- `Abstract*` prefix — Kotlin's `abstract` modifier already says it.
- Verb-based names for classes (e.g., `Authenticate`) — use `AuthStep` or `Authenticator`.

#### How to introduce a new pipeline step — walkthrough

A step author follows this sequence. **All conventions inherit Section 0; this is the per-step recipe.**

**Step 1: Pick a stage.**

Decision flow:

```
Is this step a pillar (only one should exist; "retry-like" semantically)?
├── Yes → Subclass the pillar base (RetryStep, RedirectStep, AuthStep, InstrumentationStep).
│        Don't put your step in Stage.RETRY etc. directly; the pillar bases own those slots.
└── No → Pick a non-pillar stage based on what your step needs to observe:
         - Run BEFORE auth header is stamped?  → Stage.PRE_AUTH
         - See the auth-stamped request?       → Stage.POST_AUTH
         - Run once per retry attempt (fresh)? → Stage.POST_RETRY
         - Run once total, even with retries?  → Stage.PRE_RETRY
         - Log / instrument?                   → Stage.POST_LOGGING
         - Touch the bytes about to hit wire?  → Stage.PRE_SEND
```

**Step 2: Implement the step class.**

```kotlin
package org.dexpace.sdk.core.http.pipeline.steps

/**
 * Sets a `X-Foo-Header` on every request. Runs once per send (not per attempt).
 *
 * Thread-safety: stateless after construction — safe to share across concurrent requests.
 * Cancellation: blocking-free; no interrupt handling needed.
 */
class FooHeaderStep(private val value: String) : HttpStep {
    override val stage: Stage = Stage.PRE_AUTH

    @Throws(IOException::class)
    override fun process(request: Request, next: PipelineNext): Response {
        request.headers.set(HttpHeaderName.fromString("X-Foo-Header"), value)
        return next.process()
    }
}
```

Required conventions:
- `override val stage` — explicit, not inferred. Compiler-checked against the pillar base if extending one.
- `@Throws(IOException::class)` on `process` — surfaces the checked exception to Java callers.
- KDoc has **three required lines**: what the step does, thread-safety (stateless / per-request-only / requires synchronization), cancellation (blocking-free / interrupt-respecting / N/A).
- Step lives in `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/pipeline/steps/` for built-in steps. User steps live wherever the user wants.

**Step 3: Options pattern (if the step is configurable).**

```kotlin
class FooHeaderStep @JvmOverloads constructor(
    private val options: FooHeaderOptions = FooHeaderOptions(),
) : HttpStep { /* ... */ }

class FooHeaderOptions @JvmOverloads constructor(
    val value: String = "default",
    val overwriteExisting: Boolean = false,
)
```

`@JvmOverloads` on the constructor enables Java's `new FooHeaderStep()`, `new FooHeaderStep(opts)`. Options class is immutable; if Java callers need fluent construction, add a nested `Builder`.

**Step 4: Concurrency declaration (if the step is stateful).**

```kotlin
class RateLimitStep(...) : HttpStep {
    override val stage = Stage.PRE_SEND
    private val tokenBucket = TokenBucket(...)   // guarded by its own ReentrantLock

    @Throws(IOException::class)
    override fun process(request: Request, next: PipelineNext): Response {
        tokenBucket.acquire()    // may block; respects interrupts per Rank 22
        return next.process()
    }
}
```

KDoc must explicitly state: thread-safe via the lock; blocking acquires respect interrupts.

**Step 5: Re-invoking downstream (`next.copy()`).**

```kotlin
class RepeatStep(private val times: Int) : HttpStep {
    override val stage = Stage.POST_RETRY

    @Throws(IOException::class)
    override fun process(request: Request, next: PipelineNext): Response {
        var last: Response = next.copy().process()
        for (i in 1 until times) {
            last.close()                       // release previous attempt's body
            last = next.copy().process()       // fresh state for the next attempt
        }
        return last
    }
}
```

Never re-use the same `PipelineNext` across multiple invocations; always `copy()`.

**Step 6: Add the step to the pipeline.**

```kotlin
val pipeline = HttpPipelineBuilder(httpClient)
    .append(FooHeaderStep("hello"))                  // primary API; respects step's declared stage
    .build()

// Surgical insertion relative to a known type:
val customised = HttpPipelineBuilder.from(pipeline)
    .insertAfter<FooHeaderStep>(BarStep())
    .build()
```

**Step 7: Test using `FakeHttpClient` + `RequestRecorder` (Rank 26).**

```kotlin
@Test
fun `FooHeaderStep stamps X-Foo-Header on every request`() {
    val client = FakeHttpClient().enqueue { status(200) }
    val pipeline = HttpPipelineBuilder(client)
        .append(FooHeaderStep("hello"))
        .build()

    pipeline.send(Request.builder().url("https://api.example.com").method(Method.GET).build())

    val captured = client.requests.single()
    assertEquals("hello", captured.headers["X-Foo-Header"])
}
```

**Step 8: Document in `docs/pipelines.md`.**

Add a one-line entry to the "Built-in steps" table with: name, stage, what-it-does, prerequisites, when-to-use.

#### Module layout (where things go)

```
sdk-core/src/main/kotlin/org/dexpace/sdk/core/
├── http/
│   ├── client/                       HttpClient SPI (existing)
│   ├── common/                       Headers, MediaType, HttpRange, ETag, RequestConditions, HttpHeaderName
│   ├── context/                      CallContext → DispatchContext → RequestContext → ExchangeContext
│   ├── pipeline/
│   │   ├── HttpPipeline.kt           Pipeline runtime
│   │   ├── HttpPipelineBuilder.kt    Builder with stage + type-based API
│   │   ├── HttpStep.kt               Bidirectional step interface
│   │   ├── PipelineNext.kt           Chain accessor with copy()
│   │   ├── Stage.kt                  Enum
│   │   ├── PipelineCallState.kt      Internal state (`internal` visibility)
│   │   └── steps/                    Built-in step concretes
│   │       ├── RetryStep.kt
│   │       ├── RedirectStep.kt
│   │       ├── AuthStep.kt
│   │       ├── KeyCredentialAuthStep.kt
│   │       ├── BearerTokenAuthStep.kt
│   │       ├── InstrumentationStep.kt
│   │       ├── UserAgentStep.kt
│   │       ├── RequestIdStep.kt
│   │       ├── SetDateStep.kt
│   │       └── AddHeadersStep.kt
│   ├── request/                      Request, RequestBody, FileRequestBody, LoggableRequestBody (existing)
│   ├── response/                     Response, ResponseBody, LoggableResponseBody, HttpResponseException (existing)
│   └── auth/                         Credential, KeyCredential, BearerToken, BearerTokenProvider,
│                                     ChallengeHandler, BasicChallengeHandler, DigestChallengeHandler,
│                                     AuthScheme, AuthMetadata, AuthChallengeParser, AuthenticateChallenge
├── instrumentation/
│   ├── ClientLogger.kt               Structured logger facade
│   ├── LoggingEvent.kt
│   ├── LogLevel.kt
│   ├── UrlRedactor.kt
│   ├── tracing/                      Tracer, Span, TracingScope, NoopSpan (existing abstractions)
│   └── metrics/                      Meter, LongCounter, DoubleHistogram, NoopMeter
├── io/                               Source, Sink, BufferedSource, BufferedSink, Buffer,
│                                     IoProvider, Io, TeeSink (existing)
├── config/                           Configuration, ConfigurationBuilder
├── util/
│   ├── Clock.kt
│   ├── Uuids.kt
│   ├── DateTimeRfc1123.kt
│   ├── RetryUtils.kt
│   ├── ProxyOptions.kt
│   └── ExpandableEnum.kt
└── generics/                         Builder (existing)

sdk-core/src/testFixtures/kotlin/org/dexpace/sdk/core/testing/
├── FakeHttpClient.kt
├── MockResponse.kt
├── RequestRecorder.kt
└── FixedClock.kt                     Lives in testFixtures so consumers can use it too

sdk-io-okio3/src/main/kotlin/org/dexpace/sdk/io/        (existing — unchanged)
sdk-instrumentation-otel/                                 (future — per 5.2 resolution)
```

Rules:
- **`steps/` package holds only concrete step implementations.** `HttpStep` interface itself lives in `pipeline/`.
- **Auth code groups into `http/auth/`** rather than scattering credentials, challenge handlers, and auth steps across packages.
- **`util/` is for stateless utility singletons.** `object`-shaped, single-responsibility.
- **Tests of `sdk-core` go in `src/test/`.** Tests consumers can REUSE go in `src/testFixtures/` (`java-test-fixtures` Gradle plugin).
- **No `impl/` or `internal/` packages in `sdk-core`** — use the `internal` Kotlin modifier instead. Adapter modules (`sdk-io-okio3`) use `internal/` as a folder name AND `internal` modifier together for emphasis.

#### Anti-patterns to avoid

| Don't | Do | Why |
|---|---|---|
| Extend `HttpStep` directly for an auth/retry/redirect/instrumentation step | Extend the pillar base class (`AuthStep` etc.) | Pillar bases lock the stage at the type level and prevent miswiring |
| Network I/O in step constructors | Defer I/O to `process()` | `HttpPipelineBuilder.build()` must return immediately |
| Catch `InterruptedException` without re-interrupting | Catch, call `Thread.currentThread().interrupt()`, then re-throw as `InterruptedIOException` (with original as suppressed) | Loses cancellation signal otherwise |
| `synchronized(this)` for state guards | `ReentrantLock` with `lock.withLock { … }` | `synchronized` pins virtual-thread carriers |
| Sleep / busy-wait without a `Clock` | Inject `clock: Clock = Clock.SYSTEM` and use `clock.sleep(...)` | Tests need to fast-forward |
| `field("k", v)` with `field(String, Any)` when `v` is a primitive | Use the primitive overload `field(String, Long/Int/Boolean)` | Boxing on every log line |
| Re-use the same `PipelineNext` for multiple downstream invocations | Call `next.copy()` per attempt | State (current index) advances forward; reusing produces wrong chain position |
| `MutableList<T>` exposed in a public API | `List<T>` (immutable view) with a private `MutableList<T>` backing | Callers shouldn't mutate SDK internal state |
| Throwing `RuntimeException` for documented HTTP failures | Throw `HttpResponseException` with `isRetryable` set | Retry policy needs to classify |
| Public types in adapter modules | `internal class` in `sdk-io-okio3` | Only `OkioIoProvider` is public; everything else is internal |
| Hard-coded `Stage` ordinals (`100`, `200`) in API | Use the `Stage` enum constants; ordinals are internal only | Magic numbers are unreadable |
| `String` for ETag / range / auth-scheme | Typed value (`ETag`, `HttpRange`, `AuthScheme`) | Construction-time validation; compile-time type safety |
| Allocating a `HashMap` for log key-value pairs on every call | Lazy-allocate on first `field()`; reuse `LoggingEvent.NOOP` for disabled levels | Hot-path allocations |
| Custom thread pools inside a step | Use the calling thread; pipelines are synchronous | Concurrency model is per-request, not per-step |

#### Conventions for non-step components

**Utility singletons (`object Uuids`, `object UrlRedactor`):**
- Stateless. No mutable fields.
- `internal object` if not part of public API; `object` (public) otherwise.

**Abstract instrumentation primitives (`Tracer`, `Meter`, `Clock`):**
- `interface` with `companion object` for shared singletons (`Clock.SYSTEM`).
- Default no-op impl in `Noop*` object.
- Real impl lives in adapter module (`sdk-instrumentation-otel`).
- Never reference concrete OTel types from `sdk-core`.

**Adapter modules (`sdk-io-okio3`, future `sdk-instrumentation-otel`):**
- One public type per module: the provider/factory (`OkioIoProvider`).
- Concrete adapter classes are `internal class`.
- Adapter packages mirror the abstract interface packages (`org.dexpace.sdk.io` mirrors `org.dexpace.sdk.core.io`).
- Adapter targets the same JDK 8 toolchain as `sdk-core`.

**Typed values (`HttpRange`, `ETag`):**
- `@JvmInline value class` when single-field.
- `companion object` with `@JvmStatic` factories (`HttpRange.bytes(...)`, `ETag.strong(...)`).
- `parse(raw: String)` factory for round-trip from header strings.
- `toHeaderValue(): String` (or `toString()`) for emit.
- All validation at construction; never lazy.

**Strategies / handlers (`ChallengeHandler`, `BearerTokenProvider`):**
- `interface` (or `fun interface` if single-method).
- Concrete impls named after the strategy (`BasicChallengeHandler`, `CachingBearerTokenProvider`).
- Composable via `static of(...)` factory where it makes sense.

#### Worked example: a `RateLimitStep`

End-to-end view applying every convention together.

```kotlin
package org.dexpace.sdk.core.http.pipeline.steps

import org.dexpace.sdk.core.http.pipeline.HttpStep
import org.dexpace.sdk.core.http.pipeline.PipelineNext
import org.dexpace.sdk.core.http.pipeline.Stage
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.util.Clock
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Throttles outbound requests to a configurable rate via token-bucket. Blocks when the
 * bucket is empty, drains as tokens refill.
 *
 * Thread-safety: safe to share across concurrent requests; guards the bucket with a
 * [ReentrantLock]. Multiple callers may block here simultaneously.
 *
 * Cancellation: blocking acquires respect [Thread.interrupt] per the SDK convention.
 * Interrupted acquires throw [InterruptedIOException] with the original [InterruptedException]
 * suppressed; thread-interrupt status is preserved.
 */
class RateLimitStep @JvmOverloads constructor(
    private val options: RateLimitOptions,
    private val clock: Clock = Clock.SYSTEM,
) : HttpStep {

    override val stage: Stage = Stage.PRE_SEND

    private val lock = ReentrantLock()
    private val tokens = AtomicLong(options.bucketCapacity)
    private var lastRefillNanos: Long = clock.monotonic()

    @Throws(IOException::class)
    override fun process(request: Request, next: PipelineNext): Response {
        acquire()
        return next.process()
    }

    @Throws(InterruptedIOException::class)
    private fun acquire() {
        lock.withLock {
            while (true) {
                refill()
                if (tokens.get() > 0) {
                    tokens.decrementAndGet()
                    return
                }
                val wait = nextRefillDelay()
                try {
                    clock.sleep(wait)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("RateLimitStep acquire interrupted").apply {
                        addSuppressed(interrupted)
                    }
                }
            }
        }
    }

    // refill() and nextRefillDelay() omitted for brevity
}

class RateLimitOptions @JvmOverloads constructor(
    val rate: Double = 10.0,                         // requests per second
    val bucketCapacity: Long = 10L,                  // burst budget
    val refillInterval: Duration = Duration.ofMillis(100),
)
```

What this demonstrates:
1. **Stage choice**: `PRE_SEND` — runs once per attempt, but after retry/auth (retry's exponential backoff runs before our rate limit). Adjustable per intent.
2. **Pillar?** No — rate limiting isn't a singleton concept like auth; users could chain multiple rate limiters at different scopes (per-host, global). Non-pillar.
3. **Options pattern**: separate `RateLimitOptions` class with `@JvmOverloads`.
4. **Concurrency**: explicitly documented (thread-safe via `ReentrantLock`).
5. **Cancellation**: explicitly handles `InterruptedException` per Rank 22.
6. **Clock injection**: `Clock.SYSTEM` default, `FixedClock` for tests.
7. **Codestyle**: `@Throws`, `@JvmOverloads`, narrow `internal` visibility on helpers (`refill`, `nextRefillDelay` are `private`).
8. **No I/O in constructor**: bucket state initialised in fields; no network/disk call on `RateLimitStep(...)`.

Tests would use `FakeHttpClient` + `FixedClock` to assert that consecutive requests are gated correctly without real `Thread.sleep`.

#### Review checklist (for a new step PR)

Before merging a new step:

- [ ] Class name follows the suffix convention (`*Step` / `*AuthStep` / `Default*Step`).
- [ ] `override val stage` is explicit; matches the step's intent.
- [ ] If extending a pillar base, the stage is `final` (inherited via `final override` in the base).
- [ ] `@Throws(IOException::class)` on `process` (and any other checked-exception annotation as needed).
- [ ] KDoc states **what / thread-safety / cancellation** explicitly.
- [ ] Stateful steps name their concurrency primitive (lock / atomic / volatile).
- [ ] No network or disk I/O in the constructor.
- [ ] No `synchronized` (use `ReentrantLock.withLock`).
- [ ] If the step sleeps or measures time, it takes a `Clock = Clock.SYSTEM` parameter.
- [ ] If the step has options, they're in a separate `*Options` class with `@JvmOverloads`.
- [ ] If the step re-invokes downstream, it uses `next.copy()`.
- [ ] If the step is meant to be a pillar singleton, it extends the pillar base (not `HttpStep` directly).
- [ ] Tests use `FakeHttpClient` + `RequestRecorder`; no `Thread.sleep` in tests (use `FixedClock`).
- [ ] Race test added if the step has shared mutable state.
- [ ] Allocation test for any no-alloc claim (e.g., disabled-level logging).
- [ ] Documented in `docs/pipelines.md` "Built-in steps" table.
- [ ] No public types beyond what the user actually constructs (use `internal` aggressively).

### 2.1 Pipeline architecture (hybrid: stages + type-based operations)

**Decision**: replace the placeholder `RequestPipeline` with a hybrid model. **Stage-based API as primary** (semantic intent), **type-based operations as surgical secondary** (relative-to-existing edits). Both target the same underlying ordered list.

#### Stages

```kotlin
/**
 * Pipeline stage. Lower [order] runs first (closer to caller entry); higher [order] runs
 * later (closer to wire send). Pillar stages hold exactly one policy each; non-pillar stages
 * hold an ordered deque of user policies.
 */
enum class Stage(val order: Int, val isPillar: Boolean) {

    // -- Wrapping policies (re-invoke downstream via next.copy()) --
    REDIRECT      (100,  true ),    // pillar: RedirectStep singleton
    POST_REDIRECT (150,  false),    // runs *inside* the redirect loop, per hop
    RETRY         (200,  true ),    // pillar: RetryStep singleton
    POST_RETRY    (250,  false),    // runs *inside* the retry loop, per attempt

    // -- Auth --
    PRE_AUTH      (300,  false),    // request not yet authenticated
    AUTH          (400,  true ),    // pillar: AuthStep singleton
    POST_AUTH     (500,  false),    // request now carries auth header

    // -- Instrumentation --
    PRE_LOGGING   (600,  false),    // bytes not yet captured
    LOGGING       (700,  true ),    // pillar: InstrumentationStep singleton
    POST_LOGGING  (800,  false),    // body wrapped in Loggable{Request,Response}Body

    // -- Serialization / send --
    PRE_SERDE     (900,  false),    // body still typed object (if applicable)
    SERDE         (1000, true ),    // pillar: body-to-bytes (currently unused; future-proof slot)
    POST_SERDE    (1100, false),    // body now a byte stream
    PRE_SEND      (1200, false),    // last hop before the wire
    SEND          (1300, true ),    // terminal: HttpClient.execute (not a user-policy slot)
}
```

Sparse `order` values (100s apart) leave room to insert new stages later without renumbering.

#### Policy interface

Every policy declares its own stage at the type level:

```kotlin
fun interface HttpStep {
    fun process(request: Request, next: PipelineNext): Response
    val stage: Stage
}

// Pillar base classes lock the stage in — can't be miswritten
abstract class RedirectStep        : HttpStep { final override val stage = Stage.REDIRECT }
abstract class RetryStep           : HttpStep { final override val stage = Stage.RETRY }
abstract class AuthStep            : HttpStep { final override val stage = Stage.AUTH }
abstract class InstrumentationStep : HttpStep { final override val stage = Stage.LOGGING }

// User policies pick a non-pillar stage
class UserAgentStep(val userAgent: String) : HttpStep {
    override val stage = Stage.PRE_AUTH
    override fun process(request: Request, next: PipelineNext): Response { /* ... */ }
}
```

#### `PipelineNext` with `copy()`

Required for retry/redirect to re-invoke the downstream chain:

```kotlin
class PipelineNext internal constructor(private val state: PipelineCallState) {

    fun process(): Response {
        val nextPolicy = state.advance()
        return if (nextPolicy == null) state.httpClient.execute(state.request)
               else nextPolicy.process(state.request, this)
    }

    /** Returns a fresh next-chain rooted at the same position. Used by retry/redirect. */
    fun copy(): PipelineNext = PipelineNext(state.copy())
}

internal class PipelineCallState(
    val pipeline: HttpPipeline,
    val request: Request,
    val httpClient: HttpClient,
    private var index: Int = 0,
) {
    fun advance(): HttpStep? =
        if (index < pipeline.policies.size) pipeline.policies[index++] else null

    fun copy(): PipelineCallState = PipelineCallState(pipeline, request, httpClient, index)
}
```

#### Builder API (hybrid)

```kotlin
class HttpPipelineBuilder(private val httpClient: HttpClient) {

    private val perStage: MutableMap<Stage, ArrayDeque<HttpStep>> = mutableMapOf()
    private val pillars: MutableMap<Stage, HttpStep> = mutableMapOf()
    private val logger = ClientLogger(HttpPipelineBuilder::class)

    // ---- Primary API: stage-based ----

    /** Append at the tail of [policy]'s stage deque (runs after policies already there). */
    fun append(policy: HttpStep): HttpPipelineBuilder = apply {
        if (policy.stage.isPillar) installPillar(policy)
        else perStage.getOrPut(policy.stage) { ArrayDeque() }.addLast(policy)
    }

    /** Prepend at the head of [policy]'s stage deque (runs before policies already there). */
    fun prepend(policy: HttpStep): HttpPipelineBuilder = apply {
        if (policy.stage.isPillar) installPillar(policy)
        else perStage.getOrPut(policy.stage) { ArrayDeque() }.addFirst(policy)
    }

    // ---- Surgical API: type-based ----

    /** Insert [policy] immediately after the first instance of [T] in the pipeline. */
    inline fun <reified T : HttpStep> insertAfter(policy: HttpStep): HttpPipelineBuilder {
        val flat = flattenedView()
        val idx = flat.indexOfFirst { it is T }
        require(idx >= 0) { "No ${T::class.simpleName} in pipeline" }
        return reinsertAt(idx + 1, policy, flat)
    }

    /** Insert [policy] immediately before the first instance of [T] in the pipeline. */
    inline fun <reified T : HttpStep> insertBefore(policy: HttpStep): HttpPipelineBuilder {
        val flat = flattenedView()
        val idx = flat.indexOfFirst { it is T }
        require(idx >= 0) { "No ${T::class.simpleName} in pipeline" }
        return reinsertAt(idx, policy, flat)
    }

    /** Replace the first instance of [T] with [policy]. */
    inline fun <reified T : HttpStep> replace(policy: HttpStep): HttpPipelineBuilder { /* … */ }

    /** Remove every instance of [T] from the pipeline. */
    inline fun <reified T : HttpStep> remove(): HttpPipelineBuilder { /* … */ }

    // ---- Build ----

    fun build(): HttpPipeline {
        val ordered = Stage.values().asSequence()
            .filter { it != Stage.SEND }
            .flatMap { stage ->
                if (stage.isPillar) listOfNotNull(pillars[stage]).asSequence()
                else (perStage[stage] ?: emptyList()).asSequence()
            }
            .toList()
        return HttpPipeline(httpClient, ordered)
    }

    private fun installPillar(policy: HttpStep) {
        check(policy.stage != Stage.SEND) { "SEND is the terminal HttpClient — not a policy slot" }
        val existing = pillars[policy.stage]
        if (existing != null && existing !== policy) {
            logger.atWarning()
                .event("pipeline.pillar.replaced")
                .field("stage", policy.stage)
                .field("previous", existing::class.simpleName)
                .field("replacement", policy::class.simpleName)
                .log()
        }
        pillars[policy.stage] = policy
    }
}
```

#### Usage patterns

```kotlin
// Common path: stage-based add. Order is implied by each policy's declared stage.
val pipeline = HttpPipelineBuilder(httpClient)
    .append(RedirectStep())                         // pillar → REDIRECT
    .append(RetryStep(maxRetries = 3))              // pillar → RETRY
    .append(SetDateStep())                              // policy.stage = POST_RETRY (fresh per attempt)
    .append(UserAgentStep("MyApp/1.0"))                 // policy.stage = PRE_AUTH
    .append(KeyCredentialAuthStep(credential))              // pillar → AUTH
    .append(InstrumentationStep(logOptions))        // pillar → LOGGING
    .build()

// Surgical edit: take a default pipeline and drop instrumentation for tests.
val testPipeline = HttpPipelineBuilder.from(defaultPipeline)
    .remove<InstrumentationStep>()
    .insertAfter<RetryStep>(TestFailureInjectionPolicy())
    .build()

// Custom auth replacement
val pipeline = HttpPipelineBuilder(httpClient)
    .append(defaultPolicies)
    .replace<AuthStep>(MyCustomAuthPolicy(...))
    .build()
```

#### Semantics worth pinning down

- **`POST_RETRY` runs per attempt, `PRE_AUTH` runs once total.** Choose the stage carefully:
  - `RequestIdStep` at `POST_RETRY` → fresh UUID per attempt (server-side dedupe).
  - `RequestIdStep` at `PRE_AUTH` → same UUID across all attempts (client-side correlation).
- **`POST_REDIRECT` runs per redirect hop.** Same logic: use it for things that must observe the post-redirect URL.
- **Pillar singleton enforcement**: installing a second pillar at the same stage replaces the first and emits a warning event (`pipeline.pillar.replaced`). Same-instance re-install is idempotent.
- **`SEND` is the terminal `HttpClient.execute`** — cannot accept user policies. The builder rejects installation at `SEND`.
- **`SERDE` is currently unused** but reserved for future typed-body conversion (e.g., if we ever add annotation-based interface clients).
- **`PipelineNext.copy()` is mandatory** for any policy that re-invokes downstream — used by retry and redirect, available to any custom policy.

#### What this beats Azure on

1. **Stage names read directly** — `POST_AUTH` vs `AFTER_AUTHENTICATION` is the same idea but tighter.
2. **Explicit prepend/append within a stage** — Azure forces "add in the right order"; we let users say which side of existing steps to insert on.
3. **Surgical type-based ops** — `insertAfter<X>`, `replace<X>`, `remove<X>`. Azure has no equivalent.
4. **Pillar enforcement at the type level** — `RetryStep.stage = final override Stage.RETRY`. Users can't accidentally write a fake retry-pillar step.
5. **No magic numbers in the public surface** — only the internal `order: Int` for sorting.

#### Implementation guardrails

**Edge cases**
- **Empty pipeline** (no steps installed): `HttpPipeline.send(request)` calls `HttpClient.execute(request)` directly. No `PipelineNext` allocation needed.
- **Same pillar instance re-installed**: idempotent (same reference). Different instance at same pillar stage: replaces with warning event `pipeline.pillar.replaced`.
- **Step short-circuits** (doesn't call `next.process()`): legal — returns a synthetic `Response` without invoking downstream. Used by circuit-breaker / mock-injection patterns.
- **Step throws after receiving response from `next.process()`**: step must `response.close()` in its own `finally` if it received one before throwing.
- **`next.copy()` called multiple times in same step**: each `copy()` produces an independent `PipelineCallState`; safe (retry+redirect combined).
- **`insertAfter<T>(step)` with no `T` in pipeline**: `IllegalArgumentException("No <T> in pipeline")`. `remove<T>()` is no-op if nothing matches.
- **`insertAfter<T>(step)` with multiple `T` instances**: matches FIRST; `insertAfterLast<T>` variant deferred (rare need).

**Performance / allocations**
- `Array<HttpStep>` backing; never `List` iteration in `PipelineCallState.advance()`.
- `Stage.entries` cached at class init; never `values()` per `build()`.
- `HttpPipeline.send(request)` allocates exactly one `PipelineCallState` + one `PipelineNext` per call.
- `PipelineNext.copy()` is a single allocation (new state); the steps array is shared and immutable.
- Iterative `Stage` enumeration in `build()` (no `Sequence.flatMap` — avoids `SequenceScope` overhead).

**Non-happy paths**
- **Pillar at `Stage.SEND` rejected** at install time: `IllegalStateException("SEND is the terminal HttpClient — not a step slot")`.
- **Re-entry**: `pipeline.send(request)` called recursively from inside a step gets its own `PipelineCallState`; safe.
- **`Error` (OOM, StackOverflow)** propagates without retry/redirect — only `Exception` is intercepted by stateful steps.

**Codestyle**
- `HttpStep` is `fun interface` (lambda single-method construction).
- `HttpPipeline.steps` returns `List<HttpStep>` (unmodifiable view); backing is `Array<HttpStep>` internal.
- `Stage` enum carries explicit `order: Int` for stability across recompiles.
- `PipelineCallState` is `internal`; cloning exposed only via `PipelineNext.copy()`.

---

## Section 3 — Under review (from `azure-comp-report.md`)

Items ranked from that report, awaiting per-item decisions. Each has a status field, an effort estimate, and space for notes. Edit in place as you review.

### ✅ Rank 1 — `UrlRedactor`

- **What**: Allow-list-based query-parameter redactor; replaces all values except names in the list with `***`.
- **Why**: Prerequisite for any URL logging. Prevents SAS tokens / API keys leaking into logs.
- **Effort**: Small (one class, one test).
- **Status**: ✅ **Agreed** (from comp report §11.2).

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| Allow-list of query param names | ✅ Adopt — `Set<String>`, default `["api-version"]` |
| Redact non-allowed values to `***` | ✅ Adopt |
| Header redaction lives in `InstrumentationStep` options (not this class) | ✅ Adopt — match Azure's separation |

#### Kotlin shape

```kotlin
object UrlRedactor {
    /** Returns a URL string with values of disallowed query params replaced by `***`. */
    fun redact(url: URL, allowedQueryParams: Set<String> = DEFAULT_ALLOWED): String { /* ... */ }

    val DEFAULT_ALLOWED: Set<String> = setOf("api-version")
}
```

#### Implementation guardrails

**Edge cases**
- URL with no query string → return original `url.toString()` directly (no rebuild).
- URL with empty query string `?` → preserve `?` (some servers care).
- URL with multiple values for same key (`?api-version=1&api-version=2`) → if allow-listed, both preserved; otherwise both redacted.
- **URL with userinfo (`https://user:pass@host`) → ALWAYS redact userinfo** as `***:***@`. Userinfo leaks credentials more directly than query params; this is the #1 thing a URL redactor must handle.
- URL with fragment (`#...`) → preserve as-is (not transmitted on wire, but kept for log consistency).
- URL-encoded characters in keys → match allow-list against the DECODED name (so `api-version` allow-list matches both `api-version` and `api%2Dversion`).
- Allow-list contains case variations: case-insensitive match on query keys (HTTP query keys are case-sensitive per RFC but APIs in practice are lenient).
- Empty allow-list → redact ALL query param values.

**Performance / allocations**
- Fast-path: URL with no `?` and no userinfo → return `url.toString()` directly, no rebuild.
- Single `StringBuilder` with pre-computed capacity (URL length + small overhead) for rebuilt URLs.
- `Set<String>` allow-list lookup is O(1).
- Avoid `URL.getQuery()` if you can scan the string directly — `URL` parsing is heavier than needed.

**Non-happy paths**
- Malformed URL → return `"[malformed url]"` (don't throw — logging must never fail).
- Null URL → throw `NullPointerException` (Kotlin enforces; explicit `requireNotNull`).
- URL with non-standard scheme (e.g., `ws://`, `s3://`) → still redact query params; userinfo redaction applies.

**Codestyle**
- `object UrlRedactor` (singleton, no state).
- Default allow-list `setOf("api-version")` as a top-level `val DEFAULT_ALLOWED`.
- Java 8 compat — `URLEncoder.encode(value, "UTF-8")` overload, not the `Charset` one (Java 9+).
- `@JvmStatic` on companion utilities if added.

- **Notes**:

### ✅ Rank 2 — `ClientLogger` structured-logging facade

- **What**: Fluent `logger.atInfo().event("...").field("k", v).cause(t).log()` API with no-alloc disabled-level path.
- **Why**: Production observability standard; required by every policy that logs.
- **Effort**: Medium (`ClientLogger` + `LoggingEvent` + SLF4J `KeyValuePair` integration).
- **Status**: ✅ **Agreed** (from comp report §11.1).

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| `atError`/`atWarning`/`atInfo`/`atVerbose` entry points | ✅ Adopt |
| `.field(key, value)` for String, primitive, and Object values | ✅ Adopt |
| `.event(name)` for event categorization (searchability) | ✅ Adopt |
| `.cause(throwable)` for exception attachment | ✅ Adopt |
| `.context(InstrumentationContext)` for trace propagation | ✅ Adopt |
| Per-logger global context (constructor parameter) | ✅ Adopt |
| **`LoggingEvent.NOOP`** singleton for disabled levels — zero-allocation fast path | ✅ Adopt |
| SLF4J 2.x `KeyValuePair` bridge for structured output | ✅ Adopt (no new runtime dep) |
| Log level read from `Configuration` (Rank 9) | ✅ Adopt — landed alongside Rank 9 |

#### Kotlin shape

```kotlin
class ClientLogger(
    name: String,
    private val globalContext: Map<String, Any?> = emptyMap(),
) {
    fun atError(): LoggingEvent   = LoggingEvent.create(this, LogLevel.ERROR)
    fun atWarning(): LoggingEvent = LoggingEvent.create(this, LogLevel.WARNING)
    fun atInfo(): LoggingEvent    = LoggingEvent.create(this, LogLevel.INFO)
    fun atVerbose(): LoggingEvent = LoggingEvent.create(this, LogLevel.VERBOSE)
    fun canLog(level: LogLevel): Boolean
}

class LoggingEvent internal constructor(
    private val logger: ClientLogger?,
    private val level: LogLevel?,
    private val isEnabled: Boolean,
) {
    fun field(key: String, value: Any?): LoggingEvent
    fun event(name: String): LoggingEvent
    fun cause(t: Throwable?): LoggingEvent
    fun context(ctx: InstrumentationContext?): LoggingEvent
    fun log(message: String = "")

    companion object {
        /** Shared singleton for disabled levels. All builder methods return `this`; log() is a no-op. */
        val NOOP: LoggingEvent = LoggingEvent(null, null, isEnabled = false)
        internal fun create(logger: ClientLogger, level: LogLevel): LoggingEvent =
            if (logger.canLog(level)) LoggingEvent(logger, level, isEnabled = true) else NOOP
    }
}
```

#### Implementation guardrails

**Edge cases**
- `field(key, null)` → record as the literal string `"null"` (not skipped). Skipped fields look like bugs in log searches.
- `field` with `Throwable` value → render via `Throwable.message + class.simpleName`, not full stack (use `.cause(t)` for stacks).
- `field` with array/collection value → join as `[a, b, c]`, not the default `[Ljava.lang.Object;@deadbeef`.
- `event(name)` called twice → last write wins (rare, but document).
- `log()` called twice on same `LoggingEvent` instance → second call is no-op (`isEnabled = false` after first `log()`).
- Logger created with `globalContext = null` parameter → treat as empty map (don't NPE).
- Empty event name → log without event-name field (don't write `event=""`).
- Very large field value (multi-MB string) → truncate to 8 KiB by default with `…[truncated]` suffix; configurable.

**Performance / allocations**
- **`LoggingEvent.NOOP` is THE critical no-alloc path.** Verify with a JMH test: `logger.atVerbose().field("k", v).field("a", b).cause(ex).log()` with verbose disabled must allocate ZERO objects (no Map, no StringBuilder, no chained `LoggingEvent`).
- `atError/atWarning/atInfo/atVerbose` returns the NOOP singleton OR allocates one enabled `LoggingEvent`. Hot path is the disabled branch.
- Per-event field map: lazily allocated `LinkedHashMap` (preserves insertion order for log readability). Skip allocation until first `field()` call.
- **Primitive overloads to avoid boxing**: `field(String, Long)`, `field(String, Int)`, `field(String, Boolean)`, `field(String, Double)`. Not just `field(String, Any?)`.
- `globalContext` (per-logger) is `Map<String, Any?>` shared; never copied per event.
- SLF4J 2.x `KeyValuePair` bridge — emit via `LoggingEventBuilder` (allocation amortized per log call).

**Non-happy paths**
- Underlying SLF4J not on classpath → fall back to a `DefaultLogger` that writes to `System.err` (matches Azure's behavior).
- Null logger name in constructor → throw `IllegalArgumentException` with the field name.
- Null field key → throw `IllegalArgumentException`.
- `log()` while `LoggingEvent` is in NOOP state → silent no-op.
- Exception during field serialization (e.g., `value.toString()` throws) → catch internally; emit `field<key>=<error: serializer threw>` placeholder instead of failing the log call.

**Codestyle**
- `class ClientLogger` (final by default in Kotlin).
- `class LoggingEvent internal constructor(…)` — constructor is package-private; only `LoggingEvent.create()` and `NOOP` initialize it.
- `enum class LogLevel { ERROR, WARNING, INFO, VERBOSE }` — order matches verbosity.
- `@JvmOverloads` on `ClientLogger` constructors.
- `@JvmStatic` not applicable here (object methods are already statically dispatched on the companion).

- **Notes**:

### ✅ Rank 3 — `InstrumentationStep` (pillar at `LOGGING`)

- **What**: Wraps request/response in our existing `LoggableRequestBody`/`LoggableResponseBody`, emits `http.request`/`http.response` structured events, applies URL redaction and header allow-listing.
- **Why**: Makes the body-logging machinery we already built actually visible end-to-end.
- **Effort**: Medium. Depends on Rank 1, Rank 2, and Section 2.1 pipeline.
- **Status**: ✅ **Agreed** — implicit consequence of agreeing to §11 (which covers all building blocks: `UrlRedactor`, `ClientLogger`, `Tracer`/`Span` abstractions we already have). The step is the consumer of those.

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| Wrap request body in `LoggableRequestBody` when content logging enabled | ✅ Adopt |
| Wrap response body in `LoggableResponseBody` (lazy `LoggingResponse` wrapper) when content logging enabled | ✅ Adopt |
| Emit `http.request` and `http.response` structured events through `ClientLogger` | ✅ Adopt |
| `HttpLogLevel`: `NONE` / `HEADERS` / `BODY_AND_HEADERS` granularity | ✅ Adopt |
| `HttpInstrumentationOptions.allowedHeaderNames` allow-list (default ~20 standard headers) | ✅ Adopt |
| `HttpInstrumentationOptions.allowedQueryParamNames` allow-list (default `["api-version"]`) | ✅ Adopt |
| `isRedactedHeaderNamesLoggingEnabled` — true: log "REDACTED" placeholder; false: omit excluded headers entirely | ✅ Adopt |
| Distributed-trace span via `Tracer` / `Span` abstractions (already present in `sdk-core`) | ✅ Adopt — uses existing abstract types; concrete OTel adapter lives in a future `sdk-instrumentation-otel` module |
| Body-size logging (request and response) | ✅ Adopt |
| Time-to-response and time-to-last-byte metrics | ✅ Adopt |

#### Implementation guardrails

**Edge cases**
- Request with null body → log `body.size=0`.
- Response with null body → log `body.size=unknown`.
- `HttpLogLevel.NONE` → step is effectively pass-through, but still emits trace span.
- Body capture on streaming responses → drains eagerly (per `LoggableResponseBody` semantics); may stall slow responses. Document that `BODY_AND_HEADERS` is unsuitable for very large streamed responses.
- Allow-listed header missing from request/response → skip; don't log empty value.
- Empty allow-list AND `isRedactedHeaderNamesLoggingEnabled = true` → log every header as `REDACTED` (probably not what user wants; document).
- Exception in `next.process()` → emit `http.response` event with `error.type = <exception class>` and elapsed time.
- Response never closed by caller → `LoggableResponseBody` survives; underlying transport leak is the caller's bug, not the step's.

**Performance / allocations**
- `HttpLogLevel.NONE` fast path: no `LoggableRequestBody`/`LoggableResponseBody` wrapping; only span start/end and a single `http.request`/`http.response` event each.
- Cache redacted URL per request: redact once at the start of `process()`, reuse for both events.
- `Span` lifecycle: `start()` → end-before-error in `try/finally`. Span end is idempotent.
- Avoid wrapping bodies when `isContentLoggingEnabled = false` — saves one allocation per request.
- Header iteration: use the allowed-set as a filter via `.intersect`-style logic, not iterate all headers and check each.

**Non-happy paths**
- Drain throws on body capture → already handled by `LoggableResponseBody` (cached error + partial bytes available for snapshot).
- Trace context propagation fails → fall back to no context; do not block the request.
- Span end exception → swallow (we're already in error path, don't compound).
- Logging itself throws (unlikely but possible — e.g., `field()` serializer fails) → swallow + emit a `http.instrumentation.error` event at WARNING.

**Codestyle**
- `abstract class InstrumentationStep : HttpStep { final override val stage = Stage.LOGGING }`.
- Concrete impl is `internal class DefaultInstrumentationStep(...)`; user can subclass `InstrumentationStep` for custom behavior.
- `HttpInstrumentationOptions` is a builder-style data carrier (mutable builder, immutable result via `build()`).
- Use the universal `Clock` (Rank 21) for elapsed-time measurement.

- **Notes**:

### ✅ Rank 4 — `RetryStep` (pillar at `RETRY`)

- **What**: Exponential backoff with ±5% jitter, three-variant `Retry-After` parsing (`Retry-After`, `retry-after-ms`, `x-ms-retry-after-ms`), exception-chain walking, suppressed-exception accumulation, default classification (408/429/5xx-except-501,505).
- **Why**: Most-requested production feature.
- **Effort**: Medium-large. Depends on `PipelineNext.copy()` from Section 2.1.
- **Status**: ✅ **Agreed — adopt all sub-features from comp report Section 2.**

#### Sub-decisions (all agreed)

| Sub-feature | Decision | Source in comp report |
|---|---|---|
| Exponential backoff with `(1L << tryCount) * baseDelay`, capped at `maxDelay` | ✅ Adopt | §2.2 |
| ±5% random jitter on `baseDelay` via `ThreadLocalRandom` | ✅ Adopt | §2.2 |
| Default `baseDelay = 800ms`, `maxDelay = 8s`, `maxRetries = 3` | ✅ Adopt as defaults | §2 intro |
| Alternative fixed-delay strategy (constructor variant) | ✅ Adopt | §2 intro |
| Parse `x-ms-retry-after-ms` header (milliseconds) | ❓ Open — see open question below | §2.1 |
| Parse `retry-after-ms` header (milliseconds) | ✅ Adopt | §2.1 |
| Parse standard `Retry-After` (seconds-as-long OR RFC 1123 date) | ✅ Adopt | §2.1 |
| Custom delay function `Function<HttpRetryCondition, Duration>` override hook | ✅ Adopt | §2 (`HttpRetryOptions.delayFromRetryCondition`) |
| Custom should-retry predicate `Predicate<HttpRetryCondition>` override hook | ✅ Adopt | §2 (`HttpRetryOptions.shouldRetryCondition`) |
| Default retry-on-status: 408, 429, 5xx **except** 501 and 505 | ✅ Adopt | §2.3 |
| Default retry-on-exception: walk `getCause()` chain for `IOException` / `TimeoutException` | ✅ Adopt | §2.3 |
| `next.copy().process()` per attempt (fresh chain state) | ✅ Adopt — relies on Section 2.1 pipeline | §2 intro |
| `response.close()` before retry (release failed-response body) | ✅ Adopt | §2 intro |
| `InterruptedException` during sleep → abort retries, suppress on original | ✅ Adopt | §2.4 |
| Accumulate prior attempts as `Throwable.addSuppressed` on final failure | ✅ Adopt | §2.5 |
| `MAX_RETRY_ATTEMPTS` env-var override of default max retries | ⏸️ Depends on Rank 9 (`Configuration`) — adopt when that lands |  §2 intro |
| Configurable retry condition (custom status / exception sets) | ✅ Adopt via the override hooks above | §2 |
| Structured log events on every retry attempt (`http.retry` event) | ✅ Adopt — depends on Rank 2 (`ClientLogger`) | §2 |

#### Implicit prerequisites pulled in by this decision

- **Section 2.1 pipeline (Phase A)** — `next.copy()` is a hard requirement.
- **`DateTimeRfc1123` parsing** from Rank 18 — required for the RFC 1123 form of `Retry-After`. Pulled forward as a prerequisite. (`SetDateStep` itself stays pending in Rank 18.)
- **Rank 2 (`ClientLogger`)** — for the per-attempt `http.retry` structured events. If Rank 2 isn't landed yet, retry policy ships logging through plain SLF4J as an interim.

#### Kotlin shape we'll build

```kotlin
abstract class RetryStep : HttpStep {
    final override val stage: Stage = Stage.RETRY
}

class DefaultRetryStep(
    private val options: HttpRetryOptions = HttpRetryOptions(),
) : RetryStep() {

    override fun process(request: Request, next: PipelineNext): Response =
        attempt(request, next, tryCount = 0, suppressed = null)

    private fun attempt(
        request: Request,
        next: PipelineNext,
        tryCount: Int,
        suppressed: MutableList<Throwable>?,
    ): Response {
        val response: Response
        try {
            response = next.copy().process()
        } catch (err: RuntimeException) {
            val condition = HttpRetryCondition(response = null, exception = err, tryCount = tryCount,
                                                retriedExceptions = suppressed ?: emptyList())
            if (shouldRetryException(condition)) {
                val delay = options.delayFor(condition)
                sleepOrThrow(delay, err)
                val acc = suppressed ?: mutableListOf<Throwable>().also { /* lazy init */ }
                acc.add(err)
                return attempt(request, next, tryCount + 1, acc)
            }
            suppressed?.forEach(err::addSuppressed)
            throw err
        }

        val condition = HttpRetryCondition(response = response, exception = null, tryCount = tryCount,
                                            retriedExceptions = suppressed ?: emptyList())
        if (tryCount < options.maxRetries && options.shouldRetry(condition)) {
            val delay = options.delayFromCondition(condition) ?: retryAfterFromHeaders(response.headers)
                                                              ?: options.delayFor(condition)
            response.close()
            sleepOrThrow(delay, fallbackThrowable = null)
            return attempt(request, next, tryCount + 1, suppressed)
        }
        return response
    }

    // sleepOrThrow handles InterruptedException → abort + suppress
    // retryAfterFromHeaders parses the three Retry-After variants
}

class HttpRetryOptions @JvmOverloads constructor(
    val maxRetries: Int = 3,
    val baseDelay: Duration = Duration.ofMillis(800),
    val maxDelay: Duration = Duration.ofSeconds(8),
    val fixedDelay: Duration? = null,
    val shouldRetry: (HttpRetryCondition) -> Boolean = ::defaultShouldRetry,
    val delayFromCondition: (HttpRetryCondition) -> Duration? = { null },
) {
    companion object {
        fun fixed(maxRetries: Int, delay: Duration) = HttpRetryOptions(
            maxRetries = maxRetries, fixedDelay = delay,
            baseDelay = Duration.ZERO, maxDelay = Duration.ZERO,
        )
    }
}

data class HttpRetryCondition(
    val response: Response?,
    val exception: Exception?,
    val tryCount: Int,
    val retriedExceptions: List<Throwable>,
)
```

#### Resolved decisions

- **`x-ms-retry-after-ms` parsing**: ✅ **Option 3 selected** — `HttpRetryOptions.retryAfterHeaders: List<HttpHeaderName>` is configurable. Default includes all three (`Retry-After`, `retry-after-ms`, `x-ms-retry-after-ms`). Callers can override to drop the Microsoft-specific variants if they need a stricter posture.

#### Implementation guardrails

**Edge cases**
- `maxRetries = 0` → no retries, one attempt only. Tested explicitly.
- `maxRetries < 0` → clamp to default 3 with a verbose log (not an error — too disruptive).
- `baseDelay = 0` → no delay between retries (allowed; for high-throughput retry to a flaky endpoint).
- `fixedDelay` set AND `baseDelay` set → `fixedDelay` wins (matches Azure).
- `maxDelay < baseDelay` → no automatic clamp; jitter calculation handles it correctly (the `min(... , maxDelayNanos)` cap).
- First attempt succeeds → fast path: no jitter, no `HttpRetryCondition` allocation, no logging if verbose is off.
- `Retry-After` header parses to zero → use 0 (immediate retry).
- `Retry-After` header parses to negative → use default (treat as parse failure).
- `Retry-After` is huge (e.g., 1 hour) → respect, but warn at verbose; callers can override via `delayFromCondition` to cap.
- Network exception with no response → retry via exception classifier; no `Retry-After` header to consult.
- Interrupt during sleep → abort retries; throw original exception with interrupt suppressed; preserve thread-interrupt status.
- Custom `delayFromCondition` returns `null` → fall back to default delay calculation.
- Custom `shouldRetry` throws → wrap in `IllegalStateException("shouldRetry predicate threw")`; abort.

**Performance / allocations**
- **Iterative loop, NOT recursion.** Stack-safe for `maxRetries = 1000+` scenarios (rare but real for test injection).
- `HttpRetryCondition` allocation: per-attempt. Acceptable cost (≤ maxRetries+1 allocations per call).
- `suppressed: MutableList<Throwable>` lazily allocated on first failure; never on success path.
- `RetryUtils.isRetryable(statusCode)`: O(1) set membership via `IntArray` or `EnumSet` (not HashSet — small set, cache-friendly array works best).
- `(1L shl tryCount) * delayWithJitterInNanos` — `1L shl tryCount` for `tryCount > 62` overflows; cap `tryCount` at 30 before shifting (delay caps at maxDelay anyway).
- `ThreadLocalRandom.current()` is per-thread cached; no contention.
- Cache the `Retry-After` header names array at class init (`HttpHeaderName` instances are interned per Rank 8).
- Use `Clock.SYSTEM.sleep(delay)` via Rank 21 abstraction; tests pass `FixedClock`.

**Non-happy paths**
- Response from previous attempt never closed → `attempt(...)` calls `response.close()` before retry. Verified by test.
- `next.copy().process()` throws non-`Exception` (e.g., `Error`) → propagate, do not retry.
- Concurrent calls to same `RetryStep` instance with different requests → each gets independent state via `PipelineCallState`; safe.
- `Thread.sleep(0)` → may yield or no-op; either is fine.
- Interrupt during between-attempts setup (not in sleep) → InterruptedException not thrown; tested.

**Codestyle**
- `abstract class RetryStep : HttpStep { final override val stage = Stage.RETRY }`.
- `DefaultRetryStep(options: HttpRetryOptions)` concrete impl is `internal`; users extending get the abstract base.
- `HttpRetryOptions` is a fluent-builder-friendly data class with `@JvmOverloads` constructor + `companion object` factories like `HttpRetryOptions.fixed(maxRetries, delay)`.
- `HttpRetryCondition` is a `data class` with read-only properties.
- `@Throws(IOException::class)` on `process(request, next)`.
- All time math uses `Long` nanos internally; convert to `Duration` only at API boundaries.

- **Notes**:

### ✅ Rank 5 — `RedirectStep` (pillar at `REDIRECT`)

- **What**: Follows 301/302/307/308 (303 separately decided), default GET/HEAD only, strips `Authorization` header on redirect, detects URI loops via `LinkedHashSet<String>`, configurable max attempts (default 3).
- **Why**: Security-critical (Authorization stripping); loop detection prevents infinite chains.
- **Effort**: Small-medium.
- **Status**: ✅ **Agreed — adopt all sub-features from comp report Section 3.**

#### Sub-decisions (all agreed)

| Sub-feature | Decision | Source in comp report |
|---|---|---|
| Default max 3 redirect attempts | ✅ Adopt | §3 intro |
| Follow status codes 301 (MOVED_PERM), 302 (MOVED_TEMP), 307, 308 | ✅ Adopt | §3.4 |
| **303 (See Other)** handling | ❓ Open — see open question below | §3.4 |
| Default allowed methods: GET, HEAD only | ✅ Adopt | §3.3 |
| Configurable `allowedRedirectHttpMethods` via `EnumSet<HttpMethod>` | ✅ Adopt | §3.3 |
| Configurable `Location` header name (default `Location`) | ✅ Adopt | §3 intro |
| **Strip `Authorization` header before redirect** (security) | ✅ Adopt | §3.1 |
| Loop detection via `LinkedHashSet<String>` of attempted URIs | ✅ Adopt | §3.2 |
| Custom should-redirect predicate `Predicate<HttpRedirectCondition>` override | ✅ Adopt | §3 intro |
| `next.copy().process()` per redirect hop | ✅ Adopt — relies on Section 2.1 pipeline | §3 intro |
| `response.close()` before re-issuing redirect | ✅ Adopt | §3.1 |
| Structured log event (`http.redirect`) per hop with URI redaction | ✅ Adopt — depends on Rank 1, Rank 2 | §3 intro |

#### Implicit prerequisites pulled in by this decision

- **Section 2.1 pipeline (Phase A)** — `next.copy()` is a hard requirement.
- **Rank 1 (`UrlRedactor`)** — to redact `Location` URIs in log events.
- **Rank 2 (`ClientLogger`)** — for the per-hop `http.redirect` structured events.

#### Kotlin shape we'll build

```kotlin
abstract class RedirectStep : HttpStep {
    final override val stage: Stage = Stage.REDIRECT
}

class DefaultRedirectStep(
    private val options: HttpRedirectOptions = HttpRedirectOptions(),
) : RedirectStep() {

    override fun process(request: Request, next: PipelineNext): Response =
        attempt(next, redirectAttempt = 0, attempted = LinkedHashSet())

    private fun attempt(next: PipelineNext, redirectAttempt: Int, attempted: LinkedHashSet<String>): Response {
        val response = next.copy().process()
        val condition = HttpRedirectCondition(response, redirectAttempt, attempted)

        if (options.shouldRedirect?.invoke(condition) == true ||
            (options.shouldRedirect == null && defaultShouldRedirect(condition))) {
            recreateRedirectRequest(response)
            return attempt(next, redirectAttempt + 1, attempted)
        }
        return response
    }

    private fun recreateRedirectRequest(response: Response) {
        // Critical: strip Authorization to prevent token leak to redirect target.
        response.request.headers.remove(HttpHeaderName.AUTHORIZATION)
        response.request.uri = URI.create(response.headers.value(options.locationHeader)!!)
        response.close()
    }
}

class HttpRedirectOptions @JvmOverloads constructor(
    val maxAttempts: Int = 3,
    val allowedMethods: EnumSet<HttpMethod> = EnumSet.of(HttpMethod.GET, HttpMethod.HEAD),
    val locationHeader: HttpHeaderName = HttpHeaderName.LOCATION,
    val shouldRedirect: ((HttpRedirectCondition) -> Boolean)? = null,
)

data class HttpRedirectCondition(
    val response: Response,
    val tryCount: Int,
    val redirectedUris: Set<String>,
)
```

#### Resolved decisions

- **303 (See Other) handling**: ✅ **Option 3 selected** — `HttpRedirectOptions.follow303: Boolean` is configurable, defaults to `false` (matches Azure). Opt-in `follow303 = true` enables RFC 7231-conformant behavior: the redirect re-issues as a `GET` regardless of the original method, with the request body dropped.

#### Implementation guardrails

**Edge cases**
- Empty `Location` header → don't redirect; return response.
- Relative `Location` URL → resolve against current request URI (per RFC 7231).
- Malformed `Location` URL → don't redirect; log warning with redacted original URL.
- Redirect to same URL (immediate loop) → caught by URI set; max-attempts also serves as a fallback.
- Cross-scheme redirect (`http→https`) → allowed; (`https→http`) → allowed but log warning at info level (potential downgrade attack).
- Original request had a body AND status preserves method (307/308) AND body is not replayable → throw `IllegalStateException("Redirect requires replayable body for 307/308"); use Request.body.toReplayable() before send`.
- 303 with `follow303 = true` → drop request body, set method to GET, strip `Content-*` headers.
- 308 → must preserve method AND body; non-replayable body fails (as above).
- Max attempts reached → return last response (do NOT throw — the response IS the result, even if it's another redirect).
- `Location` header with userinfo (`https://user:pass@host`) → strip userinfo before re-issue (defensive: server-supplied credentials should not be used).

**Performance / allocations**
- **Iterative loop, NOT recursion** (stack-safe for max-attempts misconfiguration).
- `LinkedHashSet<String>` for attempted URIs — small (≤ max-attempts); fine.
- `URI.create(location)` per hop — unavoidable; result is short-lived.
- `HttpRedirectCondition` per hop — allocate only if `shouldRedirect` predicate is non-null (Azure's default uses a static method; ours follows).
- Use `Clock.SYSTEM` (Rank 21) only if we add per-hop timing (currently not needed).

**Non-happy paths**
- `Location` value is `null` vs missing — both treated as "don't redirect".
- Original request method not in `allowedRedirectHttpMethods` → don't redirect; log at verbose.
- `shouldRedirect` predicate returns true but URL is invalid → log + don't redirect (predicate's job, but defensive).
- Response body never closed before redirect → `createRedirectRequest(response)` calls `response.close()`. Tested.
- Cross-origin redirect with Authorization → strip the header BEFORE re-issue (already documented). Test: redirect from `https://api1.example.com` to `https://api2.example.com` must not carry `Authorization`.

**Codestyle**
- `abstract class RedirectStep : HttpStep { final override val stage = Stage.REDIRECT }`.
- `DefaultRedirectStep(options: HttpRedirectOptions)` concrete impl is `internal`.
- `HttpRedirectOptions` immutable; builder if needed for fluent construction.
- `HttpRedirectCondition` is `data class` (read-only).
- `@Throws(IOException::class)` on `process`.

- **Notes**:

### ✅ Rank 6 — HTTPS-only credential policies + `Credential` hierarchy

- **What**: `sealed interface Credential` with `KeyCredential`, `BearerToken(token, expiresAt)`; `BearerTokenProvider` for refresh; policies throw `IllegalStateException` on non-HTTPS scheme.
- **Why**: Fail-fast principle applied to credentials. Compile-time-safe credential injection.
- **Effort**: Small (types) + medium (auth policy base + concrete impls).
- **Status**: ✅ **Agreed — adopt all sub-features from comp report Section 4.**

#### Sub-decisions (all agreed)

| Sub-feature | Decision | Source in comp report |
|---|---|---|
| **HTTPS-only enforcement** in every credential policy (`IllegalStateException` on non-HTTPS) | ✅ Adopt | §4.1 |
| `Credential` sealed hierarchy at the type level | ✅ Adopt | §4.2 |
| `KeyCredential(apiKey, headerName)` for static API keys | ✅ Adopt | §4.2 |
| `NamedKeyCredential(name, key)` for SAS-style auth | ✅ Adopt | (Azure has) |
| `BearerToken(token, expiresAt)` for OAuth access tokens | ✅ Adopt | §4.2 |
| `BearerTokenProvider` interface for refresh-capable token sources | ✅ Adopt | §4.2 |
| `AuthStep` base class (pillar at `AUTH`, locks stage at type level) | ✅ Adopt | §4 (`HttpCredentialPolicy` parent) |
| `KeyCredentialAuthStep` concrete impl (static-key header injection) | ✅ Adopt | §4 (Azure has) |
| `BearerTokenAuthStep` concrete impl (calls `provider.fetch()` per request) | ✅ Adopt | §4 (Azure `BearerTokenAuthStep`) |
| **`AuthScheme.NO_AUTH`** for per-request auth skip | ✅ Adopt | §4.4 |
| **`AuthMetadata` in `RequestContext`** for per-request auth scheme override | ✅ Adopt | §4.4 |
| Per-operation token-request-context merging (operation overrides service-level) | ✅ Adopt | §4 intro |
| **`authorizeRequestOnChallenge` hook** on 401+`WWW-Authenticate` | ✅ Adopt | §4.5 |
| Token caching with expiry-margin refresh (e.g. refresh N seconds before `expiresAt`) | ✅ Adopt | implicit in §4.2 |

#### Implicit prerequisites pulled in by this decision

- **Section 2.1 pipeline (Phase A)** — `AuthStep` pillar slot lives here.
- **Rank 12 (`ChallengeHandler`)** — needed by `authorizeRequestOnChallenge` to interpret `WWW-Authenticate` headers. Now also ✅ Agreed (see below).
- **`Configuration` (Rank 9)** — optional, for reading credentials from env vars at builder construction time. Adopt when Rank 9 lands.

#### Kotlin shape we'll build

```kotlin
sealed interface Credential

class KeyCredential(
    val apiKey: String,
    val headerName: HttpHeaderName = HttpHeaderName.AUTHORIZATION,
    val prefix: String? = null,                  // e.g. "SharedAccessKey"
) : Credential

class NamedKeyCredential(val name: String, val key: String) : Credential

data class BearerToken(val token: String, val expiresAt: Instant?) : Credential {
    fun isExpiredAt(now: Instant, marginBefore: Duration = Duration.ZERO): Boolean =
        expiresAt != null && now.plus(marginBefore).isAfter(expiresAt)
}

fun interface BearerTokenProvider {
    /** Fetches a fresh token. May block (network call). Implementations should cache + refresh. */
    fun fetch(scopes: List<String>, params: Map<String, Any> = emptyMap()): BearerToken
}

// --- Auth pillar ---

abstract class AuthStep : HttpStep {
    final override val stage: Stage = Stage.AUTH

    @Throws(IOException::class)
    final override fun process(request: Request, next: PipelineNext): Response {
        if (request.url.scheme != "https") {
            throw IllegalStateException(
                "${this::class.simpleName} requires HTTPS to prevent credential leak " +
                "(URL scheme: ${request.url.scheme})"
            )
        }

        // Per-request override via AuthMetadata
        val authMetadata = request.context.get(AuthMetadata)
        if (authMetadata?.schemes?.contains(AuthScheme.NO_AUTH) == true) {
            return next.process()
        }

        authorizeRequest(request, authMetadata)
        val response = next.copy().process()

        val challenge = response.headers.value(HttpHeaderName.WWW_AUTHENTICATE)
        if (response.statusCode == 401 && challenge != null && authorizeRequestOnChallenge(request, response)) {
            response.close()
            return next.process()
        }
        return response
    }

    protected abstract fun authorizeRequest(request: Request, metadata: AuthMetadata?)

    /** Default: don't auto-handle 401. Subclasses override to refresh tokens or step up auth. */
    protected open fun authorizeRequestOnChallenge(request: Request, response: Response): Boolean = false
}

class KeyCredentialAuthStep(private val credential: KeyCredential) : AuthStep() {
    override fun authorizeRequest(request: Request, metadata: AuthMetadata?) {
        val value = credential.prefix?.let { "$it ${credential.apiKey}" } ?: credential.apiKey
        request.headers.set(credential.headerName, value)
    }
}

class BearerTokenAuthStep(
    private val provider: BearerTokenProvider,
    private val scopes: List<String>,
    private val refreshMargin: Duration = Duration.ofSeconds(30),
) : AuthStep() {
    @Volatile private var cachedToken: BearerToken? = null

    override fun authorizeRequest(request: Request, metadata: AuthMetadata?) {
        val token = currentToken()
        request.headers.set(HttpHeaderName.AUTHORIZATION, "Bearer ${token.token}")
    }

    private fun currentToken(): BearerToken {
        val now = Instant.now()
        cachedToken?.takeIf { !it.isExpiredAt(now, refreshMargin) }?.let { return it }
        return provider.fetch(scopes).also { cachedToken = it }
    }
}

// --- Per-request auth metadata ---

enum class AuthScheme { OAUTH2, API_KEY, BASIC, DIGEST, NO_AUTH }

class AuthMetadata(
    val schemes: List<AuthScheme>,
    val oauthScopes: List<String> = emptyList(),
    val oauthParams: Map<String, Any> = emptyMap(),
) {
    companion object Key : RequestContext.Key<AuthMetadata>
}
```

#### Open questions still to settle

- **Token refresh margin default**: Azure doesn't expose this; default to 30 seconds (refresh 30s before expiry). Adjustable on `BearerTokenAuthStep` constructor.
- **Async token refresh**: Should `BearerTokenProvider.fetch()` support non-blocking refresh (coroutine `suspend`)? The interface is currently blocking. Adding `suspend` would force a coroutines dependency on the auth module. **Lean: blocking-only in core; suspend wrapper in optional `sdk-auth-coroutines` adapter module.**

#### Implementation guardrails

**Edge cases**
- Empty API key in `KeyCredential` → throw `IllegalArgumentException` in the credential constructor.
- `BearerToken` with `expiresAt = null` → never refresh (assume long-lived). Cached forever until `provider.fetch()` is manually re-invoked.
- `BearerToken` with `expiresAt` already in the past at construction → refresh immediately on first use.
- Refresh margin > token lifetime → refresh every request (log warning at construction).
- URL scheme check is case-insensitive: `"https".equals(scheme, ignoreCase = true)` — matches both `HTTPS` and `https`.
- URL scheme is `null` (malformed URL) → throw with clear message naming the URL.
- `AuthMetadata` with empty `schemes` list → treat as `NO_AUTH` (skip auth).
- Per-request `AuthMetadata` with `AuthScheme.NO_AUTH` AND a configured `Credential` → skip auth (NO_AUTH wins).
- 401 with `WWW-Authenticate` AND `authorizeRequestOnChallenge` returns `false` → return the 401 response as-is (caller deals).
- 401 with no `WWW-Authenticate` header → return the 401 response as-is (no auto-handling).

**Performance / allocations**
- **Bearer header value caching**: cache `"Bearer ${token}"` string per cached token; avoid concat per request.
- **Token cache check is a `@Volatile` field read** + `isExpiredAt` (no allocation).
- **Token refresh synchronized via `ReentrantLock`**: only one thread fetches; others wait. Double-check after lock acquisition (volatile re-read + expiry check).
- Avoid allocating `AuthMetadata` if it's not in the context — `request.context.get(AuthMetadata)` returns `null`; skip the metadata path.
- Inline the HTTPS check (no helper allocation).

**Non-happy paths**
- `BearerTokenProvider.fetch()` throws → propagate; do NOT cache the exception (next call retries the fetch).
- `BearerTokenProvider.fetch()` returns null → throw `IllegalStateException("BearerTokenProvider returned null")`.
- `BearerTokenProvider.fetch()` returns expired token → wrap in `IllegalStateException` (provider misbehaving).
- HTTPS-only failure → clear message: `"AuthStep requires HTTPS to prevent credential leak (URL scheme: ${scheme})"`.
- Token refresh during heavy concurrent load → single-fetch semantics enforced by lock; verified by race test.
- `authorizeRequestOnChallenge` throws → propagate; the 401 response is the natural next state.

**Codestyle**
- `sealed interface Credential` — closed hierarchy for type-safe credential injection.
- `abstract class AuthStep : HttpStep { final override val stage = Stage.AUTH }`.
- Concrete `KeyCredentialAuthStep` and `BearerTokenAuthStep` are `class` (open for subclassing — e.g., user wants to override `authorizeRequestOnChallenge`).
- `@JvmOverloads` on `BearerTokenAuthStep` constructor (refreshMargin default).
- `BearerToken` is a `data class` (for value semantics).
- `AuthMetadata.Key` is a `RequestContext.Key<AuthMetadata>` companion (typed key pattern).
- `@Throws(IOException::class)` on `process`.

- **Notes**:

### ✅ Rank 7 — `HttpResponseException` carrying `isRetryable`

- **What**: Exception classifies itself; retry policy queries it.
- **Why**: Cleaner separation than a separate predicate; matches Azure model.
- **Effort**: Small.
- **Status**: ✅ **Agreed** (from comp report §8).

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| `HttpResponseException` carries the `Response` and an optional deserialized `value` | ✅ Adopt |
| `isRetryable: Boolean` precomputed at construction from `RetryUtils.isRetryable(statusCode)` | ✅ Adopt |
| `RetryUtils.isRetryable(statusCode)` shared utility (408, 429, 5xx except 501/505) | ✅ Adopt — reused by Rank 4 default classifier |
| `RetryUtils.isRetryable(Throwable)` for cause-chain walk (`IOException`/`TimeoutException`) | ✅ Adopt — reused by Rank 4 |

#### Kotlin shape

```kotlin
open class HttpResponseException(
    message: String,
    val response: Response?,
    val value: Any? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {
    val isRetryable: Boolean = response?.let { RetryUtils.isRetryable(it.status.code) }
        ?: cause?.let { RetryUtils.isRetryable(it) }
        ?: false
}

object RetryUtils {
    private val nonRetryable5xx = setOf(501, 505)
    fun isRetryable(statusCode: Int): Boolean = statusCode == 408 || statusCode == 429 ||
        (statusCode in 500..599 && statusCode !in nonRetryable5xx)
    fun isRetryable(t: Throwable): Boolean = generateSequence(t) { it.cause }
        .any { it is IOException || it is java.util.concurrent.TimeoutException }
}
```

#### Implementation guardrails

**Edge cases**
- `response = null && cause = null` → `isRetryable = false`.
- Response with success status (200) → `isRetryable = false` (success isn't an exception condition).
- Cause chain wraps `IOException` inside `RuntimeException` inside `IllegalStateException` → walk full chain.
- Self-referential cause chain (cycle) → bounded walk depth (max 16) to avoid infinite loop.

**Performance / allocations**
- `nonRetryable5xx` is `setOf(501, 505)` — use `IntArray + contains` for primitive check (no boxing).
- `isRetryable(Throwable)`: avoid `generateSequence` allocation. Use a manual loop with a depth counter.
- `isRetryable` computed at construction; cached in field. No re-computation per query.

**Codestyle**
- `open class HttpResponseException` (extensible for typed subclasses).
- `object RetryUtils` with `@JvmStatic` on both `isRetryable` overloads.
- Loop variant: `private fun isRetryable(t: Throwable): Boolean = ... bounded loop ...`.

- **Notes**:

### ✅ Rank 8 — Audit `Headers.kt` for interning + case-insensitive identity + multi-value

- **What**: Verify `HttpHeaderName.fromString` caches instances, equals/hashCode use lowercase, `List<String>` values supported for headers like `Set-Cookie`.
- **Why**: Likely-existing bugs. Headers like `set-cookie` vs `Set-Cookie` should compare equal.
- **Effort**: Small (mostly audit; fix if needed).
- **Status**: ✅ **Agreed** (from comp report §7).

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| Interned `HttpHeaderName.fromString` via `ConcurrentHashMap` | ✅ Adopt — audit and fix if missing |
| Case-preserving on the wire, case-insensitive `equals`/`hashCode` (lowercase form) | ✅ Adopt |
| Static constants for ~150 well-known header names | ✅ Adopt |
| **Multi-value** support: `Headers.set(name, List<String>)`, `getValues(name): List<String>` | ✅ Adopt — required for `Set-Cookie`, `WWW-Authenticate`, `Via` |
| `add(name, value)` appends if header exists; `set(name, value)` replaces | ✅ Adopt |
| `addAll(Headers)` for bulk merging | ✅ Adopt |

#### Implementation guardrails

**Edge cases**
- Header name with leading/trailing whitespace → trim and interpret normalized form (matches what servers send).
- Empty header value → preserve (some headers like `X-Empty: ` are intentional).
- `set(name, null)` → remove header (matches Azure / OkHttp semantics).
- `add(name, value)` when header exists → append value (multi-value); `set(name, value)` replaces.
- Concurrent modification of `Headers.Builder` → undocumented; treat as caller's bug.
- Very long header value (JWT, base64 cert) → no truncation; pass through.
- Comma-joined value vs separate `add` calls → handle both on parse; produce comma-joined on emit by default, with opt-in for newline-separated (`Set-Cookie`).

**Performance / allocations**
- `HttpHeaderName.fromString(name)` returns interned instance from `ConcurrentHashMap`.
- `HttpHeaderName.equals/hashCode` use lowercase form; allows `HashMap<HttpHeaderName, ...>` lookups to be case-insensitive.
- `headers.get(name)` is a single `HashMap` lookup.
- ~150 well-known header constants as `static final` fields (avoid `fromString` lookups in hot paths).

**Non-happy paths**
- `add(null, value)` or `add(name, null)` → no-op (Azure pattern).
- `HttpHeaderName.fromString(null)` → returns null (matches Azure).
- Iterating headers while modifying → `ConcurrentModificationException` (HashMap default behavior); document.

**Codestyle**
- `class Headers` (existing): audit visibility and add `Set-Cookie`-aware emit if not present.
- `class HttpHeaderName implements ExpandableEnum<String>` (per ExpandableEnum pattern; if we don't have it, define a simple marker).
- `@JvmStatic` on `HttpHeaderName.fromString`.

- **Notes**: This is an audit + fixup of existing `Headers.kt`, not greenfield. The fix-list comes out of inspection.

### ✅ Rank 9 — `Configuration` system

- **What**: Layered env-var + system-property + default config. Read `MAX_RETRY_ATTEMPTS`, `LOG_LEVEL`, etc.
- **Why**: Runtime tunables without redeploying. Required by retry / instrumentation defaults.
- **Effort**: Small-medium.
- **Status**: ✅ **Agreed** (from comp report §12.1).

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| `Configuration.getGlobalConfiguration()` singleton | ✅ Adopt |
| `get(name): String?` with lookup order: explicit override → env var → system property → default | ✅ Adopt |
| Typed accessors: `getInt(name, default)`, `getBoolean(name, default)`, `getDuration(name, default)` | ✅ Adopt |
| Builder-time override via `ConfigurationBuilder` (test seam + per-client config) | ✅ Adopt |
| Well-known keys as `static final String` constants (e.g. `MAX_RETRY_ATTEMPTS`, `LOG_LEVEL`, `HTTP_PROXY`) | ✅ Adopt |
| Snake-case env-var → property mapping (e.g. `MAX_RETRY_ATTEMPTS` env → `max.retry.attempts` sys prop) | ✅ Adopt |

#### Kotlin shape

```kotlin
class Configuration internal constructor(
    private val overrides: Map<String, String>,
    private val envSource: (String) -> String? = System::getenv,
    private val propsSource: (String) -> String? = System::getProperty,
) {
    fun get(name: String, default: String? = null): String? = overrides[name]
        ?: envSource(name)
        ?: propsSource(envToProp(name))
        ?: default

    fun getInt(name: String, default: Int): Int = get(name)?.toIntOrNull() ?: default
    fun getBoolean(name: String, default: Boolean): Boolean = get(name)?.toBooleanStrictOrNull() ?: default
    fun getDuration(name: String, default: Duration): Duration = get(name)?.let(Duration::parse) ?: default

    companion object {
        val MAX_RETRY_ATTEMPTS = "MAX_RETRY_ATTEMPTS"
        val LOG_LEVEL          = "LOG_LEVEL"
        val HTTP_PROXY         = "HTTP_PROXY"
        // ...

        @Volatile private var global: Configuration = Configuration(emptyMap())
        fun getGlobalConfiguration(): Configuration = global
        fun setGlobalConfiguration(c: Configuration) { global = c }
    }
}

class ConfigurationBuilder {
    private val overrides = mutableMapOf<String, String>()
    fun put(name: String, value: String): ConfigurationBuilder = apply { overrides[name] = value }
    fun build(): Configuration = Configuration(overrides.toMap())
}
```

#### Implementation guardrails

**Edge cases**
- Env var name contains spaces / special chars → use as-is (System.getenv is permissive); sys-prop name is normalized form.
- Property value doesn't parse → return default, log at verbose (don't throw — config issues shouldn't break the pipeline).
- `getGlobalConfiguration()` before `setGlobalConfiguration()` → returns empty default config.
- Concurrent `setGlobalConfiguration()` calls → last-write-wins via `@Volatile` write.
- Empty string env var → treat as null (matches shell behavior where `EMPTY=` is "set but empty").
- Boolean parse: accept `"true"`, `"false"` only (case-insensitive); reject `"1"`, `"yes"`, `"on"` (explicit by design).
- `Duration` parse: support ISO-8601 (`PT5S`) and a shorthand (`5s`, `500ms`, `1m`). Document the supported forms.

**Performance / allocations**
- `Configuration.getGlobalConfiguration()` is a single `@Volatile` field read.
- Per-`get()` lookups call `System.getenv`/`System.getProperty` — synchronized in JDK but fast.
- Type-converters (`getInt`, `getDuration`) catch `NumberFormatException` and use defaults; minimal allocation.

**Non-happy paths**
- `setGlobalConfiguration(null)` → throw NPE.
- `ConfigurationBuilder.put(null, ...)` or `put(..., null)` → throw NPE.

**Codestyle**
- `object Configuration.Companion` for `getGlobalConfiguration`/`setGlobalConfiguration`.
- `class Configuration internal constructor(...)` — only `ConfigurationBuilder` can construct.
- `@JvmStatic` on companion methods.
- Well-known keys as `const val` constants (e.g., `Configuration.MAX_RETRY_ATTEMPTS`).

- **Notes**:

### ✅ Rank 10 — `HttpRange`, `ETag`, `RequestConditions` typed values

- **What**: Value classes that produce the right header strings, with construction-time validation.
- **Why**: Compile-time type safety for common HTTP semantics.
- **Effort**: Small (three small classes + tests).
- **Status**: ✅ **Agreed** (from comp report §6).

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| `HttpRange` value class with `bytes(offset, length)` and `suffix(length)` factories | ✅ Adopt |
| `ETag` value class with `strong(opaque)`, `weak(opaque)`, `parse(raw)`, and `ALL` (`*`) | ✅ Adopt |
| Construction-time validation throws `IllegalArgumentException` on invalid syntax | ✅ Adopt |
| `RequestConditions` builder for `If-Match`, `If-None-Match`, `If-Modified-Since`, `If-Unmodified-Since` | ✅ Adopt |
| `RequestConditions` consumes typed `ETag` and `Instant` — fixes Azure's typed/string inconsistency | ✅ Adopt (improvement over Azure) |
| `RequestConditions.applyTo(Headers.Builder)` to stamp the headers correctly | ✅ Adopt |

#### Kotlin shape

See `azure-comp-report.md` §6 for the proposed signatures. Implementation: three `value class`es + one builder.

#### Implementation guardrails

**Edge cases**
- `HttpRange.bytes(0, 0)` → zero-length range; valid per RFC ("bytes=0-(-1)" is undefined though). Reject in `bytes(...)` factory.
- `HttpRange.bytes(Long.MAX_VALUE, ...)` → `offset + length - 1` overflows; check `Math.addExact` and throw `ArithmeticException`.
- `HttpRange.suffix(0)` → invalid; reject ("bytes=-0" is meaningless).
- `ETag.parse("")` → invalid; throw.
- `ETag.weak("")` → produces `W/""` — valid per RFC 7232 but unusual; allow.
- `ETag.parse(null)` → return `ETag.NULL` sentinel (matches Azure).
- `RequestConditions` with both `ifModifiedSince` and `ifUnmodifiedSince` → contradictory but legal per spec; pass both through.
- `RequestConditions.applyTo()` called with `null` builder → throw NPE.

**Performance / allocations**
- `@JvmInline value class` for `HttpRange` and `ETag` — inline at most call sites; only allocates when used as `Any?`/`Object` parameter or in generic collections.
- `RequestConditions` is a regular `class` (multiple fields); fluent builder pattern; small instance.

**Non-happy paths**
- Invalid ETag syntax → `IllegalArgumentException` with the raw input echoed back (helpful for debugging).
- `HttpRange.bytes` with negative offset → `IllegalArgumentException`.

**Codestyle**
- `@JvmInline value class HttpRange private constructor(...)` + `companion object { @JvmStatic fun bytes(...): HttpRange; @JvmStatic fun suffix(length: Long): HttpRange }`.
- `@JvmInline value class ETag` similarly.
- `class RequestConditions` with `Builder` inner class.
- `data class` not used (value class is better — primitive-like, inlined).

- **Notes**:

### ✅ Rank 11 — `BufferedSource.slice(offset, byteCount)`

- **What**: Non-consuming, length-bounded view over a `BufferedSource`.
- **Why**: Enables partial uploads, multipart bodies, range response handling.
- **Effort**: Small.
- **Status**: ✅ **Agreed** (from comp report §5 partial).

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| `BufferedSource.slice(offset: Long, byteCount: Long): BufferedSource` returning a non-consuming view | ✅ Adopt — defined on the contract; implemented by adapter |
| Bounds-check on construction: throws `IndexOutOfBoundsException` if `offset < 0` or beyond source size where determinable | ✅ Adopt |
| Closing the slice does **not** close the parent | ✅ Adopt — slice ownership is independent |
| Sliced source supports the same typed-read surface (`readUtf8`, `readByteArray`, etc.) | ✅ Adopt — that's the whole point |
| Internal impl: Okio's `peek()` then a length-bounded wrapper (for the `sdk-io-okio3` adapter) | ✅ Adopt |

#### Implementation guardrails

**Edge cases**
- Slice of empty source → produces empty source (reads return -1 immediately).
- `slice(offset = 0, byteCount = source.size)` → equivalent to the original source but non-consuming on parent.
- `slice` with `byteCount > source.size` → may legitimately span partial data; EOF at actual end.
- `slice` with `offset > source.size` → throw `EOFException` on first read (lazy detection; matches `peek().skip()` semantics).
- Closing the slice does NOT close the parent → verified by test.
- Closing the parent invalidates the slice → reads on the slice throw `IllegalStateException("Parent source closed")`.
- Multiple slices of same source → each is independent; safe.
- Reading the slice does NOT advance the parent → that's the whole point.

**Performance / allocations**
- Single wrapper allocation per `slice()` call.
- Okio's `peek()` is segment-share — no byte copying for the slice setup.
- Length tracking is a `Long` counter on every read.

**Non-happy paths**
- Negative offset / negative byteCount → `IllegalArgumentException` at construction.
- Underlying source throws during read → propagates through slice.

**Codestyle**
- Add `fun slice(offset: Long, byteCount: Long): BufferedSource` to `sdk-core/io/BufferedSource.kt` interface.
- `internal class SlicedOkioBufferedSource(parent: OkioBufferedSource, offset: Long, byteCount: Long)` in `sdk-io-okio3`.
- `@Throws(IOException::class)`.

- **Notes**: Add as a new method on our existing `BufferedSource` interface (`sdk-core/io/BufferedSource.kt`).

### ✅ Rank 12 — `WWW-Authenticate` `ChallengeHandler` (Basic + Digest)

- **What**: Composable challenge handlers for `401 + WWW-Authenticate`. `Basic` (trivial) + `Digest` (qop, nc, cnonce, MD5).
- **Why**: Enterprise / on-prem APIs use these. Also reusable for proxy auth (Rank 16).
- **Effort**: Medium (Digest is non-trivial).
- **Status**: ✅ **Agreed — adopt all sub-features from comp report Section 4.3.** Pulled in by Rank 6's `authorizeRequestOnChallenge` hook.

#### Sub-decisions (all agreed)

| Sub-feature | Decision | Source |
|---|---|---|
| `ChallengeHandler` interface (`handleChallenge`, `canHandle`) | ✅ Adopt | §4.3 |
| `AuthenticateChallenge` data class — parses `scheme realm="..." qop="..."` parameters | ✅ Adopt | §4.3 (Azure has) |
| `WWW-Authenticate` header parser (multi-challenge, parameter parsing with quoted strings) | ✅ Adopt | §4.3 (Azure's `AuthUtils.parseAuthenticateHeader`) |
| `BasicChallengeHandler(username, password)` — emits `Basic <base64(user:pass)>` | ✅ Adopt | §4.3 |
| `DigestChallengeHandler(username, password)` — full RFC 7616 (MD5, MD5-sess, qop=auth, nc, cnonce) | ✅ Adopt | §4.3 |
| Composite via `ChallengeHandler.of(handler1, handler2, ...)` — ordering matters (Digest first, Basic last) | ✅ Adopt | §4.3 |
| Separate `Proxy-Authenticate` / `Proxy-Authorization` header pair for proxy auth | ✅ Adopt | §4.3 |
| Identity by `isProxy: Boolean` flag throughout the API (one handler, two header conventions) | ✅ Adopt | §4.3 |

#### Implicit prerequisites pulled in by this decision

- **Headers audit (Rank 8)** — challenge parsing relies on case-insensitive header lookup. Pulled forward as a prerequisite.
- **Used by Rank 6** — `BearerTokenAuthStep.authorizeRequestOnChallenge` will inspect the `WWW-Authenticate` header via a `ChallengeHandler` if one is configured.
- **Used by Rank 16** (`ProxyOptions`) — proxy auth uses the same handler chain.

#### Kotlin shape we'll build

```kotlin
interface ChallengeHandler {
    /** Apply the right Authorization (or Proxy-Authorization) header to [request] given the challenges. */
    fun handleChallenge(request: Request, response: Response, isProxy: Boolean)

    /** Can this handler deal with any challenge in the response? */
    fun canHandle(response: Response, isProxy: Boolean): Boolean

    /** Lower-level: handle pre-parsed challenges directly. */
    fun handleChallenges(method: HttpMethod, uri: URI, challenges: List<AuthenticateChallenge>): Pair<String, AuthenticateChallenge>?

    fun canHandle(challenges: List<AuthenticateChallenge>): Boolean

    companion object {
        /** Compose handlers — first-handler-that-can-handle wins. Order Digest before Basic. */
        fun of(vararg handlers: ChallengeHandler): ChallengeHandler = CompositeChallengeHandler(handlers.toList())
    }
}

data class AuthenticateChallenge(
    val scheme: String,                         // "Basic", "Digest", "Bearer", ...
    val parameters: Map<String, String>,        // realm, qop, nonce, opaque, ...
)

object AuthChallengeParser {
    /** Parses a `WWW-Authenticate` / `Proxy-Authenticate` header value. May contain multiple challenges. */
    fun parse(header: String): List<AuthenticateChallenge> { /* ... */ }
}

class BasicChallengeHandler(username: String, password: String) : ChallengeHandler {
    private val authHeader: String = "Basic " +
        Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    // ...
}

class DigestChallengeHandler(
    private val username: String,
    private val password: String,
    private val algorithm: DigestAlgorithm = DigestAlgorithm.MD5,
) : ChallengeHandler {
    private val nonceCount = AtomicInteger(0)
    // Implements RFC 7616: HA1, HA2, response computation, qop=auth path, nc, cnonce
    // ...
}
```

#### Open questions still to settle

- **Digest algorithm support**: RFC 7616 defines `MD5`, `MD5-sess`, `SHA-256`, `SHA-256-sess`, `SHA-512-256`, `SHA-512-256-sess`. **Lean: MD5 + SHA-256 + their `-sess` variants for v1**; reject others with a clear error. Most enterprise servers still issue MD5; SHA-256 is the modern upgrade.
- **`auth-int` qop**: Requires body-hashing during the challenge. Skips streaming. **Lean: declined for v1** — only support `qop=auth` (most servers don't issue `auth-int` anyway).
- **Mutual auth (`rspauth`)**: RFC 7616 §3.5 server-side response authentication. **Lean: declined for v1** — niche.

#### Implementation guardrails

**Edge cases**
- Empty `WWW-Authenticate` header → return false from `canHandle`; no challenges to handle.
- Multiple challenges in a single header (comma-separated) → parse all; first one we can handle wins.
- Malformed challenge (e.g., unbalanced quotes) → skip with verbose log; don't throw.
- Quoted-string with embedded commas → preserve correctly (`realm="foo, bar"` is one realm, not two).
- Quoted-string with embedded quotes (`\"`) → unescape per RFC 7235.
- Algorithm mismatch (server demands SHA-512-256 but client only does MD5+SHA-256) → don't match the challenge; `canHandle` returns false.
- Reserved chars in nonce/cnonce (non-ASCII, spaces) → quote-wrap on emit.
- `nc` (nonce count) wraps after 0xffffffff → reset and refresh nonce; document.
- Same nonce used across many requests → `nc` increments correctly; tested.

**Performance / allocations**
- `AuthenticateChallenge` parsing produces one `Map<String, String>` per challenge. Small (typically ≤6 params).
- `MessageDigest.getInstance("MD5")` per Digest computation — not thread-safe so per-call init is required. Cost is microseconds; acceptable.
- Pre-compute HA1 if username/password are stable (cache per `(realm, algorithm)` tuple).
- `StringBuilder` with reserved capacity for response hash construction.
- `Base64.getEncoder().encodeToString` for Basic — JDK-internal, fast.

**Non-happy paths**
- `MessageDigest.getInstance("MD5")` throws `NoSuchAlgorithmException` → wrap in `IllegalStateException("MD5 unavailable on JVM")` (should never happen but document).
- Concurrent access to same `DigestChallengeHandler` instance from multiple threads → `nc` counter is `AtomicInteger`; safe. HA1 cache uses `ConcurrentHashMap`.
- Challenge with `qop="auth-int"` ONLY (not auth) → can't handle (per declined sub-decision); return false from `canHandle`.
- Empty username/password → throw `IllegalArgumentException` in handler constructor.

**Codestyle**
- `interface ChallengeHandler` (existing convention).
- `class BasicChallengeHandler(username, password)` — pre-computes `authHeader` at construction.
- `class DigestChallengeHandler(username, password, algorithm: DigestAlgorithm = MD5)` — `algorithm` enum.
- `data class AuthenticateChallenge(scheme, parameters)`.
- `object AuthChallengeParser { fun parse(header: String): List<AuthenticateChallenge> }`.
- `AtomicInteger` for `nc`; `ConcurrentHashMap` for HA1 cache.

- **Notes**:

### ✅ Rank 13 — Memory-mapped `FileRequestBody.toByteBuffer()`

- **What**: `FileChannel.map(READ_ONLY)` for `FileRequestBody`.
- **Why**: Zero-heap local hashing / signing for large files.
- **Effort**: Small.
- **Status**: ✅ **Agreed** (from comp report §5 partial).

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| `FileRequestBody.toByteBuffer(): ByteBuffer` via `FileChannel.map(READ_ONLY, position, count)` | ✅ Adopt |
| Returned `ByteBuffer` is read-only (`asReadOnlyBuffer()`) | ✅ Adopt |
| Mapping survives the channel close (relies on OS page cache lifecycle) | ✅ Adopt — Java NIO semantics |
| Document that the mapping holds a file handle implicitly (Linux unmap is non-deterministic until GC) | ✅ Adopt — kdoc on the method |

#### Kotlin shape

```kotlin
class FileRequestBody(/* existing constructor */) : RequestBody() {
    // ... existing members ...

    /**
     * Returns a read-only memory-mapped view of the file region. Useful for local hashing
     * or signature computation without copying bytes onto the heap.
     *
     * The returned [ByteBuffer] survives the underlying [FileChannel] close — its lifetime is
     * tied to GC of the buffer itself, not to any channel.
     */
    @Throws(IOException::class)
    fun toByteBuffer(): ByteBuffer {
        check(count <= Integer.MAX_VALUE.toLong()) {
            "FileRequestBody.toByteBuffer(): mapped region must fit in Int range " +
                "(count=$count > ${Integer.MAX_VALUE}). Map a smaller slice manually for larger files."
        }
        return FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, position, count).asReadOnlyBuffer()
        }
    }
}
```

#### Implementation guardrails

**Edge cases**
- **`count > Integer.MAX_VALUE` (~2 GiB) → throw `IllegalStateException` at the API boundary.** `ByteBuffer.limit` can't exceed `Int.MAX_VALUE`; `FileChannel.map` accepts `long count` but the resulting buffer is bounded by Int. Caller can `toByteBuffer()` on multiple smaller slices.
- File size 0 → map of length 0 — Java NIO allows; returned buffer has `remaining = 0`.
- File modified during mapping → behavior is OS-defined (Linux: copy-on-write semantics for the mapping; Windows: may fail). Document.
- File deleted while mapped → Linux: mapping survives until GC; Windows: mapping invalidated. Document.
- Mapping memory exhaustion → throws `IOException` from `FileChannel.map`.
- Read-only mapping but caller tries to write to the buffer → `ReadOnlyBufferException` (already enforced by `asReadOnlyBuffer()`).

**Performance / allocations**
- Memory-mapped reads are page-cached by the OS — fast for repeated reads.
- One `ByteBuffer` per call; the underlying mapping is OS-page-cached.
- `FileChannel` is closed via `.use { }` — the mapping survives the channel close (Java NIO guarantee).

**Non-happy paths**
- File doesn't exist (race with deletion between FileRequestBody construction and `toByteBuffer` call) → `NoSuchFileException`.
- Permission denied → `AccessDeniedException`.
- `position + count > Files.size(file)` after a truncation → `IOException` from `FileChannel.map`.

**Codestyle**
- Add `fun toByteBuffer(): ByteBuffer` to existing `FileRequestBody` class in `sdk-core/http/request/`.
- `@Throws(IOException::class)`.
- KDoc explicitly notes the mapping lifetime is GC-bound on Linux.

- **Notes**:

### ✅ Rank 14 — `ServerSentEventReader`

- **What**: Reads SSE protocol from a `BufferedSource`. `ServerSentEvent(id, event, data: List<String>, comment, retry)` per the spec's multi-line data fields.
- **Why**: LLM/AI streaming APIs use SSE; real-time data feeds.
- **Effort**: Medium (parser + tests).
- **Status**: ✅ **Agreed** (from comp report §9).

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| `ServerSentEvent(id, event, data: List<String>, comment, retry: Duration?)` — full spec compliance | ✅ Adopt |
| `data: List<String>` (one entry per `data:` line) — preserves multi-line data fields | ✅ Adopt |
| `ServerSentEventReader(BufferedSource)` with `next(): ServerSentEvent?` (null at stream end) | ✅ Adopt |
| Listener interface `ServerSentEventListener.onEvent / onError / onClose` (default-method `onError`/`onClose`) | ✅ Adopt |
| `BufferedSource.readServerSentEvents(): Sequence<ServerSentEvent>` extension for `for`-loop style | ✅ Adopt |
| Coroutine `Flow` adapter | ⏸️ Deferred to optional `sdk-sse-coroutines` module |
| Reconnect-handling | ❌ Out of scope — caller-side concern (the SSE retry field is exposed; reconnecting is application logic) |

#### Kotlin shape

```kotlin
data class ServerSentEvent(
    val id: String? = null,
    val event: String? = null,
    val data: List<String> = emptyList(),
    val comment: String? = null,
    val retry: Duration? = null,
)

class ServerSentEventReader(private val source: BufferedSource) {
    /** Returns the next event, or null at stream end. */
    fun next(): ServerSentEvent? { /* parse per https://html.spec.whatwg.org/multipage/server-sent-events.html */ }
}

fun interface ServerSentEventListener {
    fun onEvent(event: ServerSentEvent)
    fun onError(t: Throwable) { /* default no-op */ }
    fun onClose() { /* default no-op */ }
}

fun BufferedSource.readServerSentEvents(): Sequence<ServerSentEvent> =
    generateSequence { ServerSentEventReader(this).next() }
```

#### Implementation guardrails

**Edge cases**
- **BOM (`﻿`) at stream start** → consume per spec (mandatory; tested).
- Empty event (just `\n\n`) → discard; don't emit a default-valued event.
- Event with only `id:` (no `data:`, no `event:`) → emit with null event and empty data list per spec.
- Event with `data:` and no terminator at stream end → emit on stream-end with the accumulated data.
- `:` comment lines → skip; don't emit.
- `id:` with embedded null bytes → invalid per spec; replace null with `�` (replacement char) or skip — pick one and document.
- `retry:` field that doesn't parse as int → ignore per spec.
- Unknown field names (anything other than `id`, `event`, `data`, `retry`) → discard per spec.
- **Line endings**: support `\n`, `\r`, and `\r\n` (use `BufferedSource.readUtf8Line` which handles all three).
- Whitespace handling: per spec, ONE optional space after the colon (`field: value`) is consumed; further whitespace is part of the value.
- Multi-line data (`data: line1\ndata: line2\n\n`) → emit as `data: List("line1", "line2")`.

**Performance / allocations**
- One `ServerSentEvent` allocation per emitted event.
- Per-event state: accumulate `data` lines into a `MutableList<String>`; lazy init on first `data:` field.
- `BufferedSource.readUtf8Line` is efficient (no intermediate buffer for line termination handling).
- The sequence is lazy — consumer pulls events on demand; no buffering beyond one event.

**Non-happy paths**
- Underlying source throws during read → propagates through the sequence (consumer sees the exception on `next()`).
- Listener-based variant: `ServerSentEventListener.onError(t)` invoked; `onClose()` invoked when stream ends or errors.
- Reconnect / `retry:` handling → exposed as a `Duration?` field on `ServerSentEvent`; reconnecting is application logic, out of scope.

**Codestyle**
- `data class ServerSentEvent` with `@JvmOverloads` for Java-friendly construction.
- `class ServerSentEventReader(source: BufferedSource)`.
- `fun interface ServerSentEventListener` with default `onError` / `onClose` methods.
- Extension function `BufferedSource.readServerSentEvents()` for Kotlin idiom.

- **Notes**:

### ✅ Rank 15 — `PagedIterable<T>`

- **What**: Sequence-based pagination with HATEOAS links + continuation tokens + offset.
- **Why**: Required by any list-heavy service.
- **Effort**: Medium.
- **Status**: ✅ **Agreed** (from comp report §10).

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| `PagedResponse<T> : Response` carrying `value: List<T>` + paging metadata | ✅ Adopt |
| Paging metadata: `continuationToken`, `nextLink`, `previousLink`, `firstLink`, `lastLink` | ✅ Adopt — full HATEOAS link set |
| `PagingOptions`: `offset`, `pageSize`, `pageIndex`, `continuationToken` — covers all three addressing modes | ✅ Adopt |
| `PagedIterable<T> : Iterable<T>` flattening across pages | ✅ Adopt |
| `byPage()` for one-page-at-a-time `Sequence<PagedResponse<T>>` | ✅ Adopt |
| Empty-page handling: keep requesting until data arrives or `done` | ✅ Adopt |
| Both first-page-only and first+next constructors | ✅ Adopt |
| Coroutine `Flow` adapter | ⏸️ Deferred to optional `sdk-paging-coroutines` module |

#### Kotlin shape

```kotlin
class PagedIterable<T>(
    private val firstPage: (PagingOptions) -> PagedResponse<T>,
    private val nextPage: (PagingOptions, String) -> PagedResponse<T>? = { _, _ -> null },
) : Iterable<T> {
    fun byPage(options: PagingOptions = PagingOptions()): Sequence<PagedResponse<T>> = sequence {
        var page: PagedResponse<T>? = firstPage(options)
        while (page != null) {
            yield(page)
            val link = page.nextLink ?: page.continuationToken ?: break
            page = nextPage(options, link)
        }
    }

    override fun iterator(): Iterator<T> = byPage().flatMap { it.value.asSequence() }.iterator()
    fun stream(): Stream<T> = byPage().flatMap { it.value.asSequence() }.asStream()
}

class PagedResponse<T>(
    request: Request,
    statusCode: Int,
    headers: Headers,
    val value: List<T>,
    val continuationToken: String? = null,
    val nextLink: String? = null,
    val previousLink: String? = null,
    val firstLink: String? = null,
    val lastLink: String? = null,
) : Response(...)

class PagingOptions(
    var offset: Long? = null,
    var pageSize: Long? = null,
    var pageIndex: Long? = null,
    var continuationToken: String? = null,
)
```

#### Implementation guardrails

**Edge cases**
- First page is empty (`value.isEmpty()`) but `nextLink` is set → continue to next page.
- All pages are empty → done after the first call; iterator returns false on `hasNext()`.
- `nextLink` is empty string (`""`) AND `continuationToken` is empty string → treat as done.
- **`nextLink` is non-null but never changes (server bug returning same URL)** → infinite loop! Mitigation: optional max-page safety limit on the iterable (default `Long.MAX_VALUE`); document.
- `nextLink` leads to 404 → exception propagates on the next `hasNext()` / iterator advance.
- `iterator()` called multiple times → each call starts fresh (matches `Iterable` contract).
- `byPage()` and `iterator()` called simultaneously on same instance → each gets independent state via separate `Iterator` instances.

**Performance / allocations**
- Page-by-page fetch is sequential (no pre-fetch). Document; users wanting parallel pre-fetch wrap manually.
- One `PagedResponse<T>` allocation per fetched page.
- One iterator state per consumer.
- `byPage().flatMap` for `iterator()` — lazy; no intermediate `List`.

**Non-happy paths**
- Mid-iteration network failure → throws on `hasNext()` or `next()`.
- `pageRetriever` returns `null` page → treat as done (no exception).
- Consumer mutates the page's `value` list externally → undefined; document as caller's bug.

**Codestyle**
- `class PagedIterable<T>` implements `Iterable<T>`.
- `class PagedResponse<T>` extends `Response`; constructor takes the paging metadata as `String?` fields.
- `class PagingOptions` is mutable (matches caller-mutation patterns common to paging).
- `@JvmOverloads` on `PagedResponse` constructor.
- Java interop: `stream()` returns `Stream<T>`; uses `asStream()` from kotlinx-coroutines OR `StreamSupport.stream(...)` (Java 8 compat).

- **Notes**:

### ✅ Rank 16 — `ProxyOptions`

- **What**: Reads `https.proxyHost`/etc. system properties + `HTTPS_PROXY`/`HTTP_PROXY`/`NO_PROXY` env vars; pattern-matched `nonProxyHosts`; proxy auth via `ChallengeHandler`.
- **Why**: Enterprise deployment behind corporate proxies.
- **Effort**: Medium.
- **Status**: ✅ **Agreed** (from comp report §12.2).

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| Read `https.proxyHost`/`https.proxyPort`/`http.proxyHost`/`http.proxyPort` system properties | ✅ Adopt |
| Read `https.proxyUser`/`https.proxyPassword` system properties | ✅ Adopt |
| Read `HTTPS_PROXY`/`HTTP_PROXY`/`NO_PROXY` environment variables | ✅ Adopt |
| Read `http.nonProxyHosts` system property | ✅ Adopt |
| Pattern-matched `nonProxyHosts` with backslash-escape handling (`(?<!\\)\|` split for sys-prop, `(?<!\\),` for env-var) | ✅ Adopt |
| Wildcard support in non-proxy patterns (`*.internal.example.com`) | ✅ Adopt |
| `ProxyOptions(type, address)` direct constructor | ✅ Adopt |
| `ProxyOptions.fromConfiguration(Configuration)` factory — depends on Rank 9 | ✅ Adopt |
| `setCredentials(username, password)` for inline proxy auth | ✅ Adopt |
| `setChallengeHandler(ChallengeHandler)` for Digest proxy auth — depends on Rank 12 | ✅ Adopt |
| `ProxyOptions.Type`: `HTTP`, `SOCKS4`, `SOCKS5` | ✅ Adopt |

#### Implementation guardrails

**Edge cases**
- Both system property and env var set for the same proxy concept → system property wins (matches JDK convention).
- Invalid proxy port (non-numeric, `< 0`, `> 65535`) → reject in builder; `IllegalArgumentException`.
- `nonProxyHosts` with mixed wildcards (`*.internal.example.com|localhost|127.*`) → support all; pattern-compile each clause once at construction.
- Empty `nonProxyHosts` list → no host is exempt; everything routes through proxy.
- IPv6 proxy address → supported via `InetSocketAddress`; bracket form (`[::1]:8080`) handled.
- `HTTPS_PROXY` with embedded credentials (`http://user:pass@proxy:8080`) → parse out userinfo into `username` / `password`.
- `NO_PROXY = "*"` → proxy nothing (special case).
- Mixed separators in env vars: `NO_PROXY` uses `,`; `http.nonProxyHosts` uses `|`. Honor both via the right split regex per source.
- Backslash-escape: `(?<!\\\\),` for NO_PROXY, `(?<!\\\\)\|` for nonProxyHosts. Document the regex.

**Performance / allocations**
- Patterns compiled once at construction (`Pattern.compile`).
- Per-request match against `nonProxyHosts` patterns — usually <5 patterns; linear scan is fine.
- `ProxyOptions` instance is immutable after construction.

**Non-happy paths**
- `ProxyOptions.fromConfiguration` with invalid proxy URL → log warning + return null (don't throw — proxy is optional).
- `ChallengeHandler` not provided AND proxy demands auth → request fails with 407; document.
- Both `username`/`password` AND `challengeHandler` set → `challengeHandler` wins (more flexible).

**Codestyle**
- `class ProxyOptions` is immutable.
- `enum class Type { HTTP, SOCKS4, SOCKS5 }`.
- `@JvmOverloads` on constructor.
- `companion object { @JvmStatic fun fromConfiguration(config: Configuration): ProxyOptions? }`.
- `Pattern` instances are `private val`.

- **Notes**: Depends on Rank 9 (`Configuration`) and Rank 12 (`ChallengeHandler`) — both now agreed.

### ✅ Rank 17 — `randomUuid()` without `SecureRandom` blocking

- **What**: `ThreadLocalRandom`-based v4 UUID generator.
- **Why**: Avoids `/dev/random` blocking trap that `UUID.randomUUID()` hits under load.
- **Effort**: Tiny (one utility function).
- **Status**: ✅ **Agreed** (from comp report §14.1). Used by `RequestIdStep` and `InstrumentationContext` trace-id generation.

#### Kotlin shape

```kotlin
internal object Uuids {
    /** Type-4 UUID via [ThreadLocalRandom]. Non-cryptographic but non-blocking. */
    fun random(): UUID {
        val tlr = ThreadLocalRandom.current()
        var msb = tlr.nextLong()
        var lsb = tlr.nextLong()
        msb = msb and 0xffffffffffff0fffL.toLong() or 0x0000000000004000L  // v4
        lsb = lsb and 0x3fffffffffffffffL or Long.MIN_VALUE                // IETF variant
        return UUID(msb, lsb)
    }
}
```

#### Implementation guardrails

**Edge cases**
- Concurrent calls from many threads → `ThreadLocalRandom` is per-thread; no contention.
- Very rare collision → type-4 UUID; non-cryptographic but collision probability is still negligible for non-cryptographic use cases (request IDs, trace IDs).

**Performance / allocations**
- One `UUID` allocation per call. No `SecureRandom` seeding cost.
- `ThreadLocalRandom.current()` is a thread-local lookup; fast.

**Codestyle**
- `internal object Uuids` (not part of public API).
- `fun random(): UUID` only.

- **Notes**:

### ✅ Rank 18 — `SetDateStep` + `DateTimeRfc1123`

- **What**: Adds `Date` header in RFC 1123 format. Date util handles both emit + parse.
- **Why**: Required by S3, signed-URL services, and `Retry-After` parsing (Rank 4).
- **Effort**: Small (`DateTimeRfc1123`); trivial (step).
- **Status**: ✅ **Agreed** (both halves).
  - ✅ **`DateTimeRfc1123` (parse + emit)** — pulled forward as a Rank 4 prerequisite. Will ship in the same phase as retry.
  - ✅ **`SetDateStep`** (was `SetDateStep` in comp report — renamed to match our `*Step` convention) — agreed via comp report §14. Lives at `POST_RETRY` stage so the Date header is fresh on every retry attempt.

#### Sub-decisions

| Sub-feature | Decision |
|---|---|
| `DateTimeRfc1123.parse(String): Instant` | ✅ Adopt — needed by Rank 4 `Retry-After` parsing |
| `DateTimeRfc1123.format(Instant): String` | ✅ Adopt |
| `SetDateStep` declares `stage = Stage.POST_RETRY` (fresh per attempt, not cached for whole request) | ✅ Adopt |
| Falls back to JDK `DateTimeFormatter` if `DateTimeRfc1123.format` throws (defensive) | ✅ Adopt |

#### Implementation guardrails

**Edge cases**
- Date header already present on request → overwrite (matches Azure behavior).
- System clock skew → emit current local time anyway; servers tolerate small drift.
- `DateTimeRfc1123.parse` with extra whitespace, lowercase month → tolerate (`"mon, 01 jan 2024 00:00:00 GMT"` parses).
- `DateTimeRfc1123.parse` with comma after weekday but no space → strict parse, throws.
- `DateTimeRfc1123.parse` with non-GMT zone (e.g., `+0000`, `UTC`) → tolerate per RFC's "obsolete" variants.
- `Instant` before EPOCH → format produces `Thu, 01 Jan 1970 00:00:00 GMT` etc.

**Performance / allocations**
- `DateTimeFormatter.ofPattern` cached as static field.
- One `String` per emit (the formatted date).
- `Instant.now()` allocates an `Instant` per call (unavoidable).

**Non-happy paths**
- `DateTimeRfc1123.parse` on garbage input → `DateTimeParseException` (matches Java convention).
- `DateTimeRfc1123.format` on `Instant` outside the formatter's range → fallback to JDK `DateTimeFormatter.RFC_1123_DATE_TIME`.

**Codestyle**
- `object DateTimeRfc1123` with `@JvmStatic` `parse` / `format`.
- `class SetDateStep` overrides `stage = Stage.POST_RETRY`.
- `@Throws(DateTimeParseException::class)` on `parse`.

- **Notes**:

### 🔍 Rank 19 — Annotation-based interface clients (Retrofit/Azure paradigm)

- **What**: `@HttpRequestInformation`, `@PathParam`, `@QueryParam`, `@BodyParam`, `@HeaderParam` annotations + KSP code generator that emits client implementations from annotated interfaces.
- **Why**: Massive scaling for SDKs with many endpoints (especially generated from OpenAPI/TypeSpec specs).
- **Effort**: Large (KSP module, generator, runtime, tests).
- **Status**: ⏸️ **Deferred** — blocked on Section 5.1 (SDK target shape). Revisit when the target service shape is clearer; this is a bet on either 1-2 hand-written SDKs (skip) or many generated SDKs (build).
- **Notes**:

---

### ✅ Rank 20 — Metrics abstractions (`Meter`, `LongCounter`, `DoubleHistogram`)

- **What**: Abstract instrumentation interfaces for metric emission. Concrete OTel adapter deferred to `sdk-instrumentation-otel` module.
- **Why**: `InstrumentationStep` should emit request count / latency / status code / body size metrics — without these abstractions those numbers are stuck in log lines.
- **Effort**: Small (interfaces) — Phase B.
- **Status**: ✅ **Agreed**.

#### Kotlin shape

```kotlin
// sdk-core/instrumentation/metrics/
interface Meter {
    fun counter(name: String, description: String, unit: String): LongCounter
    fun histogram(name: String, description: String, unit: String): DoubleHistogram
}
interface LongCounter { fun add(value: Long, attributes: Map<String, Any> = emptyMap()) }
interface DoubleHistogram { fun record(value: Double, attributes: Map<String, Any> = emptyMap()) }

object NoopMeter : Meter { /* returns NoopCounter / NoopHistogram singletons */ }
```

`InstrumentationStep` accepts a `Meter` in its options; defaults to `NoopMeter` so no metrics are emitted unless the consuming app installs a real implementation.

#### Implementation guardrails

**Edge cases**
- `NoopMeter` returns shared `NoopCounter.INSTANCE` / `NoopHistogram.INSTANCE` singletons — zero allocation on noop paths.
- `Counter.add(negative)` → undefined by OpenTelemetry; document as caller-responsibility.
- `Histogram.record(NaN, Infinity)` → log + skip (don't propagate corrupted values).

**Performance / allocations**
- All `Noop*` methods are empty — JIT inlines to nothing.
- Shared `emptyMap()` for unattributed records (no allocation).
- Primitive overloads: `LongCounter.add(Long)` no boxing; attributed variant `add(Long, attrs)` only when needed.

**Codestyle**
- `interface Meter` / `LongCounter` / `DoubleHistogram` in `sdk-core/instrumentation/metrics/`.
- `object NoopMeter : Meter` (singleton).
- `internal object NoopCounter`, `internal object NoopHistogram` (returned only by NoopMeter).
- `@JvmOverloads` on `add` / `record` for Java-friendly two-arg overloads.

- **Notes**:

---

### ✅ Rank 21 — `Clock` abstraction

- **What**: `Clock` interface injectable into `RetryStep`, `BearerTokenAuthStep`, anything else with time-dependent behavior.
- **Why**: Without it, retry-timing tests have to sleep. With it, tests use `FixedClock`/`TickingClock`.
- **Effort**: Tiny (one interface, two impls, one test util).
- **Status**: ✅ **Agreed**. Phase A.

#### Kotlin shape

```kotlin
// sdk-core/util/
interface Clock {
    fun now(): Instant
    fun monotonic(): Long             // nanoTime; for elapsed-duration measurement
    fun sleep(duration: Duration)     // interruptible

    companion object { val SYSTEM: Clock = SystemClock() }
}

private class SystemClock : Clock {
    override fun now(): Instant = Instant.now()
    override fun monotonic(): Long = System.nanoTime()
    override fun sleep(duration: Duration) { Thread.sleep(duration.toMillis()) }
}

// In testFixtures:
class FixedClock(var current: Instant = Instant.EPOCH) : Clock { /* advance() helper */ }
```

`RetryStep` takes `clock: Clock = Clock.SYSTEM`; tests pass a `FixedClock`.

#### Implementation guardrails

**Edge cases**
- `monotonic()` rolls over (~292 years of nanos) → not a real concern in practice.
- `now()` before EPOCH → allowed by `FixedClock` for testing; `SystemClock` follows OS clock.
- `sleep(0)` → may yield, may not — caller-tolerant.
- `sleep(negative)` → throw `IllegalArgumentException`.
- `SystemClock.sleep` interrupted → `InterruptedException` re-thrown unchanged; caller decides how to handle (per Rank 22 convention).

**Performance / allocations**
- `Clock.SYSTEM` is a singleton; no allocation per access.
- `now()` allocates one `Instant` per call (unavoidable).
- `monotonic()` returns a primitive `Long`; no allocation.

**Codestyle**
- `interface Clock` in `sdk-core/util/`.
- `private class SystemClock : Clock` (only `Clock.SYSTEM` exposes an instance).
- `class FixedClock(var current: Instant)` lives in `testFixtures` source set.
- `@Throws(InterruptedException::class)` on `sleep`.

- **Notes**:

---

### ✅ Rank 22 — Cancellation convention (docs only)

- **What**: Documented convention that every blocking call in the SDK respects `Thread.interrupt()`. Aborts the current call with `InterruptedIOException`, classified as `isRetryable = false`.
- **Why**: Without a stated convention, behavior is implementation-defined and consumers can't reason about cancellation.
- **Effort**: Zero code; doc note in `docs/architecture.md`, `CLAUDE.md`, and step kdocs.
- **Status**: ✅ **Agreed**. Phase A.

#### Implementation guardrails

**What we document (no code change in `sdk-core` proper, but visible everywhere)**
- Every blocking method respects `Thread.interrupt()` — catches `InterruptedException`, calls `Thread.currentThread().interrupt()` to preserve the interrupt status, then throws `InterruptedIOException` (or the operation's natural failure exception with `InterruptedException` added as suppressed).
- `Thread.currentThread().isInterrupted` checked at the top of any loop bounded by user input (retry attempts, paged iteration, SSE event consumption).
- `RetryStep` aborts retries on interrupt; classified as `isRetryable = false`.
- `Loggable*Body` drain loops bail on interrupt.
- `BearerTokenAuthStep` token-refresh blocks but can be interrupted (caller's responsibility to interrupt the refreshing thread).

**Files to update**
- `docs/architecture.md` — add a "Cancellation" section.
- `CLAUDE.md` — add a one-liner in the conventions list pointing at the doc.
- Step base classes (`HttpStep`, `RetryStep`, `RedirectStep`, etc.) — kdoc references the convention.

- **Notes**:

---

### ⏸️ Rank 23 — `MultipartBody` builder

- **What**: `MultipartBody.Builder().addPart(...)` for multipart/form-data uploads with file + metadata mixing.
- **Why**: Common SDK requirement; not in Azure's `sdk-core`.
- **Status**: ⏸️ **Deferred** — pick up when a target service needs it.

- **Notes**:

---

### ⏸️ Rank 24 — Outbound / inbound compression (`CompressionStep`)

- **What**: Step at `PRE_SERDE` that adds `Accept-Encoding: gzip, deflate`, inflates gzip-encoded response bodies, optionally compresses outbound bodies above a size threshold.
- **Why**: Saves bandwidth; many APIs expect it.
- **Status**: ⏸️ **Deferred** — adopt when bandwidth-sensitive use case appears.

- **Notes**:

---

### ⏸️ Rank 25 — Circuit breaker step

- **What**: `CircuitBreakerStep` at `PRE_RETRY` that opens after N consecutive failures, transitions through half-open to closed.
- **Why**: Production resilience pattern beyond retry.
- **Status**: ⏸️ **Deferred** — significant complexity (state machine + recovery); revisit when targeting flaky upstreams.

- **Notes**:

---

### ✅ Rank 26 — Test infrastructure

- **What**: `FakeHttpClient` (queue of canned responses), `RequestRecorder` (captures requests), `MockResponse.Builder`, pipeline integration test helpers.
- **Why**: No step can be properly tested without these. Resolves Section 5.3.
- **Effort**: Small-medium.
- **Status**: ✅ **Agreed**. Phase A. Ships in a `testFixtures` source set of `sdk-core` (consumers pull via `testFixtures(project(":sdk-core"))`).

#### Kotlin shape

```kotlin
// sdk-core/src/testFixtures/kotlin/.../FakeHttpClient.kt
class FakeHttpClient : HttpClient {
    private val responses = ArrayDeque<MockResponse>()
    private val recorder = RequestRecorder()

    fun enqueue(response: MockResponse): FakeHttpClient = apply { responses.addLast(response) }
    fun enqueue(build: MockResponse.Builder.() -> Unit): FakeHttpClient = enqueue(MockResponse.Builder().apply(build).build())

    val requests: List<Request> get() = recorder.snapshot()
    val callCount: Int get() = recorder.callCount

    override fun execute(request: Request): Response {
        recorder.record(request)
        val mock = responses.pollFirst() ?: error("FakeHttpClient: no response enqueued for request to ${request.url}")
        if (mock.delay > Duration.ZERO) Thread.sleep(mock.delay.toMillis())
        return mock.toResponse(request)
    }
}

class MockResponse internal constructor(
    val statusCode: Int,
    val headers: Headers,
    val body: ResponseBody?,
    val delay: Duration,
) {
    class Builder { /* status(code), header(name, value), body(...), delay(d), build() */ }
}

class RequestRecorder {
    private val list = mutableListOf<Request>()
    fun record(r: Request) { synchronized(list) { list.add(r) } }
    fun snapshot(): List<Request> = synchronized(list) { list.toList() }
    val callCount: Int get() = synchronized(list) { list.size }
}
```

#### Implementation guardrails

**Edge cases**
- Pop from empty queue → throw `IllegalStateException("FakeHttpClient: no response enqueued for request to ${url}")`. Clear message so test failure points at the missing `enqueue()` call.
- `MockResponse.Builder.body(null)` → set null body (legal; some responses have no body).
- `MockResponse.delay > 0` → `Thread.sleep` to simulate latency. Document that tests should pair with `FixedClock` (Rank 21) to assert on the elapsed time without real sleep — use the clock for assertions; only use real `delay` for integration-style tests.
- `RequestRecorder.snapshot()` while another thread calls `record` → snapshot is consistent (synchronized).
- `FakeHttpClient` reused across tests → each `@Test` should construct a new instance (test isolation). Document.

**Performance / allocations**
- Test-only code; perf less critical.
- `ArrayDeque` for the response queue is the right structure (O(1) `pollFirst` and `addLast`).
- `synchronized(list)` blocks for `RequestRecorder` — fine for test scale.

**Non-happy paths**
- Test doesn't enqueue any response → first request throws clear error.
- Test enqueues fewer responses than expected requests → same clear error.
- Test enqueues more responses than consumed → ignored (no assertion that the queue is empty); callers can assert `responses.isEmpty()` explicitly.

**Codestyle**
- `class FakeHttpClient : HttpClient` — implements the existing `HttpClient` interface.
- `class MockResponse internal constructor(...)` with public `Builder`.
- `class RequestRecorder` — owns the captured requests; expose `snapshot()` for immutable view.
- Lives in `sdk-core/src/testFixtures/kotlin/...`. Consumers depend via `testFixtures(project(":sdk-core"))`.
- No external deps beyond what `sdk-core` itself has.

- **Notes**:

---

## Section 4 — Explicitly declined (`won't implement`)

Stated in `azure-comp-report.md` Section 16 — listed here for the record.

| Item | Why declined |
|---|---|
| ❌ Unified `BinaryData` body type | Our split `RequestBody`/`ResponseBody` matches wire-protocol asymmetry; unification would force every impl to support five accessors |
| ❌ Embedded Jackson Core | Bloat; `Serde` abstract in core, concrete impl in optional module |
| ❌ Embedded Aalto XML | Same reasoning; niche |
| ❌ Full OpenTelemetry SDK runtime dep | Keep `Tracer`/`Span`/`TracingScope` abstract; OTel adapter in separate module (same pattern as `sdk-io-okio3`) |
| ❌ `AccessibleByteArrayOutputStream`-style unsafe-array-return | Irrelevant for our segment-pool model |
| ❌ `AtomicReferenceFieldUpdater` everywhere | ~20ns savings per first-access vs `ReentrantLock`; not measurable |

---

## Section 5 — Open architectural questions

Things that need a decision before we can finalize the roadmap, beyond the per-item rankings.

### 5.1 SDK target shape

The annotation-based code generator (Rank 19) is only worth building if we expect to author **many** service SDKs from specs. The retry / redirect / auth steps are useful regardless.

**Resolved**: ⏸️ **Defer** — adapt and decide as we go. Rank 19 stays deferred until target shape is concrete. Everything else (Phases A-G) ships independently of this decision.

**Notes**:

### 5.2 OpenTelemetry adapter module

We've kept `Tracer` / `Span` / `TracingScope` interfaces abstract in `sdk-core`. Mirroring the `sdk-io-okio3` pattern, an `sdk-instrumentation-otel` adapter would provide the concrete implementation.

**Resolved**: Keep abstract interfaces in `sdk-core` (already present). **Concrete OTel adapter is deferred** to a future `sdk-instrumentation-otel` module — same pattern as `sdk-io-okio3` — to be built when tracing demand emerges. `InstrumentationStep` (Rank 3) consumes the abstract `Tracer` / `Span` types; works correctly with the `NoopSpan` / `NoopInstrumentationContext` defaults until a real OTel adapter is installed.

**Notes**:

### 5.3 Test infrastructure for pipeline

The pipeline rebuild needs tests that exercise `next.copy()` semantics (retry re-runs the chain), pillar singleton enforcement, type-based surgical edits, stage ordering. This is a non-trivial test surface.

**Resolved**: ✅ See Rank 26 — `FakeHttpClient` + `RequestRecorder` + `MockResponse.Builder` ship in `sdk-core`'s `testFixtures` source set as part of Phase A.

**Notes**:

---

## Section 6 — Suggested implementation phases

Tentative ordering once decisions are made. Phases group items that can ship as a single PR.

### Phase A — Pipeline foundation (🛠️ **IMPLEMENTED** — uncommitted as of handoff)
- ✅ Section 2.1 — pipeline architecture (`HttpPipeline`, `HttpPipelineBuilder`, `HttpStep`, `PipelineNext.copy()`, `Stage`, hybrid stage+type-based ops) — 14 tests
- ✅ Rank 21 — `Clock` abstraction — 7 tests
- ✅ Rank 22 — Cancellation convention (docs)
- ✅ Rank 26 — Test infrastructure (`FakeHttpClient`, `RequestRecorder`, `MockResponse.Builder`, `FixedClock`) — 16 tests

Total Phase A: 37 new tests passing; full build green (106 tests across modules).
**Next**: commit Phase A (see Session handoff at top), then start Phase B.

### Phase B — Safe HTTP logging end-to-end (✅ all agreed)
- Rank 1 — `UrlRedactor`
- Rank 2 — `ClientLogger`
- Rank 20 — `Meter` / `LongCounter` / `DoubleHistogram` (alongside `ClientLogger`)
- Rank 8 — Audit `Headers.kt` (pulled forward — needed by `ClientLogger` event serialization + `ChallengeHandler` parsing in Phase D)
- Rank 17 — `randomUuid()` (used by `RequestIdStep` + trace-id generation)
- Rank 9 — `Configuration` (also unblocks `MAX_RETRY_ATTEMPTS` env override deferred from Rank 4)
- Rank 3 — `InstrumentationStep`

### Phase C — Production steps (✅ all agreed)
- Rank 18 — `DateTimeRfc1123` (parse + emit; prerequisite of Rank 4)
- Rank 7 — `HttpResponseException` with `isRetryable` + `RetryUtils` shared classifier
- Rank 4 — `RetryStep`
- Rank 5 — `RedirectStep`
- Rank 18 — `SetDateStep` (small follow-on, lives at `POST_RETRY`)

### Phase D — Auth (✅ all agreed)
- Rank 6 — `Credential` hierarchy + `AuthStep` + `KeyCredentialAuthStep` + `BearerTokenAuthStep` + `AuthMetadata` / `AuthScheme.NO_AUTH`
- Rank 12 — `ChallengeHandler` (`Basic` + `Digest` with MD5 + SHA-256 variants); used by `AuthStep.authorizeRequestOnChallenge` and later by Rank 16

### Phase E — Type-safe HTTP semantics + body extras (✅ all agreed)
- Rank 10 — `HttpRange`, `ETag`, `RequestConditions`
- Rank 11 — `BufferedSource.slice(offset, byteCount)`
- Rank 13 — Memory-mapped `FileRequestBody.toByteBuffer()`

### Phase F — Streaming & list APIs (✅ all agreed)
- Rank 14 — `ServerSentEventReader` + `ServerSentEvent` + listener
- Rank 15 — `PagedIterable<T>` + `PagedResponse<T>` + `PagingOptions`

### Phase G — Enterprise deployment (✅ all agreed)
- Rank 16 — `ProxyOptions` with system-property / env-var integration

### Phase H — Deferred / decision-dependent
- Rank 19 — Annotation-based interface clients (still 🔍, blocked on Section 5.1)
- Future: `sdk-instrumentation-otel` adapter module (per Section 5.2 resolution)

---

## Section 7 — Status snapshot

All 26 ranks have been triaged. Recap:

| Status | Count | Ranks |
|---|---|---|
| ✅ Agreed | 23 | 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 21, 22, 26 |
| ⏸️ Deferred | 4 | 19 (annotation-based clients), 23 (multipart), 24 (compression), 25 (circuit breaker) |
| ❌ Declined | 0 | (separately tracked in Section 4 for items from comp-report Section 16) |

Section 5 open questions: all resolved (5.1 deferred with Rank 19; 5.2 keep abstract + defer concrete OTel adapter; 5.3 resolved by Rank 26).

**The roadmap is unblocked**. Phase A is **implemented (uncommitted)**; Phase B is next.

### Next step (fresh session)

1. Read the **Session handoff** block at the top of this doc.
2. Verify the working tree is clean of uncommitted state from elsewhere via `git status`. The uncommitted state from Phase A should match the handoff description.
3. Run `./gradlew build` — expect 106 tests green.
4. Make the two Phase A commits per the handoff instructions.
5. Begin **Phase B** using parallel local subagents (user preference). Phase B items: `UrlRedactor` (Rank 1), `ClientLogger` (Rank 2), `Meter`/`LongCounter`/`DoubleHistogram` (Rank 20), `Headers.kt` audit (Rank 8), `randomUuid()` (Rank 17), `Configuration` (Rank 9), `InstrumentationStep` (Rank 3).
   - Ranks 1, 2, 17, 20 can run in parallel (no interdependencies).
   - Rank 8 audits existing `Headers.kt` — independent.
   - Rank 9 (`Configuration`) is independent of the above but unblocks `MAX_RETRY_ATTEMPTS` env override later.
   - Rank 3 (`InstrumentationStep`) is the integration point and must land **last** — depends on ranks 1, 2, 20.

Suggested parallel-agent split for Phase B (mirroring Phase A's working pattern):
- Agent 1: `UrlRedactor` + `randomUuid` (small utility singletons, fast)
- Agent 2: `ClientLogger` + `LoggingEvent` + SLF4J 2.x `KeyValuePair` bridge
- Agent 3: `Meter` + `LongCounter` + `DoubleHistogram` + `NoopMeter` (instrumentation abstractions)
- Agent 4: `Headers.kt` audit + fixup (interning, case-insensitive identity, multi-value)
- Agent 5: `Configuration` + `ConfigurationBuilder`
- (Then) Agent 6: `InstrumentationStep` integrating the above

Each agent should follow the Section 2.0 "How to introduce a new step" walkthrough and the per-rank Implementation guardrails subsections.
