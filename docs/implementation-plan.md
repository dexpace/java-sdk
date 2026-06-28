# Implementation Plan — Tier 1 + Tier 2 Features

Drives the [refs-comparison](refs-comparison.md) backlog. Optimized for parallel subagent
execution: each work unit (WU) is sized so a single subagent can land it independently, with
explicit dependencies, file scopes, and conflict surfaces.

**Status: all ten work units have shipped.** Phases 0 and 1 landed as designed; Phase 2 (auth)
landed in `sdk-core` rather than a standalone `sdk-auth` module — see WU-10 for where the code
actually lives and how the shape differs from the original sketch. Each WU below carries a
**Status** line so the plan doubles as the as-built record. Tier 3 remains the live backlog.

## Table of Contents

- [Overview](#overview)
- [Dependency Graph](#dependency-graph)
- [Conflict Map](#conflict-map)
- [Phase 0 — Foundational (2 parallel WUs)](#phase-0--foundational-2-parallel-wus)
  - [WU-1: AfterError pipeline upgrade](#wu-1-aftererror-pipeline-upgrade)
  - [WU-2: Typed HttpException hierarchy](#wu-2-typed-httpexception-hierarchy)
- [Phase 1 — Features & Adapters (7 parallel WUs)](#phase-1--features--adapters-7-parallel-wus)
  - [WU-3: Retry pipeline step](#wu-3-retry-pipeline-step)
  - [WU-4: HttpClient.close() lifecycle](#wu-4-httpclientclose-lifecycle)
  - [WU-5: Idempotency-Key step](#wu-5-idempotency-key-step)
  - [WU-6: Client identity header step](#wu-6-client-identity-header-step)
  - [WU-7: Tracer event vocabulary](#wu-7-tracer-event-vocabulary)
  - [WU-8: sdk-serde-jackson adapter module](#wu-8-sdk-serde-jackson-adapter-module)
  - [WU-9: Pagination primitives](#wu-9-pagination-primitives)
- [Phase 2 — Auth (1 WU)](#phase-2--auth-1-wu)
  - [WU-10: auth](#wu-10-auth-shipped-in-sdk-core-not-a-separate-module)
- [Tier 3 (deferred)](#tier-3-deferred)
- [Execution Protocol](#execution-protocol)
- [Subagent Briefing Template](#subagent-briefing-template)

---

## Overview

Three sequenced phases. Within each phase, all work units run in parallel.

| Phase | WUs | Parallelism | Blocks |
|---|---|---|---|
| 0 | WU-1, WU-2 | 2 agents | All of Phase 1+2 |
| 1 | WU-3 .. WU-9 | 7 agents | Phase 2 (auth needs WU-3 + WU-2) |
| 2 | WU-10 | 1 agent | — |

Total: **10 work units**. Wall-clock time with full parallelism ≈ time of the slowest WU
per phase × 3 phases.

## Dependency Graph

```
                ┌────────────────────────────────────────────┐
                │ Phase 0 — foundational                     │
                │                                            │
                │  WU-1 AfterError      WU-2 HttpException  │
                │  pipeline upgrade     hierarchy           │
                └───────┬─────────────────────┬──────────────┘
                        │                     │
        ┌───────────────┼─────────────┬───────┼──────┬───────────┬─────────────┐
        ▼               ▼             ▼       ▼      ▼           ▼             ▼
   ┌─────────┐  ┌──────────────┐ ┌────────┐ ┌──────┐ ┌────────┐ ┌────────┐ ┌──────────┐
   │ WU-3    │  │ WU-4         │ │ WU-5   │ │ WU-6 │ │ WU-7   │ │ WU-8   │ │ WU-9     │
   │ Retry   │  │ HttpClient.  │ │ Idemp. │ │ ID   │ │ Tracer │ │ Serde  │ │ Paginate │
   │         │  │ close()      │ │ Key    │ │ hdr  │ │ events │ │ Jackson│ │          │
   └────┬────┘  └──────────────┘ └────────┘ └──────┘ └────────┘ └────────┘ └──────────┘
        │             Phase 1 — parallel features + adapter modules
        │
        ▼
   ┌─────────┐
   │ WU-10   │   Phase 2 — auth (needs WU-2 typed exceptions + WU-3 retry semantics)
   │ Auth    │
   └─────────┘
```

WU-8 and WU-9 are technically independent of Phase 0 (new modules/packages, no shared
files) but are grouped in Phase 1 so the 7-agent batch is one cohesive parallel wave.

## Conflict Map

Files that more than one work unit might touch — surface these to each subagent so they
don't trip over each other.

| File / path | Touched by | Resolution |
|---|---|---|
| `sdk-core/pipeline/step/` (directory — add new files only) | WU-3, WU-5, WU-6 | Each adds its own file(s); no shared index. Safe. |
| `sdk-core/client/HttpClient.kt` | WU-4 only | WU-3/5/6/7 must NOT touch this file. |
| `sdk-core/client/AsyncHttpClient.kt` | WU-4 only | Same. |
| `sdk-core/instrumentation/` | WU-7 only | WU-3/5/6 emit no tracer events in this pass — wired in a follow-up. |
| `sdk-core/pipeline/ResponsePipeline.kt` and step contracts | WU-1 only | WU-3 reads the contract WU-1 produces but does not modify pipeline types. |
| `sdk-core/http/response/Status.kt` | WU-2 only | New exception package alongside, but Status itself only WU-2. |
| `sdk-transport-okhttp/`, `sdk-transport-jdkhttp/` | WU-4 only (lifecycle) | Other WUs don't touch transport modules. |
| `settings.gradle.kts` | WU-8 (sdk-serde-jackson) | WU-10's auth landed in `sdk-core`, so no second `settings.gradle.kts` edit materialized. |

## Phase 0 — Foundational (2 parallel WUs)

### WU-1: AfterError pipeline upgrade

**Status: shipped.** `ResponseOutcome` (sealed `Success`/`Failure`), `ResponseRecoveryStep`, the
recovery-aware `ResponsePipeline`, and `ExecutionPipeline` are all in `sdk-core/.../pipeline`.

**Goal.** Replace the empty `ResponsePipeline` placeholder with Airbyte-style recovery
semantics. Add a third step type that takes `Either<Response, Throwable>` and can rescue or
rethrow. Funnel **all** exceptions through this path uniformly (don't repeat Airbyte's bug
where `BeforeRequest` throws bypass `AfterError`).

**Dependencies:** none.

**Files (create):**
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline/step/ResponseRecoveryStep.kt` — new `fun interface`; recovery type alias.
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline/ResponseOutcome.kt` — sealed class `ResponseOutcome { data class Success(response); data class Failure(throwable) }`. Simpler than `Either<>`.

**Files (modify):**
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline/ResponsePipeline.kt` — promote from empty interface to a fold over `(ResponsePipelineStep+, ResponseRecoveryStep+)` lists. Document semantics.
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline/ExecutionPipeline.kt` — wire request → transport → outcome → recovery → response steps. Catch transport exceptions, wrap into `ResponseOutcome.Failure`, feed through the recovery chain.
- `docs/pipelines.md` — update to reflect new architecture.

**Acceptance criteria:**
- A `ResponseRecoveryStep` can convert a failure into a success (rescue case).
- A `ResponseRecoveryStep` can leave the failure intact (passthrough case).
- A `ResponseRecoveryStep` can replace the throwable with a different one (re-mapping).
- Exceptions thrown inside any pipeline step (request, response, recovery) are caught and routed through subsequent recovery steps — never bypass the chain.
- Unit tests cover all four paths plus the "all steps no-op" baseline.

**Estimated complexity:** Medium. ~2-3 days of careful work + tests.

---

### WU-2: Typed HttpException hierarchy

**Status: shipped.** The hierarchy lives in `sdk-core/.../http/response/exception` — `HttpException`,
the per-status subclasses in `HttpExceptions.kt`, `NetworkException`, and `HttpExceptionFactory`.
One change from the original sketch: `retryable` is **derived**, not hand-set per subclass. The
base class computes `retryable = RetryUtils.isRetryable(status.code)` once at construction, so the
flag can never drift from the live retry policy. That single source of truth also fixes two codes
the original per-subclass table got wrong: **408 is retryable** (it has its own
`RequestTimeoutException`) and **501 / 505 are not**.

**Dependencies:** none.

**Files (create):**
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/response/exception/HttpException.kt` — abstract base. Fields: `status: Status`, `headers: Headers`, `body: ResponseBody?`, `retryable: Boolean` (derived from `RetryUtils.isRetryable(status.code)`), `cause: Throwable?`.
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/response/exception/HttpExceptions.kt` — concrete subclasses, one per common status (none sets its own `retryable`; the base derives it):
  - `BadRequestException` (400)
  - `UnauthorizedException` (401)
  - `ForbiddenException` (403)
  - `NotFoundException` (404)
  - `MethodNotAllowedException` (405)
  - `RequestTimeoutException` (408, retryable)
  - `ConflictException` (409)
  - `GoneException` (410)
  - `PayloadTooLargeException` (413)
  - `UnsupportedMediaTypeException` (415)
  - `UnprocessableEntityException` (422)
  - `TooManyRequestsException` (429, retryable)
  - `InternalServerErrorException` (500, retryable)
  - `BadGatewayException` (502, retryable)
  - `ServiceUnavailableException` (503, retryable)
  - `GatewayTimeoutException` (504, retryable)
  - `ClientErrorException` / `ServerErrorException` — generic 4xx / 5xx fallbacks.
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/response/exception/NetworkException.kt` — sibling type (no status; for connect/read failures; always retryable).
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/response/exception/HttpExceptionFactory.kt` — `fun fromResponse(response): HttpException` switch.

**Files (modify):**
- `docs/http.md` — document the hierarchy.

**Acceptance criteria:**
- `HttpExceptionFactory.fromResponse(response)` returns the correct subclass for each canonical status.
- Unknown status falls back to either `ClientErrorException` (4xx) or `ServerErrorException` (5xx) generic class.
- `retryable` is read-only and derived at construction from `RetryUtils.isRetryable(status.code)`, so it always mirrors the live retry policy.
- Body is exposed as `ResponseBody?` (lazy stream), **not** eagerly buffered into a string.
- `@JvmOverloads` on public constructors.
- Unit tests for factory dispatch + retryable flag values.

**Estimated complexity:** Low-Medium. ~1.5-2 days.

## Phase 1 — Features & Adapters (7 parallel WUs)

### WU-3: Retry pipeline step

**Status: shipped.** `RetrySettings`, `RetryStep`, `BackoffCalculator`, and `RetryAfterParser`
are all in `sdk-core/.../pipeline/step/retry`.

**Goal.** Build the best-in-class retry step combining Square's `Retry-After` /
`X-RateLimit-Reset` parsing with gax's per-attempt shrinking deadline algorithm.

**Dependencies:** WU-1 (uses `ResponseRecoveryStep`), WU-2 (uses `HttpException.retryable`).

**Files (create):**
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline/step/retry/RetrySettings.kt` — immutable settings via Builder: `totalTimeout`, `initialDelay`, `delayMultiplier`, `maxDelay`, `maxAttempts`, `jitter` (fraction 0.0..1.0), `retryableStatuses: Set<Int>`, `retryableMethods: Set<Method>`.
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline/step/retry/RetryStep.kt` — implements both `ResponseRecoveryStep` (decide retry on failure) and a `RequestPipelineStep` (records attempt start). Uses `ScheduledExecutorService` for delay (never `Thread.sleep`).
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline/step/retry/BackoffCalculator.kt` — exponential backoff + symmetric jitter; deadline-shrinking cap (per gax).
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline/step/retry/RetryAfterParser.kt` — `Retry-After` (numeric seconds OR HTTP date) + `X-RateLimit-Reset` (Unix epoch) parsing. Precedence: `Retry-After` numeric > `Retry-After` HTTP-date > `X-RateLimit-Reset` > exponential fallback.

**Files (modify):**
- `docs/architecture.md` — Cancellation section: cross-reference retry.

**Critical rules:**
- **Idempotency-aware.** Retry only when (a) request method is in `retryableMethods` (default: GET/HEAD/OPTIONS/PUT/DELETE), OR (b) request body's `isReplayable()` returns true.
- **No `Thread.sleep`.** Use `ScheduledExecutorService` injected via settings (default = lazily-created single-thread scheduler).
- **Interrupt-respect.** If interrupted while waiting for next attempt: restore flag via `Thread.currentThread().interrupt()`, throw `InterruptedIOException`.
- **Deadline shrinkage.** Each attempt's effective timeout = min(per-attempt-timeout, totalTimeout - elapsed - nextDelay). Don't let the last attempt exceed the user's total budget.

**Acceptance criteria:**
- Retries on 429/500/502/503/504; does not retry on 400/401/404.
- Does not retry POST without replayable body.
- Honors `Retry-After: 5` (5s wait, no jitter).
- Honors `Retry-After: <HTTP-date>` (parses date, computes delta).
- Honors `X-RateLimit-Reset: <unix epoch>` (positive jitter 100-120%).
- Exponential fallback when no headers present, with symmetric jitter.
- Stops retrying at `maxAttempts` or `totalTimeout`, whichever first.
- Tests cover virtual-thread safety (no carrier pinning during delay) using `Thread.ofVirtual()`.

**Estimated complexity:** High. ~4-5 days.

---

### WU-4: HttpClient.close() lifecycle

**Status: shipped.** `HttpClient` and `AsyncHttpClient` extend `AutoCloseable` with default no-op
`close()`; `OkHttpTransport` and `JdkHttpTransport` override it to release SDK-managed resources
(BYO clients are never closed).

**Goal.** Add `AutoCloseable` to `HttpClient` + `AsyncHttpClient`, plus close-on-error /
close-from-builder lifecycle. Wire transports to release their pools.

**Dependencies:** none.

**Files (modify):**
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/client/HttpClient.kt` — extend `AutoCloseable`. Default no-op `close()`.
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/client/AsyncHttpClient.kt` — same.
- `sdk-transport-okhttp/src/main/kotlin/.../OkHttpTransport.kt` — implement `close()`: shutdown `Dispatcher.executorService()`, `ConnectionPool.evictAll()`.
- `sdk-transport-jdkhttp/src/main/kotlin/.../JdkHttpTransport.kt` — implement `close()`: shutdown executor (if owned).

**Acceptance criteria:**
- Closing an `HttpClient` releases its resources (verified by checking the executor's `isShutdown()` for OkHttp; JDK's HttpClient is GC'd).
- Ownership distinction: user-supplied executors/dispatchers are NOT closed; SDK-created ones ARE.
- `close()` is idempotent (multiple calls are safe).
- `close()` is interrupt-safe (interrupted shutdown propagates correctly).
- Documented in `docs/architecture.md`.

**Estimated complexity:** Low. ~1-1.5 days.

---

### WU-5: Idempotency-Key step

**Status: shipped.** `IdempotencyKeyStep` is in `sdk-core/.../pipeline/step`.

**Goal.** Auto-inject `Idempotency-Key: UUID.randomUUID()` for `POST`/`PUT`/`PATCH` if the
header isn't already set. Allow caller override + custom UUID strategy.

**Dependencies:** none.

**Files (create):**
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline/step/IdempotencyKeyStep.kt` — implements `RequestPipelineStep`. Configurable:
  - `header: String = "Idempotency-Key"`
  - `methods: Set<Method> = setOf(POST, PUT, PATCH)`
  - `keyStrategy: () -> String = { UUID.randomUUID().toString() }`
  - `respectExisting: Boolean = true`

**Acceptance criteria:**
- Injects header on POST/PUT/PATCH when absent.
- Skips when header already present (`respectExisting = true`).
- Replaces existing header when `respectExisting = false`.
- Custom `keyStrategy` used when supplied.
- Unit tests cover all method/strategy combinations.

**Estimated complexity:** Low. ~0.5-1 day.

---

### WU-6: Client identity header step

**Status: shipped.** `ClientIdentityStep` is in `sdk-core/.../pipeline/step`; `SdkInfo` is in
`sdk-core/.../util`.

**Goal.** Adopt gax's composite token line. `User-Agent` and/or `X-Dexpace-Client` carrying
`dexpace-sdk/<sdkver> jvm/<javaver> <transport>/<ver>`.

**Dependencies:** none.

**Files (create):**
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline/step/ClientIdentityStep.kt` — implements `RequestPipelineStep`. Builds an ordered token list at construction (provided by builder); appends to existing `User-Agent` rather than overwrites.
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/util/SdkInfo.kt` — read SDK version from JAR manifest or generated `BuildInfo` constant; JVM version from `System.getProperty("java.version")`.

**Files (modify):**
- `sdk-core/build.gradle.kts` — embed version in resources or `BuildInfo` constant via `buildSrc` / generated source task.

**Acceptance criteria:**
- Default identity step emits `User-Agent: dexpace-sdk/<ver> jvm/<javaver>`.
- Transport adapters can register additional tokens (e.g. okhttp transport adds `okhttp/<ver>`).
- Existing user-set `User-Agent` is preserved (appended to, not replaced) unless explicitly overridden.
- Unit tests.

**Estimated complexity:** Low. ~1 day.

---

### WU-7: Tracer event vocabulary

**Status: shipped.** `HttpTracer`, `NoopHttpTracer`, and `HttpTracerFactory` (whose default impl
is `NoopHttpTracerFactory`) are in `sdk-core/.../instrumentation`; `InstrumentationContext` carries
an `httpTracerFactory` slot defaulting to the no-op factory.

**Goal.** Extend `InstrumentationContext` with named, RPC-shape-aware events. Mirror gax's
`ApiTracer` vocabulary scaled to HTTP. Keep defaults no-op so the change is non-breaking.

**Dependencies:** none.

**Files (create):**
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/HttpTracer.kt` — new interface with default no-op methods:
  - `operationStarted(operationName: String?)`
  - `operationSucceeded()`
  - `operationFailed(error: Throwable)`
  - `attemptStarted(attemptNumber: Int)`
  - `attemptFailed(error: Throwable, nextDelayMillis: Long?)`
  - `attemptRetriesExhausted(error: Throwable)`
  - `requestUrlResolved(url: String)`
  - `requestSent(byteCount: Long?)`
  - `responseHeadersReceived(status: Int, headers: Headers)`
  - `responseReceived(byteCount: Long?)`
  - `connectionAcquired(host: String, port: Int)`
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/NoopHttpTracer.kt` — singleton no-op impl.
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/HttpTracerFactory.kt` — factory SPI; `newTracer(operationName, attributes): HttpTracer`.

**Files (modify):**
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/InstrumentationContext.kt` — add `httpTracerFactory: HttpTracerFactory` field with no-op default; preserve existing `Span` API.

**Acceptance criteria:**
- Default factory returns a no-op tracer (no performance regression).
- A test custom tracer captures every event and asserts the expected sequence for a typical request lifecycle.
- Wiring into existing pipeline/transport is documented as a follow-up (separate WU) so this WU stays small.

**Estimated complexity:** Low-Medium. ~2 days.

---

### WU-8: sdk-serde-jackson adapter module

**Status: shipped.** The `sdk-serde-jackson` module is in `settings.gradle.kts`; it ships
`JacksonSerde`, `JacksonObjectMappers`, and `TristateModule`. The `Tristate<T>` type itself lives
in `sdk-core/.../serde` (it is part of the abstract surface, as planned).

**Goal.** New module providing a Jackson-backed `Serde` implementation with the right SDK
defaults (per Square: `FAIL_ON_UNKNOWN_PROPERTIES=false`, `WRITE_DATES_AS_TIMESTAMPS=false`,
`Jdk8Module`, `JavaTimeModule`). Plus a `Tristate<T>` sealed class for PATCH semantics.

**Dependencies:** none (new module).

**Files (create):**
- `sdk-serde-jackson/build.gradle.kts` — JVM target Java 8; deps: `sdk-core` + `com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2` + `jackson-datatype-jsr310` + `jackson-datatype-jdk8`.
- `sdk-serde-jackson/src/main/kotlin/org/dexpace/sdk/serde/jackson/JacksonSerde.kt` — implements `Serde<T>`.
- `sdk-serde-jackson/src/main/kotlin/org/dexpace/sdk/serde/jackson/JacksonObjectMappers.kt` — `defaultObjectMapper(): ObjectMapper` with SDK defaults.
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/serde/Tristate.kt` (in `sdk-core` because the type is part of the abstract surface) — sealed class with `Absent`, `Present<T>(value)`, `Null` cases; Jackson serializer/deserializer lives in `sdk-serde-jackson`.
- `sdk-serde-jackson/src/main/kotlin/org/dexpace/sdk/serde/jackson/TristateModule.kt` — Jackson module registering ser/de for `Tristate<T>`.

**Files (modify):**
- `settings.gradle.kts` — include `sdk-serde-jackson`.
- `docs/architecture.md` — register the new module.
- Root README.md — list new module.

**Acceptance criteria:**
- Round-trip serde of a representative model (nested DTO, list, optional, tri-state field).
- Tri-state distinguishes absent vs. null vs. value at both serialize and deserialize.
- `FAIL_ON_UNKNOWN_PROPERTIES=false` verified by deserializing a JSON with an extra field.
- ISO-8601 dates by default (not numeric timestamps).
- Builder-pattern friendly (recognizes `@JsonDeserialize(builder=...)` and `@JsonPOJOBuilder`).

**Estimated complexity:** Medium. ~2-3 days.

---

### WU-9: Pagination primitives

> Superseded by #30 (pagination unification): `Page` now exposes the raw per-page `Response` and is `Closeable` (materialized `items` and derived `statusCode` / `headers` / `request` survive `close()`), strategies return `PageInfo` (`nextRequest == null` = end of stream), and `SimplePage` was removed.

**Status: shipped.** `Page`, `Paginator`, `PaginationStrategy`, and the three strategies
(`Cursor` / `PageNumber` / `LinkHeader`) are in `sdk-core/.../pagination`, alongside
helper types `SimplePage` and `RequestRebuilder`. `Paginator` gained a `maxPages` safety cap
(default `Long.MAX_VALUE`) beyond the original sketch, to bound runaway iteration against servers
that never advance their cursor.

**Goal.** Add a generic pagination primitive set sized to cover the common cursor / page-number /
link-header strategies without over-engineering. Sync first; async adapter follow-up.

**Dependencies:** none (new package).

**Files (create):**
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pagination/Page.kt` — interface: `items: List<T>`, `hasNext: Boolean`, `nextPageRequest(): Request?`.
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pagination/Paginator.kt` — class wrapping `HttpClient` + initial `Request` + `PaginationStrategy<T>`. Exposes `iterateAll(): Iterable<T>` and `streamAll(): Stream<T>`.
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pagination/PaginationStrategy.kt` — interface: `parse(Response): Page<T>`. Implementations:
  - `CursorPaginationStrategy<T>(cursorPath, itemsPath, parser)` — read `next_cursor` from body
  - `PageNumberPaginationStrategy<T>(pageParam, itemsPath, parser)` — increment page number
  - `LinkHeaderPaginationStrategy<T>(itemsPath, parser)` — RFC 5988 `Link: <url>; rel="next"`
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/pagination/PaginatorTests.kt` (test) — table-driven tests against MockWebServer fixtures.

**Acceptance criteria:**
- Each strategy handles its golden-path fixture.
- `iterateAll()` is lazy — exhausting one page triggers exactly one fetch for the next.
- Empty page short-circuits even if `hasNext = true` (defensive, matches Square's pattern).
- Strategies handle URL parsing safely (Link header values with quoted segments, special chars).
- Strategy interface allows future `BiDirectionalPaginationStrategy` without breaking changes.

**Estimated complexity:** Medium. ~3-4 days.

## Phase 2 — Auth (1 WU)

### WU-10: auth (shipped in `sdk-core`, not a separate module)

**Status: shipped, but not as the `sdk-auth` module this WU sketched.** Auth landed inside
`sdk-core` rather than a standalone module, and the type names differ from the sketch below — the
abstractions are credential-and-challenge-shaped instead of `AuthProvider`-shaped:

- **Credentials** (`sdk-core/.../auth`): a sealed `Credential` interface with `BearerToken`
  (plus `BearerTokenProvider` for rotation) and `KeyCredential` / `NamedKeyCredential`. No
  `AuthProvider` / `BasicAuthProvider` / `OAuth2ClientCredentialsProvider` types — the planned
  OAuth client-credentials flow did **not** ship.
- **Challenge handling** (RFC 7235): `AuthChallengeParser` + `AuthenticateChallenge`, with
  `BasicChallengeHandler`, `DigestChallengeHandler`, and `CompositeChallengeHandler`.
- **Pipeline steps** (`sdk-core/.../http/pipeline/steps`): an abstract `AuthStep` pillar at
  `Stage.AUTH`, with concrete `BearerTokenAuthStep` and `KeyCredentialAuthStep`. The challenge
  retry hook lives on `AuthStep` itself rather than in a separate `UnauthorizedRecoveryStep`.

The remaining body of this WU is the original sketch, kept for the design rationale; the type
names and module layout above are authoritative.

**Goal.** Pluggable authentication. Pipeline-step-based, supports per-call override via
`RequestContext`, coalesces concurrent token refreshes, evicts cached tokens on 401.

**Dependencies:** WU-1 (uses `ResponseRecoveryStep` for 401-handling), WU-2 (uses
`UnauthorizedException`), WU-3 (uses retry semantics for OAuth token-fetch).

**Files (create):**
- `sdk-auth/build.gradle.kts` — JVM Java 8; deps: `sdk-core`.
- `sdk-auth/src/main/kotlin/org/dexpace/sdk/auth/AuthProvider.kt` — interface: `apply(request: Request): Request`. Optional `refresh(): AuthProvider` for token rotation.
- `sdk-auth/src/main/kotlin/org/dexpace/sdk/auth/BasicAuthProvider.kt` — Base64 of `user:pass`. Cached, thread-safe.
- `sdk-auth/src/main/kotlin/org/dexpace/sdk/auth/BearerTokenAuthProvider.kt` — static or `Supplier<String>`-backed bearer token.
- `sdk-auth/src/main/kotlin/org/dexpace/sdk/auth/ApiKeyAuthProvider.kt` — configurable header or query-param injection.
- `sdk-auth/src/main/kotlin/org/dexpace/sdk/auth/OAuth2ClientCredentialsProvider.kt` — full client_credentials flow: token endpoint, scopes, expiry buffer, `Clock` injection, **coalesced refresh** via `ConcurrentHashMap<CacheKey, CompletableFuture<Token>>.computeIfAbsent`.
- `sdk-auth/src/main/kotlin/org/dexpace/sdk/auth/AuthStep.kt` — `RequestPipelineStep` that reads `AuthProvider` from `RequestContext` (per-call) or from a client-default.
- `sdk-auth/src/main/kotlin/org/dexpace/sdk/auth/UnauthorizedRecoveryStep.kt` — `ResponseRecoveryStep` that on `UnauthorizedException` evicts the cached token and retries once.

**Files (modify):**
- `settings.gradle.kts` — include `sdk-auth`.
- `sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/context/RequestContext.kt` — add optional `authProvider: AuthProvider?` slot (just a typed key; sdk-core has no `sdk-auth` dependency, so use a generic attribute map).

**Anti-patterns to avoid (from Expedia/Airbyte):**
- **No `synchronized`** around network calls. Use `ReentrantLock` for cache mutation, `CompletableFuture` for refresh coalescing.
- **No `Thread.sleep`**, no `.join()` mid-pipeline.
- **No per-request reflection.** Concrete typed providers only.
- Refresh must NOT block other unrelated requests — partition by cache key.

**Acceptance criteria:**
- Concurrent calls during token expiry trigger exactly one refresh fetch.
- 401 response triggers eviction + single retry; second 401 propagates `UnauthorizedException`.
- Per-call override via `RequestContext` wins over client-default.
- OAuth token storage immutable; `Clock` injected for tests.
- All blocking calls respect `Thread.interrupt()`.
- Virtual-thread safety verified.

**Estimated complexity:** High. ~5-6 days.

## Tier 3 (deferred)

Out of scope for the phases above. Two of the original four have since shipped:

- **WU-11: webhook-signature verification** — HMAC-SHA256/SHA1 verifier with constant-time compare + replay protection (Square's `WebhooksHelper` done right). **Still unbuilt.**
- **WU-12: metrics seam** — **shipped** in `sdk-core/.../instrumentation/metrics` (`Meter`, `LongCounter`, `DoubleHistogram`, `NoopMeter`), distinct from the tracing vocabulary.
- **Configuration Settings → Context resolution split** — gax's `StubSettings` → `ClientSettings` → `ClientContext` pattern adapted. **Still unbuilt.**
- **Streaming responses (SSE)** — **shipped** as the `sdk-core/.../http/sse` package (`ServerSentEvent`, `ServerSentEventReader`, `ServerSentEventListener`); the Reactor adapter exposes the SSE → `Flux` backpressure path.

## Execution Protocol

Run order:

1. **Phase 0 launch.** Dispatch WU-1 and WU-2 as two parallel subagents. Each works on a git worktree (or branch) so they don't fight.
2. **Phase 0 gate.** When both are merged to a `phase-0` integration branch, run `./gradlew build` + tests to confirm baseline.
3. **Phase 1 launch.** Dispatch WU-3 through WU-9 as seven parallel subagents off the `phase-0` branch. Each on its own worktree/branch.
4. **Phase 1 integration.** Merge in order: WU-4 → WU-7 → WU-6 → WU-8 → WU-9 → WU-5 → WU-3 (retry last because it touches the most surface). Resolve conflicts only if they appear. After each merge, `./gradlew build`.
5. **Phase 2 launch.** Dispatch WU-10 against the integrated `phase-1` branch.
6. **Phase 2 integration.** Merge to main. Tag release.

Per-WU exit criteria (apply to every subagent):

- All new code has unit tests with meaningful assertions.
- `./gradlew build` passes on its branch.
- `./gradlew :sdk-core:compileKotlin` passes (faster smoke check during dev).
- Strict-mode Kotlin: every public declaration has explicit visibility + explicit return type.
- Public API stable: no public types removed or signatures changed without explicit callout in the WU summary.
- `docs/` updated for any new public concept.

## Subagent Briefing Template

When dispatching a subagent for a work unit, the briefing should include:

```
You are implementing WU-X: <title>.

## Context
- Repo: /Users/omar/IdeaProjects/dexpace/java-sdk
- Branch: phase-N/wu-X (create from <base-branch>)
- Plan reference: docs/implementation-plan.md § WU-X

## Goal
<paste WU goal>

## Files to create
<paste list>

## Files to modify
<paste list>

## Hard constraints (project-wide)
- Java 8 bytecode target; no Java 9+ APIs.
- Zero non-SLF4J runtime deps in sdk-core.
- ReentrantLock not synchronized; respect Thread.interrupt().
- Explicit Kotlin visibility (Strict mode); @JvmSynthetic on Java-mangled internals.
- Immutable models with private constructor + Builder.
- @JvmOverloads on public constructors.
- No AI attribution in commits.

## Conflict surface (avoid)
<paste from Conflict Map for this WU>

## Acceptance criteria
<paste from WU>

## Done means
- Tests pass: ./gradlew build
- Code lands on phase-N/wu-X branch ready to merge
- Brief summary of what changed, with file:line citations
```
