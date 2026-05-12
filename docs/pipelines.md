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

| File                   | Package         | Purpose                                         |
|------------------------|-----------------|-------------------------------------------------|
| `RequestPipeline.kt`   | `pipeline`      | Sequential request processing                   |
| `ResponsePipeline.kt`  | `pipeline`      | Sequential response processing (interface)      |
| `BuilderPipeline.kt`   | `pipeline`      | Builder-based object construction through steps |
| `ExecutionPipeline.kt` | `pipeline`      | Core HTTP execution stage (WIP)                 |
| `PipelineStep.kt`      | `pipeline.step` | Step interfaces and specializations             |
| `StepConfigTrait.kt`   | `pipeline.step` | Step configuration: metadata, retry             |

---

## Architecture

### Pipeline Types

The SDK defines four pipeline types, each handling a different phase of the HTTP lifecycle:

```
                    BuilderPipeline<Request>
                    (configure request builder)
                            │
                            ▼
                      RequestPipeline
                    (transform request)
                            │
                            ▼
                     ExecutionPipeline
                    (send via HttpClient)
                            │
                            ▼
                     ResponsePipeline
                    (transform response)
```

| Pipeline             | Input             | Output    | Phase                                               |
|----------------------|-------------------|-----------|-----------------------------------------------------|
| `BuilderPipeline<T>` | `BuilderTrait<T>` | `T`       | Pre-construction: configure a builder through steps |
| `RequestPipeline`    | `Request`         | `Request` | Pre-execution: transform the request                |
| `ExecutionPipeline`  | —                 | —         | Execution: invoke the HTTP transport (WIP)          |
| `ResponsePipeline`   | —                 | —         | Post-execution: transform the response (interface)  |

### Step System

All steps implement the generic `PipelineStep<T, V>` functional interface:

```kotlin
fun interface PipelineStep<in T, out V> {
    fun execute(input: T, context: DispatchContext): V
}
```

Specialized type aliases narrow the generics for common use cases:

```kotlin
interface RequestPipelineStep : PipelineStep<Request, Request>
interface ResponsePipelineStep : PipelineStep<Response, Response>
```

The `StepRetryTrait` adds retry capability:

```kotlin
interface StepRetryTrait<in T, out V> : PipelineStep<T, V> {
    fun retry(context: ExchangeContext): V
}
```

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
fun interface RequestPipeline {
    fun execute(request: Request, context: DispatchContext): Request

    val steps: List<RequestPipelineStep>
        get() = emptyList()
}
```

Steps execute **sequentially** — the output of step N becomes the input of step N+1.
The pipeline is a `fun interface`, meaning a lambda can serve as a simple pipeline:

```kotlin
val pipeline = RequestPipeline { request, context ->
    request.newBuilder()
        .header("X-Request-Id", context.instrumentationContext.traceId.value)
        .build()
}
```

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

The `ResponsePipeline` is the post-execution counterpart to `RequestPipeline`. It is
currently defined as an interface placeholder:

```kotlin
interface ResponsePipeline {}
```

When implemented, it will process the `Response` through a sequence of
`ResponsePipelineStep`s — each receiving the current response and context, and returning
a (potentially modified) response.

### Planned response steps

| Step              | Purpose                                             |
|-------------------|-----------------------------------------------------|
| Status validation | Throw on 4xx/5xx if configured                      |
| Body logging      | Wrap body in `LoggableResponseBody` for diagnostics |
| Header extraction | Pull rate limit headers, pagination tokens          |
| Deserialization   | Convert body to domain objects                      |
| Metric recording  | Record latency, status codes, body sizes            |

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

The `ExecutionPipeline` represents the core execution stage where the request is sent
to the HTTP transport and a response is received. This is currently a placeholder:

```kotlin
class ExecutionPipeline {}
```

When implemented, it will coordinate between the `RequestPipeline` (pre-processing),
the `HttpClient` (transport), and the `ResponsePipeline` (post-processing), forming the
complete request/response lifecycle.

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

---

## File Index

| File                      | Visibility | Description                                                                                      |
|---------------------------|------------|--------------------------------------------------------------------------------------------------|
| `RequestPipeline.kt`      | public     | Sequential request processing (`fun interface`)                                                  |
| `ResponsePipeline.kt`     | public     | Response processing interface (placeholder)                                                      |
| `BuilderPipeline.kt`      | public     | Builder-based construction through steps                                                         |
| `ExecutionPipeline.kt`    | public     | Core execution stage (placeholder)                                                               |
| `step/PipelineStep.kt`    | public     | Step interfaces: `PipelineStep`, `StepRetryTrait`, `RequestPipelineStep`, `ResponsePipelineStep` |
| `step/StepConfigTrait.kt` | public     | Configuration traits: metadata, retry config                                                     |
