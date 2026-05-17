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
    - [Lifecycle](#lifecycle)
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

5. **Pluggable I/O**: `sdk-core` defines only I/O contracts (`Source`, `Sink`,
   `BufferedSource`, `BufferedSink`, `Buffer`). Concrete I/O implementations live in
   adapter modules (today: `sdk-io-okio3`). Performance characteristics are a property
   of the chosen adapter, not of the core.

---

## Module Structure

```
java-sdk/
  sdk-core/                           Primary SDK module (Java 8 target)
    src/main/kotlin/                  Kotlin sources — primary API
      org/dexpace/sdk/core/
        io/                           I/O contracts: Source, Sink, BufferedSource, BufferedSink, Buffer, IoProvider, Io, TeeSink
        http/request/                 Request, RequestBody, LoggableRequestBody, Method
        http/response/                Response, ResponseBody, LoggableResponseBody, Status
        http/common/                  Headers, MediaType, Protocol, CommonMediaTypes
        http/context/                 Call/dispatch/request/exchange contexts
        pipeline/                     Pipeline orchestration
        pipeline/step/                Step interfaces and config traits
        client/                       HttpClient interface (transport SPI)
        serde/                        Serialization abstractions
        instrumentation/              Tracing, spans, scopes, logging
        generics/                     Builder<T>
        util/                         SDK-level annotations
    src/main/java/                    Java sources — legacy/compat layer (~367 files)

  sdk-io-okio3/                       Okio 3.x IoProvider implementation (Java 8 target)
  sdk-async-coroutines/               Kotlin coroutines adapter (Java 8 target)
  sdk-async-reactor/                  Reactor Mono/Flux adapter (Java 8 target)
  sdk-async-netty/                    Netty Future adapter (Java 8 target)
  sdk-async-virtualthreads/           Virtual-thread executor adapter (Java 21 target)

  sdk-transport-okhttp/               Reference transport: OkHttp 5.x (Java 8 target)
    src/main/kotlin/
      org/dexpace/sdk/transport/okhttp/
        OkHttpTransport.kt            Public — implements HttpClient + AsyncHttpClient
        internal/                     Internal adapters (request, response, body, restricted-headers)
    src/test/kotlin/                  JUnit Platform tests (MockWebServer)

  sdk-transport-jdkhttp/              Reference transport: java.net.http.HttpClient (Java 11 target)
    src/main/kotlin/
      org/dexpace/sdk/transport/jdkhttp/
        JdkHttpTransport.kt           Public — implements HttpClient + AsyncHttpClient
        internal/                     Internal adapters (request, response, body publishers, restricted-headers)
    src/test/kotlin/

  sdk-serde-jackson/                  Jackson 2.18 Serde adapter (Java 8 target)
    src/main/kotlin/
      org/dexpace/sdk/serde/jackson/
        JacksonSerde.kt               Public — Serde implementation + typed deserializeAs helpers
        JacksonObjectMappers.kt       Public — defaultObjectMapper() factory
        TristateModule.kt             Public — Jackson module wiring Tristate ser/de
    src/test/kotlin/

  docs/                               Design documentation
```

All modules except `sdk-async-virtualthreads` and `sdk-transport-jdkhttp` target Java 8 bytecode. `sdk-async-virtualthreads` overrides the toolchain to JDK 21 because virtual threads require it; `sdk-transport-jdkhttp` overrides to JDK 11 because `java.net.http.HttpClient` was finalised in JEP 321. Consumers of each module must be on the corresponding JDK or newer.

`sdk-core` defines only contracts and contains no concrete I/O implementation. Adapter modules depend on `sdk-core` and bring exactly one third-party library each; consumers pay only for what they use.

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

#### Body logging (`http.request` / `http.response`)

`LoggableRequestBody` lives in `http.request`; `LoggableResponseBody` lives in `http.response`.
There is no separate `http.logging` package.

| Type                   | Package        | Role                                                                |
|------------------------|----------------|---------------------------------------------------------------------|
| `LoggableRequestBody`  | `http.request` | Tee-write wrapper — captures request bytes during write for logging |
| `LoggableResponseBody` | `http.response`| Eager-buffering wrapper — repeatable reads + snapshot for logging   |
| `BodySnapshot`         | `http.request` or `http.response` | Immutable body capture with text detection and preview generation |
| `BodySegment`          | (shared)       | Fixed-size chunk + handler callback for streaming observation       |

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
fun interface HttpClient : AutoCloseable {
    fun execute(request: Request): Response
    override fun close() { /* default no-op */ }
}
```

A minimal interface that consuming libraries implement against their chosen HTTP transport
(HttpURLConnection, Apache HC, Jetty, Netty, etc.). The SDK provides everything around
this interface — body abstractions, logging, pipelines, contexts — but not the transport.

Both `HttpClient` and `AsyncHttpClient` extend `AutoCloseable` with a default no-op
`close()`, so SAM literals (`HttpClient { req -> ... }`) and lightweight wrappers remain
valid without an explicit close override. Transports that own background threads,
connection pools, or executors override `close()` to release them. See the
[Lifecycle](#lifecycle) cross-cutting section for the full contract (idempotency,
ownership distinction, interrupt-safety).

Two production-ready reference transports ship with the project today: `sdk-transport-okhttp`
(OkHttp 5.x, Java 8 bytecode) and `sdk-transport-jdkhttp` (`java.net.http.HttpClient`, Java 11
bytecode). Both implement `HttpClient` and `AsyncHttpClient` on a single class and can be
instantiated either by passing a preconfigured underlying client (BYO factory — `close()` is a
no-op, the caller owns the client's lifecycle) or by using the SDK-managed builder (`close()`
releases the underlying transport resources). See the README's "Choosing a transport" section
for usage examples.

### Serialization

**Package**: `org.dexpace.sdk.core.serde`

| Type             | Role                                                     |
|------------------|----------------------------------------------------------|
| `Serde`          | Combined serializer/deserializer interface               |
| `Deserializer`   | Deserialize from `String`, `ByteArray`, or `InputStream` |
| `SerializeTrait` | Mixin for types that can serialize themselves            |
| `Tristate<T>`    | Three-valued container (Absent / Null / Present) for `PATCH` payloads |

The core module defines abstractions only. Concrete implementations (Jackson, Moshi, kotlinx.serialization) belong in
optional extension modules. `sdk-serde-jackson` ships today as the reference Jackson 2.18 adapter, including a
`TristateModule` that wires the [`Tristate<T>`](#tristate) type through Jackson's
serializer / deserializer pipeline.

#### Jackson adapter (`sdk-serde-jackson`)

| Type                       | Visibility | Role                                                                 |
|----------------------------|------------|----------------------------------------------------------------------|
| `JacksonSerde`             | public     | `Serde` impl + typed `deserializeAs(input, TypeReference<T>)` helpers |
| `JacksonObjectMappers`     | public     | `defaultObjectMapper()` factory with SDK-correct defaults             |
| `TristateModule`           | public     | Jackson `SimpleModule` wiring `Tristate<T>` ser/de + property-omit hook |

SDK-correct mapper defaults installed by `JacksonObjectMappers.defaultObjectMapper()`:

- `KotlinModule`, `JavaTimeModule`, `Jdk8Module`, `TristateModule` all registered.
- `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` disabled — payloads can grow without breaking clients.
- `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` disabled — emits ISO-8601 strings, not epoch numbers.

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

The reference transport modules are the deliberate exception to the zero-dep rule: each pulls
in exactly one transport library — OkHttp 5.x for `sdk-transport-okhttp`, and no additional
runtime dependency for `sdk-transport-jdkhttp` (it uses the JDK standard library's
`java.net.http.HttpClient`). The principle still holds: `sdk-core` itself has zero runtime
deps; transport libraries are isolated to their own modules so consumers only pay for the
transport they pick.

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
this to hide implementation details:

- `sdk-core` / `io` package — `TeeSink` is `internal`; callers interact through `Buffer`,
  `Source`, `Sink`, `BufferedSource`, `BufferedSink`, `IoProvider`, and `Io` (all public).
- `sdk-io-okio3` — the concrete adapter classes (`OkioBuffer`, `OkioBufferedSource`,
  `OkioBufferedSink`) are `internal`; only `OkioIoProvider` is public.

Concrete I/O implementations belong in adapter modules, not in `sdk-core`. This keeps the
core dependency-free while still letting `internal` hide adapter internals within their
respective modules.

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

### Lifecycle

`HttpClient` and `AsyncHttpClient` extend `java.lang.AutoCloseable`. The interfaces ship a
default no-op `close()` so SAM literals (`HttpClient { req -> ... }`) and lightweight
wrappers stay valid without modification. Transports that own background threads,
connection pools, or executors override `close()` to release those resources.

The contract every transport implementation must uphold:

1. **Idempotent.** Repeated `close()` calls must be safe. The canonical pattern uses
   `private val closed = AtomicBoolean(false)` plus `closed.compareAndSet(false, true)` —
   lock-free, virtual-thread-friendly, no `synchronized` (which would pin a carrier thread
   under Loom).
2. **Ownership-aware.** Distinguish SDK-owned resources from user-supplied dependencies.
   An `internal val owned: Boolean` field — set to `true` only by the SDK's own builder
   (`OkHttpTransport.builder().build()`, `JdkHttpTransport.builder().build()`) and `false`
   by the BYO factory (`OkHttpTransport.create(yourClient)`,
   `JdkHttpTransport.create(yourClient)`) — gates the close action. Caller-supplied
   `OkHttpClient`s, `java.net.http.HttpClient`s, and `Executor`s are NEVER touched by the
   SDK; their lifecycle belongs to the caller.
3. **Interrupt-safe.** If `close()` waits on `executorService.shutdown()` or similar, it
   must respect `Thread.interrupt()` per the [Cancellation](#cancellation) convention. The
   current OkHttp adapter calls `shutdown()` (non-blocking) rather than `shutdownNow()` or
   `awaitTermination(...)`, so this is enforced trivially — no blocking step exists. Any
   future blocking close path must catch `InterruptedException`, restore the interrupt
   status, and surface as `InterruptedIOException`.
4. **Best-effort, non-throwing.** A failure to shut down one sub-resource must not
   prevent the rest of the close path from running. Adapters log the failure at
   `WARN` via the SDK's `ClientLogger` and continue.

Concrete adapter behaviour:

- **`sdk-transport-okhttp`** — `OkHttpTransport.close()` on an SDK-owned client calls
  `dispatcher.executorService.shutdown()` (graceful drain), `connectionPool.evictAll()`
  (release idle sockets), and `cache?.close()` (release file descriptors). On a
  user-supplied client, all three are skipped.
- **`sdk-transport-jdkhttp`** — `JdkHttpTransport.close()` on an SDK-owned client casts
  the underlying `java.net.http.HttpClient` to `AutoCloseable`. The interface was added
  in JDK 21 (JEP 461), so on JDK 11–20 the `instanceof` check returns `false` and the
  close is a documented no-op; on JDK 21+ the JDK client's selector and internal daemon
  executor are shut down promptly. The transport additionally shuts down any SDK-owned
  `ExecutorService` it passed to `HttpClient.Builder.executor(...)`; today the builder
  does not expose that knob, so the field is wired in advance for a future
  `Builder.executor(...)` opt-in.

After `close()` returns, the behaviour of subsequent `execute(...)` / `executeAsync(...)`
calls is undefined — implementations may throw or return an error response, but the SDK
does not mandate a specific failure mode. Callers should not reuse a closed transport;
they should construct a fresh one.

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
