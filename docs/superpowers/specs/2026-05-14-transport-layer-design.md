# Transport Layer Design — OkHttp 5.x + java.net.http.HttpClient

## Goal

Ship two production-ready transport implementations behind the SDK's existing `HttpClient` / `AsyncHttpClient` SPIs:

- `sdk-transport-okhttp` — OkHttp 5.x (Java 8 bytecode target).
- `sdk-transport-jdkhttp` — `java.net.http.HttpClient` (Java 11 bytecode target).

Both must support synchronous and asynchronous calls natively (no executor wrapping), stream both request and response bodies through the existing `IoProvider` seam, honour `Thread.interrupt()` / `CompletableFuture.cancel()` for cancellation, and be safe for concurrent use by multiple threads.

## Out of scope

- HTTP/3 support (neither transport offers it without extra deps; not requested).
- Connection-pool tuning beyond what each library exposes natively.
- Apache HttpClient, Jetty client, or any third transport.
- Auto-discovery / `ServiceLoader` of transports. Consumers instantiate explicitly.
- WebSocket support.
- Refactoring `sdk-core`'s `HttpClient` / `AsyncHttpClient` SPI surface; both interfaces remain unchanged.

## Module structure

Two new modules in `settings.gradle.kts`, alongside the existing six:

```
sdk-transport-okhttp/
├── build.gradle.kts                          # kotlin("jvm") + java-test-fixtures NOT needed
├── api/sdk-transport-okhttp.api              # ABI snapshot (generated)
└── src/
    ├── main/kotlin/org/dexpace/sdk/transport/okhttp/
    │   ├── OkHttpTransport.kt                # public class — implements HttpClient + AsyncHttpClient
    │   └── internal/                         # internal adapters: request, response, body bridges
    └── test/kotlin/org/dexpace/sdk/transport/okhttp/
        └── (JUnit Platform tests with MockWebServer)

sdk-transport-jdkhttp/
├── build.gradle.kts                          # JDK toolchain pinned to 11+, jvmTarget = JVM_11
├── api/sdk-transport-jdkhttp.api
└── src/
    ├── main/kotlin/org/dexpace/sdk/transport/jdkhttp/
    │   ├── JdkHttpTransport.kt               # public class — implements HttpClient + AsyncHttpClient
    │   └── internal/                         # internal adapters
    └── test/kotlin/org/dexpace/sdk/transport/jdkhttp/
        └── (JUnit Platform tests; can reuse okhttp3.mockwebserver.MockWebServer)
```

Both modules:

- Depend on `:sdk-core` and (for tests) `:sdk-io-okio3` for the `IoProvider` install.
- Apply `kotlin("jvm")`, `org.jetbrains.kotlinx.kover`, `maven-publish`, `signing` — the same plugin set the existing modules use.
- Are added to the root `kover { … }` aggregation in `build.gradle.kts`.
- Inherit `explicitApi = Strict`, `allWarningsAsErrors = true`, and the project's ktlint / detekt config.

`sdk-transport-jdkhttp` overrides `jvmToolchain(11)` and `jvmTarget = JVM_11` in its own build script (the same pattern `sdk-async-virtualthreads` uses for JDK 21, with a different version). Bytecode targets Java 11 — consumers on Java 8/10 cannot use this module.

## Public API

### `sdk-transport-okhttp` — `org.dexpace.sdk.transport.okhttp`

```kotlin
public class OkHttpTransport private constructor(
    private val client: okhttp3.OkHttpClient,
) : HttpClient, AsyncHttpClient {

    public companion object {
        @JvmStatic
        public fun create(client: okhttp3.OkHttpClient): OkHttpTransport

        @JvmStatic
        public fun builder(): Builder
    }

    override fun execute(request: Request): Response
    override fun executeAsync(request: Request): CompletableFuture<Response>

    public class Builder internal constructor() :
        org.dexpace.sdk.core.generics.Builder<OkHttpTransport> {

        public fun connectTimeout(d: Duration): Builder
        public fun readTimeout(d: Duration): Builder
        public fun writeTimeout(d: Duration): Builder
        public fun callTimeout(d: Duration): Builder
        public fun proxy(p: ProxyOptions?): Builder
        public fun followRedirects(enabled: Boolean): Builder   // default false

        override fun build(): OkHttpTransport
    }
}
```

- `create(client)` is the BYO factory: SDK wraps a fully-configured `OkHttpClient` (interceptors, dispatcher, SSL, connection pool, etc. are caller's responsibility).
- `builder()` returns a builder that internally constructs an `OkHttpClient` from the SDK-managed knobs.
- Default `followRedirects = false` because the SDK already has `DefaultRedirectStep`. Builder reflects this default into the underlying `OkHttpClient.Builder`.
- `Builder` implements the existing `org.dexpace.sdk.core.generics.Builder<T>` interface for consistency with every other SDK builder.

### `sdk-transport-jdkhttp` — `org.dexpace.sdk.transport.jdkhttp`

```kotlin
public class JdkHttpTransport private constructor(
    private val client: java.net.http.HttpClient,
    private val responseTimeout: Duration?,    // applied per-request, not on the client
) : HttpClient, AsyncHttpClient {

    public companion object {
        @JvmStatic
        public fun create(client: java.net.http.HttpClient): JdkHttpTransport

        @JvmStatic
        @JvmOverloads
        public fun create(
            client: java.net.http.HttpClient,
            responseTimeout: Duration?,
        ): JdkHttpTransport

        @JvmStatic
        public fun builder(): Builder
    }

    override fun execute(request: Request): Response
    override fun executeAsync(request: Request): CompletableFuture<Response>

    public enum class HttpVersion { HTTP_1_1, HTTP_2 }

    public class Builder internal constructor() :
        org.dexpace.sdk.core.generics.Builder<JdkHttpTransport> {

        public fun connectTimeout(d: Duration): Builder
        public fun responseTimeout(d: Duration): Builder      // per-request timeout
        public fun proxy(p: ProxyOptions?): Builder
        public fun followRedirects(enabled: Boolean): Builder // default false → Redirect.NEVER
        public fun httpVersion(v: HttpVersion): Builder       // default HTTP_2

        override fun build(): JdkHttpTransport
    }
}
```

- `connectTimeout` is set on the `java.net.http.HttpClient` builder. `responseTimeout` is captured on `JdkHttpTransport` and applied to every outgoing request via `HttpRequest.Builder.timeout(...)` — the JDK client puts request timeout on the request, not the client.
- `followRedirects=true` maps to `Redirect.NORMAL`; `false` (default) maps to `Redirect.NEVER`.
- `HttpVersion` is an SDK enum that maps to the JDK's `HttpClient.Version.HTTP_1_1` / `HTTP_2`. Default `HTTP_2`.

## Request adaptation

For both transports the implementation must:

1. Read `Request.method` and map to the lib's method enum.
2. Read `Request.url`. The SDK's `Request` holds the URL as a `java.net.URL` — both libs accept `URI` / `URL` directly.
3. Iterate `Request.headers` and copy each name/value pair onto the transport's request builder.
4. Adapt `Request.body` (an `org.dexpace.sdk.core.http.request.RequestBody?` — nullable) into the lib's body type.
5. Apply per-request timeout (JDK only).

Critical-path headers that the underlying lib computes are silently dropped from the SDK `Request.headers` before being copied over, with a debug log naming the dropped header. The minimum drop list is:

| Header | Reason | Affected lib |
|---|---|---|
| `Content-Length` | Always recomputed from the body. Manual values would conflict. | OkHttp + jdk.http |
| `Host` | Always populated by both libs from the request URL. | OkHttp + jdk.http |
| `Transfer-Encoding` | Determined by streaming semantics, not user choice. | OkHttp + jdk.http |
| `Connection` | jdk.http rejects manual values in some JDK builds. | jdk.http |

The drop list lives in `internal/RestrictedHeaders.kt` as a `private val` set; tests assert that user-supplied values for these headers are silently dropped and the underlying request still ships.

## Response adaptation

For both transports:

1. Build the SDK's `Response.Builder` populated with:
   - `Status` from the response code (look up via existing `Status.fromCode(code)`).
   - `Protocol` from the response's HTTP version.
   - `Headers` from the response's header map (preserve case-insensitive multi-value semantics).
   - `Body` wrapping the response's body input stream in the active `IoProvider.source(InputStream)`. The response body is **not** pre-buffered — caller must close it.
2. Return the SDK `Response` — caller closes it.

The `Response.Builder` keeps the body's underlying connection / call alive until the `Response` is closed. Closing the SDK `Response` closes the `BufferedSource` which closes the wrapped `InputStream` which (for OkHttp) calls `Call.close()` and (for jdk.http) releases the underlying socket.

## Request body streaming

Both transports must accept SDK `RequestBody`s that may not fit in memory.

### OkHttp

OkHttp's `okhttp3.RequestBody.writeTo(okio.BufferedSink)` is the streaming hook. The adapter:

1. Creates an `okhttp3.RequestBody` subclass overriding:
   - `contentType()` — from SDK `RequestBody.mediaType`.
   - `contentLength()` — from SDK `RequestBody.contentLength` (return `-1L` if unknown).
   - `writeTo(sink: okio.BufferedSink)` — bridge to SDK's `RequestBody.writeTo(BufferedSink)` via `sink.outputStream()` → `IoProvider.sink(outputStream)`. The wrapped SDK sink writes through to okio's sink which writes through to the network.
2. Hands the resulting `okhttp3.RequestBody` to `Request.Builder.method(...)`.

The `IoProvider.sink(OutputStream)` indirection is what keeps `sdk-transport-okhttp` free of a direct Okio API dependency in its own code — the okio dep is transitive via OkHttp, but our adapter only touches an `OutputStream`.

### jdk.http

`java.net.http.HttpRequest.BodyPublisher` is the streaming hook, but it speaks Reactive Streams (`Flow.Publisher<ByteBuffer>`), not byte streams.

The adapter uses this strategy:

- **If `RequestBody.contentLength` ≤ 64 KiB**: eagerly write the body to a `Buffer` (via the `IoProvider`), snapshot to a `ByteArray`, and use `BodyPublishers.ofByteArray(...)`. Simple and fast.
- **If `contentLength > 64 KiB` or unknown (`-1L`)**: use `BodyPublishers.ofInputStream { … }` with a `PipedInputStream` / `PipedOutputStream` pair. A dedicated single-thread executor (lazy, daemon-thread, named `dexpace-jdkhttp-body-writer`) drives the writeTo on the OutputStream side; the publisher reads from the InputStream side. The executor is owned by the transport's lifecycle.

The 64 KiB threshold is hard-coded; not exposed via the builder. Justification: smaller bodies do not benefit from streaming and the pipe approach has measurable overhead.

## Response body streaming

Both transports return the body as a streamed `InputStream`:

- OkHttp: `response.body!!.byteStream()` (Java InputStream, transitively backed by okio).
- jdk.http: `response.body()` typed via `BodyHandlers.ofInputStream()`.

In both cases the adapter wraps the InputStream with `Io.provider.source(inputStream)` to produce the SDK's `BufferedSource`, and constructs a `ResponseBody` over that.

The response InputStream owns the underlying network resources. Closing the SDK `Response` closes the body which closes the InputStream.

## Cancellation

### Synchronous (`execute`)

Both libraries throw on `Thread.interrupt()` because their socket implementations participate in `InterruptibleChannel`. The adapter:

- Catches `InterruptedIOException` / `InterruptedException`, sets `Thread.currentThread().interrupt()` to preserve status, and rethrows the original.
- Catches all other `IOException`s and rethrows as-is (transport contract).

### Asynchronous (`executeAsync`)

#### OkHttp

```kotlin
val future = CompletableFuture<Response>()
val call = client.newCall(okHttpRequest)
call.enqueue(object : okhttp3.Callback {
    override fun onResponse(call: Call, response: okhttp3.Response) {
        try { future.complete(adapt(response)) }
        catch (t: Throwable) { future.completeExceptionally(t); response.close() }
    }
    override fun onFailure(call: Call, e: IOException) {
        future.completeExceptionally(e)
    }
})
future.whenComplete { _, _ -> if (future.isCancelled) call.cancel() }
return future
```

#### jdk.http

```kotlin
val httpRequest: java.net.http.HttpRequest = adaptRequest(request)
val responseFuture = client.sendAsync(httpRequest, BodyHandlers.ofInputStream())
val adapted = responseFuture.thenApply { jdkResponse -> adapt(jdkResponse) }

// jdk.http's CompletableFuture cancellation already propagates to the underlying exchange
// as of JDK 11; no additional hook needed. The .thenApply chain inherits cancellation.
return adapted
```

In both cases the returned future, on `cancel()`, releases the in-flight transport resources. Already-delivered responses are not auto-closed — caller still must call `Response.close()` on success-path completions.

## Configuration → underlying client mapping

### OkHttp

| Builder field | Underlying mapping |
|---|---|
| `connectTimeout(d)` | `OkHttpClient.Builder.connectTimeout(d.toMillis(), MILLISECONDS)` |
| `readTimeout(d)` | `.readTimeout(...)` |
| `writeTimeout(d)` | `.writeTimeout(...)` |
| `callTimeout(d)` | `.callTimeout(...)` |
| `proxy(ProxyOptions)` | `.proxy(proxyOptions.toJavaProxy())` and (if auth) `.proxyAuthenticator(...)` |
| `followRedirects(b)` | `.followRedirects(b).followSslRedirects(b)` |

### jdk.http

| Builder field | Underlying mapping |
|---|---|
| `connectTimeout(d)` | `HttpClient.Builder.connectTimeout(d)` |
| `responseTimeout(d)` | captured; applied per-request via `HttpRequest.Builder.timeout(d)` |
| `proxy(ProxyOptions)` | `.proxy(ProxySelector.of(proxyOptions.toInetSocketAddress()))`; auth via `Authenticator` |
| `followRedirects(b)` | `.followRedirects(if (b) NORMAL else NEVER)` |
| `httpVersion(v)` | `.version(if (HTTP_2) HTTP_2 else HTTP_1_1)` |

Proxy mapping: the SDK's `ProxyOptions` carries host, port, optional credentials, and a non-proxy host list. The adapter translates to:
- OkHttp: a `java.net.Proxy` plus a `okhttp3.Authenticator` for credentials and a `java.net.ProxySelector` for non-proxy hosts.
- jdk.http: a `java.net.ProxySelector` (with non-proxy-host handling) plus a `java.net.Authenticator`.

The transport applies a SLF4J `ClientLogger`-debug log when a proxy is configured, naming the proxy host/port (credentials are never logged).

## Configuration defaults

| Knob | Default | Rationale |
|---|---|---|
| `connectTimeout` | 10 seconds | Matches OkHttp's own default. |
| `readTimeout` | 30 seconds | Matches OkHttp's own default; jdk.http has no equivalent default but the SDK applies 30s as the `responseTimeout`. |
| `writeTimeout` | 30 seconds | OkHttp-specific; jdk.http does not differentiate. |
| `callTimeout` | none (unbounded) | OkHttp-specific. |
| `proxy` | none | Pipeline doesn't proxy by default. |
| `followRedirects` | `false` | SDK has `DefaultRedirectStep`. |
| `httpVersion` | `HTTP_2` (jdk.http only) | Matches jdk.http's own default. |

## Thread-safety

Both transport classes are immutable after construction. The underlying `OkHttpClient` and `java.net.http.HttpClient` are documented thread-safe by their respective libraries. Each `execute(...)` / `executeAsync(...)` call confines its per-request state to local variables and the returned `Response` / `CompletableFuture`.

## Logging

Both transports use a single `ClientLogger("org.dexpace.sdk.transport.okhttp.OkHttpTransport")` / `… jdkhttp.JdkHttpTransport`. They emit at DEBUG:

- Construction (with the relevant config knobs).
- Per-call: method + URL (URL-redacted via the SDK's `UrlRedactor`) on dispatch, status + latency on completion.
- On failure: the exception class and message (DEBUG; the pipeline's `InstrumentationStep` is the structured-log canonical path).

These transport-level logs are intentionally sparse — the SDK's `DefaultInstrumentationStep` is where consumers should look for full request/response logging.

## Testing strategy

Both modules use JUnit Platform + `kotlin("test")` (same as every other module).

`okhttp3.mockwebserver:mockwebserver3-junit5:5.0.0` is the test server for both transport modules. (MockWebServer is part of the OkHttp project but is a generic HTTP test server — it works fine with the JDK HTTP client.) Test-only dependency in both modules.

Test coverage targets (≥ 80% line coverage per the project's Kover floor):

- Sync `execute(...)` golden path: every common method (GET, POST, PUT, DELETE, PATCH), with and without a request body.
- Async `executeAsync(...)` golden path: same matrix.
- Headers round-trip (case-insensitive, multi-value).
- Request body streaming for sizes 0 bytes / 1 KB / 100 KB / 5 MB (covers the 64 KiB threshold for jdk.http).
- Response body streaming for the same sizes.
- Connect timeout (point at a closed port).
- Read timeout (server delay > timeout).
- Cancellation:
  - Sync: interrupt the calling thread mid-call.
  - Async: cancel the returned future mid-call; assert that the underlying call is cancelled and the future completes exceptionally.
- Redirect behaviour: default `followRedirects = false` means the SDK gets the 3xx response (not the auto-followed destination).
- Proxy plumbing: a `ProxyOptions` pointed at a second MockWebServer relays correctly.
- BYO factory: construct with a preconfigured underlying client; confirm SDK config is not overridden.
- HTTP/2 path (jdk.http only): one test against MockWebServer's HTTPS endpoint with `httpVersion(HTTP_2)`.

Tests install `Io.installProvider(OkioIoProvider)` in `@BeforeAll`.

## Module additions to root build

Append to root `build.gradle.kts`:

```kotlin
dependencies {
    kover(project(":sdk-core"))
    kover(project(":sdk-io-okio3"))
    kover(project(":sdk-async-coroutines"))
    kover(project(":sdk-async-reactor"))
    kover(project(":sdk-async-netty"))
    kover(project(":sdk-async-virtualthreads"))
    kover(project(":sdk-transport-okhttp"))   // NEW
    kover(project(":sdk-transport-jdkhttp"))  // NEW
}
```

Append to `settings.gradle.kts`:

```kotlin
include(
    ":sdk-core",
    ":sdk-io-okio3",
    ":sdk-async-coroutines",
    ":sdk-async-reactor",
    ":sdk-async-netty",
    ":sdk-async-virtualthreads",
    ":sdk-transport-okhttp",   // NEW
    ":sdk-transport-jdkhttp",  // NEW
)
```

Append to `gradle/libs.versions.toml`:

```toml
[versions]
okhttp = "5.0.0"
mockwebserver = "5.0.0"

[libraries]
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver-junit5 = { module = "com.squareup.okhttp3:mockwebserver3-junit5", version.ref = "mockwebserver" }
```

The `mockwebserver` and `mockwebserver-junit5` artifacts are test-only.

## Detekt risk

The user's daemon is JDK 25, which is incompatible with detekt 1.23.x (see the documented skip on `sdk-async-virtualthreads`). The new transport modules are likely to be affected the same way **if** their content trips the same parser code-path. The implementation defers this: if `:sdk-transport-okhttp:detekt` or `:sdk-transport-jdkhttp:detekt` fails for the JDK 25 reason after the modules land, the same documented-skip block (`tasks.matching { it.name == "detekt" }.configureEach { enabled = false }` with the issue link) is added to that module's build script. This is a known acceptable degradation pending detekt 2.x adoption.

## ABI snapshots

After implementation, run `./gradlew apiDump` to write `api/sdk-transport-okhttp.api` and `api/sdk-transport-jdkhttp.api`. These are committed and gated by the project's binary-compatibility-validator (`./gradlew apiCheck` runs as part of `build`).

## Documentation updates (separate commit)

After the modules land:

- `README.md`: add the two new modules to the Modules table, add a usage section under "Usage" demonstrating OkHttpTransport and JdkHttpTransport, update the Requirements table to note Java 11 for jdk.http.
- `docs/architecture.md`: mention the transport plug-ins in the "Pluggable transport" discussion.
- `docs/http.md`: brief note pointing to the two transport modules as the reference implementations of `HttpClient` / `AsyncHttpClient`.

## Commit structure

Three commits in this order, each independently revertable:

1. **`feat: add sdk-transport-okhttp module`** — build script, dependencies, source files (`OkHttpTransport` + internal adapters), tests, ABI snapshot, root-build updates (settings.gradle.kts, kover deps, libs.versions.toml).
2. **`feat: add sdk-transport-jdkhttp module`** — same shape as #1 for the JDK client. Module-level toolchain pinned to 11+.
3. **`docs: document transport modules in README and architecture`** — readme + architecture + http docs updated.

Each commit can be iterated under one subagent dispatch.

## Risks and trade-offs

- **OkHttp 5.x is recent (Aug 2025 GA).** Some consumers may already pin OkHttp 4.x. The BYO factory mitigates this — they can pass a 4.x-compatible OkHttpClient as long as our compile-time dep stays on 5.x. We don't run on 4.x though; if a user mixes versions, they own the classpath. Worth flagging in the docs.
- **The 64 KiB jdk.http streaming threshold is a heuristic.** Real workloads may want this tunable. Deferred — easy to add later as a builder field, no API break.
- **The piped-stream body writer thread for jdk.http** is one extra thread per concurrent large-body request. For high-throughput servers this may want a shared executor. Deferred.
- **`Response.protocol` detection on jdk.http** is straightforward (the JDK exposes `HttpResponse.version()`); on OkHttp 5.x it's `response.protocol`. Both map to the SDK's existing `Protocol` enum (`HTTP_1_0`, `HTTP_1_1`, `HTTP_2`).
