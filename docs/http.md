# HTTP Layer

This document covers the design and API of the SDK's HTTP abstractions — requests, responses,
headers, media types, protocols, the typed exception hierarchy, and the context system that
carries metadata through the request/response lifecycle.

## Table of Contents

- [Overview](#overview)
- [Request](#request)
    - [Request Model](#request-model)
    - [RequestBody](#requestbody)
    - [Method](#method)
- [Response](#response)
    - [Response Model](#response-model)
    - [ResponseBody](#responsebody)
    - [Status](#status)
- [Exceptions](#exceptions)
    - [HttpException Hierarchy](#httpexception-hierarchy)
    - [NetworkException](#networkexception)
    - [HttpExceptionFactory](#httpexceptionfactory)
- [Common Types](#common-types)
    - [Headers](#headers)
    - [MediaType](#mediatype)
    - [CommonMediaTypes](#commonmediatypes)
    - [Protocol](#protocol)
- [Context System](#context-system)
    - [Context Hierarchy](#context-hierarchy)
    - [CallContext](#callcontext)
    - [DispatchContext](#dispatchcontext)
    - [RequestContext](#requestcontext)
    - [ExchangeContext](#exchangecontext)
    - [ContextStore](#contextstore)
    - [Context Flow](#context-flow)
- [HttpClient Interface](#httpclient-interface)
- [Design Decisions](#design-decisions)
    - [Bodies Over the SDK's I/O Abstraction](#bodies-over-the-sdks-io-abstraction)
    - [Immutable Models With Builders](#immutable-models-with-builders)
    - [Single-Use vs Replayable Bodies](#single-use-vs-replayable-bodies)
    - [JDK 8 Compatibility](#jdk-8-compatibility)
- [Usage Examples](#usage-examples)
- [File Index](#file-index)

---

## Overview

The HTTP layer provides a complete, transport-agnostic abstraction for HTTP requests
and responses, targeting **JDK 8+**.

Bodies are read and written through the SDK's own Okio-inspired I/O abstraction: a
`RequestBody` writes to a `BufferedSink` and a `ResponseBody` exposes a `BufferedSource`.
Those interfaces live in `sdk-core` with zero runtime dependencies; the concrete
implementation is supplied at startup by an `IoProvider` (the only adapter today is Okio
3.x in `sdk-io-okio3`). See [I/O Module](io.md) for the seam itself.

The layer is split into the following sub-packages:

| Package         | Contents                                                                              |
|-----------------|---------------------------------------------------------------------------------------|
| `http.request`  | `Request`, `RequestBody`, `Method`, `LoggableRequestBody`                             |
| `http.response` | `Response`, `ResponseBody`, `Status`, `LoggableResponseBody`                          |
| `http.response.exception` | `HttpException` + concrete subclasses, `NetworkException`, `HttpExceptionFactory` |
| `http.common`   | `Headers`, `MediaType`, `CommonMediaTypes`, `Protocol`                                |
| `http.context`  | `CallContext`, `DispatchContext`, `RequestContext`, `ExchangeContext`, `ContextStore` |

The transport SPIs `HttpClient` / `AsyncHttpClient` live one level up, in the `client`
package.

Most model classes follow the same pattern: **immutable data classes with builder APIs** and
full Java interop via `@JvmStatic`, `@JvmOverloads`, and the generic `Builder<T>` interface.

---

## Request

### Request Model

`Request` is an immutable data class representing an HTTP request:

```kotlin
@ConsistentCopyVisibility
data class Request private constructor(
    val method: Method,
    val url: URL,
    val headers: Headers,
    val body: RequestBody?
)
```

**Construction** via builder:

```kotlin
val request = Request.builder()
    .method(Method.POST)
    .url("https://api.example.com/v1/users")
    .addHeader("Accept", "application/json")
    .body(RequestBody.create(payload, MediaType.parse("application/json")))
    .build()
```

**Modification** via `newBuilder()`:

```kotlin
val retryRequest = request.newBuilder()
    .addHeader("X-Retry-Count", "1")
    .build()
```

The private constructor forces all construction through the builder, ensuring validation
runs on every instance. `@ConsistentCopyVisibility` prevents the Kotlin `copy()` method
from bypassing the private constructor.

### RequestBody

`RequestBody` is an abstract class that encapsulates HTTP request content:

```kotlin
abstract class RequestBody {
    abstract fun mediaType(): MediaType?
    open fun contentLength(): Long = -1
    abstract fun writeTo(sink: BufferedSink)
    open fun isReplayable(): Boolean = false
    open fun toReplayable(provider: IoProvider = Io.provider): RequestBody
}
```

The body produces bytes on demand: the transport drives the write by calling `writeTo(sink)`.
`isReplayable()` reports whether the body can be written more than once and produce the same
bytes — retry logic queries it before deciding whether to buffer. `toReplayable()` returns a
replayable equivalent, draining a single-use body into an in-memory `Buffer` when needed.

**Factory methods:**

| Factory                                       | Replayable      | Backing                                  | `contentLength()` |
|-----------------------------------------------|-----------------|------------------------------------------|-------------------|
| `create(source, mediaType?, contentLength?)`  | No (single-use) | `BufferedSource`, drained + closed once  | Explicit or -1    |
| `create(buffer, mediaType?, contentLength?)`  | Yes             | in-memory `Buffer`, read via `peek()`    | `buffer.size`     |
| `create(bytes, mediaType?)`                   | Yes             | `ByteArray` written directly             | `bytes.size`      |
| `create(content, mediaType?, charset?)`       | Yes             | String encoded to `ByteArray`            | Computed          |
| `create(input, length, mediaType?)`           | Conditional     | `InputStream`; replayable iff `mark/reset` is supported and `length` fits the readLimit | `length` |
| `create(file, mediaType?, position?, count?)` | Yes             | `FileRequestBody` (transports may `sendfile`) | `count` or file size |
| `create(formData, charset?)`                  | Yes             | URL-encoded map to `ByteArray`           | Computed          |

The `InputStream`-based copy uses a manual 8 KiB scratch loop rather than
`InputStream.transferTo()` because `transferTo` is Java 9+ and the SDK targets JDK 8.

**Thread safety**: Instances are not required to be thread-safe; concurrent `writeTo` on the
same instance is undefined. The stream-backed bodies use atomic consume-guards so a second
`writeTo` on a single-use body fails loudly (`IllegalStateException`) rather than silently
emitting zero bytes.

**Logging**: Wrap with `LoggableRequestBody` to capture written bytes for diagnostics
without consuming the write. See [HTTP Body Logging](http-body-logging-and-concurrency.md).

### Method

`Method` is an enum of standard HTTP methods:

```
GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, TRACE, CONNECT
```

Each entry stores the canonical method token in its `method` property and returns it from
`toString()`.

---

## Response

### Response Model

`Response` is an immutable data class implementing `Closeable`:

```kotlin
@ConsistentCopyVisibility
data class Response private constructor(
    val request: Request,
    val protocol: Protocol,
    val status: Status,
    val message: String?,
    val headers: Headers,
    val body: ResponseBody?
) : Closeable
```

**Key properties:**

| Property   | Type            | Description                                    |
|------------|-----------------|------------------------------------------------|
| `request`  | `Request`       | The originating request                        |
| `protocol` | `Protocol`      | HTTP protocol version (HTTP/1.1, HTTP/2, etc.) |
| `status`   | `Status`        | HTTP status code                               |
| `message`  | `String?`       | Reason phrase (may be null for HTTP/2)         |
| `headers`  | `Headers`       | Response headers                               |
| `body`     | `ResponseBody?` | Response body (null for 204, HEAD, etc.)       |

There is no `isSuccessful` property on `Response`. The 2xx check lives on the status:
`response.status.isSuccess` is `true` when the code is in 200..299.

**Closing**: `response.close()` closes the body, releasing the underlying connection.
Always use `response.use { }` or try-with-resources:

```kotlin
val text = response.use { it.body?.source()?.use(BufferedSource::readUtf8) }
```

### ResponseBody

`ResponseBody` is an abstract `Closeable` that exposes a `BufferedSource`:

```kotlin
abstract class ResponseBody : Closeable {
    abstract fun mediaType(): MediaType?
    abstract fun contentLength(): Long
    abstract fun source(): BufferedSource
    abstract override fun close()
}
```

**Single-use contract**: `source()` returns the same `BufferedSource` instance on every
call. Once those bytes are read, they are gone. The body **must be closed** after use to
release the connection — even when the body is never read — so prefer `use {}` or
try-with-resources.

**Factory method:**

```kotlin
ResponseBody.create(source, mediaType?, contentLength?)
```

Wraps an existing `BufferedSource` in a single-use body. Intended for transport adapters
and test code that already hold a source.

**Repeatable reads**: Wrap with `LoggableResponseBody` for repeatable, thread-safe access.
It drains the wrapped body once into an internal `Buffer`, then serves a fresh
non-consuming `source()` view on each call. See
[HTTP Body Logging](http-body-logging-and-concurrency.md).

### Status

`Status` is a **total** type for HTTP status codes — a small class (not an enum) carrying a
numeric `code` and an optional `statusName`:

```kotlin
class Status private constructor(
    val code: Int,
    val statusName: String?
) {
    val isSuccess: Boolean get() = code in 200..299
}
```

Canonical codes recognized by the SDK are exposed as named constants in the companion
object, each carrying a human-readable `statusName`:

| Range   | Category      | Examples                                                        |
|---------|---------------|-----------------------------------------------------------------|
| 100-199 | Informational | `CONTINUE`, `SWITCHING_PROTOCOLS`                               |
| 200-299 | Successful    | `OK`, `CREATED`, `NO_CONTENT`                                   |
| 300-399 | Redirection   | `MOVED_PERMANENTLY`, `TEMPORARY_REDIRECT`                       |
| 400-499 | Client Error  | `BAD_REQUEST`, `UNAUTHORIZED`, `NOT_FOUND`, `TOO_MANY_REQUESTS` |
| 500-599 | Server Error  | `INTERNAL_SERVER_ERROR`, `BAD_GATEWAY`, `SERVICE_UNAVAILABLE`   |

Plus the non-standard `THIS_IS_FINE` (218). `Status.canonicalStatuses` is the full list of
recognized constants in declaration order. Two `Status` values are equal when their codes
are equal, so `Status.fromCode(200) == Status.OK`.

**Lookup by code:**

```kotlin
val known   = Status.fromCode(404)  // Status.NOT_FOUND
val vendor  = Status.fromCode(530)  // Status(code = 530, statusName = null)
```

`fromCode` is **total**: it never throws. A recognized code returns its canonical constant;
any other code returns a `Status` carrying that `code` with a `null` `statusName`, so
transports can faithfully surface vendor-specific codes (nginx 499, Cloudflare 520–526/530)
instead of losing the wire value. Use `fromCodeOrNull` when you specifically want to branch
on whether a code is one the SDK recognizes.

---

## Exceptions

The SDK ships a typed exception hierarchy under `org.dexpace.sdk.core.http.response.exception`.
The shape mirrors `gax`'s `ApiException` taxonomy translated to HTTP terms: one base class plus
one concrete subclass per canonical status code. The base class derives its retryable flag
from a single source of truth, so a downstream retry policy can read `exception.retryable`
instead of maintaining a parallel predicate map.

### HttpException Hierarchy

`HttpException` is the abstract base for every exception that carries a parsed HTTP response.
It extends `RuntimeException` — not `IOException` — because by the time you have one, a
response was received and parsed; the failure is at the protocol level, not at the I/O level.

```kotlin
abstract class HttpException(
    val status: Status,
    val headers: Headers,
    val body: ResponseBody?,         // lazy — NOT eagerly buffered
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val retryable: Boolean = RetryUtils.isRetryable(status.code)
    fun bodySnapshot(maxBytes: Int = DEFAULT_SNAPSHOT_BYTES): ByteArray?
}
```

`retryable` is a `val` **derived** at construction from `RetryUtils.isRetryable(status.code)`,
not hardcoded per subclass and not a constructor parameter. This guarantees the baked flag
can never disagree with the live retry policy: 408 / 429 and the 5xx range except 501 and 505
are retryable, everything else is not.

`bodySnapshot()` returns a non-consuming preview of the body bytes — it reads from a fresh
`source().peek()` view so the primary read path is undisturbed — capped at `maxBytes`
(default 4096) so a misbehaving server cannot OOM the logger. Returns `null` when the
response had no body.

**Concrete subclasses** (one per canonical status):

| Status | Subclass | Retryable |
|--------|----------|:---------:|
| 400 | `BadRequestException` | no |
| 401 | `UnauthorizedException` | no |
| 403 | `ForbiddenException` | no |
| 404 | `NotFoundException` | no |
| 405 | `MethodNotAllowedException` | no |
| 408 | `RequestTimeoutException` | **yes** |
| 409 | `ConflictException` | no |
| 410 | `GoneException` | no |
| 413 | `PayloadTooLargeException` | no |
| 415 | `UnsupportedMediaTypeException` | no |
| 422 | `UnprocessableEntityException` | no |
| 429 | `TooManyRequestsException` | **yes** |
| 500 | `InternalServerErrorException` | **yes** |
| 502 | `BadGatewayException` | **yes** |
| 503 | `ServiceUnavailableException` | **yes** |
| 504 | `GatewayTimeoutException` | **yes** |
| other 4xx | `ClientErrorException` (fallback) | no |
| other 5xx | `ServerErrorException` (fallback) | per code — 501/505 are **not** retryable |

Each subclass takes the `Response` and pulls `status`, `headers`, and `body` from it. All
subclasses are `open` so service-client codegen can derive a per-operation typed subclass
that stamps a deserialized error payload (Expedia-style `{Op}{StatusCode}Exception`) without
modifying this module.

### NetworkException

`NetworkException` covers transport-level failures — connection refused, DNS lookup failure,
TLS handshake failure, socket read timeout, peer reset — i.e. anything that prevents a full
response from reaching the SDK in the first place. It is a sibling of `HttpException`, not a
subclass: it extends `java.io.IOException` so existing `catch (IOException)` call sites keep
working, and it carries no status/headers/body because none arrived.

```kotlin
open class NetworkException(message: String? = null, cause: Throwable? = null) : IOException(message, cause) {
    val retryable: Boolean = true  // always retryable at the SDK level
}
```

The `retryable` flag is always `true`: nothing reached the server, so the SDK can safely
attempt the request again. Whether the request itself is *safe* to retry (HTTP method
idempotency, replayable body) is the retry policy's call, not this class's.

### HttpExceptionFactory

`HttpExceptionFactory.fromResponse(response)` maps a non-2xx `Response` to the right
subclass:

```kotlin
val response = httpClient.execute(request)
if (!response.status.isSuccess) {
    throw HttpExceptionFactory.fromResponse(response)
}
```

The factory throws `IllegalArgumentException` if called with a status outside 400..599 —
1xx/2xx/3xx outcomes are not exceptions and should not be funneled through this path.

---

## Common Types

### Headers

`Headers` is an immutable multi-map of HTTP headers with case-insensitive name lookup:

```kotlin
@ConsistentCopyVisibility
data class Headers private constructor(
    private val headersMap: Map<String, List<String>>
)
```

**API:**

| Method          | Description                                                          |
|-----------------|---------------------------------------------------------------------|
| `get(name)`     | First value for the name (case-insensitive), or `null`              |
| `values(name)`  | All values for the name (unmodifiable), or empty list               |
| `contains(name)`| Whether any value is present for the name                           |
| `names()`       | Immutable snapshot of all header names                              |
| `entries()`     | Immutable snapshot of header entries as `Map.Entry<String, List<String>>` |
| `newBuilder()`  | Returns a pre-filled `Builder` for modification                     |

Both a `String`-based API and an `HttpHeaderName`-typed API are exposed; they interoperate
freely. Header names are normalized to lowercase internally for case-insensitive matching.

**Builder:**

```kotlin
val headers = Headers.Builder()
    .add("Content-Type", "application/json")
    .add("Accept", "application/json")
    .add("Cache-Control", "no-cache")
    .add("Cache-Control", "no-store")   // multi-value
    .build()

headers.get("content-type")     // "application/json" (case-insensitive)
headers.values("Cache-Control") // ["no-cache", "no-store"]
```

### MediaType

`MediaType` represents a parsed MIME type with optional parameters:

```kotlin
@ConsistentCopyVisibility
data class MediaType private constructor(
    val type: String,        // e.g., "application"
    val subtype: String,     // e.g., "json"
    val parameters: Map<String, String>
)
```

**Key properties:**

| Property   | Description                                              |
|------------|----------------------------------------------------------|
| `fullType` | `"$type/$subtype"` — e.g., `"application/json"`          |
| `charset`  | Parsed `Charset` from the `charset` parameter, or `null` |

**Parsing:**

```kotlin
val json = MediaType.parse("application/json; charset=utf-8")
json.type      // "application"
json.subtype   // "json"
json.charset   // UTF-8
json.fullType  // "application/json"
```

`parse` throws `IllegalArgumentException` on a malformed value; `of(type, subtype, params?)`
builds one from explicit parts. `toString()` round-trips with `parse` — parameter values that
are not bare RFC 7230 tokens are emitted as quoted-strings with proper escaping.

**Includes check**: `mediaType.includes(other)` determines if one media type encompasses
another (wildcards in the type or subtype match anything) — useful for content negotiation.

### CommonMediaTypes

Constants for frequently used media types, exposed as `@JvmField` statics:

```kotlin
CommonMediaTypes.APPLICATION_JSON              // application/json
CommonMediaTypes.APPLICATION_XML               // application/xml
CommonMediaTypes.APPLICATION_FORM_URLENCODED   // application/x-www-form-urlencoded
CommonMediaTypes.TEXT_PLAIN                     // text/plain
CommonMediaTypes.APPLICATION_OCTET_STREAM      // application/octet-stream
// ... and more
```

### Protocol

`Protocol` is an enum of HTTP protocol versions:

```
HTTP_1_0, HTTP_1_1, HTTP_2, H2_PRIOR_KNOWLEDGE, QUIC
```

**Parsing:**

```kotlin
Protocol.get("HTTP/2")   // Protocol.HTTP_2
Protocol.get("HTTP/2.0") // Protocol.HTTP_2 (normalized)
Protocol.get("http/1.1") // Protocol.HTTP_1_1 (case-insensitive)
```

`get` throws `IllegalArgumentException` for an unrecognized identifier.

---

## Context System

The context system carries metadata — instrumentation, tracing, and request/response
references — through the HTTP lifecycle.

### Context Hierarchy

```
              CallContext (interface)
                  │
        ┌─────────┼──────────┐
        ▼         ▼          ▼
 DispatchContext  RequestContext  ExchangeContext
 (pre-request)   (has request)   (has request + response)
```

All contexts implement `CallContext`, which provides an `InstrumentationContext` for tracing,
a per-call `callKey`, and `AutoCloseable` for cleanup (evicts the context from the store on
close).

### CallContext

The base interface:

```kotlin
interface CallContext : AutoCloseable {
    val instrumentationContext: InstrumentationContext
    val callKey: String

    override fun close() {
        ContextStore.remove(callKey, this)
    }
}
```

Each call is registered in `ContextStore` under its `callKey`. The key is **per call**, not
per trace: the trace id alone is not call-unique (the no-op instrumentation context shares one
constant trace id, and an inbound W3C trace shares a trace id across spans), so keying by it
would let concurrent calls collide.

Closing a context evicts the chain's entry from `ContextStore` — but only when the closing
context is still the registered occupant (identity-conditional eviction). An earlier link in a
promotion chain whose live child has already replaced it in the store therefore does not evict
that child. Only the **terminal** context of a chain needs to be closed.

### DispatchContext

Created at dispatch time — before the request exists:

```kotlin
data class DispatchContext(
    override val instrumentationContext: InstrumentationContext,
    override val callKey: String = deriveCallKey(instrumentationContext)
) : CallContext
```

**Promotion**: `toRequestContext(request)` creates a `RequestContext`, carries the same
`callKey` forward, and stores it in `ContextStore`.

**Default factory**: `DispatchContext.default()` creates a context with
`NoopInstrumentationContext` for non-instrumented calls. Because the no-op context's trace and
span ids are shared constants, `default()` mints a process-unique `callKey` so two untraced
calls cannot collide in the store.

### RequestContext

Created when the `Request` is available:

```kotlin
data class RequestContext(
    override val instrumentationContext: InstrumentationContext,
    val request: Request,
    override val callKey: String = DispatchContext.deriveCallKey(instrumentationContext)
) : CallContext
```

**Promotion**: `toExchangeContext(response)` creates an `ExchangeContext` under the same
`callKey` and updates the `ContextStore` entry.

### ExchangeContext

The terminal context with both request and response:

```kotlin
data class ExchangeContext(
    override val instrumentationContext: InstrumentationContext,
    val request: Request,
    val response: Response,
    override val callKey: String = DispatchContext.deriveCallKey(instrumentationContext)
) : CallContext
```

This is used by retry logic, response pipeline steps, and post-execution instrumentation.
As the terminal link, it is the context whose `close()` should be called to evict the chain.

### ContextStore

A process-wide, `callKey`-keyed registry for retrieving the latest context of a call:

```kotlin
object ContextStore {
    fun get(callKey: String): CallContext?
    fun put(callKey: String, context: CallContext)              // rejects a duplicate key
    fun set(callKey: String, context: CallContext)               // overwrites
    fun remove(callKey: String)                                  // no-op if absent
    fun remove(callKey: String, expected: CallContext): Boolean  // identity-conditional
}
```

| Method                  | Behavior                                                                                |
|-------------------------|-----------------------------------------------------------------------------------------|
| `put`                   | Throws `IllegalArgumentException` if the key already exists (CAS via `putIfAbsent`)     |
| `set`                   | Overwrites silently (used during context promotion)                                     |
| `remove(key)`           | Removes the entry; **no-op (does not throw)** when the key is absent                     |
| `remove(key, expected)` | Removes only if the slot still maps to `expected`; returns whether an entry was removed  |

The backing map is a `ConcurrentHashMap`, so calls with distinct keys need no external
synchronization. The `remove(key)` no-op makes the close contract easy to honour from cleanup
paths — closing a context twice, or closing one that was never registered, is well defined.

**Lifecycle**: Context is stored on `toRequestContext()` / `toExchangeContext()` and removed
on `close()`. This keeps contexts available for the duration of the HTTP call and cleaned up
afterwards.

### Context Flow

```
1. DispatchContext.default()              → DispatchContext created (mints a unique callKey)
2. dispatchCtx.toRequestContext(request)  → RequestContext stored in ContextStore
3. httpClient.execute(request)            → HTTP call happens
4. requestCtx.toExchangeContext(response) → ExchangeContext replaces it in ContextStore
5. // Pipeline steps, retry logic, instrumentation use the ExchangeContext
6. exchangeCtx.close()                    → Removed from ContextStore
```

---

## HttpClient Interface

The SDK's transport abstraction (in the `client` package) is a single-method functional
interface that also extends `AutoCloseable`:

```kotlin
fun interface HttpClient : AutoCloseable {
    fun execute(request: Request): Response
    override fun close() { /* no-op default */ }
}
```

An asynchronous sibling, `AsyncHttpClient`, exposes
`executeAsync(request): CompletableFuture<Response>`.

Consuming libraries implement these against their chosen HTTP transport:

| Transport           | Implementation                    |
|---------------------|-----------------------------------|
| `HttpURLConnection` | JDK built-in, zero-dependency     |
| Apache HttpClient   | Full-featured, connection pooling |
| Jetty HttpClient    | HTTP/2 native support             |
| OkHttp              | Okio-based, interceptors          |
| Netty               | Async, high-performance           |

The SDK provides everything around this interface — body abstractions, logging, pipelines,
contexts, serialization — but **not the transport itself**. This separation ensures the
SDK core has zero transport dependencies. The default `close()` is a no-op so SAM literals
(`HttpClient { request -> ... }`) remain valid; transports that own threads, pools, or
executors override it to release them (BYO clients are never closed by the SDK).

Two reference transport implementations ship with the project:

- **`sdk-transport-okhttp`** — OkHttp 5.x; Java 8 bytecode; sync (`Call.execute`) and async (`Call.enqueue`) paths with native cancellation propagation.
- **`sdk-transport-jdkhttp`** — `java.net.http.HttpClient` (JEP 321, JDK 11+); Java 11 bytecode; sync and async via JDK-native APIs; `CompletableFuture.cancel()` propagates to the underlying exchange natively.

Both implement `HttpClient` and `AsyncHttpClient` on a single class. See the README's Usage section for instantiation examples.

---

## Design Decisions

### Bodies Over the SDK's I/O Abstraction

Request and response bodies are written to / read from the SDK's own Okio-inspired I/O
abstraction — `BufferedSink` for writes, `BufferedSource` for reads — rather than raw
`java.io.OutputStream` / `InputStream`. The motivation:

1. **Zero `sdk-core` runtime dependency**: the `io` package is interfaces only. The concrete
   implementation arrives via an `IoProvider` installed at startup (Okio 3.x today, in
   `sdk-io-okio3`), so the core artifact stays dependency-free.
2. **Segment-based buffering where it pays**: the abstraction provides zero-copy segment
   transfers (e.g. `writeAll`, non-consuming `peek()`) used by the body-logging and
   replay paths without forcing every transport onto a heavyweight type.
3. **A clean `java.io` bridge at the edges**: `BufferedSource.inputStream()` and
   `BufferedSink.outputStream()` adapt to the `java.io` surface that transports and callers
   already speak, so integration boundaries stay simple.

See [I/O Module](io.md) for the abstraction itself and the `IoProvider` seam.

### Immutable Models With Builders

All HTTP model classes use the same pattern:

- **Private constructor**: Forces use of the builder for validation
- **`data class`**: Free `equals()`, `hashCode()`, `toString()`
- **`@ConsistentCopyVisibility`**: Prevents `copy()` from bypassing the private constructor
- **`newBuilder()`**: Creates a pre-filled builder for modification without mutation
- **`Builder<T>`**: Generic interface (in `org.dexpace.sdk.core.generics`) ensuring all
  builders expose `fun build(): T`

This pattern ensures models are always in a valid state, modifications produce new instances
(safe for concurrent use), and Java interop is clean.

### Single-Use vs Replayable Bodies

| Body type                          | Replayable? | Why                                                          |
|------------------------------------|-------------|--------------------------------------------------------------|
| `RequestBody.create(bytes)`        | Yes         | `ByteArray` is a flat copy, can be written many times        |
| `RequestBody.create(content)`      | Yes         | Delegates to the byte-array body                             |
| `RequestBody.create(buffer)`       | Yes         | Read via non-consuming `peek()` on every write               |
| `RequestBody.create(formData)`     | Yes         | Encoded once at construction; delegates to the byte-array body |
| `RequestBody.create(file)`         | Yes         | Re-reads the file on each write                              |
| `RequestBody.create(input, length)`| Conditional | Replayable iff the stream supports `mark/reset` within `length` |
| `RequestBody.create(source)`       | No          | `BufferedSource` is drained and closed on first `writeTo`    |
| `ResponseBody.create(source)`      | No          | `BufferedSource` is consumed on first read                   |
| `LoggableResponseBody`             | Yes         | Drains once into a `Buffer`, serves a fresh `source()` view each call |

The single-use contract for stream-backed bodies is intentional: it avoids hidden buffering
costs and makes the consumption model explicit. When replay is needed, call `toReplayable()`
on a request body **before** the first write, or wrap a response body with
`LoggableResponseBody` so the buffering cost is visible and controlled.

### JDK 8 Compatibility

Specific API choices driven by JDK 8 targeting:

| Modern API (unavailable)              | SDK alternative                             |
|---------------------------------------|---------------------------------------------|
| `InputStream.transferTo()` (Java 9+)  | Manual 8 KiB copy loop                      |
| `Thread.threadId()` (Java 19+)        | `Thread.currentThread().id`                 |
| `java.net.http.HttpClient` (Java 11+) | `HttpClient` interface (transport-agnostic) |
| `HttpHeaders` (Java 11+)              | Custom `Headers` class                      |

---

## Usage Examples

### Building and sending a request

```kotlin
// Build a JSON POST request
val body = RequestBody.create(
    """{"name": "Alice", "email": "alice@example.com"}""",
    MediaType.parse("application/json")
)

val request = Request.builder()
    .method(Method.POST)
    .url("https://api.example.com/v1/users")
    .addHeader("Accept", "application/json")
    .addHeader("Authorization", "Bearer $token")
    .body(body)
    .build()

// Execute and consume
httpClient.execute(request).use { response ->
    if (response.status.isSuccess) {
        val json = response.body?.source()?.use(BufferedSource::readUtf8)
        // parse json...
    } else {
        logger.error("Request failed: {} {}", response.status, response.message)
    }
}
```

### Form data submission

```kotlin
val body = RequestBody.create(
    mapOf(
        "username" to "alice",
        "password" to "secret",
        "grant_type" to "password"
    )
)

val request = Request.builder()
    .method(Method.POST)
    .url("https://auth.example.com/token")
    .body(body)
    .build()
```

### Working with headers

```kotlin
// Build headers
val headers = Headers.Builder()
    .add("Content-Type", "application/json")
    .add("X-Request-Id", UUID.randomUUID().toString())
    .add("Accept-Encoding", "gzip")
    .add("Accept-Encoding", "deflate")  // multi-value
    .build()

// Access (case-insensitive)
headers.get("content-type")           // "application/json"
headers.values("accept-encoding")     // ["gzip", "deflate"]

// Modify via newBuilder
val updated = headers.newBuilder()
    .set("Authorization", "Bearer refreshed-token")
    .build()
```

### Context lifecycle

```kotlin
val context = DispatchContext.default()

// Promote to request context
val requestCtx = context.toRequestContext(request)

// Execute
val response = httpClient.execute(request)

// Promote to exchange context
val exchangeCtx = requestCtx.toExchangeContext(response)

// Use in pipeline steps, logging, etc.
logger.info("Trace: {}", exchangeCtx.instrumentationContext.traceId)

// Cleanup — close the terminal context to evict the chain
exchangeCtx.close()
```

---

## File Index

| File                       | Package                  | Visibility | Description                                  |
|----------------------------|--------------------------|------------|----------------------------------------------|
| `Request.kt`               | `http.request`           | public     | Immutable request data class + builder       |
| `RequestBody.kt`           | `http.request`           | public     | Abstract request body + factory methods      |
| `LoggableRequestBody.kt`   | `http.request`           | public     | TeeSink-mirroring body for write logging     |
| `Method.kt`                | `http.request`           | public     | HTTP method enum                             |
| `Response.kt`              | `http.response`          | public     | Immutable response data class + builder      |
| `ResponseBody.kt`          | `http.response`          | public     | Abstract response body over `BufferedSource` |
| `LoggableResponseBody.kt`  | `http.response`          | public     | Buffering wrapper for repeatable reads       |
| `Status.kt`                | `http.response`          | public     | Total HTTP status type (class)               |
| `HttpException.kt`         | `http.response.exception`| public     | Abstract base for typed HTTP exceptions      |
| `HttpExceptions.kt`        | `http.response.exception`| public     | One concrete subclass per canonical status   |
| `NetworkException.kt`      | `http.response.exception`| public     | Transport-level failure (IOException sibling)|
| `HttpExceptionFactory.kt`  | `http.response.exception`| public     | `Response` → typed exception dispatcher      |
| `Headers.kt`               | `http.common`            | public     | Immutable multi-map + builder                |
| `MediaType.kt`             | `http.common`            | public     | Parsed MIME type with charset extraction     |
| `CommonMediaTypes.kt`      | `http.common`            | public     | Media type constants                         |
| `Protocol.kt`              | `http.common`            | public     | HTTP protocol version enum                   |
| `CallContext.kt`           | `http.context`           | public     | Base context interface                       |
| `DispatchContext.kt`       | `http.context`           | public     | Pre-request context                          |
| `RequestContext.kt`        | `http.context`           | public     | Request-scoped context                       |
| `ExchangeContext.kt`       | `http.context`           | public     | Full exchange context (request + response)   |
| `ContextStore.kt`          | `http.context`           | public     | Process-wide callKey-keyed context store     |
| `HttpClient.kt`            | `client`                 | public     | Synchronous transport SPI                    |
| `AsyncHttpClient.kt`       | `client`                 | public     | Asynchronous transport SPI                   |
</content>
</invoke>
