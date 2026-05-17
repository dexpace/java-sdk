# HTTP Layer

This document covers the design and API of the SDK's HTTP abstractions — requests, responses,
headers, media types, protocols, and the context system that carries metadata through the
request/response lifecycle.

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
    - [OutputStream and InputStream Over Okio](#outputstream-and-inputstream-over-okio)
    - [Immutable Models With Builders](#immutable-models-with-builders)
    - [Single-Use vs Reusable Bodies](#single-use-vs-reusable-bodies)
    - [JDK 8 Compatibility](#jdk-8-compatibility)
- [Usage Examples](#usage-examples)
- [File Index](#file-index)

---

## Overview

The HTTP layer provides a complete, transport-agnostic abstraction for HTTP requests
and responses. It is built entirely on `java.io` APIs with no external dependencies,
targeting **JDK 8+**.

The layer is split into four sub-packages:

| Package         | Contents                                                                              |
|-----------------|---------------------------------------------------------------------------------------|
| `http.request`  | `Request`, `RequestBody`, `Method`                                                    |
| `http.response` | `Response`, `ResponseBody`, `Status`                                                  |
| `http.common`   | `Headers`, `MediaType`, `CommonMediaTypes`, `Protocol`                                |
| `http.context`  | `CallContext`, `DispatchContext`, `RequestContext`, `ExchangeContext`, `ContextStore` |

All model classes follow the same pattern: **immutable data classes with builder APIs** and
full Java interop via `@JvmStatic`, `@JvmOverloads`, and `BuilderTrait<T>`.

---

## Request

### Request Model

`Request` is an immutable data class representing an HTTP request:

```kotlin
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
    .header("Accept", "application/json")
    .body(RequestBody.create(payload, MediaType.parse("application/json")))
    .build()
```

**Modification** via `newBuilder()`:

```kotlin
val retryRequest = request.newBuilder()
    .header("X-Retry-Count", "1")
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
    abstract fun writeTo(stream: OutputStream)
}
```

**Factory methods:**

| Factory                                           | Reusable        | Backing                             | `contentLength()` |
|---------------------------------------------------|-----------------|-------------------------------------|-------------------|
| `create(inputStream, mediaType?, contentLength?)` | No (single-use) | `InputStream` consumed via `use {}` | Explicit or -1    |
| `create(bytes, mediaType?)`                       | Yes             | `ByteArray` written directly        | `bytes.size`      |
| `create(content, mediaType?, charset?)`           | Yes             | String encoded to `ByteArray`       | Computed          |
| `create(formData, charset?)`                      | Yes             | URL-encoded map to `ByteArray`      | Computed          |

The `InputStream`-based factory uses a manual 8 KB copy loop instead of
`InputStream.transferTo()` because `transferTo` is Java 9+ and the SDK targets JDK 8.

**Thread safety**: Instances are not thread-safe. A single `RequestBody` should be
written from one thread at a time.

**Logging**: Wrap with `LoggableRequestBody` to capture written bytes for diagnostics
without consuming the write. See [HTTP Body Logging](http-body-logging-and-concurrency.md).

### Method

`Method` is an enum of standard HTTP methods:

```
GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, TRACE, CONNECT
```

Each entry stores the method string and provides it via `toString()`.

---

## Response

### Response Model

`Response` is an immutable data class implementing `Closeable`:

```kotlin
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

| Property       | Type            | Description                                    |
|----------------|-----------------|------------------------------------------------|
| `request`      | `Request`       | The originating request                        |
| `protocol`     | `Protocol`      | HTTP protocol version (HTTP/1.1, HTTP/2, etc.) |
| `status`       | `Status`        | HTTP status code enum                          |
| `message`      | `String?`       | Reason phrase (may be null for HTTP/2)         |
| `headers`      | `Headers`       | Response headers                               |
| `body`         | `ResponseBody?` | Response body (null for 204, HEAD, etc.)       |
| `isSuccessful` | `Boolean`       | `true` if `status.code` is in 200..299         |

**Closing**: `response.close()` closes the body, releasing the underlying connection.
Always use `response.use { }` or try-with-resources:

```kotlin
val data = response.use { it.body?.string() }
```

### ResponseBody

`ResponseBody` is an abstract `Closeable` that wraps an `InputStream`:

```kotlin
abstract class ResponseBody : Closeable {
    abstract fun mediaType(): MediaType?
    abstract fun contentLength(): Long
    abstract fun byteStream(): InputStream

    fun bytes(): ByteArray      // reads all + closes
    fun string(charset): String // reads all + closes
}
```

**Single-use contract**: `byteStream()` returns the same stream instance. Once consumed,
the bytes are gone. The `bytes()` and `string()` convenience methods drain the stream and
close the body in one call.

**Factory method:**

```kotlin
ResponseBody.create(inputStream, mediaType?, contentLength?)
```

Creates a body backed by a `BufferedInputStream` wrapping the given input stream.

**Repeatable reads**: Wrap with `LoggableResponseBody` for repeatable, thread-safe access.
See [HTTP Body Logging](http-body-logging-and-concurrency.md).

### Status

`Status` is a comprehensive enum of HTTP status codes, covering:

| Range   | Category      | Examples                                                        |
|---------|---------------|-----------------------------------------------------------------|
| 100-199 | Informational | `CONTINUE`, `SWITCHING_PROTOCOLS`                               |
| 200-299 | Successful    | `OK`, `CREATED`, `NO_CONTENT`                                   |
| 300-399 | Redirection   | `MOVED_PERMANENTLY`, `TEMPORARY_REDIRECT`                       |
| 400-499 | Client Error  | `BAD_REQUEST`, `UNAUTHORIZED`, `NOT_FOUND`, `TOO_MANY_REQUESTS` |
| 500-599 | Server Error  | `INTERNAL_SERVER_ERROR`, `BAD_GATEWAY`, `SERVICE_UNAVAILABLE`   |

Plus non-standard codes like `THIS_IS_FINE` (218).

**Lookup by code:**

```kotlin
val status = Status.fromCode(404)  // Status.NOT_FOUND
```

Throws `IllegalArgumentException` for unknown codes.

---

## Exceptions

The SDK ships a typed exception hierarchy under `org.dexpace.sdk.core.http.response.exception`.
The shape mirrors `gax`'s `ApiException` taxonomy translated to HTTP terms: one base class plus
one concrete subclass per canonical status code. Each subclass bakes its retryable flag at
construction time, so a downstream retry policy can read `exception.retryable` instead of
maintaining a parallel predicate map.

### HttpException Hierarchy

`HttpException` is the abstract base for every exception that carries a parsed HTTP response.
It extends `RuntimeException` — not `IOException` — because by the time you have one, a
response was received and parsed; the failure is at the protocol level, not at the I/O level.

```kotlin
abstract class HttpException(
    val status: Status,
    val headers: Headers,
    val body: ResponseBody?,         // lazy — NOT eagerly buffered
    val retryable: Boolean,           // baked at construction; never re-derived
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    fun bodySnapshot(maxBytes: Int = DEFAULT_SNAPSHOT_BYTES): ByteArray?
}
```

`bodySnapshot()` returns a non-consuming preview of the body bytes (uses
`BufferedSource.peek()` internally) — capped at `maxBytes` so a misbehaving server cannot OOM
the logger. Returns `null` when the response had no body.

**Concrete subclasses** (one per canonical status):

| Status | Subclass | Retryable |
|--------|----------|:---------:|
| 400 | `BadRequestException` | no |
| 401 | `UnauthorizedException` | no |
| 403 | `ForbiddenException` | no |
| 404 | `NotFoundException` | no |
| 405 | `MethodNotAllowedException` | no |
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
| other 5xx | `ServerErrorException` (fallback) | **yes** |

All subclasses are `open` so service-client codegen can derive a per-operation typed subclass
that stamps a deserialized error payload (Expedia-style `{Op}{StatusCode}Exception`) without
modifying this module.

### NetworkException

`NetworkException` covers transport-level failures — connection refused, DNS lookup failure,
TLS handshake failure, socket read timeout, peer reset — i.e. anything that prevents a full
response from reaching the SDK in the first place. It is a sibling of `HttpException`, not a
subclass: it extends `java.io.IOException` so existing `catch (IOException)` call sites keep
working, and it carries no status/headers/body because none arrived.

```kotlin
class NetworkException(message: String? = null, cause: Throwable? = null) : IOException(message, cause) {
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

The factory throws `IllegalArgumentException` if called with a 1xx/2xx/3xx response — those
are not exceptions and should not be funneled through this path.

---

## Common Types

### Headers

`Headers` is an immutable multi-map of HTTP headers with case-insensitive name lookup:

```kotlin
data class Headers private constructor(
    private val headersMap: Map<String, List<String>>
)
```

**API:**

| Method         | Description                                             |
|----------------|---------------------------------------------------------|
| `get(name)`    | First value for the name (case-insensitive), or `null`  |
| `values(name)` | All values for the name, or empty list                  |
| `names()`      | Set of all header names                                 |
| `entries()`    | All header entries as `Map.Entry<String, List<String>>` |
| `newBuilder()` | Returns a pre-filled `Builder` for modification         |

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

Header names are normalized to lowercase internally for case-insensitive matching.

### MediaType

`MediaType` represents a parsed MIME type with optional parameters:

```kotlin
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
json.charset   // Charsets.UTF_8
json.fullType  // "application/json"
```

**Includes check**: `mediaType.includes(other)` determines if one media type encompasses
another — useful for content negotiation.

### CommonMediaTypes

Constants for frequently used media types:

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

All contexts implement `CallContext`, which provides `InstrumentationContext` for tracing
and `AutoCloseable` for cleanup (removes the context from the store on close).

### CallContext

The base interface:

```kotlin
interface CallContext : AutoCloseable {
    val instrumentationContext: InstrumentationContext

    override fun close() {
        ContextStore.remove(instrumentationContext.traceId.value)
    }
}
```

Closing a context removes it from the global `ContextStore`, preventing memory leaks.

### DispatchContext

Created at dispatch time — before the request exists:

```kotlin
data class DispatchContext(
    override val instrumentationContext: InstrumentationContext
) : CallContext
```

**Promotion**: `toRequestContext(request)` creates a `RequestContext` and registers it
in the `ContextStore` under the trace ID.

**Default factory**: `DispatchContext.default()` creates a context with `NoopInstrumentationContext`
for non-instrumented calls.

### RequestContext

Created when the `Request` is available:

```kotlin
data class RequestContext(
    override val instrumentationContext: InstrumentationContext,
    val request: Request
) : CallContext
```

**Promotion**: `toExchangeContext(response)` creates an `ExchangeContext` and updates
the `ContextStore` entry.

### ExchangeContext

The final context with both request and response:

```kotlin
data class ExchangeContext(
    override val instrumentationContext: InstrumentationContext,
    val request: Request,
    val response: Response
) : CallContext
```

This is used by retry logic, response pipeline steps, and post-execution instrumentation.

### ContextStore

A global, trace-ID-keyed store for retrieving contexts:

```kotlin
object ContextStore {
    fun get(runId: String): CallContext?
    fun put(runId: String, context: CallContext)   // throws if duplicate
    fun set(runId: String, context: CallContext)    // overwrites
    fun remove(traceId: String)                    // throws if not found
}
```

| Method   | Behavior                                                                               |
|----------|----------------------------------------------------------------------------------------|
| `put`    | Throws `IllegalArgumentException` if the trace ID already exists (duplicate detection) |
| `set`    | Overwrites silently (used during context promotion)                                    |
| `remove` | Throws `IllegalArgumentException` if the trace ID is not found                         |

**Lifecycle**: Context is stored on `toRequestContext()` / `toExchangeContext()` and removed
on `close()`. This ensures contexts are available for the duration of the HTTP call and
cleaned up afterwards.

### Context Flow

```
1. DispatchContext.default()              → DispatchContext created
2. dispatchCtx.toRequestContext(request)  → RequestContext stored in ContextStore
3. httpClient.execute(request)            → HTTP call happens
4. requestCtx.toExchangeContext(response) → ExchangeContext replaces in ContextStore
5. // Pipeline steps, retry logic, instrumentation use the ExchangeContext
6. exchangeCtx.close()                   → Removed from ContextStore
```

---

## HttpClient Interface

The SDK's transport abstraction:

```kotlin
interface HttpClient {
    fun execute(request: Request): Response
}
```

Consuming libraries implement this against their chosen HTTP transport:

| Transport           | Implementation                    |
|---------------------|-----------------------------------|
| `HttpURLConnection` | JDK built-in, zero-dependency     |
| Apache HttpClient   | Full-featured, connection pooling |
| Jetty HttpClient    | HTTP/2 native support             |
| OkHttp              | Okio-based, interceptors          |
| Netty               | Async, high-performance           |

The SDK provides everything around this interface — body abstractions, logging, pipelines,
contexts, serialization — but **not the transport itself**. This separation ensures the
SDK core has zero transport dependencies.

Two reference transport implementations ship with the project:

- **`sdk-transport-okhttp`** — OkHttp 5.x; Java 8 bytecode; sync (`Call.execute`) and async (`Call.enqueue`) paths with native cancellation propagation.
- **`sdk-transport-jdkhttp`** — `java.net.http.HttpClient` (JEP 321, JDK 11+); Java 11 bytecode; sync and async via JDK-native APIs; `CompletableFuture.cancel()` propagates to the underlying exchange natively.

Both implement `HttpClient` and `AsyncHttpClient` on a single class. See the README's Usage section for instantiation examples.

---

## Design Decisions

### OutputStream and InputStream Over Okio

The HTTP abstractions use `java.io.OutputStream` for request body writes and
`java.io.InputStream` for response body reads. This was chosen over Okio's `BufferedSink`
and `BufferedSource` for several reasons:

1. **No external dependency**: Okio adds ~300 KB of Kotlin multiplatform artifacts.
2. **Universal interop**: Every JVM HTTP client speaks `InputStream`/`OutputStream`.
   Okio types require adaptation at integration boundaries.
3. **JDK 8 compatibility**: Only `java.io` APIs available since JDK 1.0.
4. **Sufficient performance**: The SDK's internal I/O layer (see [I/O Module](io.md))
   provides segment-based buffering where needed — at the logging and capture layer.

The internal I/O layer bridges the gap: `Buffer.outputStream()` and
`Buffer.readOnlyInputStream()` connect the segment-based system to the `java.io` API
surface.

### Immutable Models With Builders

All HTTP model classes use the same pattern:

- **Private constructor**: Forces use of builder for validation
- **`data class`**: Free `equals()`, `hashCode()`, `toString()`
- **`@ConsistentCopyVisibility`**: Prevents `copy()` from bypassing the private constructor
- **`newBuilder()`**: Creates a pre-filled builder for modification without mutation
- **`BuilderTrait<T>`**: Generic interface ensuring all builders have `fun build(): T`

This pattern ensures models are always in a valid state, modifications produce new instances
(safe for concurrent use), and Java interop is clean.

### Single-Use vs Reusable Bodies

| Body type                          | Reusable? | Why                                                               |
|------------------------------------|-----------|-------------------------------------------------------------------|
| `RequestBody.create(bytes)`        | Yes       | `ByteArray` is a flat copy, can be written many times             |
| `RequestBody.create(content)`      | Yes       | Delegates to byte array factory                                   |
| `RequestBody.create(formData)`     | Yes       | Delegates to byte array factory                                   |
| `RequestBody.create(inputStream)`  | No        | `InputStream` is consumed on first `writeTo()`                    |
| `ResponseBody.create(inputStream)` | No        | `InputStream` is consumed on first read                           |
| `LoggableResponseBody`             | Yes       | Eagerly buffers into `Buffer`, serves via `readOnlyInputStream()` |

The single-use contract for stream-backed bodies is intentional: it avoids hidden buffering
costs and makes the consumption model explicit. When repeatable access is needed,
`LoggableResponseBody` makes the buffering cost visible and controlled.

### JDK 8 Compatibility

Specific API choices driven by JDK 8 targeting:

| Modern API (unavailable)              | SDK alternative                             |
|---------------------------------------|---------------------------------------------|
| `InputStream.transferTo()` (Java 9+)  | Manual 8 KB copy loop                       |
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
    .header("Accept", "application/json")
    .header("Authorization", "Bearer $token")
    .body(body)
    .build()

// Execute and consume
httpClient.execute(request).use { response ->
    if (response.isSuccessful) {
        val json = response.body?.string()
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

// Cleanup
exchangeCtx.close()
```

---

## File Index

| File                  | Package         | Visibility | Description                                  |
|-----------------------|-----------------|------------|----------------------------------------------|
| `Request.kt`          | `http.request`  | public     | Immutable request data class + builder       |
| `RequestBody.kt`      | `http.request`  | public     | Abstract request body + factory methods      |
| `Method.kt`           | `http.request`  | public     | HTTP method enum                             |
| `Response.kt`         | `http.response` | public     | Immutable response data class + builder      |
| `ResponseBody.kt`     | `http.response` | public     | Abstract response body + convenience methods |
| `Status.kt`           | `http.response` | public     | HTTP status code enum (100-599)              |
| `HttpException.kt`    | `http.response.exception` | public | Abstract base for typed HTTP exceptions      |
| `HttpExceptions.kt`   | `http.response.exception` | public | One concrete subclass per canonical status   |
| `NetworkException.kt` | `http.response.exception` | public | Transport-level failure (IOException sibling)|
| `HttpExceptionFactory.kt` | `http.response.exception` | public | `Response` → typed exception dispatcher  |
| `Headers.kt`          | `http.common`   | public     | Immutable multi-map + builder                |
| `MediaType.kt`        | `http.common`   | public     | Parsed MIME type with charset extraction     |
| `CommonMediaTypes.kt` | `http.common`   | public     | Media type constants                         |
| `Protocol.kt`         | `http.common`   | public     | HTTP protocol version enum                   |
| `CallContext.kt`      | `http.context`  | public     | Base context interface                       |
| `DispatchContext.kt`  | `http.context`  | public     | Pre-request context                          |
| `RequestContext.kt`   | `http.context`  | public     | Request-scoped context                       |
| `ExchangeContext.kt`  | `http.context`  | public     | Full exchange context (request + response)   |
| `ContextStore.kt`     | `http.context`  | public     | Global trace-ID-keyed context store          |
| `HttpClient.kt`       | `client`        | public     | Transport abstraction interface              |
