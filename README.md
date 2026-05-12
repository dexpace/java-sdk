# Dexpace Java SDK

> [!CAUTION]
> **PROPRIETARY & CONFIDENTIAL SOFTWARE**
>
> **NO RIGHTS ARE GRANTED.**
>
> Any use, copying, modification, or distribution without
> explicit written consent from **Omar Aljarrah / dexpace**
> constitutes copyright infringement.

A zero-dependency, production-grade SDK core for building and maintaining Java/Kotlin HTTP
client libraries. Written in Kotlin, targeting **JDK 8+**, with first-class support for
platform threads, virtual threads (Project Loom), Kotlin coroutines, and reactive streams.

## Highlights

- **Zero external dependencies** beyond SLF4J API and Kotlin stdlib
- **Pluggable I/O** via `IoProvider` — `sdk-core` defines contracts; pick your streams lib (Okio 3.x today via `sdk-io-okio3`)
- **Non-destructive body logging** with full capture and repeatable reads
- **Pipeline architecture** for composable request/response processing with retry support
- **Immutable HTTP models** with fluent builder APIs and full Java interop (`@JvmOverloads`)
- **Virtual-thread safe** using `ReentrantLock` and `@Volatile` over `synchronized`

## Project Structure

```
java-sdk/
  sdk-core/               Single module — all SDK core code
    src/main/kotlin/       Kotlin sources (primary API)
    src/main/java/         Java sources (legacy/compat layer)
  docs/                    In-depth design documentation
```

### Kotlin Packages (`org.dexpace.sdk.core`)

| Package                                                                            | Description                                                                       |
|------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| [`io`](sdk-core/src/main/kotlin/org/dexpace/sdk/core/io)                           | I/O contracts: `Source`, `Sink`, `BufferedSource`, `BufferedSink`, `Buffer`, `IoProvider`, `Io` |
| [`http.request`](sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/request)       | Immutable `Request`, `RequestBody`, `LoggableRequestBody`, `Method`              |
| [`http.response`](sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/response)     | Immutable `Response`, `ResponseBody`, `LoggableResponseBody`, `Status`            |
| [`http.common`](sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/common)         | `Headers`, `MediaType`, `Protocol`, `CommonMediaTypes`                            |
| [`http.context`](sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/context)       | `CallContext`, `DispatchContext`, `RequestContext`, `ExchangeContext`             |
| [`pipeline`](sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline)               | `RequestPipeline`, `ResponsePipeline`, `BuilderPipeline`, `ExecutionPipeline`     |
| [`pipeline.step`](sdk-core/src/main/kotlin/org/dexpace/sdk/core/pipeline/step)     | `PipelineStep`, `RequestPipelineStep`, `ResponsePipelineStep`, step config traits |
| [`client`](sdk-core/src/main/kotlin/org/dexpace/sdk/core/client)                   | `HttpClient` interface                                                            |
| [`serde`](sdk-core/src/main/kotlin/org/dexpace/sdk/core/serde)                     | `Serde`, `Deserializer`, `SerializeTrait`                                         |
| [`instrumentation`](sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation) | `InstrumentationContext`, `Span`, `TracingScope`                                  |
| [`generics`](sdk-core/src/main/kotlin/org/dexpace/sdk/core/generics)               | `Builder<T>` — generic builder interface                                          |

### I/O Adapter (`sdk-io-okio3`)

Implements `sdk-core`'s I/O contracts over Okio 3.x. Install once at startup:

```kotlin
Io.installProvider(OkioIoProvider)
```

`sdk-core` itself ships no I/O implementation — pick an adapter (or write your own).

### Java Packages (`src/main/java`)

The Java source tree contains a comprehensive compatibility and integration layer including
annotations, binary data abstractions, credential types, HTTP models, pagination, JSON/XML
serialization (embedded Jackson core + Aalto XML), instrumentation (OpenTelemetry integration),
and trait interfaces. These classes provide the foundation for generated service clients.

## Documentation

| Document                                                                     | Description                                                                      |
|------------------------------------------------------------------------------|----------------------------------------------------------------------------------|
| [Architecture Overview](docs/architecture.md)                                | High-level design, module structure, and component responsibilities              |
| [HTTP Layer](docs/http.md)                                                   | Request/response models, headers, media types, context system, and HttpClient    |
| [I/O Module](docs/io.md)                                                     | Segment-based memory streams — architecture, design decisions, and API reference |
| [HTTP Body Logging & Concurrency](docs/http-body-logging-and-concurrency.md) | Body logging system, concurrency model, thread safety, and usage examples        |
| [Pipeline Mechanism](docs/pipelines.md)                                      | Request/response pipeline architecture and step composition                      |

## Quick Start

### Build

```bash
./gradlew build
```

### Making a request

```kotlin
val request = Request.builder()
    .url("https://api.example.com/v1/resource")
    .method(Method.POST)
    .header("Content-Type", "application/json")
    .body(RequestBody.create("""{"key": "value"}""", MediaType.parse("application/json")))
    .build()

val response = httpClient.execute(request)
response.use {
    if (it.isSuccessful) {
        val body = it.body?.string()
        // process response
    }
}
```

### Logging request/response bodies

```kotlin
// Request — captures bytes during write via tee-write
val loggable = LoggableRequestBody(body)
loggable.writeTo(outputStream)
logger.debug("Request: {}", loggable.snapshot()?.preview())

// Response — eagerly buffers for repeatable reads
val loggable = LoggableResponseBody(response.body!!)
logger.debug("Response: {}", loggable.snapshot().preview())
val data = loggable.bytes()  // body is still available
```

### Using the I/O layer

```kotlin
// Install the provider once at startup
Io.installProvider(OkioIoProvider)

// In-memory buffer
val buffer = Io.provider.buffer()
buffer.writeUtf8("Hello, world!")
val text = buffer.readUtf8()        // "Hello, world!"

// Bridge with java.io
val source = Io.provider.source(inputStream)
while (!source.exhausted()) {
    println(source.readUtf8Line())
}
source.close()
```

## Tech Stack

| Component  | Version |
|------------|---------|
| Kotlin     | 2.3.21  |
| Gradle     | 9.3.1   |
| JVM Target | Java 8  |
| SLF4J API  | 2.0.17  |
