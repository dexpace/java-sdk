# Architecture Overview

This document provides a comprehensive overview of the `java-sdk` project architecture,
covering module structure, package responsibilities, design philosophy, and cross-cutting
concerns.

## Table of Contents

- [Design Philosophy](#design-philosophy)
- [Module Structure](#module-structure)
- [Kotlin Package Map](#kotlin-package-map)
    - [I/O Layer](#io-layer)
    - [HTTP Layer](#http-layer)
    - [Body Logging](#body-logging)
    - [Context System](#context-system)
    - [Pipeline Architecture](#pipeline-architecture)
    - [Client Interface](#client-interface)
    - [Serialization](#serialization)
    - [Instrumentation](#instrumentation)
    - [Generics and Utilities](#generics-and-utilities)
- [Java Source Tree](#java-source-tree)
- [Data Flow](#data-flow)
    - [Request Lifecycle](#request-lifecycle)
    - [Response Lifecycle](#response-lifecycle)
- [Cross-Cutting Design Decisions](#cross-cutting-design-decisions)
    - [Zero Dependencies](#zero-dependencies)
    - [JDK 8 Compatibility](#jdk-8-compatibility)
    - [Immutability and Builders](#immutability-and-builders)
    - [Virtual Thread Safety](#virtual-thread-safety)
    - [Internal Visibility](#internal-visibility)
    - [Cancellation](#cancellation)
- [File Inventory](#file-inventory)

---

## Design Philosophy

The SDK is built around these principles:

1. **Zero-dependency core**: The Kotlin SDK core depends only on SLF4J API and Kotlin
   stdlib. No HTTP client implementation, no serialization library, no Okio. Every JDK
   API used is available since Java 8.

2. **Modular composition**: Core components are interfaces or abstract classes. Concrete
   implementations are plugged in by consuming libraries. The SDK provides the abstractions
   and the plumbing, not the concrete HTTP transport.

3. **Concurrency-model agnostic**: The SDK uses blocking `java.io` APIs with `ReentrantLock`
   for thread safety. This works correctly on platform threads, virtual threads (Project
   Loom), `Dispatchers.IO`, and reactive schedulers. No coroutines or reactive types leak
   into the core API.

4. **Immutable data, mutable builders**: HTTP models (`Request`, `Response`, `Headers`,
   `MediaType`) are immutable data classes. Mutation happens exclusively through builder
   APIs that produce new instances.

5. **Performance through pooling**: The I/O layer uses a segment-based architecture with
   lock-free recycling to minimize allocation pressure and enable zero-copy transfers.

---

## Module Structure

```
java-sdk/
  sdk-core/                           Single production module
    src/main/kotlin/                  Kotlin sources — primary API
      org/dexpace/sdk/core/
        io/                           Segment-based I/O (11 files)
        http/request/                 Request, RequestBody, Method (3 files)
        http/response/                Response, ResponseBody, Status (3 files)
        http/common/                  Headers, MediaType, Protocol (4 files)
        http/logging/                 Body logging and capture (4 files)
        http/context/                 Call/dispatch/request/exchange contexts (5 files)
        pipeline/                     Pipeline orchestration (4 files)
        pipeline/step/                Step interfaces and config traits (2 files)
        client/                       HttpClient interface (1 file)
        serde/                        Serialization abstractions (3 files)
        instrumentation/              Tracing, spans, scopes (6 files)
        generics/                     BuilderTrait (1 file)
        util/                         Annotations (1 file)
    src/main/java/                    Java sources — legacy/compat layer (367 files)
  docs/                               Design documentation
```

The project uses a **single-module** architecture (`sdk-core`). The Kotlin `internal`
visibility modifier is module-scoped — splitting into multiple modules would expose
implementation details (like `Segment`, `SegmentPool`, `RealBufferedSource`) that are
intentionally hidden. Keeping everything in one module preserves encapsulation.

---

## Kotlin Package Map

### I/O Layer

**Package**: `org.dexpace.sdk.core.io`

A small set of **interface contracts** plus a single factory seam (`IoProvider`). `sdk-core`
contains no concrete I/O implementation — that lives in adapter modules (today only
`sdk-io-okio3`). The HTTP layer consumes the contracts; the consuming application installs
one provider at startup via `Io.installProvider(...)`.

| Type             | Visibility | Role                                                              |
|------------------|------------|-------------------------------------------------------------------|
| `Source`         | public     | Primitive byte source (`read(Buffer, byteCount): Long`)           |
| `Sink`           | public     | Primitive byte sink (`write(Buffer, byteCount)`, `flush()`)       |
| `BufferedSource` | public     | Typed reads: byte arrays, UTF-8 strings, lines, peek, java.io     |
| `BufferedSink`   | public     | Typed writes: byte arrays, UTF-8 strings, writeAll, java.io       |
| `Buffer`         | public     | In-memory queue (source + sink + `snapshot()` for body logging)   |
| `IoProvider`     | public     | Single factory the adapter implements                             |
| `Io`             | public     | `provider` getter + `installProvider(...)` + `withProvider(...)`  |
| `TeeSink`        | internal   | `BufferedSink` that mirrors writes into a `Buffer` for logging    |

See [I/O Module](io.md) for full design documentation.

### HTTP Layer

**Package**: `org.dexpace.sdk.core.http`

The HTTP layer is split into four sub-packages:

#### `http.request`

| Type          | Role                                                                                                                      |
|---------------|---------------------------------------------------------------------------------------------------------------------------|
| `Request`     | Immutable HTTP request (method, URL, headers, body). Builder pattern.                                                     |
| `RequestBody` | Abstract body with `writeTo(OutputStream)`. Factory methods for byte array, string, form data, and input stream variants. |
| `Method`      | HTTP method enum (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS)                                                           |

#### `http.response`

| Type           | Role                                                                                                       |
|----------------|------------------------------------------------------------------------------------------------------------|
| `Response`     | Immutable HTTP response (request, protocol, status, message, headers, body). `Closeable`. Builder pattern. |
| `ResponseBody` | Abstract body with `byteStream()`, `bytes()`, `string()`. Single-use contract.                             |
| `Status`       | HTTP status code with reason phrase                                                                        |

#### `http.common`

| Type               | Role                                                                           |
|--------------------|--------------------------------------------------------------------------------|
| `Headers`          | Immutable multi-map of HTTP headers. Builder pattern. Case-insensitive lookup. |
| `MediaType`        | Parsed media type with type, subtype, and parameters. `charset` extraction.    |
| `CommonMediaTypes` | Constants for common media types (JSON, XML, form-urlencoded, etc.)            |
| `Protocol`         | HTTP protocol version (HTTP/1.0, HTTP/1.1, HTTP/2, etc.)                       |

#### `http.logging`

| Type                   | Role                                                                |
|------------------------|---------------------------------------------------------------------|
| `LoggableRequestBody`  | Tee-write wrapper — captures request bytes during write for logging |
| `LoggableResponseBody` | Eager-buffering wrapper — repeatable reads + snapshot for logging   |
| `BodySnapshot`         | Immutable body capture with text detection and preview generation   |
| `BodySegment`          | Fixed-size chunk + handler callback for streaming observation       |

See [HTTP Body Logging & Concurrency](http-body-logging-and-concurrency.md) for full design
documentation.

### Context System

**Package**: `org.dexpace.sdk.core.http.context`

The context system carries metadata through the request/response lifecycle:

| Type              | Role                                                              |
|-------------------|-------------------------------------------------------------------|
| `CallContext`     | Base interface — provides `InstrumentationContext`                |
| `DispatchContext` | Pipeline execution context — entry point for a request dispatch   |
| `RequestContext`  | Request-scoped context — extends `CallContext` with the `Request` |
| `ExchangeContext` | Full exchange context — carries both request and response         |
| `ContextStore`    | Thread-safe store for retrieving contexts by trace ID             |

**Flow**: `DispatchContext` is created at dispatch time, converted to `RequestContext` when
the request is available, and extended to `ExchangeContext` after the response arrives.
Each context carries an `InstrumentationContext` for tracing.

### Pipeline Architecture

**Package**: `org.dexpace.sdk.core.pipeline`

Modular, composable request/response processing:

| Type                 | Role                                                               |
|----------------------|--------------------------------------------------------------------|
| `RequestPipeline`    | Processes a `Request` through a sequence of `RequestPipelineStep`s |
| `ResponsePipeline`   | Processes a `Response` through response steps (interface, WIP)     |
| `BuilderPipeline<T>` | Applies builder-modifying steps to produce a configured `T`        |
| `ExecutionPipeline`  | Core execution stage that invokes the `HttpClient` (WIP)           |

#### Step System (`pipeline.step`)

| Type                           | Role                                                      |
|--------------------------------|-----------------------------------------------------------|
| `PipelineStep<T, V>`           | Generic step interface: `execute(input: T, context) -> V` |
| `RequestPipelineStep`          | Specialized: `PipelineStep<Request, Request>`             |
| `ResponsePipelineStep`         | Specialized: `PipelineStep<Response, Response>`           |
| `StepRetryTrait<T, V>`         | Adds `retry(context) -> V` to a step                      |
| `PipelineStepConfigTrait`      | Base config marker                                        |
| `PipelineStepMetadataTrait`    | Name, description, version, tags                          |
| `PipelineStepRetryConfigTrait` | Timeout, backoff, max retries, retryable exceptions       |

See [Pipeline Mechanism](pipelines.md) for full design documentation.

### Client Interface

**Package**: `org.dexpace.sdk.core.client`

```kotlin
interface HttpClient {
    fun execute(request: Request): Response
}
```

A minimal interface that consuming libraries implement against their chosen HTTP transport
(HttpURLConnection, Apache HC, Jetty, Netty, etc.). The SDK provides everything around
this interface — body abstractions, logging, pipelines, contexts — but not the transport.

### Serialization

**Package**: `org.dexpace.sdk.core.serde`

| Type             | Role                                                     |
|------------------|----------------------------------------------------------|
| `Serde`          | Combined serializer/deserializer interface               |
| `Deserializer`   | Deserialize from `String`, `ByteArray`, or `InputStream` |
| `SerializeTrait` | Mixin for types that can serialize themselves            |

The core module defines abstractions only. Concrete implementations (Jackson, Moshi, kotlinx.serialization) belong in
optional extension modules.

### Instrumentation

**Package**: `org.dexpace.sdk.core.instrumentation`

| Type                         | Role                                               |
|------------------------------|----------------------------------------------------|
| `InstrumentationContext`     | Base context carrying trace ID and span management |
| `Span`                       | Represents a unit of work in a trace               |
| `TracingScope`               | Scoped tracing lifecycle (start/end)               |
| `TraceIdType`                | Trace ID type abstraction                          |
| `NoopInstrumentationContext` | No-op default for non-instrumented calls           |
| `NoopSpan`                   | No-op span implementation                          |

The Java source tree contains the concrete OpenTelemetry integration and fallback
implementations.

## Log correlation

Log correlation is wired through SLF4J MDC: `Span.makeCurrentWithLoggingContext()` pushes `trace.id` / `span.id` for the scope, and `LoggingEvent.log()` folds MDC into every emitted event as structured fields. MDC is per-thread; callers using `CompletableFuture` chains or coroutines must propagate explicitly (see `TracingScope` KDoc).

### Generics and Utilities

| Type              | Package    | Role                                        |
|-------------------|------------|---------------------------------------------|
| `BuilderTrait<T>` | `generics` | Generic builder interface: `fun build(): T` |
| `Annotations.kt`  | `util`     | SDK-level annotation definitions            |

---

## Java Source Tree

The `src/main/java` tree contains ~367 files providing a comprehensive Java compatibility
layer. Key packages:

| Package                          | Description                                                                 |
|----------------------------------|-----------------------------------------------------------------------------|
| `annotations`                    | `@ServiceClient`, `@ServiceMethod`, `@Metadata` — code generation markers   |
| `binarydata`                     | `BinaryData` interface + implementations (byte array, file, stream, string) |
| `credentials`                    | `KeyCredential`, `NamedKeyCredential`, OAuth token types                    |
| `http.client`                    | Java HTTP client interfaces and configuration                               |
| `http.models`                    | Java HTTP models (headers, query params, URL builders)                      |
| `http.pipeline`                  | Java pipeline interfaces and policies                                       |
| `http.paging`                    | Pagination abstractions (`PagedIterable`, `PagedResponse`)                  |
| `models`                         | Domain model abstractions including geo types                               |
| `instrumentation`                | Logging, metrics, and tracing interfaces                                    |
| `implementation.instrumentation` | OpenTelemetry integration (`otel/`) and fallback impls                      |
| `serialization.json`             | Jackson-based JSON serialization (with embedded Jackson core)               |
| `serialization.xml`              | Aalto XML parser implementation                                             |
| `traits`                         | `HttpTrait`, `ProxyTrait`, `EndpointTrait`, configuration mixins            |
| `utils`                          | `Configuration`, utility classes                                            |

These Java sources form the foundation layer for generated service clients and provide
backward compatibility with Azure SDK patterns.

---

## Data Flow

### Request Lifecycle

```
                          Application Code
                                │
                                ▼
                     Request.builder().build()
                                │
                                ▼
                    ┌─── DispatchContext ───┐
                    │  InstrumentationCtx   │
                    └──────────┬────────────┘
                               │
                    ┌──────────▼────────────┐
                    │    RequestPipeline     │
                    │  step1 → step2 → ...  │  Add headers, auth, validation
                    └──────────┬────────────┘
                               │
                    ┌──────────▼────────────┐
                    │   LoggableRequestBody  │  (if logging enabled)
                    │   TeeOutputStream      │
                    └──────────┬────────────┘
                               │
                    ┌──────────▼────────────┐
                    │   HttpClient.execute() │  Transport layer
                    └──────────┬────────────┘
                               │
                               ▼
                          Response
```

### Response Lifecycle

```
                       HttpClient.execute()
                               │
                               ▼
                    ┌──────────────────────┐
                    │   ResponsePipeline    │  Post-processing steps
                    └──────────┬───────────┘
                               │
                    ┌──────────▼───────────┐
                    │ LoggableResponseBody  │  (if logging enabled)
                    │ Eager buffer + snap   │
                    └──────────┬───────────┘
                               │
                    ┌──────────▼───────────┐
                    │  ExchangeContext      │  Request + Response paired
                    └──────────┬───────────┘
                               │
                               ▼
                       Application Code
                   response.body?.string()
```

---

## Cross-Cutting Design Decisions

### Zero Dependencies

The SDK core avoids all third-party dependencies (beyond SLF4J and Kotlin stdlib):

- **No Okio**: `sdk-core` defines interface contracts only; the Okio dependency lives in
  the optional `sdk-io-okio3` adapter module (see [I/O Module](io.md))
- **No Jackson/Moshi/kotlinx**: Serialization is abstract in core; concrete implementations
  belong in extension modules
- **No coroutines**: Blocking `java.io` APIs work everywhere; coroutine adapters belong in
  optional `sdk-coroutines` modules
- **No HTTP transport**: `HttpClient` is an interface; consumers pick their transport

This means any JVM project can depend on `sdk-core` without transitive dependency conflicts.

### JDK 8 Compatibility

All code targets Java 8 bytecode (`jvmTarget = "1.8"`). Specific implications:

- `InputStream.transferTo()` (Java 9+) is avoided; manual 8 KB copy loops are used instead
- `Thread.threadId()` (Java 19+) is avoided; `Thread.currentThread().id` is used with
  `@Suppress("DEPRECATION")`
- `ReentrantLock` (Java 5+) replaces `synchronized` for future-proofing with virtual threads
- No `java.net.http.HttpClient` (Java 11+); the `HttpClient` interface is transport-agnostic

### Immutability and Builders

All HTTP model classes follow the same pattern:

```kotlin
@ConsistentCopyVisibility
data class Request private constructor(
    val method: Method,
    val url: URL,
    val headers: Headers,
    val body: RequestBody?
) {
    fun newBuilder(): Builder = Builder(this)

    class Builder : BuilderTrait<Request> {
        fun method(method: Method) = apply { ... }
        fun url(url: String) = apply { ... }
        override fun build(): Request = ...
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
```

- **Private constructor**: Forces use of builder
- **`data class`**: Gives `equals()`, `hashCode()`, `toString()`, `copy()` for free
- **`newBuilder()`**: Creates a pre-filled builder for modification
- **`BuilderTrait<T>`**: Generic interface ensuring all builders have `fun build(): T`

### Virtual Thread Safety

The SDK uses `ReentrantLock` over `synchronized` wherever locking is needed:

| Aspect                  | `synchronized`           | `ReentrantLock`             |
|-------------------------|--------------------------|-----------------------------|
| Virtual thread behavior | **Pins** carrier thread  | Virtual thread **unmounts** |
| JDK availability        | 1.0+                     | 5+ (within target)          |
| Kotlin idiom            | `synchronized(lock) { }` | `lock.withLock { }`         |

This is a forward-compatible choice: the SDK works correctly on Java 8 today and takes
full advantage of virtual threads on Java 21+ without code changes.

### Internal Visibility

Kotlin's `internal` modifier scopes visibility to the compilation module. The SDK uses
this extensively to hide implementation details:

- `Segment`, `SegmentPool` — internal (callers interact with `Buffer`, not segments)
- `RealBufferedSource`, `RealBufferedSink` — internal (callers use `Source.buffered()`)
- `PeekSource` — internal (callers use `BufferedSource.peek()`)
- `InputStreamSource`, `OutputStreamSink` — private (factory functions are public)

This is a key reason the project uses a single module: splitting `io` into `sdk-io` would
force these types to become `public`, breaking encapsulation.

### Cancellation

Every blocking call in the SDK respects `Thread.interrupt()`. When a thread is interrupted
while the SDK is blocked on a network read, a `Thread.sleep` inside a retry policy, a
`ReentrantLock` acquire in a rate limiter, or any other blocking operation, the SDK
responds in a uniform way:

1. Catches `InterruptedException` at the blocking site.
2. Calls `Thread.currentThread().interrupt()` to preserve the interrupt status so any
   subsequent blocking call also surfaces it.
3. Throws `InterruptedIOException` (or the operation's natural failure exception with
   `InterruptedException` added as a suppressed cause).
4. Classifies the interruption as **non-retryable** — `HttpResponseException.isRetryable`
   returns `false` for an interrupt-driven failure.

Loops bounded by user input (retry attempts, paged iteration, server-sent-event
consumption, drain loops in body logging) check `Thread.currentThread().isInterrupted` at
the top of each iteration to abort early between blocking calls.

What this means for consumers:

- Calling `Thread.interrupt()` on a thread that's executing an SDK call is the supported
  cancellation mechanism.
- Threads that catch `InterruptedException` from the SDK should re-throw or re-interrupt
  themselves — the SDK has already preserved the interrupt status, and swallowing the
  exception silently breaks downstream cancellation.
- Coroutine consumers running SDK calls inside `withContext(Dispatchers.IO)` get
  cancellation propagation for free — `Job` cancellation interrupts the blocked thread,
  which the SDK handles per the convention above.

---

## File Inventory

### Kotlin Sources

| Package           | Key Types                                                                                       |
|-------------------|-------------------------------------------------------------------------------------------------|
| `io`              | Source, Sink, BufferedSource, BufferedSink, Buffer, IoProvider, Io, TeeSink (internal)          |
| `http.request`    | Request, RequestBody, LoggableRequestBody, Method                                               |
| `http.response`   | Response, ResponseBody, LoggableResponseBody, Status                                            |
| `http.common`     | Headers, MediaType, CommonMediaTypes, Protocol                                                  |
| `http.context`    | CallContext, RequestContext, DispatchContext, ExchangeContext, ContextStore                     |
| `pipeline`        | RequestPipeline, ResponsePipeline, BuilderPipeline, ExecutionPipeline                           |
| `pipeline.step`   | PipelineStep, StepConfigTrait                                                                   |
| `client`          | HttpClient                                                                                      |
| `serde`           | Serde, Deserializer, SerializeTrait                                                             |
| `instrumentation` | InstrumentationContext, Span, NoopSpan, NoopInstrumentationContext, TracingScope, TraceIdType   |
| `generics`        | Builder                                                                                         |
| `util`            | Annotations                                                                                     |

### Okio adapter (`sdk-io-okio3`)

| Type             | Visibility | Role                                                          |
|------------------|------------|---------------------------------------------------------------|
| `OkioIoProvider` | public     | Singleton `IoProvider` — `Io.installProvider(OkioIoProvider)` |
| `OkioBuffer`     | internal   | `Buffer` wrapping `okio.Buffer`                               |
| `OkioBufferedSource` | internal | `BufferedSource` wrapping `okio.BufferedSource`             |
| `OkioBufferedSink`   | internal | `BufferedSink` wrapping `okio.BufferedSink`                 |
