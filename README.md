# Dexpace Java SDK

A toolkit for building Java/Kotlin HTTP client libraries. Written in Kotlin, targeting JDK 8 bytecode, with adapter modules for synchronous calls, `CompletableFuture`, Kotlin coroutines, Reactor `Mono`/`Flux`, Netty `Future`, and JDK 21+ virtual threads.

> **License — MIT.** Copyright © 2026 dexpace and Omar Aljarrah. See [LICENSE](LICENSE).

## Status

`0.0.1-alpha.1`. The public API is stabilising; breaking changes between alpha releases are expected. External pull requests are welcome.

## Overview

The SDK provides:

- An async-first HTTP request/response model with immutable builders and Java-friendly factories (`@JvmOverloads`, `@JvmStatic`, `@JvmField` where applicable).
- A composable pipeline runtime with stage-based ordering, surgical type-based edits (`insertAfter<T>`, `replace<T>`, `remove<T>`), and pillar enforcement (exactly one retry, redirect, auth, and instrumentation step per pipeline).
- Built-in resilience steps: retry (exponential backoff with jitter and `Retry-After` honouring), redirect (authorization stripping, HTTPS→HTTP rejection), auth (`KeyCredential`, `BearerToken` with caching, RFC 7616 Digest in MD5 / MD5-sess / SHA-256 / SHA-256-sess), and instrumentation (structured logging, tracing, metrics).
- Non-destructive body logging — `TeeSink`-based capture on requests, drain-and-peek on responses — with race-safe consumed-once guards and cached drain errors.
- A pluggable I/O seam (`IoProvider`) so the core has zero hard dependency on a stream library.
- A pluggable transport seam (`HttpClient` / `AsyncHttpClient`) so the core has no opinion about how bytes reach the wire.

`sdk-core` has zero runtime dependencies beyond SLF4J API (compile-only) and the Kotlin standard library. All third-party concurrency libraries (Okio, kotlinx-coroutines, Reactor, Netty) are confined to adapter modules.

## Requirements

| Requirement | Version |
|---|---|
| JDK (consumers of `sdk-core` and the Java-8 adapter modules) | 8 or newer |
| JDK (consumers of `sdk-async-virtualthreads`) | 21 or newer |
| JDK (consumers of `sdk-transport-jdkhttp`) | 11 or newer |
| Gradle (for local builds) | 9.3.1 |
| Kotlin | 2.3.21 |

## Modules

| Module | Purpose | JVM Target |
|---|---|---|
| `sdk-core` | Contracts, pipeline runtime, sync + async pipelines, built-in steps. Zero runtime deps beyond SLF4J API and Kotlin stdlib. | Java 8 |
| `sdk-io-okio3` | Okio 3.x implementation of `IoProvider`. | Java 8 |
| `sdk-async-coroutines` | Kotlin coroutines adapter: `suspend` extensions, `CoroutineScope.completableFutureOf`, MDC propagation. | Java 8 |
| `sdk-async-reactor` | Reactor `Mono` / `Flux` adapter, including SSE → `Flux` with backpressure. | Java 8 |
| `sdk-async-netty` | Netty `io.netty.util.concurrent.Future` adapter with bidirectional cancellation. | Java 8 |
| `sdk-async-virtualthreads` | JDK 21+ virtual-thread executor adapter (`AutoCloseable`). | Java 21 |
| `sdk-transport-okhttp` | OkHttp 5.x implementation of `HttpClient` + `AsyncHttpClient`. | Java 8 |
| `sdk-transport-jdkhttp` | `java.net.http.HttpClient` (JEP 321) implementation of `HttpClient` + `AsyncHttpClient`. | Java 11 |
| `sdk-serde-jackson` | Jackson 2.18 implementation of `Serde` with SDK-correct defaults (`FAIL_ON_UNKNOWN_PROPERTIES=false`, `WRITE_DATES_AS_TIMESTAMPS=false`) + `Tristate<T>` ser/de. | Java 8 |

Each adapter module depends on `sdk-core` and exactly one third-party concurrency library, so consumers only pay for the runtime they use.

## Documentation

| Document | Description |
|---|---|
| [Architecture Overview](docs/architecture.md) | Design, module structure, component responsibilities |
| [HTTP Layer](docs/http.md) | Request/response models, headers, media types, context system, `HttpClient` |
| [I/O Module](docs/io.md) | I/O contracts and the `IoProvider` seam |
| [HTTP Body Logging & Concurrency](docs/http-body-logging-and-concurrency.md) | Body logging system, concurrency model, thread safety |
| [Pipeline Mechanism](docs/pipelines.md) | Pipeline architecture, stages, step composition, async pipeline |
| [Style Guides](styleguide/README.md) | Kotlin and Kotlin-on-JVM style guides this codebase follows |

## Building

```bash
./gradlew build                # build every module
./gradlew test                 # run all tests (1,496 tests across modules)
./gradlew koverHtmlReport      # aggregate coverage report at build/reports/kover/html/
./gradlew apiCheck             # binary-compatibility check against committed .api snapshots
./gradlew apiDump              # regenerate .api snapshots after intentional API changes
```

Coverage at HEAD: 93.3% line, 87.6% branch (1,496 tests, 0 failures).

## Usage

### Choosing a transport

The SDK is a toolkit, not an HTTP client — bring your own `HttpClient` / `AsyncHttpClient` implementation. Two reference transports ship with the project:

#### OkHttp — `sdk-transport-okhttp`

```kotlin
// BYO factory: pass your own preconfigured OkHttpClient
val transport = OkHttpTransport.create(myOkHttpClient)

// OR SDK-managed builder
val transport = OkHttpTransport.builder()
    .connectTimeout(Duration.ofSeconds(5))
    .readTimeout(Duration.ofSeconds(30))
    .followRedirects(false)   // default — SDK has DefaultRedirectStep
    .build()

val pipeline = HttpPipelineBuilder(transport)
    .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3)))
    .build()
```

Implements both `HttpClient` (sync) and `AsyncHttpClient` (async, via OkHttp `Call.enqueue`). Cancellation propagates from `CompletableFuture.cancel()` to `okhttp3.Call.cancel()`. Java 8 bytecode target.

#### java.net.http.HttpClient — `sdk-transport-jdkhttp` (JDK 11+)

```kotlin
// BYO factory
val transport = JdkHttpTransport.create(myJdkHttpClient)

// OR SDK-managed builder
val transport = JdkHttpTransport.builder()
    .connectTimeout(Duration.ofSeconds(5))
    .responseTimeout(Duration.ofSeconds(30))
    .httpVersion(JdkHttpTransport.HttpVersion.HTTP_2)   // default
    .build()
```

Implements both `HttpClient` and `AsyncHttpClient` (via `HttpClient.sendAsync`). Cancellation propagates natively — `CompletableFuture.cancel()` aborts the underlying exchange. Java 11 bytecode target — consumers must be on JDK 11+.

### Synchronous — `HttpClient` + `HttpPipeline`

```kotlin
Io.installProvider(OkioIoProvider)   // one-time at application startup

val pipeline = HttpPipelineBuilder(transport)
    .append(SetDateStep())
    .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3)))
    .append(DefaultRedirectStep())
    .append(KeyCredentialAuthStep(KeyCredential("my-api-key")))
    .append(DefaultInstrumentationStep(HttpInstrumentationOptions(logLevel = HttpLogLevel.HEADERS)))
    .build()

val request = Request.builder()
    .method(Method.POST)
    .url("https://api.example.com/v1/resource")
    .addHeader("Content-Type", "application/json")
    .body(RequestBody.create("""{"key": "value"}""", MediaType.parse("application/json")))
    .build()

pipeline.send(request).use { response ->
    if (response.status.isSuccess) {
        val bytes = response.body?.source()?.readByteArray()
        // process
    }
}
```

### Asynchronous — `AsyncHttpClient` + `AsyncHttpPipeline`

```kotlin
val async = AsyncHttpPipelineBuilder(asyncTransport)
    .append(/* AsyncHttpStep implementations */)
    .build()

async.sendAsync(request).whenComplete { response, error ->
    if (error != null) { /* handle */ }
    else response.use { /* process */ }
}
```

Bridge a sync pipeline to async:

```kotlin
val async = syncPipeline.toAsync(Executors.newVirtualThreadPerTaskExecutor())
```

### Kotlin coroutines — `sdk-async-coroutines`

```kotlin
import org.dexpace.sdk.async.coroutines.send

val response = async.send(request)   // suspend fun
```

### Reactor — `sdk-async-reactor`

```kotlin
import org.dexpace.sdk.async.reactor.sendMono

async.sendMono(request)
    .doOnNext { /* process */ }
    .subscribe()
```

Server-Sent Events as a `Flux` with backpressure:

```kotlin
response.body!!.source().readServerSentEventsAsFlux()
    .doOnNext { event -> /* handle event */ }
    .subscribe()
```

### Netty — `sdk-async-netty`

```kotlin
import org.dexpace.sdk.async.netty.executeNetty

val nettyFuture = asyncClient.executeNetty(request, eventLoop)
nettyFuture.addListener { /* fire on event-loop thread */ }
```

### Virtual threads — `sdk-async-virtualthreads` (JDK 21+)

```kotlin
val syncTransport = /* a blocking HttpClient */
syncTransport.asAsyncVirtualThreads().use { vt ->
    val future = vt.executeAsync(request)
    // ...
}   // close() releases the virtual-thread executor
```

### Body logging

```kotlin
// Request: bytes captured during write via TeeSink
val loggedRequest = LoggableRequestBody(body)
// pass `loggedRequest` as the request body; transport calls writeTo()
logger.debug("request body: {}", loggedRequest.snapshot().take(8 * 1024))

// Response: drained lazily, drain errors cached, peek-based repeat reads
val loggedResponse = LoggableResponseBody(response.body!!)
val preview = loggedResponse.snapshot(maxBytes = 8 * 1024)
val full = loggedResponse.source().readByteArray()   // still available
```

## Pipeline Stages

Steps execute in declaration order of `Stage.entries`. Pillar stages (`isPillar = true`) admit exactly one step; non-pillar stages admit any number, ordered by `append` and `prepend`.

```
REDIRECT (pillar)  →  POST_REDIRECT     →  RETRY (pillar)   →  POST_RETRY        →
PRE_AUTH           →  AUTH (pillar)     →  POST_AUTH        →  PRE_LOGGING       →
LOGGING (pillar)   →  POST_LOGGING      →  PRE_SERDE        →  SERDE (pillar)    →
POST_SERDE         →  PRE_SEND          →  SEND (terminal — HttpClient.execute)
```

See [docs/pipelines.md](docs/pipelines.md) for the step-author walkthrough.

## Package Map (`sdk-core`)

| Package | Highlights |
|---|---|
| `client` | `HttpClient`, `AsyncHttpClient` — the two transport SPIs (sync and async). |
| `http.request` | `Request`, `RequestBody`, `FileRequestBody`, `LoggableRequestBody`, `Method`. |
| `http.response` | `Response`, `ResponseBody`, `LoggableResponseBody`, `Status`, `HttpResponseException`. |
| `http.common` | `Headers`, `HttpHeaderName` (interned), `MediaType`, `Protocol`, `HttpRange`, `ETag`, `RequestConditions`. |
| `http.context` | `CallContext` → `DispatchContext` → `RequestContext` → `ExchangeContext` chain, `ContextStore`. |
| `http.pipeline` | Sync (`HttpStep` / `HttpPipeline` / `HttpPipelineBuilder` / `PipelineNext` / `Stage`) and async (`AsyncHttpStep` / `AsyncHttpPipeline` / `AsyncHttpPipelineBuilder` / `AsyncPipelineNext`) pipeline machinery, plus `AsyncPipelineBridges`. |
| `http.pipeline.steps` | Concrete steps: `RetryStep`, `RedirectStep`, `AuthStep`, `KeyCredentialAuthStep`, `BearerTokenAuthStep`, `InstrumentationStep`, `SetDateStep`, and their `*Options` / `*Condition` types. |
| `http.auth` | `Credential` sealed hierarchy (`KeyCredential`, `NamedKeyCredential`, `BearerToken`), `BearerTokenProvider`, `AuthScheme`, `AuthMetadata`, RFC 7235 challenge parser, `BasicChallengeHandler`, `DigestChallengeHandler`. |
| `http.sse` | `ServerSentEventReader` (WHATWG spec), `ServerSentEvent`, `ServerSentEventListener`, `BufferedSource.readServerSentEvents()`. |
| `http.paging` | `PagedIterable<T>`, `PagedResponse<T>`, `PagingOptions` with `byPage()` and `stream()` accessors. |
| `io` | `Source`, `Sink`, `Buffer`, `BufferedSource`, `BufferedSink`, `IoProvider`, `Io`, `TeeSink`. |
| `instrumentation` | `ClientLogger` (zero-alloc disabled path), `LoggingEvent`, `UrlRedactor`, `Tracer` / `NoopTracer`, `Span` / `NoopSpan`, `InstrumentationContext`. |
| `instrumentation.metrics` | `Meter`, `LongCounter`, `DoubleHistogram`, `NoopMeter`. |
| `config` | `Configuration` (system-property + env-var layered lookup), `ConfigurationBuilder`. |
| `util` | `Clock`, `Uuids` (non-blocking v4), `DateTimeRfc1123`, `RetryUtils`, `ProxyOptions`, `Futures`. |
| `generics` | `Builder<T>` — the generic builder interface every SDK builder implements. |

The `src/main/java` tree under `sdk-core` carries a legacy/compat layer that backs generated service clients (Azure-style annotations, embedded Jackson Core, embedded Aalto XML, OpenTelemetry adapters). It is intentionally not part of the hand-written Kotlin surface.

## Build Quality Gates

- `explicitApi = ExplicitApiMode.Strict` on every Kotlin module — every public declaration must declare its visibility and return type.
- `allWarningsAsErrors = true` for every Kotlin compile task.
- ktlint and detekt run with `ignoreFailures = false`. Detekt is currently skipped on `sdk-async-virtualthreads` pending a detekt release that supports JDK 25; see the module's build script for the upstream issue link and re-enable conditions.
- `kotlinx-binary-compatibility-validator` gates the public API surface against committed `.api` snapshots.
- Aggregate Kover coverage is gated at an 80% line-coverage floor.

## Dependencies

| Component | Version | Scope |
|---|---|---|
| Kotlin | 2.3.21 | All modules |
| Gradle | 9.3.1 | Build |
| SLF4J API | 2.0.18 | `sdk-core` (compileOnly) |
| Okio | 3.17.0 | `sdk-io-okio3` |
| kotlinx-coroutines | 1.11.0 | `sdk-async-coroutines` |
| Reactor Core | 3.8.5 | `sdk-async-reactor` |
| Netty Common | 4.2.13.Final | `sdk-async-netty` |
| OkHttp | 5.0.0 | `sdk-transport-okhttp` |
| mockwebserver3 | 5.0.0 | `sdk-transport-okhttp`, `sdk-transport-jdkhttp` (test-only) |
| Jackson | 2.18.2 | `sdk-serde-jackson` |
| Kover | 0.9.8 | Coverage (root project) |

## License

This project is licensed under the [MIT License](LICENSE). Copyright © 2026 dexpace and Omar Aljarrah. Every source file carries an MIT license header.
