# HTTP Body Logging and Concurrency

This document covers the design of the SDK's HTTP body abstractions, the body logging system,
and the concurrency decisions behind them.

## Table of Contents

- [Overview](#overview)
- [HTTP Body Abstractions](#http-body-abstractions)
    - [RequestBody](#requestbody)
    - [ResponseBody](#responsebody)
    - [Why Not Okio](#why-not-okio)
- [Body Logging System](#body-logging-system)
    - [Architecture](#architecture)
    - [BodySnapshot — Full Capture](#bodysnapshot--full-capture)
    - [LoggableRequestBody — Tee-Write Strategy](#loggablerequestbody--tee-write-strategy)
    - [LoggableResponseBody — Eager Buffering Strategy](#loggableresponsebody--eager-buffering-strategy)
    - [Segmented Streaming](#segmented-streaming)
- [Internal Stream Utilities](#internal-stream-utilities)
- [Concurrency Design](#concurrency-design)
    - [Why ReentrantLock Over synchronized](#why-reentrantlock-over-synchronized)
    - [Double-Checked Locking with Volatile](#double-checked-locking-with-volatile)
    - [Per-Concurrency-Model Guidance](#per-concurrency-model-guidance)
    - [Why Not Coroutine Mutex or Reactive Operators](#why-not-coroutine-mutex-or-reactive-operators)
- [Edge Cases and Safety](#edge-cases-and-safety)
- [Usage Examples](#usage-examples)

---

## Overview

The SDK provides a zero-dependency HTTP body abstraction layer built entirely on `java.io`
APIs. It targets **JDK 8+** compatibility while being safe to use from platform threads,
virtual threads (Project Loom), Kotlin coroutines, and reactive streams.

The body logging system wraps request and response bodies to capture their content for
diagnostics without consuming the underlying streams or altering the HTTP data flow.

### Key source files

| File                      | Package         | Purpose                                                      |
|---------------------------|-----------------|--------------------------------------------------------------|
| `RequestBody.kt`          | `http.request`  | Abstract request body with factory methods                   |
| `ResponseBody.kt`         | `http.response` | Abstract response body with `bytes()`/`string()`             |
| `LoggableRequestBody.kt`  | `http.logging`  | Tee-write wrapper + internal stream utilities                |
| `LoggableResponseBody.kt` | `http.logging`  | Eager-buffering wrapper with thread-safe init                |
| `BodySnapshot.kt`         | `http.logging`  | Immutable captured content with text detection               |
| `BodySegment.kt`          | `http.logging`  | Segment data class + handler interface                       |
| `io/*.kt`                 | `io`            | Segment-based memory stream system (see [I/O Module](io.md)) |

---

## HTTP Body Abstractions

### RequestBody

`RequestBody` is an abstract class with a single core method:

```kotlin
abstract fun writeTo(stream: OutputStream)
```

**Design decision: `OutputStream` over Okio's `BufferedSink`.**

The `writeTo` contract pushes bytes to an `OutputStream` provided by the HTTP transport.
This is the standard JDK abstraction for byte output, understood by every HTTP client
implementation (HttpURLConnection, Apache HC, Jetty, etc.).

Factory methods produce two variants:

| Factory                    | Reusable        | Backing                                        |
|----------------------------|-----------------|------------------------------------------------|
| `create(inputStream, ...)` | No (single-use) | `InputStream` consumed and closed via `use {}` |
| `create(bytes, ...)`       | Yes             | `ByteArray` written directly                   |
| `create(content, ...)`     | Yes             | Delegates to `ByteArray` factory               |
| `create(formData, ...)`    | Yes             | URL-encoded, delegates to `ByteArray` factory  |

The `InputStream`-based factory uses a manual 8 KB buffer copy loop instead of
`InputStream.transferTo()` because `transferTo` is a Java 9+ API and the SDK targets
JDK 8. The 8 KB buffer size matches what `transferTo` uses internally.

### ResponseBody

`ResponseBody` is an abstract `Closeable` class:

```kotlin
abstract fun byteStream(): InputStream
fun bytes(): ByteArray      // reads all + closes
fun string(charset): String // reads all + closes
```

**Design decision: `InputStream` over Okio's `BufferedSource`.**

The response body exposes a `BufferedInputStream` for efficient reads. The `bytes()` and
`string()` convenience methods drain the stream and close the body in one call, following
the same pattern as OkHttp's `ResponseBody`.

**Single-use contract**: `byteStream()` returns the same stream instance. Once consumed,
the bytes are gone. For repeatable access, use `LoggableResponseBody`.

### Why Not Okio

The original implementation used Okio 3.x (`BufferedSink`, `BufferedSource`, `ByteString`,
`Source`). It was removed for the following reasons:

1. **Dependency cost**: Okio pulls in Kotlin stdlib multiplatform artifacts and adds ~300 KB
   to the SDK. For a core HTTP abstraction, this is unnecessary weight.

2. **JDK sufficiency**: Java's `InputStream`, `OutputStream`, `BufferedInputStream`, and
   `ByteArrayOutputStream` provide equivalent buffering and performance. The 8 KB copy loop
   matches Okio's internal buffer size.

3. **Interoperability**: Every JVM HTTP client speaks `InputStream`/`OutputStream`. Okio
   types require adaptation at integration boundaries, adding friction for SDK consumers.

4. **JDK 8 compatibility**: The replacement uses only `java.io` APIs available since JDK 1.0.

---

## Body Logging System

### Architecture

The logging system provides **non-destructive observation** of HTTP body content through
two complementary strategies:

```
                        ┌─────────────────────────────────────────────┐
                        │           Observation Strategies            │
                        ├──────────────────────┬──────────────────────┤
                        │   Snapshot Mode      │   Segment Mode       │
                        │   (full capture)     │   (streaming chunks) │
                        ├──────────────────────┼──────────────────────┤
                        │ Memory: full body    │ Memory: 1 segment    │
                        │ Access: after write  │ Access: during write │
                        │ Output: BodySnapshot │ Output: callbacks    │
                        └──────────────────────┴──────────────────────┘
```

Both strategies can be active simultaneously on the same body.

### BodySnapshot — Full Capture

`BodySnapshot` is an immutable container for captured body bytes. It provides:

- **Text detection** via media type analysis (`text/*`, `application/json`, `+json`, `+xml`,
  `x-www-form-urlencoded`)
- **Charset-aware decoding** using the media type's `charset` parameter, falling back to UTF-8
- **Preview generation** that produces human-readable log output:
    - `[empty]` for empty bodies
    - `[binary 1024 bytes]` for non-text content
    - Decoded and optionally truncated text for textual content

### LoggableRequestBody — Tee-Write Strategy

**Problem**: Capture request body bytes for logging without consuming the body or altering
the write to the HTTP transport.

**Solution**: Tee-write. When `writeTo(stream)` is called, bytes flow through a
`TeeOutputStream` that writes to both the real output stream and a capture branch
simultaneously:

```
delegate.writeTo(...)
      │
      ▼
TeeOutputStream
      │                    │
      ▼ primary            ▼ branch
  Real OutputStream    Buffer.outputStream()
  (HTTP transport)         │
                           ▼
                    Buffer (segment-based)
                     ├─► snapshot()          → BodySnapshot (full body)
                     ├─► emitBodySegments()  → BodySegmentHandler (if configured)
                     └─► clear()             → recycle segments
```

**Why tee-write over pre-buffering?**

An alternative approach would buffer the entire body first, then write from the buffer.
Tee-write is superior because:

1. It preserves single-use semantics — `InputStream`-backed bodies work correctly.
2. It doesn't double the write cost for reusable bodies.
3. Bytes reach the transport in real-time, preserving streaming behavior.
4. The capture is a side effect of the write, not a prerequisite.

### LoggableResponseBody — Eager Buffering Strategy

**Problem**: Log response body content without consuming the stream that the caller needs.

**Solution**: Eager buffering. On first access, the entire delegate body is drained into
a segment-based `Buffer` and the delegate is closed. All subsequent reads are served from
the buffer:

```
First access (byteStream / bytes / string / snapshot):
  delegate.byteStream() ──read──► Buffer.readFrom(inputStream) ──► buffer: Buffer
  delegate.close()                (drains into pooled segments)       │
                                                                     ▼
Subsequent access:                                         buffer.readOnlyInputStream()
  Non-consuming InputStream with independent cursor        (no copy, no consumption)
```

**Why eager buffering over tee-read?**

For responses, a tee-read (wrapping the InputStream) would require the consumer to read
the body to trigger the observation — if the consumer never reads, nothing is logged.
Eager buffering ensures the body is always available for logging regardless of whether
the consumer reads it.

**Trade-off**: The full response is held in memory. This is acceptable for API responses
(typically JSON, < 1 MB) but not suitable for streaming large downloads. The class
documents this constraint.

**Why `byteStream()` returns a new read-only `InputStream` each time:**

Each call returns an independent, non-consuming `InputStream` over the buffer's segments
(via `Buffer.readOnlyInputStream()`). This turns a single-use body into a repeatable one —
the caller can read the body multiple times without re-fetching or copying.

### Segmented Streaming

For streaming the body to external systems (telemetry, audit logs) with bounded memory
per chunk, a `BodySegmentHandler` callback processes the body in fixed-size segments
(default 8 KB). Each segment carries metadata for reconstruction:

```kotlin
class BodySegment(
    val data: ByteArray,  // segment bytes (final segment may be shorter)
    val offset: Long,     // byte offset within the full body
    val index: Int,       // 0-based segment index
    val isLast: Boolean   // true for the final segment
)
```

**Memory model**: Only one segment exists in memory at a time. After the handler returns,
the segment buffer is reset. Total memory usage for observation is bounded to `segmentSize`
bytes regardless of body size.

**Error isolation**: Handler exceptions are caught and suppressed. A failing observer
(e.g., a logger that loses its disk connection) never disrupts the HTTP data flow.

**Segment mode coexists with snapshot mode**: The snapshot captures the full body for
quick log lines via `preview()`; the segment handler streams every byte to an external
system with bounded per-segment memory.

---

## Internal Stream Utilities

The write pipeline uses two internal `OutputStream` implementations plus the `Buffer`-based
I/O system (see [I/O Module](io.md)):

### TeeOutputStream

Duplicates every write to a `primary` and a `branch` stream. Closing flushes both but
**does not close either** — each stream's lifecycle is managed by its owner.

**Design decision**: The tee doesn't close its streams because:

- The primary stream is owned by the HTTP transport.
- The branch stream is owned by the logging wrapper.
- Premature close of the primary would corrupt the HTTP connection.

### Buffer-Based Capture

`LoggableRequestBody` uses `Buffer.outputStream()` as the branch stream of the
`TeeOutputStream`, capturing the entire body into pooled segments. After the write
completes:

1. `Buffer.snapshot()` creates a `BodySnapshot` with the full body
2. If a segment handler is configured, `emitBodySegments(buffer, handler, segmentSize)`
   iterates the buffer's segments and emits them as `BodySegment` callbacks
3. `buffer.clear()` recycles all segments back to the pool

### Segment Emission

The `emitBodySegments()` function in `BodySegment.kt` iterates a `Buffer`'s segments via
`buffer.forEach()` and re-chunks them into `BodySegment` callbacks. Chunks are bounded by
both the configured segment size and the buffer's internal 8 KiB segment boundaries.
Handler exceptions are caught and suppressed to protect the primary data flow.

---

## Concurrency Design

### Why ReentrantLock Over synchronized

`LoggableResponseBody` uses `ReentrantLock` to guard the one-time buffering of the
delegate body. This was chosen over `synchronized` for virtual thread compatibility:

| Aspect                  | `synchronized`                                    | `ReentrantLock`                                  |
|-------------------------|---------------------------------------------------|--------------------------------------------------|
| Platform threads        | Monitor lock                                      | `AbstractQueuedSynchronizer`                     |
| Virtual thread behavior | **Pins** the virtual thread to its carrier thread | Virtual thread **unmounts**, freeing the carrier |
| JDK availability        | JDK 1.0+                                          | JDK 5+ (within JDK 8 target)                     |
| Kotlin idiom            | `synchronized(lock) { }`                          | `lock.withLock { }`                              |

**Carrier pinning explained**: When a virtual thread enters a `synchronized` block, the
JVM pins it to the carrier platform thread for the duration of the critical section. Other
virtual threads waiting for the same carrier are starved. `ReentrantLock` uses
`LockSupport.park()` internally, which the JVM recognizes as a yield point — the virtual
thread unmounts, and the carrier is free to run other virtual threads.

### Double-Checked Locking with Volatile

The buffering initialization uses double-checked locking:

```kotlin
@Volatile
private var buffer: Buffer? = null

private fun ensureBuffered() {
    if (buffer != null) return         // Fast path: no lock, volatile read
    lock.withLock {
        if (buffer != null) return     // Re-check under lock
        try {
            delegate.use { buffer = drain(it.byteStream()) }
        } catch (e: Throwable) {
            closed = true              // Delegate closed by use{}; prevent confusing retry
            throw e
        }
    }
}
```

**Why this pattern?**

1. **Fast path**: After initialization, `buffer != null` is a single volatile read with
   no locking. This is the common case — the body is read once, then accessed many times.
2. **Safety**: The `@Volatile` annotation ensures the buffer reference and the array
   contents are visible to all threads after the lock is released (happens-before via
   volatile write).
3. **Single initialization**: The re-check inside `withLock` prevents duplicate drains
   when two threads race on the first access.

### Per-Concurrency-Model Guidance

The core HTTP body classes use blocking `java.io` streams. No lock or concurrency primitive
can make `InputStream.read()` non-blocking. The lock choice affects only the initialization
guard, not the I/O itself.

#### Platform threads

Both `synchronized` and `ReentrantLock` work correctly. The lock is held only during the
one-time initialization; subsequent accesses are lock-free via the volatile fast path.

#### Virtual threads (Project Loom, JDK 21+)

`ReentrantLock` is the correct choice. During the one-time buffer initialization:

1. The lock acquisition uses `LockSupport.park()` — if contended, the virtual thread
   unmounts from its carrier, freeing it for other virtual threads.
2. The `InputStream.read()` calls within `drain()` also unmount (JDK 21+ recognizes I/O
   operations on `java.io` streams as yield points).

With `synchronized`, both the lock and the I/O would pin the carrier thread.

#### Kotlin coroutines

The SDK core module intentionally does **not** depend on `kotlinx-coroutines`. Callers
using coroutines should:

- Dispatch body reads to `Dispatchers.IO` (or a `limitedParallelism` dispatcher).
- Both the lock and the `InputStream` I/O will block the dispatcher thread, which is
  expected behavior on `Dispatchers.IO`.
- If running coroutines on virtual thread dispatchers (JDK 21+), `ReentrantLock` avoids
  carrier pinning.

#### Reactive streams (Project Reactor, RxJava)

Callers should wrap body access in a blocking-compatible scheduler:

```java
// Project Reactor
Mono.fromCallable(() ->loggable.

snapshot())
        .

subscribeOn(Schedulers.boundedElastic())

// RxJava
        Single.

fromCallable(() ->loggable.

snapshot())
        .

subscribeOn(Schedulers.io())
```

The blocking is inherent to `InputStream` — no lock choice can eliminate it.

### Why Not Coroutine Mutex or Reactive Operators

**`kotlinx.coroutines.sync.Mutex`** suspends instead of blocking, which would be ideal
for coroutine callers. However:

1. Adding `kotlinx-coroutines` as a dependency to the core SDK module would force it on
   all consumers, including pure Java projects that don't use coroutines.
2. `Mutex` requires a `suspend` function context. Changing `byteStream()` and `snapshot()`
   to `suspend fun` would break the `ResponseBody` contract and make the class unusable
   from Java.

The recommended pattern for coroutine-native APIs is a separate `sdk-coroutines` extension
module that wraps the core classes with `suspend` functions and `Flow` types. The core
module remains blocking-friendly for the widest compatibility.

**Reactive types** (`Mono`, `Flux`, `Single`, `Observable`) follow the same reasoning —
they belong in optional extension modules (`sdk-reactor`, `sdk-rxjava`), not the core.

---

## Edge Cases and Safety

| Edge Case                               | Handling                                                                         |
|-----------------------------------------|----------------------------------------------------------------------------------|
| Empty body                              | `BodySnapshot.isEmpty` returns `true`; `preview()` returns `"[empty]"`           |
| Null media type                         | `BodySnapshot.isTextual` returns `false`; charset defaults to UTF-8              |
| Unknown charset in media type           | `MediaType.charset` returns `null`; falls back to UTF-8                          |
| Binary content                          | `preview()` returns `"[binary N bytes]"` instead of garbled text                 |
| Large body                              | Full body captured into pooled segments; `preview()` truncates for display only  |
| Double `close()`                        | Idempotent — `LoggableResponseBody.close()` checks `closed` flag under lock      |
| `close()` before read                   | Delegate is closed, releasing the connection                                     |
| `close()` after read                    | Buffer segments recycled via `clear()`; delegate already closed during buffering |
| Concurrent first access                 | `ReentrantLock` ensures exactly one thread drains the delegate                   |
| Segment handler throws                  | Exception caught and suppressed; primary data flow unaffected                    |
| `close()` then `byteStream()`           | `ensureBuffered()` throws `IllegalStateException("closed")`                      |
| `writeTo()` on single-use `RequestBody` | `InputStream` is consumed and closed via `use {}`; second call fails             |
| `writeTo()` on reusable `RequestBody`   | `ByteArray`-backed; safe to call multiple times                                  |

---

## Usage Examples

### Basic request body logging

```kotlin
val body = RequestBody.create("""{"name": "test"}""", MediaType.parse("application/json"))
val loggable = LoggableRequestBody(body)

// Pass to HTTP client — writeTo is called internally
loggable.writeTo(connection.outputStream)

// Log after write
val snapshot = loggable.snapshot()
logger.debug("Request body: {}", snapshot?.preview())
// Output: Request body: {"name": "test"}
```

### Basic response body logging

```kotlin
val loggable = LoggableResponseBody(response.body!!)

// Log the response
logger.debug("Response: {}", loggable.snapshot().preview())

// Body is still fully available (repeatable reads)
val data = loggable.bytes()
```

### Segment handler for large payloads

```kotlin
val handler = BodySegmentHandler { segment ->
    auditLog.write(segment.data)
    if (segment.isLast) {
        auditLog.flush()
        logger.info(
            "Body fully logged: {} segments, {} bytes",
            segment.index + 1, segment.offset + segment.size
        )
    }
}

// Request — segments emitted during writeTo
val loggable = LoggableRequestBody(body, segmentHandler = handler)
loggable.writeTo(outputStream)

// Response — segments emitted during buffering
val loggable = LoggableResponseBody(responseBody, segmentHandler = handler)
val json = loggable.string()
```

### Snapshot + segments together

```kotlin
val handler = BodySegmentHandler { segment ->
    telemetry.recordBodyChunk(traceId, segment.index, segment.data)
}

val loggable = LoggableResponseBody(
    delegate = response.body!!,
    segmentHandler = handler,          // full body streamed to telemetry
    segmentSize = 16 * 1024            // 16 KB segments
)

// Quick preview in application log
logger.debug("Response preview: {}", loggable.snapshot().preview())

// Full body available for parsing
val result = deserializer.deserialize(loggable.byteStream(), MyType::class.java)
```

### Coroutine context

```kotlin
// Dispatch to IO because body I/O is blocking
val result = withContext(Dispatchers.IO) {
    val loggable = LoggableResponseBody(response.body!!)
    logger.debug("Response: {}", loggable.snapshot().preview())
    loggable.string()
}
```

### Reactive context (Project Reactor)

```java
Mono.fromCallable(() ->{
LoggableResponseBody loggable = new LoggableResponseBody(response.body());
    logger.

debug("Response: {}",loggable.snapshot().

preview());
        return loggable.

string();
})
        .

subscribeOn(Schedulers.boundedElastic())
        .

subscribe(result ->

process(result));
```
