# Dexpace Java SDK

> [!CAUTION]
> **PROPRIETARY & CONFIDENTIAL SOFTWARE**
>
> **NO RIGHTS ARE GRANTED.**
>
> Any use, copying, modification, or distribution without
> explicit written consent from **Omar Aljarrah / dexpace**
> constitutes copyright infringement.

A zero-dependency, production-grade SDK core for building Java/Kotlin HTTP client libraries.
Written in Kotlin, targeting **JDK 8+**, with first-class support for synchronous calls,
`CompletableFuture` async, Kotlin coroutines, Reactor `Mono`/`Flux`, Netty `Future`, and
JDK 21+ virtual threads.

## Highlights

- **Zero runtime dependencies** in `sdk-core` beyond SLF4J API and Kotlin stdlib.
- **Pluggable I/O** via `IoProvider` — `sdk-core` defines contracts; `sdk-io-okio3` is the
  Okio 3.x reference implementation. Write your own to support a different stream lib.
- **Pluggable transport** via `HttpClient` / `AsyncHttpClient` — the SDK is a *toolkit*, not
  an HTTP client. Plug in OkHttp, the JDK 11 HTTP client, Apache HttpClient, etc.
- **Hybrid pipeline architecture** with stage-based ordering, surgical type-based edits
  (`insertAfter<T>` / `replace<T>` / `remove<T>`), and pillar enforcement (one retry, one
  redirect, one auth, one instrumentation step).
- **Production resilience steps shipped**: `RetryStep` (exponential backoff + jitter +
  `Retry-After`), `RedirectStep` (authorization stripping, HTTPS→HTTP rejection),
  `AuthStep` family (`KeyCredential`, `BearerToken` with caching, Digest with RFC 7616),
  `InstrumentationStep` (structured logging + tracing + metrics).
- **Non-destructive body logging** with full capture and repeatable reads, race-safe
  consumed-once guards, drain-error caching.
- **Async-first contract** with `CompletableFuture` and ergonomic adapters for coroutines,
  Reactor, Netty, and virtual threads.
- **Immutable HTTP models** with fluent builder APIs and full Java interop
  (`@JvmOverloads`, `@JvmStatic`, `@JvmField` where it matters).
- **Virtual-thread safe** — `ReentrantLock` over `synchronized`, `AtomicBoolean.compareAndSet`
  over `@Volatile` flips, no carrier-pinning code paths in the hot path.
- **97%+ line coverage via Kotlinx Kover** with ~1,400 tests across modules.

## Module Layout

| Module | Description | JVM Target |
|---|---|---|
| `sdk-core` | Contracts, pipeline runtime, sync + async pipelines, all built-in steps. Zero runtime deps. | Java 8 |
| `sdk-io-okio3` | Okio 3.x implementation of `IoProvider`. | Java 8 |
| `sdk-async-coroutines` | Kotlin coroutines adapter — `suspend` extensions, `CoroutineScope.completableFutureOf`, `runInterruptible`-aware sync bridge. | Java 8 |
| `sdk-async-reactor` | Reactor `Mono` / `Flux` adapter, including SSE → `Flux` with backpressure. | Java 8 |
| `sdk-async-netty` | Netty `io.netty.util.concurrent.Future` adapter with bidirectional cancellation. | Java 8 |
| `sdk-async-virtualthreads` | JDK 21+ virtual-thread executor adapter (`AutoCloseable`). | Java 21 |

Each adapter module follows the same pattern: depend on `sdk-core`, bring in exactly one
third-party concurrency lib, expose ergonomic extensions. Consumers pay only for what they
use.

## Package Map (sdk-core)

| Package | Highlights |
|---|---|
| `client` | `HttpClient`, `AsyncHttpClient` — the two transport SPIs (sync + async). |
| `http.request` | `Request`, `RequestBody`, `FileRequestBody`, `LoggableRequestBody`, `Method`. |
| `http.response` | `Response`, `ResponseBody`, `LoggableResponseBody`, `Status`, `HttpResponseException`. |
| `http.common` | `Headers`, `HttpHeaderName` (interned), `MediaType`, `Protocol`, `HttpRange`, `ETag`, `RequestConditions`. |
| `http.context` | `CallContext` → `DispatchContext` → `RequestContext` → `ExchangeContext` chain, `ContextStore`. |
| `http.pipeline` | Sync (`HttpStep`/`HttpPipeline`/`HttpPipelineBuilder`/`PipelineNext`/`Stage`) and async (`AsyncHttpStep`/`AsyncHttpPipeline`/`AsyncHttpPipelineBuilder`/`AsyncPipelineNext`) pipeline machinery, plus `AsyncPipelineBridges`. |
| `http.pipeline.steps` | Concrete steps: `RetryStep` / `RedirectStep` / `AuthStep` / `KeyCredentialAuthStep` / `BearerTokenAuthStep` / `InstrumentationStep` / `SetDateStep` + their `*Options` / `*Condition` types. |
| `http.auth` | `Credential` sealed hierarchy (`KeyCredential`, `NamedKeyCredential`, `BearerToken`), `BearerTokenProvider`, `AuthScheme`, `AuthMetadata`, RFC 7235 challenge parser, `BasicChallengeHandler`, `DigestChallengeHandler` (MD5 / MD5-sess / SHA-256 / SHA-256-sess). |
| `http.sse` | `ServerSentEventReader` (WHATWG spec), `ServerSentEvent`, `ServerSentEventListener`, `BufferedSource.readServerSentEvents()`. |
| `http.paging` | `PagedIterable<T>`, `PagedResponse<T>`, `PagingOptions` with `byPage()` + `stream()` accessors. |
| `io` | `Source`, `Sink`, `Buffer`, `BufferedSource`, `BufferedSink`, `IoProvider`, `Io`, `TeeSink`. |
| `instrumentation` | `ClientLogger` (zero-alloc disabled path), `LoggingEvent`, `UrlRedactor`, `Tracer` / `NoopTracer`, `Span` / `NoopSpan`, `InstrumentationContext`. |
| `instrumentation.metrics` | `Meter`, `LongCounter`, `DoubleHistogram`, `NoopMeter`. |
| `config` | `Configuration` (system-property + env-var layered lookup), `ConfigurationBuilder`. |
| `util` | `Clock`, `Uuids` (non-blocking v4), `DateTimeRfc1123`, `RetryUtils`, `ProxyOptions`, `Futures`. |
| `generics` | `Builder<T>` — the generic builder interface every SDK builder implements. |

A separate `src/main/java` tree carries the legacy/compat layer that backs generated service
clients (Azure-style annotations, embedded Jackson Core, embedded Aalto XML, OTel adapters).
It is intentionally not part of the hand-written Kotlin surface.

## Documentation

| Document | Description |
|---|---|
| [Architecture Overview](docs/architecture.md) | High-level design, module structure, component responsibilities |
| [HTTP Layer](docs/http.md) | Request/response models, headers, media types, context system, `HttpClient` |
| [I/O Module](docs/io.md) | I/O contracts and the `IoProvider` seam |
| [HTTP Body Logging & Concurrency](docs/http-body-logging-and-concurrency.md) | Body logging system, concurrency model, thread safety |
| [Pipeline Mechanism](docs/pipelines.md) | Pipeline architecture, stages, step composition, async pipeline |
| [Style Guides](styleguide/README.md) | Kotlin and Kotlin-on-JVM style guides this codebase follows |

## Quick Start

### Build

```bash
./gradlew build           # build everything
./gradlew test            # run all tests
./gradlew koverHtmlReport # coverage report
```

### Sync — `HttpClient` + `HttpPipeline`

```kotlin
Io.installProvider(OkioIoProvider)   // one-time at app startup

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

### Async — `AsyncHttpClient` + `AsyncHttpPipeline`

```kotlin
val async = AsyncHttpPipelineBuilder(asyncTransport)
    .append(/* AsyncHttpStep implementations */)
    .build()

async.sendAsync(request).whenComplete { response, error ->
    if (error != null) { /* handle */ }
    else response.use { /* process */ }
}
```

Or bridge from a sync pipeline:

```kotlin
val async = syncPipeline.toAsync(Executors.newVirtualThreadPerTaskExecutor())
```

### Kotlin coroutines (sdk-async-coroutines)

```kotlin
import org.dexpace.sdk.async.coroutines.send

val response = async.send(request)   // suspend fun
```

### Reactor (sdk-async-reactor)

```kotlin
import org.dexpace.sdk.async.reactor.sendMono

async.sendMono(request)
    .doOnNext { /* process */ }
    .subscribe()
```

SSE → `Flux` with backpressure:

```kotlin
response.body!!.source().readServerSentEventsAsFlux()
    .doOnNext { event -> /* handle event */ }
    .subscribe()
```

### Netty (sdk-async-netty)

```kotlin
import org.dexpace.sdk.async.netty.executeNetty

val nettyFuture = asyncClient.executeNetty(request, eventLoop)
nettyFuture.addListener { /* fire on event-loop thread */ }
```

### Virtual threads (sdk-async-virtualthreads, JDK 21+)

```kotlin
val syncTransport = /* a blocking HttpClient */
syncTransport.asAsyncVirtualThreads().use { vt ->
    val future = vt.executeAsync(request)
    // ...
}   // close() releases the virtual-thread executor
```

### Body logging

```kotlin
// Request — captures bytes during write via TeeSink
val logged = LoggableRequestBody(body)
// ... pass `logged` as the request body; transport calls writeTo()
logger.debug("request body: {}", logged.snapshot().take(8 * 1024))

// Response — drains lazily, caches drain errors, peek-based repeat reads
val logged = LoggableResponseBody(response.body!!)
val preview = logged.snapshot(maxBytes = 8 * 1024)
val full = logged.source().readByteArray()   // still available
```

### Coverage

```bash
./gradlew koverHtmlReport
open build/reports/kover/html/index.html
```

Current line coverage: **97.7%**. Branch coverage: **92.1%**.

## Pipeline Stages

Steps run in declaration order of `Stage.entries`. Pillar stages (`isPillar = true`) admit
exactly one step; non-pillar stages admit any number, ordered by `append` / `prepend`.

```
REDIRECT (pillar)  →  POST_REDIRECT     →  RETRY (pillar)   →  POST_RETRY        →
PRE_AUTH           →  AUTH (pillar)     →  POST_AUTH        →  PRE_LOGGING       →
LOGGING (pillar)   →  POST_LOGGING      →  PRE_SERDE        →  SERDE (pillar)    →
POST_SERDE         →  PRE_SEND          →  SEND (terminal — HttpClient.execute)
```

See [docs/pipelines.md](docs/pipelines.md) for the full step-author walkthrough.

## Tech Stack

| Component | Version |
|---|---|
| Kotlin | 2.3.21 |
| Gradle | 9.3.1 |
| `sdk-core` / okio3 / coroutines / reactor / netty JVM target | Java 8 |
| `sdk-async-virtualthreads` JVM target | Java 21 |
| SLF4J API | 2.0.18 (compileOnly) |
| Okio | 3.17.0 (`sdk-io-okio3`) |
| kotlinx-coroutines | 1.11.0 (`sdk-async-coroutines`) |
| Reactor Core | 3.8.5 (`sdk-async-reactor`) |
| Netty Common | 4.2.13.Final (`sdk-async-netty`) |
| Kotlinx Kover | 0.9.8 (coverage; root) |
