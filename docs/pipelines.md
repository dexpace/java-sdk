# Pipeline Mechanism

This document covers the design, architecture, and usage of the SDK's pipeline system for
composable request/response processing.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
    - [Pipeline Types](#pipeline-types)
    - [Step System](#step-system)
    - [Context Flow](#context-flow)
- [RequestPipeline](#requestpipeline)
- [ResponsePipeline](#responsepipeline)
- [ExecutionPipeline](#executionpipeline)
- [Retry](#retry)
- [Async Dispatch](#async-dispatch)
- [Design Decisions](#design-decisions)
    - [Functional Interfaces](#functional-interfaces)
    - [Immutable Pipeline State](#immutable-pipeline-state)
    - [Step Ordering and Dependencies](#step-ordering-and-dependencies)
- [Usage Examples](#usage-examples)
- [File Index](#file-index)

---

## Overview

The SDK uses a **pipeline-based approach** for processing HTTP requests and responses. Instead
of a monolithic HTTP client with hardcoded behavior, functionality is composed from discrete,
reusable steps that execute in sequence within a shared context.

This enables:

- **Modularity**: Each concern (logging, authentication, retry, header injection) is a
  separate step that can be added, removed, or reordered independently.
- **Extensibility**: Consuming libraries add custom steps without modifying the core.
- **Testability**: Individual steps can be unit-tested in isolation with mock contexts.
- **Composability**: Steps are combined into pipelines; pipelines are combined into
  execution flows.

> Pipeline steps inherit the SDK-wide cancellation convention — see `docs/architecture.md`
> under Cancellation.

### Key source files

| File                       | Package               | Purpose                                                          |
|----------------------------|-----------------------|------------------------------------------------------------------|
| `RequestPipeline.kt`       | `pipeline`            | Sequential request transformation                                |
| `ResponsePipeline.kt`      | `pipeline`            | Recovery-aware response fold (response + recovery steps)         |
| `ExecutionPipeline.kt`     | `pipeline`            | Top-level request → transport → recovery → response orchestrator |
| `ResponseOutcome.kt`       | `pipeline`            | Sealed `Success(Response)` / `Failure(Throwable)` sum type       |
| `PipelineStep.kt`          | `pipeline.step`       | Generic `PipelineStep<T, V>` functional interface                |
| `RequestPipelineStep.kt`   | `pipeline.step`       | `Request → Request` specialization                               |
| `ResponsePipelineStep.kt`  | `pipeline.step`       | `Response → Response` success-path specialization                |
| `ResponseRecoveryStep.kt`  | `pipeline.step`       | `ResponseOutcome → ResponseOutcome` recovery hook                |
| `IdempotencyKeyStep.kt`    | `pipeline.step`       | `RequestPipelineStep` that injects an idempotency key            |
| `ClientIdentityStep.kt`    | `pipeline.step`       | `RequestPipelineStep` that injects client identity headers       |
| `RetryStep.kt`             | `pipeline.step.retry` | `ResponseRecoveryStep`: backoff retry on retryable failures      |
| `RetrySettings.kt`         | `pipeline.step.retry` | Immutable retry configuration for `RetryStep`                    |

---

## Architecture

### Pipeline Types

The SDK defines three pipeline types, each handling a different phase of the HTTP lifecycle:

```
                      RequestPipeline
                    (transform request — BeforeRequest)
                            │
                            ▼
                       HttpClient.execute
                    (transport — produces ResponseOutcome)
                            │
                            ▼
                     ResponsePipeline
                    (response steps + recovery chain
                     — AfterSuccess + AfterError)

                  Orchestrated by ExecutionPipeline
                  (catches all exceptions, threads through recovery)
```

| Pipeline            | Input             | Output            | Phase                                                  |
|---------------------|-------------------|-------------------|--------------------------------------------------------|
| `RequestPipeline`   | `Request`         | `Request`         | Pre-execution: transform the request                   |
| `ExecutionPipeline` | `Request`         | `Response`        | Top-level orchestrator: request + transport + recovery |
| `ResponsePipeline`  | `ResponseOutcome` | `ResponseOutcome` | Post-execution: response transforms + recovery chain   |

### Step System

Two distinct step shapes are wired into the pipeline. The first is the generic transformer
used by `RequestPipeline` and `ResponsePipeline`:

```kotlin
public fun interface PipelineStep<in T, out V> {
    public fun execute(input: T, context: DispatchContext): V
}
```

Specialized `fun interface`s narrow the generics for common use cases:

```kotlin
public fun interface RequestPipelineStep : PipelineStep<Request, Request>
public fun interface ResponsePipelineStep : PipelineStep<Response, Response>
```

The second shape is the recovery hook used by `ResponsePipeline`'s recovery chain — it takes
the full outcome rather than a bare value so steps can inspect failures:

```kotlin
public fun interface ResponseRecoveryStep {
    public fun invoke(outcome: ResponseOutcome): ResponseOutcome
}
```

Retry is implemented as a `ResponseRecoveryStep` (`RetryStep`) rather than a property of any
single step, so it composes uniformly with the rest of the recovery chain. See
[Retry](#retry).

### Context Flow

Every step receives a `DispatchContext` that carries the `InstrumentationContext`
for tracing. As the request progresses through its lifecycle, the context is promoted:

```
DispatchContext                          Created at dispatch time
    │
    ├─► toRequestContext(request)
    ▼
RequestContext                           When the Request is available
    │
    ├─► toExchangeContext(response)
    ▼
ExchangeContext                          When the Response arrives
```

Each context level provides access to more information while maintaining the tracing
chain through `InstrumentationContext`. Pipeline steps themselves receive the `DispatchContext`;
the promoted `RequestContext`/`ExchangeContext` carry the request and response once those are
known.

---

## RequestPipeline

The `RequestPipeline` processes a `Request` through an ordered list of
`RequestPipelineStep`s. Each step receives the current request and the dispatch context,
and returns a (potentially modified) request.

```kotlin
public class RequestPipeline(
    public val steps: List<RequestPipelineStep> = emptyList(),
) {
    public fun execute(request: Request, context: DispatchContext): Request
}
```

Steps execute **sequentially** — the output of step N becomes the input of step N+1.
Empty pipelines return the input request unchanged. Individual steps are typically lambdas
courtesy of `fun interface RequestPipelineStep`:

```kotlin
val pipeline = RequestPipeline(
    steps = listOf(
        RequestPipelineStep { request, context ->
            request.newBuilder()
                .header("X-Request-Id", context.instrumentationContext.traceId.value)
                .build()
        },
    ),
)
```

If a step throws, `RequestPipeline.execute` propagates the throwable unchanged. The
surrounding `ExecutionPipeline` is responsible for translating that throwable into a
`ResponseOutcome.Failure` so it can be observed by recovery steps (see below).

### Common request steps

| Step                 | Purpose                                                  |
|----------------------|----------------------------------------------------------|
| `ClientIdentityStep` | Inject client identity headers (`User-Agent` and friends)|
| `IdempotencyKeyStep` | Inject an idempotency key so retries are safely deduped  |
| Header injection     | Add `Authorization`, `Content-Type`                      |
| URL rewriting        | Base URL resolution, query parameter injection           |
| Request validation   | Ensure required fields are present before dispatch       |
| Logging              | Log request method, URL, headers, body preview           |

---

## ResponsePipeline

The `ResponsePipeline` is the recovery-aware post-execution counterpart to `RequestPipeline`.
It folds a `ResponseOutcome` (a sealed sum of `Success(Response)` and `Failure(Throwable)`)
through two ordered step lists:

```kotlin
public class ResponsePipeline(
    responseSteps: List<ResponsePipelineStep> = emptyList(),
    recoverySteps: List<ResponseRecoveryStep> = emptyList(),
)
```

1. **`responseSteps`** are the success-path transformers — Airbyte's `AfterSuccess` equivalent.
   Each receives the current `Response` and returns the next. They run **only** when the
   outcome is currently `Success`; if a response step throws, the throwable is converted into
   a `ResponseOutcome.Failure` and threaded through the subsequent recovery chain.
2. **`recoverySteps`** are the recovery hooks — Airbyte's `AfterError` equivalent, generalized
   so they observe every outcome (success or failure). Each `ResponseRecoveryStep` receives
   the current outcome and returns the next:
   - **Rescue.** Receive `Failure`, return `Success` (cached fallback, stale-while-revalidate).
   - **Replace.** Receive `Failure(t1)`, return `Failure(t2)` (typed-exception mapping).
   - **Pass-through.** Return the input unchanged.
   - **Retry (delegated).** Re-invoke the underlying transport and return the new outcome.

Order: response steps run first on the success path, then **all** recovery steps run
sequentially regardless of how many response steps ran. This guarantees recovery always sees
the terminal outcome, including failures produced by response steps:

```
Outcome ──► [respStep1 ─► respStep2 ─► ...] ──► [recoveryStep1 ─► recoveryStep2 ─► ...]
            (skipped when outcome is Failure)
```

If a recovery step itself throws, its throwable is wrapped in a `ResponseOutcome.Failure` and
fed to the next recovery step — recovery exceptions never bypass downstream recovery. The
pipeline's `apply` method never throws; callers inspect the returned outcome to decide whether
to surface a `Response` or rethrow.

### ResponseOutcome

```kotlin
public sealed class ResponseOutcome {
    public data class Success(val response: Response) : ResponseOutcome()
    public data class Failure(val error: Throwable) : ResponseOutcome()

    public fun isSuccess(): Boolean
    public fun isFailure(): Boolean
    public fun getOrNull(): Response?
    public fun errorOrNull(): Throwable?
    public inline fun <R> fold(onSuccess: (Response) -> R, onFailure: (Throwable) -> R): R
}
```

The `fold` helper mirrors `kotlin.Result.fold` for ergonomic collapsing of an outcome into a
single value.

### Common response steps

| Step              | Purpose                                             |
|-------------------|-----------------------------------------------------|
| Body logging      | Wrap body in `LoggableResponseBody` for diagnostics |
| Header extraction | Pull rate limit headers, pagination tokens          |
| Deserialization   | Convert body to domain objects                      |
| Metric recording  | Record latency, status codes, body sizes            |

### Common recovery steps

| Step                | Purpose                                                                |
|---------------------|------------------------------------------------------------------------|
| `RetryStep`         | Re-invoke transport on retryable failures with exponential backoff     |
| Status-to-exception | Map 4xx/5xx responses (or transport `IOException`) to typed exceptions |
| Auth-401 eviction   | Evict cached OAuth token on `UnauthorizedException` and retry once     |
| Circuit breaker     | Open the breaker on consecutive failures; fast-fail for the open phase |

---

## ExecutionPipeline

The `ExecutionPipeline` is the top-level entry point that ties `RequestPipeline`, the
`HttpClient` transport, and the recovery-aware `ResponsePipeline` together. SDK consumers
call `executionPipeline.execute(request, context)` and receive a `Response` (or rethrow the
terminal failure).

```kotlin
public class ExecutionPipeline(
    public val httpClient: HttpClient,
    public val requestPipeline: RequestPipeline = RequestPipeline(),
    public val responsePipeline: ResponsePipeline = ResponsePipeline(),
)
```

### Execution flow

```
                  ┌─────────────────────────────────────────┐
                  │              ExecutionPipeline           │
                  └─────────────────────────────────────────┘
                                       │
                                       ▼
                              ┌────────────────┐
                              │ RequestPipeline│  (BeforeRequest)
                              └────────┬───────┘
                                       │     throw
                                       │     ──────────────┐
                                       ▼                   │
                              ┌────────────────┐           │
                              │ HttpClient     │           │
                              │ .execute()     │           │
                              └────────┬───────┘           │
                                       │     throw         │
                                       │     ────────────┐ │
                                       ▼                 ▼ ▼
                            ResponseOutcome.Success  ResponseOutcome.Failure
                                       │                 │
                                       └────────┬────────┘
                                                ▼
                              ┌────────────────────────────┐
                              │      ResponsePipeline       │
                              │                             │
                              │  [responseSteps (Success)]  │  (AfterSuccess)
                              │             │               │
                              │             ▼               │
                              │  [recoverySteps (any)]      │  (AfterError, generalized)
                              └────────────────────────────┘
                                                │
                                                ▼
                                       ┌──────────────────┐
                                       │ Unwrap outcome:   │
                                       │  Success → return │
                                       │  Failure → throw  │
                                       └──────────────────┘
```

**Key correctness invariant.** **Every** exception raised inside a request step, the
transport, or a response step is caught and converted into a `ResponseOutcome.Failure` before
the recovery chain runs. Recovery steps observe the failure regardless of where in the
pipeline it originated — this fixes Airbyte's design defect where a `BeforeRequest` exception
bypassed `AfterError` entirely (`utils/Hook.java`).

`execute` rethrows the terminal `Failure.error` unchanged when no recovery step rescued it.
Wrapping into typed SDK exceptions is the recovery chain's job — the typed `HttpException`
hierarchy and the auth steps map raw failures into domain exceptions there.

---

## Retry

Retry is a `ResponseRecoveryStep` — `RetryStep` — wired into a `ResponsePipeline`'s
`recoverySteps`. It re-executes a failed request with exponential backoff, server-driven
pacing (`Retry-After` / `X-RateLimit-Reset`), and a total-timeout budget that shrinks the
per-attempt deadline as attempts accrue.

A `RetryStep` is constructed against a captured transport and a single request template:

```kotlin
public class RetryStep(
    public val httpClient: HttpClient,
    public val settings: RetrySettings,
    public val request: Request,
)
```

It retries only when the outcome is a `Failure` whose throwable is classified retryable. The
classifier keys off the `Retryable` interface, not concrete exception types:

- An `HttpException` (which is `Retryable`) with `isRetryable == true` whose status code is in
  `RetrySettings.retryableStatuses`.
- A `NetworkException` (also `Retryable`, always `true` — a transport failure with no response
  on the wire).

Idempotency is enforced independently of classification: a request is eligible only when its
method is in `RetrySettings.retryableMethods` **or** its body is replayable. Non-idempotent
methods (`POST`/`PATCH`) with a non-replayable body are never re-sent.

Waits between attempts use a `ScheduledExecutorService` plus `CompletableFuture.get`, never
`Thread.sleep`, so virtual-thread carriers can unmount during the delay. An interrupt restores
the interrupt flag, cancels the pending scheduled future, and surfaces an
`InterruptedIOException` through the recovery outcome.

### RetrySettings

`RetrySettings` is immutable, built via `RetrySettings.builder()` (or `RetrySettings.defaults()`),
and carries:

| Property            | Default                   | Purpose                                                       |
|---------------------|---------------------------|---------------------------------------------------------------|
| `totalTimeout`      | `30s`                     | Hard budget across all attempts; `Duration.ZERO` disables it  |
| `initialDelay`      | `200ms`                   | Delay before the first retry, before jitter/scaling           |
| `delayMultiplier`   | `2.0`                     | Per-attempt backoff multiplier (must be ≥ 1.0)                |
| `maxDelay`          | `8s`                      | Cap on the scaled delay                                       |
| `maxAttempts`       | `3`                       | Total attempts including the first send; `1` disables retries |
| `jitter`            | `0.2`                     | Symmetric jitter fraction in `[0.0, 1.0]`                     |
| `retryableStatuses` | `{429, 500, 502, 503, 504}` | Status codes that trigger a retry on an `HttpException`     |
| `retryableMethods`  | `{GET, HEAD, OPTIONS, PUT, DELETE}` | Methods retryable by RFC 9110; others need a replayable body |
| `scheduler`         | `null`                    | Optional caller scheduler; `null` uses a daemon scheduler     |

`408` (Request Timeout) is intentionally excluded from the default `retryableStatuses` — a
server-side 408 usually means the client was slow to send and is unlikely to improve on retry.
Callers that disagree can opt in via the builder.

---

## Async Dispatch

The `pipeline` package described above is synchronous. The parallel `http.pipeline` package
provides the stage-based dispatch runtime, including an async mirror (`AsyncHttpPipeline`,
`AsyncHttpStep`) and a sync→async bridge. See `docs/architecture.md` for that layer; the one
detail worth pinning here is how cancellation behaves when you adapt a synchronous pipeline.

`HttpPipeline.toAsync(executor)` wraps a synchronous `HttpPipeline` as an `AsyncHttpPipeline` by
submitting each `send(request)` to the supplied `executor`. Cancellation of the returned future
matches the native transports' `executeAsync` semantics:

- `cancel(true)` interrupts the worker thread running the in-flight `send(...)`. The interrupt
  lands only while the send is actually executing — a task still queued on the executor is
  abandoned, and an already-completed send is unaffected. For the interrupt to abort I/O the
  wrapped transport must honour `Thread.interrupt()` (the shipped transports do; see the
  cancellation contract in `docs/architecture.md`).
- `cancel(false)` completes the future as cancelled without interrupting the worker, so a
  blocking `send` that ignores interrupts runs to completion in the background.

The wrapped pipeline's individual steps stay synchronous and run on the dispatch thread; for
true per-step concurrency, implement `AsyncHttpStep` directly via `AsyncHttpPipelineBuilder`
rather than bridging.

---

## Design Decisions

### Functional Interfaces

`PipelineStep`, `RequestPipelineStep`, `ResponsePipelineStep`, and `ResponseRecoveryStep` are
all `fun interface`s, enabling lambda-based implementations for simple steps:

```kotlin
// As a class
class AuthStep : RequestPipelineStep {
    override fun execute(input: Request, context: DispatchContext): Request {
        return input.newBuilder().header("Authorization", "Bearer ...").build()
    }
}

// As a lambda
val authStep = RequestPipelineStep { request, context ->
    request.newBuilder().header("Authorization", "Bearer ...").build()
}
```

This keeps simple steps concise while allowing complex steps to use full class definitions
with state, dependencies, and configuration.

### Immutable Pipeline State

Steps operate on immutable `Request` and `Response` objects — each transforming step produces
a new instance via `newBuilder().build()` rather than mutating in place. The pipelines
themselves are immutable after construction: `RequestPipeline` wraps its step list in an
unmodifiable view, `ResponsePipeline` does the same for both step lists, and `ExecutionPipeline`
holds final references to its components. Instances are therefore safe to share across threads
provided the steps they hold are themselves thread-safe.

This is what makes retry tractable. Because a step never destroys its input, `RetryStep` can
re-send the original `Request` template verbatim — the only safety question is idempotency
(method + body replayability), not state corruption from a partially-applied step. Recovery is
modelled as a fold over `ResponseOutcome` rather than rollback over mutated state: a failure
flows through the recovery chain as data, and any step may rescue, replace, or pass it through.

### Step Ordering and Dependencies

Steps currently execute in list order — the order they appear in the `steps` list is the
order they execute. There is no automatic dependency resolution or topological sorting.

Consuming libraries are responsible for ensuring correct ordering. Common patterns:

```
RequestPipeline.steps (BeforeRequest):
  1. Validation steps     (fail fast on bad input)
  2. Header injection     (User-Agent, Accept, Content-Type)
  3. Authentication       (Authorization header)
  4. Logging              (log the final request)
```

Retry is **not** a request step — it lives in `ResponsePipeline.recoverySteps` so it can observe
the transport outcome and re-issue the request. Order recovery steps so retry runs before any
status-to-exception mapping you do not want a transient failure to surface prematurely.

---

## Usage Examples

### Basic request pipeline

```kotlin
val loggingStep = RequestPipelineStep { request, context ->
    logger.debug("Dispatching {} {}", request.method, request.url)
    request
}

val authStep = RequestPipelineStep { request, context ->
    request.newBuilder()
        .header("Authorization", "Bearer $token")
        .build()
}

val pipeline = RequestPipeline(steps = listOf(loggingStep, authStep))

val finalRequest = pipeline.execute(request, DispatchContext.default())
```

### Configuring a retry step

```kotlin
val settings = RetrySettings.builder()
    .maxAttempts(4)
    .initialDelay(Duration.ofMillis(250))
    .build()

val retryStep = RetryStep(httpClient = transport, settings = settings, request = request)

// Wired into a ResponsePipeline's recovery chain, or invoked directly:
val response = retryStep.attempt()
```

### End-to-end execution with recovery

```kotlin
val mapToTypedException = ResponseRecoveryStep { outcome ->
    when (outcome) {
        is ResponseOutcome.Success -> outcome
        is ResponseOutcome.Failure ->
            ResponseOutcome.Failure(NetworkException("transport failed", outcome.error))
    }
}

val pipeline = ExecutionPipeline(
    httpClient = transport,
    requestPipeline = RequestPipeline(listOf(authStep, userAgentStep)),
    responsePipeline = ResponsePipeline(
        responseSteps = listOf(decodingStep),
        recoverySteps = listOf(retryStep, mapToTypedException),
    ),
)

val response = pipeline.execute(request, DispatchContext.default())
```

If any phase of the pipeline raises an exception, the recovery chain observes it through a
`ResponseOutcome.Failure` and may rescue, replace, or pass through. The terminal outcome is
unwrapped: `Success` returns the `Response`; `Failure` rethrows.

---

## File Index

| File                            | Visibility | Description                                                                |
|---------------------------------|------------|----------------------------------------------------------------------------|
| `RequestPipeline.kt`            | public     | Sequential request transformation                                          |
| `ResponsePipeline.kt`           | public     | Recovery-aware response fold (response + recovery steps)                   |
| `ExecutionPipeline.kt`          | public     | Top-level orchestrator: request → transport → recovery → response          |
| `ResponseOutcome.kt`            | public     | Sealed `Success(Response)` / `Failure(Throwable)` sum type                 |
| `step/PipelineStep.kt`          | public     | Generic `PipelineStep<T, V>` functional interface                          |
| `step/RequestPipelineStep.kt`   | public     | `Request → Request` specialization                                         |
| `step/ResponsePipelineStep.kt`  | public     | `Response → Response` success-path specialization                          |
| `step/ResponseRecoveryStep.kt`  | public     | `ResponseOutcome → ResponseOutcome` recovery hook                          |
| `step/IdempotencyKeyStep.kt`    | public     | `RequestPipelineStep` that injects an idempotency key                      |
| `step/ClientIdentityStep.kt`    | public     | `RequestPipelineStep` that injects client identity headers                 |
| `step/retry/RetryStep.kt`       | public     | `ResponseRecoveryStep`: backoff retry on retryable failures                |
| `step/retry/RetrySettings.kt`   | public     | Immutable retry configuration for `RetryStep`                              |
