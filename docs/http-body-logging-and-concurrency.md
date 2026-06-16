# HTTP Body Logging and Concurrency

This document covers the design of the SDK's HTTP body abstractions, the body logging system,
and the concurrency decisions behind them.

## Table of Contents

- [Overview](#overview)
- [HTTP Body Abstractions](#http-body-abstractions)
    - [RequestBody](#requestbody)
    - [ResponseBody](#responsebody)
    - [The I/O Seam](#the-io-seam)
- [Body Logging System](#body-logging-system)
    - [Architecture](#architecture)
    - [LoggableRequestBody — Tee-Write Strategy](#loggablerequestbody--tee-write-strategy)
    - [LoggableResponseBody — Drain-Once Strategy](#loggableresponsebody--drain-once-strategy)
    - [Logged body size vs. the body the consumer receives](#logged-body-size-vs-the-body-the-consumer-receives)
    - [Reading a Snapshot](#reading-a-snapshot)
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

The SDK's HTTP bodies are built on its own Okio-inspired I/O abstraction rather than directly
on `java.io`. `sdk-core` defines the interfaces — `Source`/`Sink`, `BufferedSource`/`BufferedSink`,
`Buffer`, `TeeSink` — and carries zero runtime dependencies; a concrete implementation plugs in
through a single `IoProvider` seam. The reference provider is `OkioIoProvider` in `sdk-io-okio3`,
backed by Okio 3.x. Because `sdk-core` codes against the abstraction, the body layer keeps the
**JDK 8+** target while staying safe to use from platform threads, virtual threads (Project
Loom), Kotlin coroutines, and reactive streams.

The body logging system wraps request and response bodies to capture their content for
diagnostics without consuming the underlying data or altering the HTTP data flow.

### Key source files

| File                      | Package         | Purpose                                                          |
|---------------------------|-----------------|------------------------------------------------------------------|
| `RequestBody.kt`          | `http.request`  | Abstract request body with factory methods (`writeTo(BufferedSink)`) |
| `ResponseBody.kt`         | `http.response` | Abstract response body exposing `source(): BufferedSource`       |
| `LoggableRequestBody.kt`  | `http.request`  | Tee-write wrapper that taps request bytes into a `Buffer`        |
| `LoggableResponseBody.kt` | `http.response` | Drain-once wrapper with thread-safe init and repeatable reads    |
| `TeeSink.kt`              | `io`            | `BufferedSink` that mirrors each write into a tap `Buffer`       |
| `io/*.kt`                 | `io`            | Buffer-centric memory stream system (see [I/O Module](io.md))    |

---

## HTTP Body Abstractions

### RequestBody

`RequestBody` is an abstract class with a single core method:

```kotlin
abstract fun writeTo(sink: BufferedSink)
```

**Design decision: `BufferedSink` over a raw `OutputStream`.**

The `writeTo` contract pushes bytes to a `BufferedSink` provided by the transport. The sink is
one of the SDK's own I/O interfaces, so typed writes (`writeUtf8`, `writeString`, `write(ByteArray)`,
`writeAll(source)`) and buffer-to-buffer segment moves are available without forcing every body
to think in terms of a byte-at-a-time stream. Transports translate the sink to their native
output once, at the boundary.

Factory methods cover the common body shapes:

| Factory                        | Replayable                | Backing                                                        |
|--------------------------------|---------------------------|---------------------------------------------------------------|
| `create(source: BufferedSource, …)` | No (single-use)      | `BufferedSource` drained and closed by `writeTo`              |
| `create(buffer: Buffer, …)`    | Yes                       | In-memory `Buffer`, read via non-consuming `peek()`           |
| `create(bytes: ByteArray, …)`  | Yes                       | `ByteArray` written directly                                  |
| `create(content: String, …)`   | Yes                       | Encoded to a `ByteArray` at construction                      |
| `create(input: InputStream, length, …)` | Conditional         | Replayable when the stream supports `mark/reset` and `length` fits in a mark `readLimit`; otherwise single-use |
| `create(file: Path, …)`        | Yes                       | `FileRequestBody`; transports may dispatch a zero-copy `sendfile(2)` |
| `create(formData: Map, …)`     | Yes                       | URL-encoded once, delegates to the `ByteArray` shape          |

Single-use bodies (`BufferedSource`-backed, non-resettable `InputStream`-backed) guard against
a second `writeTo` with an `AtomicBoolean` and throw `IllegalStateException` rather than silently
emitting zero bytes. `isReplayable()` reports whether `writeTo` is safe to call again, and
`toReplayable(provider)` drains a single-use body once into an in-memory `Buffer` and returns a
buffer-backed replayable equivalent — call it **before** `writeTo` when retries may be needed.

### ResponseBody

`ResponseBody` is an abstract `Closeable` class with a single read entry point:

```kotlin
abstract fun source(): BufferedSource
```

**Design decision: `BufferedSource` over a raw `InputStream`.**

The response body exposes a `BufferedSource` for efficient reads. A `BufferedSource` can be
adapted to a `java.io.InputStream` (`source().inputStream()`) when a caller needs the JDK type,
so nothing is lost by speaking the SDK's I/O abstraction at the contract.

**Single-use contract**: `source()` returns the same source instance, and once consumed the
bytes are gone. The body **must be closed** after use to release the underlying connection —
prefer `use {}`. For repeatable access, wrap with `LoggableResponseBody`.

### The I/O Seam

Both bodies are written against `sdk-core`'s I/O interfaces, never a concrete implementation.
The single `IoProvider` is wired once at startup via `Io.installProvider(provider)`; touching
`Io.provider` before a provider is installed throws `IllegalStateException` with the install
instruction. Production code installs the provider in the application startup path; tests use
the test-fixtures helper `org.dexpace.sdk.core.testing.withProvider` for scoped overrides. The
only shipping implementation today is `OkioIoProvider` (`sdk-io-okio3`), backed by Okio 3.x.
This is what keeps `sdk-core` dependency-free: the Okio dependency lives entirely in the adapter
module. See the [I/O Module](io.md) document for the segment model behind `Buffer`.

---

## Body Logging System

### Architecture

The logging system provides **non-destructive observation** of HTTP body content. Requests and
responses use complementary strategies, but both funnel captured bytes into a `Buffer` and
expose them through `snapshot()`:

- **`LoggableRequestBody`** taps bytes as they stream to the transport (tee-write), so observation
  is a side effect of the normal write and never doubles a single-use body.
- **`LoggableResponseBody`** drains the response once into a `Buffer` and serves every subsequent
  read from that buffer, turning a single-use body into a repeatable one.

In both cases the captured bytes are surfaced as a raw `ByteArray` via `snapshot()` /
`snapshot(maxBytes)`. The wrappers do not interpret the bytes — charset resolution and text
detection are the caller's job (the request/response `MediaType` exposes `charset` for that).

### LoggableRequestBody — Tee-Write Strategy

**Problem**: Capture request body bytes for logging without consuming the body or altering the
write to the HTTP transport.

**Solution**: Tee-write. When `writeTo(sink)` is called, the wrapper clears its tap buffer and
then drives the delegate's `writeTo` through a `TeeSink`, which mirrors every write into both the
transport sink and an internal tap `Buffer`:

```
delegate.writeTo(...)
      │
      ▼
   TeeSink
      │                    │
      ▼ primary            ▼ tap
  Transport BufferedSink   Buffer (segment-based)
  (HTTP transport)              │
                                ▼
                         snapshot()         → ByteArray (full captured body)
                         snapshot(maxBytes) → ByteArray (bounded prefix)
```

**Why tee-write over pre-buffering?**

An alternative approach would buffer the entire body first, then write from the buffer.
Tee-write is superior because:

1. It preserves single-use semantics — `BufferedSource`-backed bodies work correctly.
2. It doesn't double the write cost for reusable bodies.
3. Bytes reach the transport as they are produced, preserving streaming behavior.
4. The capture is a side effect of the write, not a prerequisite.

**Multi-attempt safety**: each `writeTo` clears the tap before capturing, so a retry against a
replayable delegate keeps the snapshot in lock-step with the most recent attempt rather than
accumulating doubled bytes. If the delegate's `writeTo` throws partway, `snapshot()` returns the
bytes tee'd up to the failure point — useful for diagnosing a failed request. The wrapper mirrors
the delegate's replayability: `isReplayable()` delegates, and `toReplayable()` returns a new
`LoggableRequestBody` wrapping the delegate's replayable form.

### LoggableResponseBody — Bounded-Capture Strategy

**Problem**: Log response body content without consuming the stream that the caller needs, and
without buffering an arbitrarily large body into memory.

**Solution**: Drain a bounded prefix on first access. The wrapper reads at most `maxCaptureBytes`
bytes from the delegate into an internal `Buffer`; what happens next depends on whether the body
fit within that cap:

```
First access (source / snapshot / captureException):
  read up to maxCaptureBytes from delegate.source() ──► Buffer (the capture prefix)

  Body fit within the cap (default, unlimited):
    delegate closed; capture is the whole body
        │
        ▼
    Subsequent access:  captured.peek()   (fresh non-consuming view, fully repeatable)

  Body exceeded the cap:
    delegate left OPEN; only the prefix is buffered
        │
        ▼
    source() (once):  captured.peek()  ─then─►  live delegate tail
                      (one-shot: replays the prefix, then continues from the wire)
```

When the whole body fits within `maxCaptureBytes` the behavior is the classic drain-once: the
delegate is closed and every `source()` call returns a fresh `peek()` view. When the body is
larger than the cap, only the preview prefix is buffered, the delegate stays open, and `source()`
returns a **one-shot** stream that replays the captured prefix and then continues from the live
delegate tail — so the caller still receives the complete body. Because the tail is single-use, a
second `source()` call on an over-cap body throws `IllegalStateException`.

The default `maxCaptureBytes` is `Long.MAX_VALUE` (unbounded, fully repeatable). The
instrumentation steps construct the wrapper via the internal `bounded(...)` seam capped at the
configured `bodyPreviewMaxBytes`, so logging never buffers more than a preview while the caller
still streams the rest.

**Why bound the capture rather than always drain?**

A tee-read (wrapping the source) would require the consumer to read the body to trigger
observation — if the consumer never reads, nothing is logged. Draining a bounded prefix ensures a
preview is always available for logging regardless of whether the consumer reads, while keeping
memory bounded and avoiding a hang on a large or unbounded streaming body.

**Trade-off**: only the preview prefix is held in memory. For a body within the cap the capture is
the whole body and reads are fully repeatable; for a larger body the wrapper is single-consumer
(the live tail can be read only once). `snapshot(maxBytes)` returns a bounded prefix of the
capture, leaving the capture intact.

**`contentLength()`**: returns the captured size only when the body fit within the cap (then the
capture *is* the body); otherwise it returns the delegate's true length, since the capture is just
a prefix.

**Failure semantics**: a network error during the drain does not silently truncate. The wrapper
retains whatever bytes were read before the failure and caches the exception:

- `source()` **re-throws** the cached exception every time, so callers see the failure
  deterministically (no silent zero-byte body).
- `snapshot()` / `snapshot(maxBytes)` **always return** the partial bytes — useful for
  post-mortem logging that records "what we got" alongside the exception.
- `captureException` surfaces the cached exception (or `null`) without triggering a drain.

### Logged body size vs. the body the consumer receives

When `HttpLogLevel.BODY_AND_HEADERS` is enabled, the instrumentation step
(`DefaultInstrumentationStep` / `DefaultAsyncInstrumentationStep`) wraps the response body in a
`LoggableResponseBody` bounded to `HttpInstrumentationOptions.bodyPreviewMaxBytes` (default
8 KiB, `DEFAULT_BODY_PREVIEW_MAX_BYTES`). Two consequences follow that are easy to miss when
reading the logs:

**1. The body delivered downstream can be larger than the logged preview.** The cap bounds only
the in-memory *capture*, not the body. For a response larger than `bodyPreviewMaxBytes`, the
step buffers the preview prefix and the wrapper then streams the full body to the consumer — it
replays the captured prefix and continues from the live tail (see the bounded-capture diagram
above). The preview you see in the log is a prefix; the consumer still reads every byte.

**2. The logged size fields measure different things.** The step emits two size-related fields
on the `http.response` event, and they are not the same number for an over-cap body:

| Field                     | Source                                       | What it reports                                                                 |
|---------------------------|----------------------------------------------|---------------------------------------------------------------------------------|
| `response.body.size`      | `loggableBody.snapshot(bodyPreviewMaxBytes)` | Size of the **captured preview** — bounded by `bodyPreviewMaxBytes`             |
| `response.body.preview`   | the same captured bytes, decoded as UTF-8    | The preview text (a prefix for an over-cap body)                                |
| `response.content.length` | `response.body.contentLength()`              | The body's **true** length when the origin declared one (`Content-Length`); `-1` for unknown-length / streaming bodies |

So `response.body.size` is the *captured/preview* size, **not** necessarily the full body size.
When a body exceeds the cap, `response.body.size` saturates near `bodyPreviewMaxBytes` while
`response.content.length` still shows the real length. Read `content.length` (not
`body.size`) when you need the full size, and treat `body.preview` as a prefix that may be
truncated. The two agree only when the whole body fit within the cap — exactly the case where
`contentLength()` itself returns the captured size (see **`contentLength()`** above).

**Streaming / unknown-length bodies (async path).** `DefaultAsyncInstrumentationStep` skips the
capture entirely when `contentLength() < 0`, because the bounded drain would run on the
future-completion thread and a slow producer could stall it. Such bodies stream to the consumer
unwrapped, so they carry **no** `response.body.size` / `response.body.preview` fields at all —
absence of those fields is expected for chunked or streaming responses, not a logging bug. The
synchronous `DefaultInstrumentationStep` drains known-length and unknown-length bodies alike (it
runs on the caller's thread), but the size-vs-preview distinction above applies to it just the
same.

### Reading a Snapshot

The only logging output is a raw `ByteArray`:

```kotlin
fun snapshot(): ByteArray            // the full captured prefix (the whole body when it fit the cap)
fun snapshot(maxBytes: Int): ByteArray   // a bounded slice of the capture, leaving it intact
```

`snapshot()` throws `IllegalStateException` if the captured size exceeds
`Buffer.MAX_BYTE_ARRAY_SIZE` (a single `ByteArray` can't hold it); reach for `snapshot(maxBytes)`
whenever the body may be unbounded. `maxBytes` is silently clamped to `MAX_BYTE_ARRAY_SIZE`, so
passing `Int.MAX_VALUE` on a multi-gigabyte body still yields a safely bounded array. Decoding
the bytes to text for a log line is left to the caller, who can consult the body's `MediaType.charset`.

---

## Internal Stream Utilities

The request-logging write path is built on `TeeSink` plus the `Buffer`-based I/O system (see
[I/O Module](io.md)).

### TeeSink

`TeeSink` (package `io`) is a `BufferedSink` that mirrors every write into a tap `Buffer` while
forwarding to a primary sink. Typed writes (`writeUtf8`, `writeString`, `write(ByteArray)`) are
encoded **once** into a reused scratch buffer, then copied non-destructively into the tap and
drained destructively into the primary. On an Okio-backed `Buffer` both moves are segment-level
operations, so the cost is one encode plus two segment moves regardless of payload size.

`TeeSink.close()` closes the primary sink; the tap is owned by `LoggableRequestBody` and is not a
network resource. Direct buffer access (`tee.buffer`) is unsupported and throws — writing into the
backing buffer would reach only the tap, silently corrupting the wire body. Callers must use the
typed `write*` methods so bytes reach both sinks.

`TeeSink` accepts a `tapLimit` (default `Long.MAX_VALUE`) that bounds how many bytes are mirrored
into the tap. Once the limit is reached the tee stops copying into the tap entirely but keeps
forwarding the **full** payload to the primary — the wire body is never truncated. The
instrumentation steps build the request wrapper via `LoggableRequestBody.bounded(...)` with the
limit set to `bodyPreviewMaxBytes`, so a multi-GB `FileRequestBody` upload mirrors only a bounded
preview into memory while streaming zero-copy to the transport.

### Buffer-Based Capture

`LoggableRequestBody` allocates its tap `Buffer` lazily (only on first write or snapshot) from
the installed `IoProvider`. The delegate's `writeTo` runs through the `TeeSink`, after which
`snapshot()` / `snapshot(maxBytes)` read the captured bytes out of the tap. The tap is cleared at
the start of each `writeTo` so retries don't accumulate stale bytes.

`LoggableResponseBody` drains the delegate into a captured `Buffer` once, behind a
double-checked-locking guard, and serves repeatable reads via `Buffer.peek()`.

---

## Concurrency Design

### Why ReentrantLock Over synchronized

`LoggableResponseBody` uses `ReentrantLock` to guard the one-time draining of the delegate body.
This was chosen over `synchronized` for virtual thread compatibility:

| Aspect                  | `synchronized`                                    | `ReentrantLock`                                  |
|-------------------------|---------------------------------------------------|--------------------------------------------------|
| Platform threads        | Monitor lock                                      | `AbstractQueuedSynchronizer`                     |
| Virtual thread behavior | **Pins** the virtual thread to its carrier thread | Virtual thread **unmounts**, freeing the carrier |
| JDK availability        | JDK 1.0+                                           | JDK 5+ (within JDK 8 target)                     |
| Kotlin idiom            | `synchronized(lock) { }`                          | `lock.withLock { }`                              |

**Carrier pinning explained**: When a virtual thread enters a `synchronized` block, the
JVM pins it to the carrier platform thread for the duration of the critical section. Other
virtual threads waiting for the same carrier are starved. `ReentrantLock` uses
`LockSupport.park()` internally, which the JVM recognizes as a yield point — the virtual
thread unmounts, and the carrier is free to run other virtual threads.

### Double-Checked Locking with Volatile

The drain initialization uses double-checked locking:

```kotlin
@Volatile
private var captured: Buffer? = null

private fun ensureCaptured(): Buffer {
    captured?.let { return it }        // Fast path: no lock, volatile read
    return lock.withLock {
        captured?.let { return it }    // Re-check under lock
        check(!closed) { "LoggableResponseBody was closed before the body was read; nothing to capture." }
        drainAndCache()                // closes the delegate via use {}; caches partial bytes + error on failure
    }
}
```

**Why this pattern?**

1. **Fast path**: After draining, `captured != null` is a single volatile read with no locking.
   This is the common case — the body is drained once, then read many times.
2. **Safety**: The `@Volatile` annotation ensures the captured buffer reference (and the bytes it
   holds) is visible to all threads after the lock is released (happens-before via the volatile
   write).
3. **Single initialization**: The re-check inside `withLock` prevents duplicate drains when two
   threads race on the first access.

A drain failure is recorded rather than retried: `drainAndCache` caches the partial buffer and the
thrown exception in `@Volatile` fields, so `source()` re-throws deterministically and `snapshot()`
returns the partial bytes.

### Per-Concurrency-Model Guidance

The captured `Buffer` is in-memory, but the **drain** reads the delegate's `BufferedSource`, which
performs blocking I/O. No lock or concurrency primitive can make that read non-blocking. The lock
choice affects only the one-time drain guard, not the I/O itself.

#### Platform threads

Both `synchronized` and `ReentrantLock` work correctly. The lock is held only during the
one-time drain; subsequent accesses are lock-free via the volatile fast path.

#### Virtual threads (Project Loom, JDK 21+)

`ReentrantLock` is the correct choice. During the one-time drain:

1. The lock acquisition uses `LockSupport.park()` — if contended, the virtual thread
   unmounts from its carrier, freeing it for other virtual threads.
2. The blocking reads inside the drain also unmount (JDK 21+ recognizes blocking I/O as a
   yield point).

With `synchronized`, both the lock and the I/O would pin the carrier thread.

#### Kotlin coroutines

The SDK core module intentionally does **not** depend on `kotlinx-coroutines`. Callers
using coroutines should:

- Dispatch body reads to `Dispatchers.IO` (or a `limitedParallelism` dispatcher).
- Both the lock and the underlying I/O will block the dispatcher thread, which is expected
  behavior on `Dispatchers.IO`.
- If running coroutines on virtual thread dispatchers (JDK 21+), `ReentrantLock` avoids
  carrier pinning.

A coroutine-native surface ships separately in `sdk-async-coroutines` (`suspend` extensions, MDC
propagation), keeping the core blocking-friendly for the widest compatibility.

#### Reactive streams (Project Reactor, RxJava)

Callers should wrap body access in a blocking-compatible scheduler:

```java
// Project Reactor
Mono.fromCallable(() -> loggable.snapshot())
    .subscribeOn(Schedulers.boundedElastic());

// RxJava
Single.fromCallable(() -> loggable.snapshot())
    .subscribeOn(Schedulers.io());
```

The blocking is inherent to the drain — no lock choice can eliminate it. A Reactor-native surface
(`Mono`/`Flux`, including SSE → `Flux`) ships in `sdk-async-reactor`.

### Why Not Coroutine Mutex or Reactive Operators

**`kotlinx.coroutines.sync.Mutex`** suspends instead of blocking, which would be ideal
for coroutine callers. However:

1. Adding `kotlinx-coroutines` as a dependency to the core SDK module would force it on
   all consumers, including pure Java projects that don't use coroutines.
2. `Mutex` requires a `suspend` function context. Changing `source()` and `snapshot()` to
   `suspend fun` would break the `ResponseBody` contract and make the class unusable from Java.

The chosen pattern keeps the core blocking-friendly and pushes coroutine and reactive ergonomics
into the dedicated adapter modules (`sdk-async-coroutines`, `sdk-async-reactor`,
`sdk-async-netty`, `sdk-async-virtualthreads`).

---

## Edge Cases and Safety

| Edge Case                               | Handling                                                                              |
|-----------------------------------------|---------------------------------------------------------------------------------------|
| Empty body                              | `snapshot()` returns an empty `ByteArray`; the tap/captured buffer holds zero bytes   |
| Null media type                         | Body bytes are still captured; charset resolution is the caller's concern             |
| Large body                              | Full body captured into pooled segments; use `snapshot(maxBytes)` for a bounded preview |
| Snapshot exceeds `MAX_BYTE_ARRAY_SIZE`  | `snapshot()` throws `IllegalStateException`; use `snapshot(maxBytes)` instead          |
| Double `close()`                        | Idempotent — `LoggableResponseBody.close()` checks the `closed` flag under lock        |
| `close()` before read                   | Delegate is closed, releasing the connection; the captured buffer was never allocated  |
| `close()` after read                    | Delegate already closed during the drain; the captured buffer survives close           |
| Concurrent first access                 | `ReentrantLock` ensures exactly one thread drains the delegate                         |
| Drain fails mid-read                    | Partial bytes retained; `source()` re-throws, `snapshot()` returns the partial capture |
| `close()` then `source()`               | `ensureCaptured()` throws `IllegalStateException` ("closed before the body was read")  |
| `writeTo()` on single-use `RequestBody` | The underlying source is consumed and closed; a second call throws `IllegalStateException` |
| `writeTo()` on reusable `RequestBody`   | `ByteArray`/`Buffer`-backed; safe to call multiple times                               |

---

## Usage Examples

### Basic request body logging

```kotlin
val body = RequestBody.create("""{"name": "test"}""", MediaType.parse("application/json"))
val loggable = LoggableRequestBody(body)

// Pass to the transport — writeTo is called internally with the transport's BufferedSink
loggable.writeTo(transportSink)

// Log after write
val captured = loggable.snapshot()
logger.debug("Request body: {}", String(captured, Charsets.UTF_8))
// Output: Request body: {"name": "test"}
```

### Basic response body logging

```kotlin
val loggable = LoggableResponseBody(response.body!!)

// Log the response (drains once, in memory)
logger.debug("Response: {}", String(loggable.snapshot(), Charsets.UTF_8))

// Body is still fully available (repeatable reads)
val data = loggable.source().readByteArray()
```

### Bounded preview for large payloads

```kotlin
val loggable = LoggableResponseBody(response.body!!)

// Capture at most 4 KiB for the log line; the full body stays available for parsing
val preview = loggable.snapshot(maxBytes = 4 * 1024)
logger.debug("Response preview: {}", String(preview, Charsets.UTF_8))

val result = serde.deserializer.deserialize(loggable.source().inputStream(), MyType::class.java)
```

### Coroutine context

```kotlin
// Dispatch to IO because the drain is blocking
val result = withContext(Dispatchers.IO) {
    val loggable = LoggableResponseBody(response.body!!)
    logger.debug("Response: {}", String(loggable.snapshot(), Charsets.UTF_8))
    loggable.source().readByteArray()
}
```

### Reactive context (Project Reactor)

```java
Mono.fromCallable(() -> {
        LoggableResponseBody loggable = new LoggableResponseBody(response.body());
        logger.debug("Response: {}", new String(loggable.snapshot(), StandardCharsets.UTF_8));
        return loggable.source().readByteArray();
    })
    .subscribeOn(Schedulers.boundedElastic())
    .subscribe(result -> process(result));
```
