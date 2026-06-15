<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/dexpace-wordmark-dark.svg">
    <img alt="dexpace" src="docs/assets/dexpace-wordmark-light.svg" width="320">
  </picture>
</p>

<h1 align="center">Java SDKs Platform</h1>

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.21-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
![JDK](https://img.shields.io/badge/JDK-8%2B-437291.svg?logo=openjdk&logoColor=white)
![Coverage](https://img.shields.io/badge/coverage-%E2%89%A580%25-success.svg)

A toolkit for building HTTP client libraries on the JVM. Dexpace is not an HTTP client: it is the machinery a client is made of. Immutable request and response models, a staged pipeline runtime, resilience steps, and seams for transport, I/O, serialization, and async runtimes.

Written in Kotlin, targeting JDK 8 bytecode. `sdk-core` has zero runtime dependencies beyond the Kotlin standard library and the SLF4J API (compile-only); every third-party library lives behind an adapter module, so consumers pay only for the runtime they use.

Current version `0.0.1-alpha.1`. The public API is stabilising and breaking changes between alpha releases are expected. External pull requests are welcome.

## Quick start

```kotlin
Io.installProvider(OkioIoProvider)   // once, at application startup

val transport = OkHttpTransport.builder()
    .connectTimeout(Duration.ofSeconds(5))
    .readTimeout(Duration.ofSeconds(30))
    .build()

val pipeline = HttpPipelineBuilder(transport)
    .append(DefaultRetryStep(HttpRetryOptions(maxRetries = 3)))
    .append(KeyCredentialAuthStep(KeyCredential("my-api-key")))
    .build()

val request = Request.builder()
    .method(Method.GET)
    .url("https://api.example.com/v1/resource")
    .build()

pipeline.send(request).use { response ->
    if (response.status.isSuccess) {
        val bytes = response.body?.source()?.readByteArray()
        // process
    }
}
```

The rest of this document covers the moving parts: transports, the async pipeline, runtime adapters, and body logging.

## Design principles

- The request/response model is async-first and immutable: private constructors, builders, `newBuilder()` copies, and Java-friendly factories (`@JvmOverloads`, `@JvmStatic`, `@JvmField` where applicable).
- The pipeline runtime orders steps by stage, supports surgical type-based edits (`insertAfter<T>`, `replace<T>`, `remove<T>`), and enforces pillars: exactly one retry, redirect, auth, and instrumentation step per pipeline.
- Resilience ships in the box. Retry honours `Retry-After` and backs off exponentially with jitter; redirects strip authorization headers and reject HTTPS→HTTP downgrades; auth covers `KeyCredential`, cached `BearerToken`, and RFC 7616 Digest (MD5, MD5-sess, SHA-256, SHA-256-sess); instrumentation provides structured logging, tracing, and metrics.
- Body logging never disturbs the wire. Request bytes are captured through a `TeeSink` during the write; responses are drained once and re-read through `peek()` views, with race-safe consumed-once guards and cached drain errors.
- Two seams keep the core dependency-free: `IoProvider` for streams, and `HttpClient` / `AsyncHttpClient` for transport. The core has no opinion about how bytes reach the wire.

## Modules

| Module | Purpose | JVM target |
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

Each adapter module depends on `sdk-core` and exactly one third-party library. JDK 8 or newer is the baseline, with the two exceptions in the table: `sdk-transport-jdkhttp` needs JDK 11 and `sdk-async-virtualthreads` needs JDK 21. Local builds use Gradle 9.3.1 and Kotlin 2.3.21.

## Documentation

| Document | Description |
|---|---|
| [Architecture overview](docs/architecture.md) | Design, module structure, component responsibilities |
| [HTTP layer](docs/http.md) | Request/response models, headers, media types, context system, `HttpClient` |
| [I/O module](docs/io.md) | I/O contracts and the `IoProvider` seam |
| [HTTP body logging and concurrency](docs/http-body-logging-and-concurrency.md) | Body logging system, concurrency model, thread safety |
| [Pipeline mechanism](docs/pipelines.md) | Pipeline architecture, stages, step composition, async pipeline |
| [Style guides](styleguide/README.md) | Kotlin and Kotlin-on-JVM style guides this codebase follows |

## Usage

### Choosing a transport

Bring your own `HttpClient` / `AsyncHttpClient` implementation, or use one of the two reference transports that ship with the project.

#### OkHttp: `sdk-transport-okhttp`

```kotlin
// BYO factory: pass your own preconfigured OkHttpClient
val transport = OkHttpTransport.create(myOkHttpClient)

// OR SDK-managed builder
val transport = OkHttpTransport.builder()
    .connectTimeout(Duration.ofSeconds(5))
    .readTimeout(Duration.ofSeconds(30))
    .followRedirects(false)   // default — SDK has DefaultRedirectStep
    .build()
```

Implements both `HttpClient` (sync) and `AsyncHttpClient` (async, via OkHttp `Call.enqueue`). `CompletableFuture.cancel()` propagates to `okhttp3.Call.cancel()`. Java 8 bytecode.

#### java.net.http.HttpClient: `sdk-transport-jdkhttp` (JDK 11+)

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

Implements both SPIs through `HttpClient.sendAsync`; `CompletableFuture.cancel()` aborts the underlying exchange natively. Java 11 bytecode, so consumers must be on JDK 11 or newer.

### The full synchronous pipeline

The quick start above shows the minimal path. A production pipeline usually fills every pillar:

```kotlin
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

### Asynchronous: `AsyncHttpClient` + `AsyncHttpPipeline`

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

### Kotlin coroutines: `sdk-async-coroutines`

```kotlin
import org.dexpace.sdk.async.coroutines.send

val response = async.send(request)   // suspend fun
```

### Reactor: `sdk-async-reactor`

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

### Netty: `sdk-async-netty`

```kotlin
import org.dexpace.sdk.async.netty.executeNetty

val nettyFuture = asyncClient.executeNetty(request, eventLoop)
nettyFuture.addListener { /* fire on event-loop thread */ }
```

### Virtual threads: `sdk-async-virtualthreads` (JDK 21+)

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

## Pipeline stages

Steps execute in declaration order of `Stage.entries`. Pillar stages (`isPillar = true`) admit exactly one step; non-pillar stages admit any number, ordered by `append` and `prepend`.

```
REDIRECT (pillar)  →  POST_REDIRECT     →  RETRY (pillar)   →  POST_RETRY        →
PRE_AUTH           →  AUTH (pillar)     →  POST_AUTH        →  PRE_LOGGING       →
LOGGING (pillar)   →  POST_LOGGING      →  PRE_SERDE        →  SERDE (pillar)    →
POST_SERDE         →  PRE_SEND          →  SEND (terminal — HttpClient.execute)
```

See [docs/pipelines.md](docs/pipelines.md) for the step-author walkthrough.

## Package map (`sdk-core`)

| Package | Highlights |
|---|---|
| `client` | `HttpClient`, `AsyncHttpClient` — the two transport SPIs (sync and async). |
| `http.request` | `Request`, `RequestBody`, `FileRequestBody`, `LoggableRequestBody`, `Method`. |
| `http.response` | `Response`, `ResponseBody`, `LoggableResponseBody`, `Status` (a value-carrying class with a total `fromCode`), `HttpResponseException`. |
| `http.response.exception` | Typed `HttpException` hierarchy (`BadRequestException`, `RequestTimeoutException`, `TooManyRequestsException`, `ServiceUnavailableException`, …) with `retryable` derived from `RetryUtils.isRetryable`, plus `NetworkException` and `HttpExceptionFactory`. |
| `http.common` | `Headers`, `HttpHeaderName` (interned), `MediaType`, `Protocol`, `HttpRange`, `ETag`, `RequestConditions`. |
| `http.context` | `CallContext` → `DispatchContext` → `RequestContext` → `ExchangeContext` chain, `ContextStore`. |
| `http.pipeline` | Sync (`HttpStep` / `HttpPipeline` / `HttpPipelineBuilder` / `PipelineNext` / `Stage`) and async (`AsyncHttpStep` / `AsyncHttpPipeline` / `AsyncHttpPipelineBuilder` / `AsyncPipelineNext`) pipeline machinery, plus `AsyncPipelineBridges`. |
| `http.pipeline.steps` | Concrete steps: `RetryStep`, `RedirectStep`, `AuthStep`, `KeyCredentialAuthStep`, `BearerTokenAuthStep`, `InstrumentationStep`, `SetDateStep`, and their `*Options` / `*Condition` types. |
| `http.auth` | `Credential` sealed hierarchy (`KeyCredential`, `NamedKeyCredential`, `BearerToken`), `BearerTokenProvider`, `AuthScheme`, `AuthMetadata`, RFC 7235 challenge parser, `BasicChallengeHandler`, `DigestChallengeHandler`, `CompositeChallengeHandler`. |
| `http.sse` | `ServerSentEventReader` (WHATWG spec), `ServerSentEvent`, `ServerSentEventListener`, `BufferedSource.readServerSentEvents()`. |
| `http.paging` | `PagedIterable<T>`, `PagedResponse<T>`, `PagingOptions` with `byPage()` and `stream()` accessors. |
| `pagination` | `Paginator<T>` (with a `maxPages` safety cap) over cursor / page-number / link-header `PaginationStrategy` implementations, plus `Page<T>` / `SimplePage<T>`. Token-style APIs use `CursorPaginationStrategy` with the query-param name set (e.g. `"page_token"`). |
| `pipeline` | Recovery-aware primitives: `RequestPipeline`, `ResponsePipeline`, `ExecutionPipeline` over a sealed `ResponseOutcome`, with steps (`pipeline.step`, `pipeline.step.retry`) like `RetryStep`, `ResponseRecoveryStep`, `IdempotencyKeyStep`, `ClientIdentityStep`. |
| `serde` | `Serde`, `Serializer`, `Deserializer` abstractions and `Tristate<T>` (absent / null / present). |
| `io` | `Source`, `Sink`, `Buffer`, `BufferedSource`, `BufferedSink`, `IoProvider`, `Io`, `TeeSink`. |
| `instrumentation` | `ClientLogger` (zero-alloc disabled path), `LoggingEvent`, `UrlRedactor`, `Tracer` / `NoopTracer`, `Span` / `NoopSpan`, `InstrumentationContext`. |
| `instrumentation.metrics` | `Meter`, `LongCounter`, `DoubleHistogram`, `NoopMeter`. |
| `config` | `Configuration` (system-property + env-var layered lookup), `ConfigurationBuilder`. |
| `util` | `Clock`, `Uuids` (non-blocking v4), `DateTimeRfc1123`, `RetryUtils`, `ProxyOptions`, `Futures`. |
| `generics` | `Builder<T>` — the generic builder interface every SDK builder implements. |

## Building

```bash
./gradlew build                # build every module
./gradlew test                 # run all tests across modules
./gradlew koverHtmlReport      # aggregate coverage report at build/reports/kover/html/
./gradlew apiCheck             # binary-compatibility check against committed .api snapshots
./gradlew apiDump              # regenerate .api snapshots after intentional API changes
```

Aggregate line coverage sits comfortably above the 80% floor; run `koverHtmlReport` for the current numbers.

### Quality gates

All of these break the build:

- `explicitApi = ExplicitApiMode.Strict` on every Kotlin module: every public declaration states its visibility and return type.
- `allWarningsAsErrors = true` for every Kotlin compile task.
- ktlint and detekt with `ignoreFailures = false`. Detekt is skipped on `sdk-async-virtualthreads` and `sdk-transport-jdkhttp`, whose JDK 21 / JDK 11 toolchains run analysis on a JDK 25 system JVM that detekt 1.23.x cannot parse; both build scripts link the upstream issue and the re-enable conditions. It runs everywhere else, including the JDK 8 transports.
- `kotlinx-binary-compatibility-validator` gates the public API surface against committed `.api` snapshots.
- Aggregate Kover line coverage has an 80% floor.

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
