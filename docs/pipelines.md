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
- [BuilderPipeline](#builderpipeline)
- [ExecutionPipeline](#executionpipeline)
- [Step Configuration](#step-configuration)
    - [Metadata](#metadata)
    - [Retry Configuration](#retry-configuration)
- [Design Decisions](#design-decisions)
    - [Functional Interfaces](#functional-interfaces)
    - [Mutable vs Immutable Pipeline State](#mutable-vs-immutable-pipeline-state)
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

| File                       | Package         | Purpose                                                          |
|----------------------------|-----------------|------------------------------------------------------------------|
| `RequestPipeline.kt`       | `pipeline`      | Sequential request transformation                                |
| `ResponsePipeline.kt`      | `pipeline`      | Recovery-aware response fold (response + recovery steps)         |
| `ExecutionPipeline.kt`     | `pipeline`      | Top-level request → transport → recovery → response orchestrator |
| `ResponseOutcome.kt`       | `pipeline`      | Sealed `Success(Response)` / `Failure(Throwable)` sum type       |
| `BuilderPipeline.kt`       | `pipeline`      | Builder-based object construction through steps                  |
| `PipelineStep.kt`          | `pipeline.step` | Generic `PipelineStep<T, V>` functional interface                |
| `RequestPipelineStep.kt`   | `pipeline.step` | `Request → Request` specialization                               |
| `ResponsePipelineStep.kt`  | `pipeline.step` | `Response → Response` success-path specialization                |
| `ResponseRecoveryStep.kt`  | `pipeline.step` | `ResponseOutcome → ResponseOutcome` recovery hook                |
| `StepConfigTrait.kt`       | `pipeline.step` | Step configuration: metadata, retry                              |

---

## Architecture

### Pipeline Types

The SDK defines five pipeline types, each handling a different phase of the HTTP lifecycle:

```
                    BuilderPipeline<Request>
                    (configure request builder)
                            │
                            ▼
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

| Pipeline             | Input             | Output           | Phase                                                  |
|----------------------|-------------------|------------------|--------------------------------------------------------|
| `BuilderPipeline<T>` | `BuilderTrait<T>` | `T`              | Pre-construction: configure a builder through steps    |
| `RequestPipeline`    | `Request`         | `Request`        | Pre-execution: transform the request                   |
| `ExecutionPipeline`  | `Request`         | `Response`       | Top-level orchestrator: request + transport + recovery |
| `ResponsePipeline`   | `ResponseOutcome` | `ResponseOutcome`| Post-execution: response transforms + recovery chain   |

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

A `StepRetryTrait` (legacy) optionally augments a `PipelineStep` with a retry hook:

```kotlin
public interface StepRetryTrait<in T, out V> : PipelineStep<T, V> {
    public fun retry(context: ExchangeContext): V
}
```

The canonical retry implementation lives in WU-3 (`RetryStep`) and is structured as a
`ResponseRecoveryStep` so it composes uniformly with the recovery chain rather than relying
on the legacy trait.

### Context Flow

Every step receives a `DispatchContext` that carries the `InstrumentationContext`
for tracing. As the request progresses through its lifecycle, the context is promoted:

```
DispatchContext              Created at dispatch time
    │
    ├─► toRequestContext()   When the Request is available
    │
    └─► ExchangeContext      When the Response arrives
```

Each context level provides access to more information while maintaining the tracing
chain through `InstrumentationContext`.

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

| Step               | Purpose                                            |
|--------------------|----------------------------------------------------|
| Header injection   | Add `User-Agent`, `Authorization`, `Content-Type`  |
| URL rewriting      | Base URL resolution, query parameter injection     |
| Request validation | Ensure required fields are present before dispatch |
| Logging            | Log request method, URL, headers, body preview     |
| Retry preparation  | Snapshot the request for potential retry           |

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
| `RetryStep` (WU-3)  | Re-invoke transport on retryable failures with exponential backoff     |
| Status-to-exception | Map 4xx/5xx responses (or transport `IOException`) to typed exceptions |
| Auth-401 eviction   | Evict cached OAuth token on `UnauthorizedException` and retry once     |
| Circuit breaker     | Open the breaker on consecutive failures; fast-fail for the open phase |

---

## BuilderPipeline

`BuilderPipeline<T>` applies a sequence of builder-modifying functions to produce a
configured object:

```kotlin
class BuilderPipeline<T>(
    val steps: List<(BuilderTrait<T>, DispatchContext) -> BuilderTrait<T>>,
    val builderFactory: () -> BuilderTrait<T>,
)
```

Each step receives the current builder and the dispatch context, and returns the modified
builder. After all steps execute, `build()` is called on the final builder to produce `T`.

```kotlin
val pipeline = BuilderPipeline<Request>(
    builderFactory = { Request.builder() },
    steps = listOf(
        { builder, ctx -> builder.method(Method.GET) as BuilderTrait<Request> },
        { builder, ctx -> builder.url("https://api.example.com") as BuilderTrait<Request> },
    )
)

val request = pipeline.build(context)
```

**Invariant**: The pipeline requires at least one step (`require(steps.isNotEmpty())`).

**Implementation**: Steps are applied using `fold()` — the builder factory creates the
initial builder, and each step transforms it:

```kotlin
fun build(context: DispatchContext): T = steps
    .fold(builderFactory()) { builder, step -> step(builder, context) }
    .build()
```

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
Wrapping into typed SDK exceptions is the recovery chain's job (see the WU-2 typed exception
hierarchy and WU-10 auth provider).

---

## Step Configuration

Steps can implement configuration traits for metadata and retry behavior.

### Metadata

`PipelineStepMetadataTrait` provides descriptive properties:

| Property      | Default                 | Purpose                                       |
|---------------|-------------------------|-----------------------------------------------|
| `name`        | `"Default name"`        | Human-readable step name (logging, debugging) |
| `description` | `"Default description"` | Step purpose documentation                    |
| `version`     | `"Default version"`     | Step version for compatibility tracking       |
| `tags`        | `emptyList()`           | Categorization tags                           |

### Retry Configuration

`PipelineStepRetryConfigTrait` defines retry behavior for steps that implement
`StepRetryTrait`:

| Property                     | Default              | Purpose                            |
|------------------------------|----------------------|------------------------------------|
| `timeoutMilliseconds`        | `10000`              | Maximum time per retry attempt     |
| `exponentialBackoff`         | `false`              | Enable exponential backoff         |
| `initialBackoffMilliseconds` | `1000`               | Initial delay before first retry   |
| `maxBackoffMilliseconds`     | `10000`              | Maximum backoff cap                |
| `multiplier`                 | `2.0`                | Backoff multiplier                 |
| `maxRetries`                 | `3`                  | Maximum retry attempts             |
| `retryOnExceptions`          | `[Exception::class]` | Exception types that trigger retry |

---

## Design Decisions

### Functional Interfaces

`PipelineStep` and `RequestPipeline` are `fun interface`s, enabling lambda-based
implementations for simple steps:

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

### Mutable vs Immutable Pipeline State

The current design uses immutable `Request` and `Response` objects — each step produces
a new instance via `newBuilder().build()`. However, as noted in the source:

> The whole pipeline thing works based on the OOP concept of mutating request/response
> instances in-place. This limits the retry ability of any failing step.

The plan is to evolve toward:

1. **Pure functional steps**: Each step receives and returns immutable data, making it
   trivial to snapshot state at any point for retry or rollback.
2. **Step dependency graphs**: Define explicit dependencies between steps so the pipeline
   can revert to a previous state when a step fails.
3. **Retry isolation**: When a step with `StepRetryTrait` fails, the pipeline can replay
   from a known-good checkpoint instead of restarting from scratch.

### Step Ordering and Dependencies

Steps currently execute in list order — the order they appear in the `steps` list is the
order they execute. There is no automatic dependency resolution or topological sorting.

Consuming libraries are responsible for ensuring correct ordering. Common patterns:

```
1. Validation steps       (fail fast on bad input)
2. Header injection       (User-Agent, Accept, Content-Type)
3. Authentication         (Authorization header)
4. Logging                (log the final request)
5. Retry wrapper          (wrap execution for retry logic)
```

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

val pipeline = RequestPipeline { request, context ->
    listOf(loggingStep, authStep).fold(request) { req, step ->
        step.execute(req, context)
    }
}

val finalRequest = pipeline.execute(request, DispatchContext.default())
```

### Builder pipeline for request construction

```kotlin
val pipeline = BuilderPipeline<Request>(
    builderFactory = { Request.builder() },
    steps = listOf(
        { builder, ctx ->
            (builder as Request.Builder)
                .method(Method.POST)
                .url("https://api.example.com/v1/users")
            builder
        },
        { builder, ctx ->
            (builder as Request.Builder)
                .header("Content-Type", "application/json")
                .body(RequestBody.create(payload, MediaType.parse("application/json")))
            builder
        }
    )
)

val request = pipeline.build(DispatchContext.default())
```

### Retryable step

```kotlin
class RetryableAuthStep(
    private val tokenProvider: TokenProvider
) : RequestPipelineStep, StepRetryTrait<Request, Request> {

    override fun execute(input: Request, context: DispatchContext): Request {
        return input.newBuilder()
            .header("Authorization", "Bearer ${tokenProvider.token}")
            .build()
    }

    override fun retry(context: ExchangeContext): Request {
        tokenProvider.refresh()
        return execute(context.request, context.dispatchContext)
    }
}
```

### End-to-end execution with recovery

```kotlin
val mapToTypedException = ResponseRecoveryStep { outcome ->
    when (outcome) {
        is ResponseOutcome.Success -> outcome
        is ResponseOutcome.Failure ->
            ResponseOutcome.Failure(SdkException("transport failed", outcome.error))
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
| `BuilderPipeline.kt`            | public     | Builder-based construction through steps                                   |
| `step/PipelineStep.kt`          | public     | Generic `PipelineStep<T, V>` functional interface                          |
| `step/RequestPipelineStep.kt`   | public     | `Request → Request` specialization                                         |
| `step/ResponsePipelineStep.kt`  | public     | `Response → Response` success-path specialization                          |
| `step/ResponseRecoveryStep.kt`  | public     | `ResponseOutcome → ResponseOutcome` recovery hook                          |
| `step/StepConfigTrait.kt`       | public     | Configuration traits: metadata, retry config                               |
