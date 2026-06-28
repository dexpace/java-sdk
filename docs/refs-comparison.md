# Reference SDK Comparison & Codegen Strategy

A consolidated read of four reference SDKs (Expedia Group, Square, Airbyte, Google Cloud / gax)
plus the AWS Smithy 2.0 and Microsoft Kiota ecosystems, mapped against our `dexpace/java-sdk`
design. This document captures **what to copy, what to avoid, and what to build next**.

Source agent reports are not committed; this document is the synthesized conclusion.

## Table of Contents

- [TL;DR](#tldr)
- [Reference SDK Capsule Table](#reference-sdk-capsule-table)
- [Subsystem-by-Subsystem Comparison](#subsystem-by-subsystem-comparison)
  - [HTTP Transport SPI](#http-transport-spi)
  - [Request / Response Models & Body Replayability](#request--response-models--body-replayability)
  - [I/O Contracts](#io-contracts)
  - [Pipeline / Middleware](#pipeline--middleware)
  - [Retry & Backoff](#retry--backoff)
  - [Auth](#auth)
  - [Idempotency](#idempotency)
  - [Serialization](#serialization)
  - [Error Model](#error-model)
  - [Pagination](#pagination)
  - [Long-Running Operations & Streaming](#long-running-operations--streaming)
  - [Instrumentation & Tracing](#instrumentation--tracing)
  - [Async Adapters](#async-adapters)
  - [Configuration & Lifecycle](#configuration--lifecycle)
  - [Webhooks](#webhooks)
- [Where We Already Lead](#where-we-already-lead)
- [Feature Backlog](#feature-backlog)
- [Code Generation Strategy](#code-generation-strategy)
  - [Options Considered](#options-considered)
  - [Recommendation: Build Our Own (KotlinPoet + Swagger-Parser)](#recommendation-build-our-own-kotlinpoet--swagger-parser)
  - [What to Copy When Building It](#what-to-copy-when-building-it)
  - [What to Avoid](#what-to-avoid)
  - [Rough Plan](#rough-plan)

---

## TL;DR

1. **Our core architecture is sound.** Zero-dep `sdk-core`, single-method `HttpClient` SPI,
   `IoProvider` seam, body replayability, separate async adapter modules, ReentrantLock +
   interrupt discipline — every reference SDK gets at least one of these wrong.
2. **Most of the early gaps are now filled.** Retry, auth, pagination, idempotency, the typed
   exception hierarchy, a rich tracer event vocabulary, a metrics seam, and client lifecycle
   (`close`) all ship in `sdk-core` today. What remains genuinely unbuilt is narrow: webhook
   signature verification and a `Settings` → `Context` lifecycle split.
3. **The pipeline architecture absorbed Airbyte's `Hook` taxonomy**
   (`SdkInit` / `BeforeRequest` / `AfterSuccess` / `AfterError`) — the cleanest middleware
   shape we've seen. It maps onto `RequestPipelineStep` / `ResponsePipelineStep` plus a
   recovery-aware `ResponsePipeline` that folds a sealed `ResponseOutcome`
   (`Success(Response)` / `Failure(Throwable)`) rather than a bare response.
4. **For code generation, build our own.** A KotlinPoet/JavaPoet-based emitter sitting on
   swagger-parser, distributed as a Gradle plugin, targeting our `sdk-core` runtime. None
   of the off-the-shelf options (Fern, Speakeasy, Kiota, smithy-java, Expedia's plugin,
   OpenAPI Generator's stock kotlin emitter) preserves our zero-runtime-dep + Java 8 +
   Kotlin-first + pluggable-transport constraints. Forking any of them costs more than a
   typed-IR rewrite, and Mustache-based generators carry a built-in correctness tax
   (Expedia's plugin alone has 20 cataloged bugs from string-templating typos).

## Reference SDK Capsule Table

| Repo | Lang | JVM | Transport | Runtime deps | Codegen | Notable |
|---|---|---|---|---|---|---|
| Expedia Group | Kotlin | 8 | OkHttp 4.12 (+ ServiceLoader SPI) | Okio (`api`!), Jackson/SLF4J `compileOnly` | OpenAPI Generator 7.15 fork + 25 Mustache templates | Trait composition for operations; per-status typed exceptions; **unmaintained**, ~20 known bugs |
| Square | Java | 8 | OkHttp 5 + Jackson 2.18 (hardcoded) | OkHttp, Jackson, full set | Fern (TS hosted CLI) | Raw/Cooked/Async client triplet; `SyncPagingIterable` + `BiDirectionalPage`; `RetryInterceptor` with proper `Retry-After`/`X-RateLimit-Reset` parsing |
| Airbyte | Java | 11 | `java.net.http.HttpClient` (hardcoded) | Jackson 2.18, jackson-databind-nullable, commons-io | Speakeasy (Go SaaS, closed) | Hook taxonomy is gold; reflection-driven serialization is awful; one `SDKError` for everything |
| Google Cloud / gax | Java | 8 | gRPC + HTTP/JSON | Guava, gRPC, protobuf, AutoValue, OTel/OC, threetenbp | gapic-generator-java (proto plugin, Bazel) | Callable decorator chain; rich `ApiTracer` vocabulary; per-attempt shrinking deadlines; `ApiFuture` is a cautionary tale |
| AWS Smithy 2.0 / smithy-java | Java 21-only runtime | n/a (codegen) | `java.net.http.HttpClient` w/ virtual threads | OTel, etc. | `codegen-core` + `JavaCodegenIntegration` (typed) | IDL > OpenAPI; runtime locked to JDK 21; ~6 weeks since GA (2026-04-06); no Kotlin target |
| Microsoft Kiota | Java 11+ runtime | n/a (codegen) | `RequestAdapter` (default OkHttp) | Gson, jakarta.annotation, std-uritemplate, OTel | C#-based generator | Fluent path DSL; locked-down customization; ~3.7K stars; replaces our `HttpClient` SPI |

## Subsystem-by-Subsystem Comparison

### HTTP Transport SPI

| SDK | Shape | Verdict |
|---|---|---|
| Ours | `interface HttpClient { fun execute(Request): Response }` | Right shape |
| Expedia | `Transport` + `AsyncTransport` (two SPIs); `ServiceLoader` discovery | Duplicated hierarchies; ServiceLoader = silent classpath ordering bugs |
| Square | OkHttp `Call.Factory` hardcoded in every generated `Raw*Client` | No SPI |
| Airbyte | `HTTPClient { send(HttpRequest): HttpResponse<InputStream> }` over JDK 11 types | SPI exists but leaks JDK 11 types — OkHttp adapter is painful |
| gax | `TransportChannel` (marker-thin) + `TransportChannelProvider.needsX()` | The send method does NOT live on the channel — actual sending is per-Callable |
| Kiota | `RequestAdapter` (owns send + deserialize + auth + tracing) | Too thick; replaces our SPI |

**Notes:**
1. `HttpClient` and `AsyncHttpClient` already extend `AutoCloseable` with a no-op default `close()`, so consumers can shut down OkHttp dispatchers, JDK selector threads, and connection pools while SAM literals stay lightweight. Both reference transports close only SDK-managed clients (BYO clients are never closed). (Expedia's `Disposable.kt:25` was the prior art.)
2. Keep our existing transport SPI shape. Do not adopt gax's marker-channel design — our model maps to HTTP cleanly; gax's was driven by gRPC.
3. Reject `ServiceLoader` discovery (Expedia's pattern). Keep explicit installation à la `Io.installProvider(...)`.

### Request / Response Models & Body Replayability

- **We're already correct here.** Immutable models with `private constructor` + `Builder` + `newBuilder()` matches Expedia (which got this right). `RequestBody.isReplayable()` / `toReplayable(provider)` is unique to us — every other reference SDK assumes bodies are `byte[]` and silently breaks retry/auth-refresh on streaming bodies.
- **Expedia's `FileRequestBody` opportunity** (we have it spec'd in `docs/architecture.md`): transports can type-check and dispatch to `FileChannel.transferTo` for `sendfile(2)`. None of the reference SDKs do this.
- **Header handling:** Expedia's `Headers` lower-cases names but allows duplicate `add()` calls that keep both values. Our implementation should be canonicalized (HTTP/2 normalizes to lowercase) and deduplicated by header name + value at insertion time. Verify our `Headers.kt` matches.
- **Path & query encoding:** Expedia uses naïve `String.replace("{name}", value)` for path params — no percent-encoding (bug #6 in their bug catalog). When we build a query/path builder, percent-encode against RFC 3986 unreserved char tables.

### I/O Contracts

**We lead unambiguously.** Every reference SDK hard-codes one I/O library:

- Expedia welds Okio into `sdk-core`'s public API (`RequestBody.writeTo(BufferedSink)` is an Okio type — see `expediagroup-sdk-core/build.gradle:17`'s `api 'com.squareup.okio:okio:3.16.0'`). Changing I/O libs would break every consumer.
- Square reads bodies as `responseBody.string()` — fully buffered, no streaming option.
- Airbyte does `Utils.toUtf8AndClose(InputStream)` — same fully-buffered problem.
- gax has no I/O abstraction; transports operate on byte arrays directly.

Our `IoProvider` seam + `Source`/`Sink` contracts is the architecturally cleanest piece of the SDK. **Keep it.** Document the install pattern more prominently in the public README — Expedia's late-init `IllegalStateException` is a good model for failure messages.

### Pipeline / Middleware

| SDK | Architecture | Verdict |
|---|---|---|
| Ours | `RequestPipeline` / recovery-aware `ResponsePipeline` fold over a sealed `ResponseOutcome`; `PipelineStep` `fun interface`s | Right shape; recovery semantics in place |
| Expedia | `ExecutionPipeline` = `Request → Request` + `Response → Response` folds | Can't intercept transport, can't loop, can't proceed |
| Square | One OkHttp `Interceptor` (the retry one) | No SDK-level pipeline |
| Airbyte | **Hook taxonomy: `SdkInit`, `BeforeRequest`, `AfterSuccess`, `AfterError`** | Best design in the cohort |
| gax | Per-call-shape decorator chain (`Callables.retrying(...)`, `TracedUnaryCallable`, ...) | Powerful but N parallel hierarchies for N call shapes |

How Airbyte's hook taxonomy (`utils/Hook.java` in their repo) maps to our types, as built:
- `SdkInit` → builder configuration (no dedicated type)
- `BeforeRequest` ≡ `RequestPipelineStep`
- `AfterSuccess` ≡ `ResponsePipelineStep` (runs on the success path only)
- `AfterError` ≡ `ResponseRecoveryStep`, taking a sealed `ResponseOutcome` (`Success(Response)` / `Failure(Throwable)`) instead of `Either`

`ResponsePipeline` folds the outcome through the response steps (success path) and then through
every recovery step, so recovery always observes the terminal outcome — including failures
thrown by a response step. This generalizes Airbyte's `Hooks.afterError(...)`: a recovery step
may rescue a failure into a success, replace the throwable, or pass through.

Two design choices stuck:
- **No `chain.proceed(...)` looping.** Folds stay simple; retry lives in a dedicated step that delegates to `HttpClient.execute` directly, so retry composes into the pipeline without chain semantics.
- **All exceptions funnel through one path.** Airbyte's design has a corner — a `BeforeRequest` that throws bypasses `AfterError`. We avoid it: a step throwable is wrapped into `ResponseOutcome.Failure` and fed to the recovery chain, and `apply` never throws.

### Retry & Backoff

Retry now ships as a pipeline step (`RetryStep` over `RetrySettings` + `BackoffCalculator` +
`RetryAfterParser`, plus the stage-based `DefaultRetryStep`). The table below records which
reference SDK each behavior was modeled on:

| Feature | Best example |
|---|---|
| `Retry-After` (numeric + HTTP date) parsing | Square `RetryInterceptor.java:64-87` |
| `X-RateLimit-Reset` (Unix epoch) parsing | Square `RetryInterceptor.java:90-102` |
| Exponential backoff with capped jitter | Square `RetryInterceptor.java:104-107` |
| Per-attempt shrinking deadlines (each attempt timeout caps to remaining total budget) | gax `ExponentialRetryAlgorithm.java:119-173` |
| Split algorithm: `ResultRetryAlgorithm` + `TimedRetryAlgorithm` | gax `RetryAlgorithm.java:45-90` |
| Per-method `retryableCodes: Set<StatusCode>` | gax `UnaryCallSettings` |
| Streaming retry resumption | gax `StreamResumptionStrategy` |

**Critical correctness rules** (collected from anti-patterns observed across the SDKs):

1. **Idempotency awareness.** Retry only safe-by-HTTP-method (GET/HEAD/OPTIONS/PUT/DELETE) requests, or non-safe requests whose `RequestBody.isReplayable()` is true. Square retries everything (`shouldRetry` checks status only, ignores method — a real bug for POST timeouts).
2. **`ScheduledExecutorService` for delay, never `Thread.sleep`.** Square and Airbyte both block via `Thread.sleep`, which pins virtual thread carriers. Use `CompletableFuture.delayedExecutor` or a dedicated scheduler.
3. **Restore interrupt flag.** Square's `Thread.sleep` swallow + re-throw as `IOException` (`RetryInterceptor.java:44`) violates our cancellation discipline. On `InterruptedException`: `Thread.currentThread().interrupt()` + throw `InterruptedIOException`.
4. **Retry as a `RequestPipelineStep`, not a transport interceptor.** Square's pattern of "retry is an OkHttp Interceptor" means BYO `OkHttpClient` silently loses retry. Pipeline-level retry composes with other steps and works across transports.

### Auth

Auth lives in `sdk-core` today: a sealed `Credential` family (`KeyCredential`, `NamedKeyCredential`,
`BearerToken`), RFC 7235 challenge parsing, `ChallengeHandler` implementations (Basic, Digest,
Composite), and pipeline auth steps (`BearerTokenAuthStep`, `KeyCredentialAuthStep`). The table
below records where each scheme's design was sourced from:

| Scheme | Best impl to reference |
|---|---|
| OAuth2 client_credentials | Airbyte `ClientCredentialsHook.java:36-95` (one class implements 3 hook interfaces; `SessionManager.java` keyed by `MD5(clientId:clientSecret)`; auto-evict on 401) |
| OAuth token storage with `Clock` | Expedia `OAuthTokenStorage.kt:33-103` (immutable, testable) |
| Basic | Trivial — Expedia's impl is fine |
| Bearer | Generated everywhere; just a header step |
| ADC / multi-source | gax `GoogleCredentialsProvider` (JWT optimization for service accounts) |
| Pluggable auth provider SPI | Kiota's `AuthenticationProvider` (taxonomy: `AnonymousAuthenticationProvider`, `BaseBearerTokenAuthenticationProvider`, `ApiKeyAuthenticationProvider`) |

**Anti-patterns to avoid:**

- **Expedia's `OAuthStep` uses `synchronized` around a network call** (`pipeline/step/OAuthStep.kt:30,40-44`). Pins virtual thread carriers for the duration of the OAuth round-trip. Use `ReentrantLock` (our rule) plus a coalescing future so concurrent calls share a single refresh.
- **Airbyte's `Security` reflects on `@SpeakeasyMetadata` strings per request** (`Security.java:26-103`). Don't do runtime reflection over string-DSL metadata.
- **Square punts on `idempotency_key`** — required field on every write DTO, no auto-gen. (See [Idempotency](#idempotency).)

**What shipped, and what's left:**

1. Auth lives in `sdk-core` (`auth`), not a separate `sdk-auth` module: a sealed `Credential` family + `ChallengeHandler` impls (Basic, Digest, Composite) + an `AuthStep` pillar (`BearerTokenAuthStep`, `KeyCredentialAuthStep`). The `AuthStep` base requires HTTPS, strips the cross-origin redirect marker so a caller credential is never re-stamped onto a server-chosen host, and exposes a `handleChallenge` hook for token-refresh / step-up flows.
2. Token fetch is a `BearerTokenProvider` SAM (`fetch(scopes, params)`). A full OAuth2 client_credentials provider with coalesced refresh and 401 eviction is **not yet built** — the seam (`handleChallenge`) exists, the policy does not.
3. Per-call auth override is **not yet exposed** — auth is wired per pipeline. Airbyte's per-client-only model is a real limitation for multi-tenant clients; a per-call override remains worth adding.

### Idempotency

- **Square requires `idempotency_key` as a typed DTO field on every write** (e.g. `RefundPaymentRequest.java:27,93-95`). No auto-generation, no defaulting. For a payments SDK this is a customer-trust risk.
- **Airbyte ships an `IdempotencyHook`** as a built-in `BeforeRequest` hook (`utils/Hook.java:284-292`). Injects `Idempotency-Key: UUID.randomUUID().toString()` on each call.

`IdempotencyKeyStep` covers this as a `RequestPipelineStep`:

1. Injects `Idempotency-Key: UUID.randomUUID()` for `POST`/`PUT`/`PATCH` (the `methods` set is configurable, e.g. to add `DELETE`).
2. A header already on the request wins — `respectExisting` (default `true`) leaves caller-set keys untouched.
3. The key value comes from a pluggable `keyStrategy` (default `UUID.randomUUID().toString()`), so APIs that want deterministic keys from a request hash can supply their own; the header name is configurable too.

### Serialization

`sdk-core/serde/` holds the abstractions (`Serializer`/`Deserializer`/`Serde`, plus the
`Tristate<T>` sealed type), and `sdk-serde-jackson` is the concrete adapter. Reference SDKs:

- **Expedia**: hardcoded Jackson Kotlin module. Mapper is consumer-supplied. Polymorphism via `@JsonSubTypes`. Forces every consumer to ship Jackson.
- **Square**: Jackson + Jdk8Module + JavaTimeModule + custom `DateTimeDeserializer`. **`FAIL_ON_UNKNOWN_PROPERTIES=false` + `WRITE_DATES_AS_TIMESTAMPS=false`** — the de-facto-correct SDK defaults. `ObjectMappers.java:21-22`.
- **Airbyte**: Jackson + `jackson-databind-nullable` for tri-state `JsonNullable<T>` (PATCH semantics). Reflection-driven via `@SpeakeasyMetadata` strings — avoid.
- **Kiota**: Pluggable `ParseNode`/`SerializationWriter` per media type. Default = Gson.

**What shipped, and what's left:**

1. `Serde`/`Serializer`/`Deserializer` stay in `sdk-core` with **no Jackson dependency**.
2. `sdk-serde-jackson` ships the adapter: Kotlin + JSR-310 + Jdk8 modules, `FAIL_ON_UNKNOWN_PROPERTIES` and `WRITE_DATES_AS_TIMESTAMPS` both disabled (Square's two flags). A `sdk-serde-kotlinx` is still optional/later.
3. `Tristate<T>` is defined in `sdk-core/serde` (`Absent`, `Present<T>(value)`, `Null`); `sdk-serde-jackson`'s `TristateModule` maps it to Jackson's missing-field / null / present distinction for PATCH semantics.
4. **`oneOf` deserialization** is still open — codegen will drive it. The rule stands: prefer discriminator-driven; fall back to ordered candidate probing only with explicit hints; fail loudly on ambiguity. Airbyte's `OneOfDeserializer.java:104-107` silently picks first match by default — a real data-corruption risk.

### Error Model

| SDK | Approach | Verdict |
|---|---|---|
| Ours | `Status` value class (total `fromCode`) + typed `HttpException` hierarchy with a derived `retryable` flag | Typed hierarchy in place |
| Expedia | Per-operation `{Op}{StatusCode}Exception` extending `ExpediaGroupApiException` | Best per-status DX |
| Square | `SquareException` (base) + `SquareApiException` (non-2xx); tolerant body parser handles 3 error shapes | Good error-body parsing |
| Airbyte | One `SDKError` for all non-2xx | Worst — no typed access |
| gax | `ApiException` + 14 typed subclasses (`NotFoundException`, `UnavailableException`, ...); `retryable` flag baked at construction | Cleanest baseline taxonomy |

**What shipped, and what's left:**

1. `HttpException` is the base, with status-code-keyed subclasses (`NotFoundException`, `UnauthorizedException`, `TooManyRequestsException`, `InternalServerErrorException`, etc.) plus `ClientErrorException`/`ServerErrorException` fallbacks for unmapped 4xx/5xx. `NetworkException` is a sibling for transport failures. The set mirrors gax's taxonomy scaled to HTTP statuses.
2. `isRetryable: Boolean` (from the `Retryable` interface) is a `val` derived once at construction from `RetryUtils.isRetryable(status.code)` — not a per-subclass constant. This is the single source of truth, so it can never disagree with the live retry policy (408 retryable; 501/505 not). `NetworkException` implements the same interface (always `true`), so a retry predicate keys off the interface: `(t as? Retryable)?.isRetryable == true`.
3. The base exposes `status` + `headers` + a **lazy** `body: ResponseBody?` (not eagerly buffered), plus a non-consuming `bodySnapshot()` that reads from a `peek()` view so the primary read path is undisturbed — large 5xx bodies don't OOM. Per-operation per-status subclasses carrying typed bodies (Expedia pattern) are still codegen's job.
4. Tolerant error-body parsing (Square's `SquareApiException.parseErrors`) remains future work — never throw inside an exception constructor; pass through the raw body on parse failure.

### Pagination

`Paginator<T>` + `Page<T>` ship in `sdk-core`, driven by a `PaginationStrategy` (cursor,
page-number, token, link-header), and `pagination.PagedIterable` wraps the result. Reference
designs we drew on:

- **Square**: `SyncPagingIterable<T>` (`Iterable<T>` lazy iterator), `SyncPage<T>` (per-page holder), `BiDirectionalPage<T>` (forward + backward cursors), `CustomPager<T>` (user-implementation stub for HATEOAS).
- **gax**: `PagedListResponse<RequestT, ResponseT, ResourceT>` driven by `PagedListDescriptor` (`injectToken`, `extractNextToken`, `extractResources`). `iterateAll()` returns lazy `Iterable<ResourceT>`. `FixedSizeCollection` repaginates to consumer-chosen page sizes.
- **Expedia GraphQL**: `Paginator` (abstract `Iterator<T>` base) + `PaginatedStream` (`Stream<T>` over the paginator). Synchronous only.

**What shipped, and what's left:**

1. `Paginator<T>` and `Page<T>` are in `sdk-core`. `iterateAll()` returns a lazy `Iterable<T>`; `streamAll()` returns a Java 8 `Stream<T>`. Each call hands back an independent iterator with its own state.
2. The strategy is injected via `PaginationStrategy`, with concrete impls covering cursor (`next_cursor` / `prev_cursor`), page-number, token, and link-header (RFC 8288). A `maxPages` cap guards against servers that never advance their cursor.
3. Async variants for `sdk-async-coroutines` (`Flow<T>`) and `sdk-async-reactor` (`Flux<T>`) are not yet built.
4. `BiDirectionalPage` is deferred until a real API needs it; Square's pattern is good when needed.

### Long-Running Operations & Streaming

Streaming already ships in the form that matters for HTTP APIs: a WHATWG-compliant Server-Sent
Events reader (`http.sse`) in `sdk-core`, surfaced as a backpressured Reactor `Flux<ServerSentEvent>`
in `sdk-async-reactor`. Long-running-operation polling is still aspirational. Reference designs:

- **gax `OperationFuture<R, M>`**: extends `ApiFuture<R>` with `getName()`, `peekMetadata()`, `getPollingFuture()`. LRO modeled as retry-with-different-result-predicate (`OperationResponsePollAlgorithm`). `resumeFutureCall(operationName, ctx)` allows reattaching across restarts. (`gax/.../longrunning/OperationFuture.java:42-128`.)
- **gax server-streaming**: `ResponseObserver<V>` (gRPC-style) with explicit backpressure via `StreamController.disableAutoInboundFlowControl()` + `request(int)`. Watchdog closes streams with no demand→response progress within `waitTimeout`.

For us: defer LRO until a specific consumer needs it. Our SSE streaming already leans on
reactive-streams backpressure via Reactor; gax's explicit `StreamController` model (`request(int)`)
is the reference if a non-Reactor server-streaming surface is ever needed.

### Instrumentation & Tracing

| SDK | Vocabulary | Verdict |
|---|---|---|
| Ours | `Span` / `TracingScope` + an `HttpTracer` with named retry/request/response events | Event-rich vocabulary in place |
| Expedia | SLF4J only | None |
| Square | None | None |
| Airbyte | `System.out` debug logger | None |
| gax | `ApiTracer` with 15+ named events (`operationSucceeded`, `attemptStarted`, `attemptFailed`, `responseReceived`, `requestUrlResolved`, ...) | Gold standard |

gax's `ApiTracer` (`gax/.../tracing/ApiTracer.java:47-219`) treats retry/streaming/LRO as
first-class events. A generic OpenTelemetry tracer can't render meaningful retry dashboards
without convention; an event-rich tracer can.

**What shipped, and what's left:**

1. `HttpTracer` carries the named events with no-op defaults: `operationStarted`/`operationSucceeded`/`operationFailed`, `attemptStarted(attemptNumber)`, `attemptFailed(...)`, `attemptRetriesExhausted(throwable)`, `requestUrlResolved(url)`, `requestSent`, `responseHeadersReceived(...)`, `responseReceived`, `connectionAcquired(...)`. `NoopHttpTracer` is the default factory.
2. A `MetricsRecorder` seam exists separate from tracing — `Meter` with `counter(...)` (`LongCounter`) and `histogram(...)` (`DoubleHistogram`), `NoopMeter` as the default. Latency/errors/retries are the SRE-relevant signals; gax's `MetricsTracer` + `GoldenSignalsMetricsRecorder` was the model.
3. An `sdk-instrumentation-otel` adapter wiring our events to OpenTelemetry spans + metrics is not yet built.
4. **Client identity header (`User-Agent`).** `ClientIdentityStep` builds the composite token line (`dexpace-sdk/<ver> jvm/<javaver>`, custom tokens prepended), modeled on gax's `ApiClientHeaderProvider`.

### Async Adapters

We have the right architecture (separate `sdk-async-*` modules). **Anti-patterns observed:**

- **gax invented `ApiFuture`** because Guava `ListenableFuture` couldn't be in the public API (shading). Now every Cloud client returns `ApiFuture` and ergonomic composition is impossible. *Lesson:* never invent a new future type. Keep `HttpClient.execute` sync; let `sdk-async-coroutines` return `suspend`, `sdk-async-reactor` return `Mono`, etc.
- **Expedia has `Transport` + `AsyncTransport` as two parallel SPIs**, with twin executor hierarchies (`AbstractRequestExecutor` + `AbstractAsyncRequestExecutor`) and twin OAuth managers (`OAuthManager` + `OAuthAsyncManager`). ~90% code duplication. We avoid this by having one core + per-async-flavor adapters.

### Configuration & Lifecycle

- **gax** has the cleanest separation: `StubSettings` (immutable user-facing) → `ClientSettings` (facade) → `ClientContext` (resolved runtime: connected transport, fetched creds, built executor, default call context). `ClientContext.create(settings)` is the resolution boundary. `BackgroundResource` semantics: closing the context cleans up owned executors.
- **Square's `Suppliers.memoize`** for lazy sub-client init (`Suppliers.java:13-22`) — 10-line `AtomicReference.updateAndGet`, lock-free, thread-safe. Worth copying verbatim for our future generated client shell so we don't eagerly construct 30+ sub-clients on every `new XxxClient(...)`.

**Action items:**

1. Split `Configuration` into immutable user-facing `XxxSettings` and resolved runtime `XxxContext`. Resolution stage owns executor lifecycle (`AutoCloseable`).
2. Adopt `Suppliers.memoize` for any client-of-clients pattern we ship.
3. **Resource ownership**: per gax's `BackgroundResource` discipline, distinguish user-supplied (don't close) from SDK-owned (close on `client.close()`) executors and transports.

### Webhooks

- **Square `WebhooksHelper.verifySignature`**: HMAC-SHA256, but uses `String.equals` (`WebhooksHelper.java:53`) — vulnerable to timing attacks. No timestamp/replay check.

**Action items:**

1. Build `sdk-webhooks` module. `WebhookVerifier.verify(secret, payload, signature, timestamp, tolerance)`. Use `MessageDigest.isEqual` for constant-time comparison. Require timestamp + tolerance (e.g. ±5min) for replay protection.

## Where We Already Lead

These are not gaps. Stay the course.

1. **Zero non-SLF4J runtime deps in `sdk-core`.** Unique among the cohort. gax pulls Guava+gRPC+Protobuf+AutoValue+OTel+threetenbp; Expedia welds Okio into the public API; Square+Airbyte are full Jackson+OkHttp/JDK11.
2. **Java 8 bytecode target.** None of Kiota / smithy-java / Airbyte support Java 8. gax does, Square does, Expedia does — but with much heavier deps.
3. **Single `HttpClient` SPI with our own `Request`/`Response`.** Doesn't leak transport types (Airbyte leaks JDK 11; Square leaks OkHttp).
4. **`IoProvider` seam with `Source`/`Sink` contracts.** No reference SDK has this level of I/O abstraction.
5. **`RequestBody.isReplayable()` / `toReplayable(provider)`.** First-class replayability; nobody else has it.
6. **Body logging via `TeeSink` (request) + eager-buffer-then-peek (response).** `LoggableRequestBody` / `LoggableResponseBody` design preserves streaming semantics — Expedia eagerly buffers every request body when logging is on (a real regression for streaming uploads).
7. **ReentrantLock + interrupt-restore discipline.** Documented in `CLAUDE.md`. Square, Expedia, and Airbyte all violate this in their retry/auth paths.
8. **Explicit Kotlin visibility (Strict mode) + `internal` + `@JvmSynthetic`.** Real enforcement. gax uses `@InternalApi` as marker-annotations on public types, which has no compiler enforcement.
9. **Async as adapter modules, not invented futures.** No `DexpaceFuture`. We sidestep gax's `ApiFuture` debt.
10. **Separate transport modules (OkHttp + JDK HttpClient).** Already in place — most ref SDKs only ship one.

## Feature Backlog

### Landed

The foundational and most of the DX-win work from this survey now ships in `sdk-core` (with
adapters where noted):

- **Retry pipeline step.** `Retry-After` + `X-RateLimit-Reset` parsing, backoff with jitter, idempotency-aware (HTTP method + replayable body), `ScheduledExecutorService`-based delay. [`pipeline/step/retry/`, `http/pipeline/steps/DefaultRetryStep.kt`]
- **Typed exception hierarchy.** `HttpException` base + status-code subclasses; `retryable` derived from `RetryUtils.isRetryable(status.code)`. [`http/response/exception/`]
- **Recovery step.** Recovery-aware `ResponsePipeline` folding a sealed `ResponseOutcome` (`Success` / `Failure`); `ResponseRecoveryStep` is the `AfterError` analog. [`pipeline/`]
- **`HttpClient.close()` / lifecycle.** `AutoCloseable` on both SPIs and both transports; SDK-managed clients close, BYO clients don't. [`client/`]
- **Idempotency-key step.** Auto-injects `Idempotency-Key: UUID.randomUUID()` for `POST`/`PUT`/`PATCH`; caller-set header wins; pluggable key strategy. [`pipeline/step/IdempotencyKeyStep.kt`]
- **Auth.** `Credential` family + RFC 7235 challenge parsing + Basic/Digest/Composite `ChallengeHandler`s + `AuthStep` pillar. [`auth/`, `http/pipeline/steps/`]
- **`sdk-serde-jackson` adapter.** Kotlin + JSR-310 + Jdk8 modules; `FAIL_ON_UNKNOWN_PROPERTIES` and `WRITE_DATES_AS_TIMESTAMPS` disabled; `Tristate<T>` via `TristateModule`.
- **Pagination primitives.** `Paginator<T>` + `Page<T>` + `PaginationStrategy` (cursor / page-number / token / link-header) with a `maxPages` cap; `PagedIterable` wrapper.
- **Client identity header.** `ClientIdentityStep` building the `dexpace-sdk/<ver> jvm/<javaver>` token line.
- **Tracer event vocabulary + metrics seam.** `HttpTracer` with named retry/request/response events; `Meter`/`LongCounter`/`DoubleHistogram` separate from tracing.
- **SSE streaming.** WHATWG reader in `sdk-core`; backpressured `Flux<ServerSentEvent>` in `sdk-async-reactor`.

### Remaining

Ordered by leverage:

1. **OAuth2 client_credentials flow.** Coalesced refresh + 401 eviction over the existing `BearerTokenProvider` / `handleChallenge` seam; per-call auth override via `RequestContext`.
2. **`sdk-instrumentation-otel` adapter.** Wire the `HttpTracer` events and `Meter` seam to OpenTelemetry spans + metrics.
3. **`sdk-webhooks` module.** HMAC-SHA256/SHA1 verifier, constant-time compare, timestamp+tolerance replay check.
4. **Configuration `Settings` → `Context` resolution split.** Apply gax's `BackgroundResource` discipline at the resolution boundary.
5. **Async pagination variants.** `Flow<T>` (`sdk-async-coroutines`) and `Flux<T>` (`sdk-async-reactor`) over `Paginator`.
6. **Tolerant error-body parsing.** Decode typed/structured error payloads without throwing inside an exception constructor.

### Speculative

- **Long-running operation polling helpers.** Only when a real consumer needs them.
- **Batching primitives.** Only when a real consumer needs them.

## Code Generation Strategy

### Options Considered

| Option | Verdict | Reason |
|---|---|---|
| **Fork Expedia's OpenAPI plugin** | Reject | Inherits 20 cataloged bugs (uppercase discriminators, path params not URL-encoded, accept-header status-200-only, etc.); Mustache fragility; Kotlin-only; locks us to OpenAPI Generator 7.15 |
| **OpenAPI Generator (stock kotlin emitter)** | Reject | Same Mustache fragility; per-language quality variance; runtime emitted alongside generated code (consumer must ship Jackson/OkHttp) |
| **Smithy 2.0 + smithy-java runtime** | Reject | smithy-java requires JDK 21 (we're Java 8); brand-new GA (April 2026); thick runtime duplicates our `sdk-core` |
| **Smithy 2.0 + custom `JavaCodegenIntegration` targeting our runtime** | Defer | Viable but 7-10 person-weeks; only justified once we own multiple services in Smithy IDL |
| **Microsoft Kiota** | Reject | No Kotlin target; Java 11+ default; replaces our `HttpClient` SPI with `RequestAdapter`; 5-8 runtime deps; locked-down customization |
| **Fern (Square's generator)** | Reject | Generates its own runtime (effectively replacing `sdk-core`); SaaS-recommended workflow; OkHttp + Jackson hardcoded; Java only |
| **Speakeasy (Airbyte's generator)** | Reject | Closed-source SaaS; venture-funded vendor in critical path of every release; no self-host |
| **gapic-generator-java** | Reject | Proto + Bazel; would require an OpenAPI→proto front-end; co-versioned runtime model is too heavy for us |
| **Build our own (KotlinPoet + swagger-parser)** | **Recommend** | Typed IR + typed code emission; no Mustache; reuses our `sdk-core` types; comparable cost to forking/integrating any of the above |

### Recommendation: Build Our Own (KotlinPoet + Swagger-Parser)

A new Gradle module `sdk-codegen` plus a Gradle plugin `sdk-codegen-gradle-plugin`.

**Stack:**

- **`io.swagger.parser.v3:swagger-parser`** as front-end. Handles OpenAPI 2.0/3.0/3.1, resolves `$ref`s, well-maintained. (Smithy IDL can become an alternative source later via a separate parser adapter.)
- **Internal normalized IR** mapped from the swagger-parser `OpenAPI` object. Resolves discriminators, flattens `allOf`, names inline schemas deterministically, splits `readOnly`/`writeOnly` properties into request-vs-response models.
- **KotlinPoet** for Kotlin emission; **JavaPoet** for Java emission. Same IR, two emitters.
- **Generator-side `Integration` hooks** (Smithy-inspired) so consumers can inject preprocessing, decorate type/symbol resolution, intercept named code sections (e.g. "operation-error-handling") without forking templates.
- **Golden file tests.** Every meaningful spec feature gets a fixture spec + a checked-in expected output. CI compiles the generated output.

**Why this beats Mustache-based alternatives:**

1. **Compiler-checked emission.** KotlinPoet's `FileSpec`/`TypeSpec`/`FunSpec` is typed Kotlin; refactors are safe; IDE supports it. Every Mustache bug in Expedia's plugin (path params not URL-encoded, discriminators uppercased, unused imports, dead validation, builder param type drops nullability) is a string-typo that the compiler would have caught.
2. **Targets our runtime directly.** Generator emits `org.dexpace.sdk.core.http.Request`/`Response`/`Headers` and our pipeline steps. No Mustache template indirection.
3. **Hot-path: no reflection.** Generated code is straight-line builder calls. Compare Airbyte's `@SpeakeasyMetadata` runtime reflection — slow, opaque, fragile.
4. **Pluggable serde.** Codegen emits an abstraction (`Serializer<T>`) that adapter modules implement. Don't hardcode Jackson into templates.
5. **Multi-language without a runtime fork.** Same IR → KotlinPoet for Kotlin, JavaPoet for Java. We don't get TS/Go/Python for free, but we never claimed multi-language as a goal.

### What to Copy When Building It

From **Expedia's plugin** (`expediagroup-sdk-openapi-plugin`):

- **Operation-trait composition pattern.** Each operation implements only the traits relevant to its spec features (`UrlPathTrait`, `HeadersTrait`, `UrlQueryParamsTrait`, `OperationRequestBodyTrait<T>`, `OperationResponseBodyTrait<T>`, `OperationNoResponseBodyTrait`). Cleaner than annotation-based codegen; no reflection at runtime. Define these traits in a new `sdk-rest` (or `sdk-operations`) module.
- **Operation / Params split.** Keep path/query/header concerns in a dedicated `*OperationParams` class with `pathParams()` / `queryParams()` / `headers()` projections. Operation class becomes a pure request-info adapter.
- **Per-status typed exceptions.** Emit `{OperationName}{StatusCode}Exception` in a `<modelpkg>.exception` sub-package. **Improvement over Expedia:** on parse failure, attach the raw body as a suppressed throwable rather than silently `null`.
- **`@JsonDeserialize(builder = Builder::class)` + private constructor + Builder.** Jackson-friendly immutability.
- **IR processor hooks.** Expedia's `processOperation(CodegenOperation): CodegenOperation` lambdas let users adapt to spec quirks without forking templates. Same idea, but typed (`(OperationIR) -> OperationIR`).
- **Spec preprocessor pattern.** Expedia uses an external npm tool; we should build a JVM-native preprocessor pipeline: `$ref` resolution, `allOf` flattening, `operationId → tag` normalization, inline-schema naming, header injection. All testable separately from the emitter.
- **Mustache template merge mechanism** is replaced by IR processor hooks in our world; the value was the layered defaults idea, which we retain.

From **Square** (Fern's Java output):

- **Suppliers.memoize for lazy sub-client init.** Verbatim, 10 lines, Java 8.
- **Raw/Cooked client split.** `*Client` returns `T`, `Raw*Client` returns `Response<T>` (body + headers). Single `withRawResponse()` accessor for crossing the boundary. (We have our own `Response` already; raw clients return it, cooked clients call `.body()`.)
- **Async client mirror.** Optional. Generated as `Async*Client` returning `CompletableFuture<T>`, delegating to our `sdk-async-coroutines`-style adapter under the hood.
- **Forward-compatible enums.** Square's `enable-forward-compatible-enums: true` setting emits an `UNKNOWN` sentinel rather than throwing on unrecognized enum values. Adopt by default; SDK releases shouldn't break on server-side enum additions.

From **Airbyte** (Speakeasy's Java output):

- **Hook taxonomy (`SdkInit` / `BeforeRequest` / `AfterSuccess` / `AfterError`).** Already mapped onto our pipeline; see [Pipeline / Middleware](#pipeline--middleware).
- **`IdempotencyHook` pattern.** Realized as `IdempotencyKeyStep`, a `BeforeRequest` step injecting `Idempotency-Key`.
- **`ClientCredentialsHook` pattern.** Single class implements 3 hook interfaces (`SdkInit` for client init, `BeforeRequest` for token injection, `AfterError` for 401 eviction) — the model for the OAuth2 flow still on the [Remaining](#remaining) backlog.

From **gax**:

- **Per-method `CallSettings`.** Each generated operation has typed `retryableCodes: Set<Int>` + `retrySettings: RetrySettings` defaults. Consumers override at call site via `RequestContext`.
- **Composite client header.** `dexpace-sdk/<ver> jvm/<javaver> okhttp/<okver>` token line.

From **Smithy** (without adopting smithy-java):

- **Trait-based extensibility.** Custom OpenAPI vendor extensions (`x-dexpace-*`) get first-class treatment via Integration hooks; generator can be taught about new traits without forking.
- **`smithy-build.json`-style projections.** Eventually consider model transforms (filter operations by tag, rename namespaces) as first-class config.

### What to Avoid

(Distilled from the bug catalogs and anti-patterns observed across the cohort. None of these
should ever appear in our generator.)

1. **Mustache templating.** Compiler-unchecked string concatenation. Source of every Expedia bug.
2. **Auto-uppercasing or transforming discriminator values** (Expedia `Discriminator.kt:33`). Never mutate spec values on the wire.
3. **`!!` non-null assertions in generator code** (Expedia `Discriminator.kt:33`). Use explicit error messages.
4. **Hardcoded language** (Expedia `setGeneratorName("kotlin")`). Treat target language as a first-class config axis.
5. **Hardcoded serde library** (Expedia + Airbyte hardcode Jackson). Emit through a `Serializer<T>` abstraction.
6. **Path-param interpolation without percent-encoding** (Expedia, Airbyte). Use RFC 3986 unreserved char tables.
7. **Reflection-driven serialization** with string-DSL metadata (Airbyte `@SpeakeasyMetadata("...")`). Generate code, not metadata.
8. **`Thread.sleep` in retry loops** (Square, Airbyte). Use `ScheduledExecutorService`. Restore interrupt flag on `InterruptedException`.
9. **`synchronized` around network calls** (Expedia `OAuthStep`). Use `ReentrantLock` + coalescing futures.
10. **`HttpClient.newHttpClient()` per call** (Airbyte). Pool transports at the client level.
11. **One `SDKError` for everything** (Airbyte). Typed per-status exceptions.
12. **Inventing your own future type** (gax `ApiFuture`). Use `CompletableFuture` + adapter modules.
13. **Annotation-only visibility** (gax `@InternalApi` on public types). Use real visibility modifiers.
14. **Threetenbp dual API surface** (gax). Use `java.time` only.
15. **Reading entire error/response bodies as String** (Airbyte `Utils.toUtf8AndClose`, Square `responseBody.string()`). Stream via our `Source`.
16. **ServiceLoader for transport discovery** (Expedia). Explicit installation only.
17. **`String.equals` for cryptographic comparison** (Square `WebhooksHelper`). `MessageDigest.isEqual`.
18. **Eager-buffering request bodies during logging** (Expedia `RequestLoggingStep`). Use our `TeeSink` design.
19. **Dead code shipped with no tests** (Expedia's `linkType` Mustache references, `CustomPager` thrown-on-use stub). If a feature isn't implemented end-to-end, don't ship the seam.
20. **100% coverage enforcement on every module** (Expedia's Kover). Forces coverage on getters; encourages package exclusions. Target logic packages; exempt POJOs.
21. **Status-200-only Accept header aggregation** (Expedia `HttpAcceptHeaderLambda`). Aggregate across all 2xx responses.
22. **Catch-and-null on error response parse** (Expedia `getExceptionForCode`). Propagate parse failure as suppressed throwable.
23. **Auto-fallback to `String` for unschematized error bodies** (Expedia `OperationExceptionsLambda`). Pass-through raw bytes.
24. **Duplicate sync/async hierarchies** (Expedia `Transport`/`AsyncTransport`, `OAuthManager`/`OAuthAsyncManager`). Single core + async adapters.

### Rough Plan

Order of operations once the Tier 1 backlog above is partially complete (retry + auth + typed
exceptions land first; codegen can target them):

1. **Week 1-2.** `sdk-codegen` module skeleton. swagger-parser integration. Define internal IR (`Spec`, `Operation`, `Model`, `Enum`, `OneOf`, `Param`). Initial OpenAPI 3.x → IR mapping.
2. **Week 3-4.** Spec preprocessor pipeline (`$ref` resolution, `allOf` flatten, inline-schema naming). Golden-file tests against fixture specs.
3. **Week 5-6.** KotlinPoet emission for: models (immutable + Builder + `@JsonDeserialize`), enums (forward-compatible with `UNKNOWN`), per-operation `*Params` classes, per-status exception classes.
4. **Week 7-8.** Operation emission: `*Operation` classes implementing operation traits; sync + async (`Async*Client`) clients; `Raw*Client` and cooked variant.
5. **Week 9-10.** Gradle plugin packaging; Integration SPI for consumer customization (preprocessor + IR-processor + named-section hooks).
6. **Week 11-12.** Round-trip a non-trivial fixture spec (oneOf + allOf + discriminator + recursive ref + multipart + binary + readOnly/writeOnly split). Compile output. Run JVM smoke tests against MockWebServer.

**Out of scope for v1:** JavaPoet emission, Smithy IDL front-end, gRPC, multi-language. All deferrable.

**Total estimated effort:** 8-12 person-weeks for a v1 that emits a usable Kotlin SDK against
our runtime. Comparable to a Smithy `JavaCodegenIntegration` (7-10 weeks) or forking Expedia's
plugin and fixing its bugs (4-6 weeks but inherits Mustache fragility forever).
