# I/O Module — Segment-Based Memory Streams

This document covers the design, architecture, and usage of the `org.dexpace.sdk.core.io`
package — a segment-based memory stream system inspired by
[Square's Okio](https://square.github.io/okio/).

## Table of Contents

- [Overview](#overview)
- [Why Not Use Okio Directly](#why-not-use-okio-directly)
- [Architecture](#architecture)
    - [Segment — The Memory Unit](#segment--the-memory-unit)
    - [SegmentPool — Lock-Free Recycling](#segmentpool--lock-free-recycling)
    - [Buffer — The Central Type](#buffer--the-central-type)
    - [Source and Sink — Streaming Interfaces](#source-and-sink--streaming-interfaces)
    - [BufferedSource and BufferedSink — Rich Typed I/O](#bufferedsource-and-bufferedsink--rich-typed-io)
    - [RealBufferedSource and RealBufferedSink — Wrapping Raw Streams](#realbufferedsource-and-realbufferedsink--wrapping-raw-streams)
    - [PeekSource — Non-Consuming Lookahead](#peeksource--non-consuming-lookahead)
    - [Extensions — java.io Bridges](#extensions--javaio-bridges)
- [Key Design Decisions](#key-design-decisions)
    - [Zero-Copy Segment Transfers](#zero-copy-segment-transfers)
    - [Shared Segments for Snapshots](#shared-segments-for-snapshots)
    - [Lock-Free Pooling](#lock-free-pooling)
    - [Read-Only InputStream](#read-only-inputstream)
- [Thread Safety](#thread-safety)
- [Integration with HTTP Logging](#integration-with-http-logging)
- [API Reference](#api-reference)
    - [Buffer API](#buffer-api)
    - [Source / Sink API](#source--sink-api)
    - [Extension Functions](#extension-functions)
- [File Index](#file-index)

---

## Overview

The `io` package provides an efficient, segment-based byte buffer system for the SDK's
internal I/O operations. It is used by the HTTP body logging layer to:

- Buffer response bodies for repeatable reads without copying
- Capture request body bytes during writes for diagnostics
- Provide zero-copy snapshots for log output
- Reduce GC pressure through segment pooling

The system is modeled after Okio's core design but is a standalone implementation with no
external dependencies. It targets **JDK 8+** and uses only `java.io` and
`java.util.concurrent.atomic` APIs.

---

## Why Not Use Okio Directly

1. **Dependency weight**: Okio pulls in Kotlin multiplatform artifacts (~300 KB). For an
   SDK core module, this is unnecessary weight.

2. **Scope**: The SDK needs a subset of Okio's functionality — primarily `Buffer`, segment
   pooling, and `Source`/`Sink` streaming. Features like filesystem access, hashing, and
   `ByteString` are not needed.

3. **Direct Okio copy failed**: Okio's internal module structure, `@JvmField` annotations,
   and multiplatform `expect`/`actual` declarations made it impractical to extract and
   adapt individual files.

4. **Control**: Owning the implementation allows production-specific hardening (lock-free
   pool with contention fallback, read-only InputStreams, overflow-safe arithmetic) without
   waiting for upstream changes.

---

## Architecture

### Segment — The Memory Unit

```
File: Segment.kt
```

A `Segment` is an 8 KiB chunk of memory — the fundamental building block of all buffers.

```
┌─────────────────────────────────────────────┐
│                 Segment (8 KiB)             │
│                                             │
│  data: ByteArray ──────────────────────┐    │
│                                        │    │
│  ┌──────┬──────────────────┬────────┐  │    │
│  │ free │   readable data  │  free  │  │    │
│  └──────┴──────────────────┴────────┘  │    │
│  0      pos               limit    SIZE│    │
│                                        │    │
│  prev ◄──── circular doubly-linked ────► next│
│  shared: Boolean    owner: Boolean     │    │
└─────────────────────────────────────────────┘
```

**Key properties:**

| Property        | Description                                    |
|-----------------|------------------------------------------------|
| `data`          | The 8 KiB byte array holding content           |
| `pos`           | Read cursor — first readable byte (inclusive)  |
| `limit`         | Write cursor — first writable byte (exclusive) |
| `count`         | Readable bytes: `limit - pos`                  |
| `shared`        | Whether `data` is shared with other segments   |
| `owner`         | Whether this segment can extend `limit`        |
| `next` / `prev` | Links in the circular doubly-linked list       |

**Sharing semantics:**

When a segment's byte array needs to be visible in multiple places (e.g., `Buffer.copy()`
or `Buffer.snapshot()`), calling `sharedCopy()` creates a new `Segment` that references
the *same* `data` array:

```
Original:  shared=true,  owner=true   ──► can read and write
Copy:      shared=true,  owner=false  ──► can read only
```

This avoids copying the 8 KiB array. The `SHARE_MINIMUM` threshold (1024 bytes) determines
whether `split()` uses sharing or copying — small splits copy bytes into a fresh segment to
avoid long-lived shared references.

**Operations:**

| Method                     | Description                                                   |
|----------------------------|---------------------------------------------------------------|
| `sharedCopy()`             | Zero-copy: new segment shares `data` array                    |
| `unsharedCopy()`           | Deep copy: new segment owns a copy of `data`                  |
| `pop()`                    | Remove from circular list, return successor                   |
| `push(segment)`            | Insert `segment` after this in the list                       |
| `split(byteCount)`         | Split into prefix + remainder; prefix is inserted before this |
| `compact()`                | Merge this into predecessor if it fits                        |
| `writeTo(sink, byteCount)` | Move bytes from this into another owned segment               |

### SegmentPool — Lock-Free Recycling

```
File: SegmentPool.kt
```

Segments are expensive to allocate (8 KiB each). The pool maintains a thread-safe stack
of recently released segments for reuse, avoiding fresh allocations on every buffer
operation.

```
                    SegmentPool
    ┌─────────────────────────────────────────┐
    │  hashBuckets (per-thread, power-of-2)   │
    │                                         │
    │  Bucket 0:  [seg] → [seg] → [seg] → ∅  │
    │  Bucket 1:  [seg] → ∅                   │
    │  Bucket 2:  ∅                            │
    │  Bucket 3:  [seg] → [seg] → ∅           │
    │  ...                                    │
    │                                         │
    │  Max per bucket: 64 KiB (8 segments)    │
    └─────────────────────────────────────────┘
```

**Concurrency model:**

Each bucket is an `AtomicReference<Segment?>` used as a lock-free stack. Operations use
`getAndSet(LOCK)` to acquire exclusive access:

1. **No contention**: Swap in `LOCK`, operate on the stack, restore the head.
2. **Contention** (another thread holds `LOCK`): `take()` allocates a fresh segment;
   `recycle()` silently drops the segment. No blocking ever occurs.

This design ensures:

- No thread ever blocks waiting for a pool operation
- Under high contention, the worst case is extra allocations (not correctness issues)
- Each bucket is independent — threads on different CPUs don't interfere

**Capacity**: Each bucket holds at most `MAX_SIZE` (64 KiB = 8 segments). The number of
buckets is `highestOneBit(availableProcessors * 2)`, distributing threads across buckets
to reduce contention.

**API:**

| Method             | Description                                                              |
|--------------------|--------------------------------------------------------------------------|
| `take()`           | Return a recycled segment or allocate a new one                          |
| `recycle(segment)` | Return a segment to the pool (must not be in a list, must not be shared) |
| `byteCount`        | Pooled bytes in the current thread's bucket                              |

### Buffer — The Central Type

```
File: Buffer.kt
```

`Buffer` is an infinitely growable byte queue backed by a circular doubly-linked list of
segments. It implements both `BufferedSource` (reads) and `BufferedSink` (writes).

```
                         Buffer
    ┌──────────────────────────────────────────────┐
    │  head ──►┌──────┐   ┌──────┐   ┌──────┐     │
    │          │ seg0 │◄─►│ seg1 │◄─►│ seg2 │     │
    │          │ 8KB  │   │ 8KB  │   │ 3KB  │     │
    │          └──┬───┘   └──────┘   └──┬───┘     │
    │             └──────────────────────┘         │
    │              (circular: seg2.next = seg0)    │
    │                                              │
    │  size: 19 KiB                                │
    │  Writes append to seg2 (tail = head.prev)    │
    │  Reads consume from seg0 (head)              │
    └──────────────────────────────────────────────┘
```

**Performance characteristics:**

| Operation                 | Cost             | Mechanism                                           |
|---------------------------|------------------|-----------------------------------------------------|
| Write bytes               | O(n)             | Append to tail segment, link new segments as needed |
| Read bytes                | O(n)             | Consume from head segment, recycle empty segments   |
| Move between buffers      | O(1) per segment | Transfer segment ownership (no byte copy)           |
| Copy / snapshot           | O(segments)      | Shared segment references (no byte copy)            |
| Random access (`getByte`) | O(segments)      | Linear scan from nearest end via `seek()`           |
| `indexOf`                 | O(n)             | Linear scan with `seek()` for starting position     |

**Write operations** (`BufferedSink`):

| Method                                | Description                                    |
|---------------------------------------|------------------------------------------------|
| `writeByte(b)`                        | Write a single byte                            |
| `writeShort(s)` / `writeShortLe(s)`   | Write 2 bytes, big-endian / little-endian      |
| `writeInt(i)` / `writeIntLe(i)`       | Write 4 bytes, big-endian / little-endian      |
| `writeLong(v)` / `writeLongLe(v)`     | Write 8 bytes, big-endian / little-endian      |
| `write(ByteArray)`                    | Write all bytes from an array                  |
| `write(ByteArray, offset, byteCount)` | Write a range from an array                    |
| `write(Source, byteCount)`            | Pull bytes from a Source                       |
| `writeAll(Source)`                    | Drain a Source completely                      |
| `writeUtf8(String)`                   | Encode as UTF-8 and write                      |
| `writeString(String, Charset)`        | Encode with charset and write                  |
| `write(Buffer, byteCount)`            | Zero-copy segment transfer from another buffer |

**Read operations** (`BufferedSource`):

| Method                                    | Description                              |
|-------------------------------------------|------------------------------------------|
| `readByte()`                              | Read a single byte                       |
| `readShort()` / `readShortLe()`           | Read 2 bytes, big-endian / little-endian |
| `readInt()` / `readIntLe()`               | Read 4 bytes, big-endian / little-endian |
| `readLong()` / `readLongLe()`             | Read 8 bytes, big-endian / little-endian |
| `readByteArray()` / `readByteArray(n)`    | Read bytes into a new array              |
| `read(ByteArray, offset, byteCount)`      | Read into an existing array              |
| `readFully(ByteArray)`                    | Read exactly `array.size` bytes          |
| `readUtf8()` / `readUtf8(n)`              | Read and decode as UTF-8                 |
| `readUtf8Line()` / `readUtf8LineStrict()` | Read a line (handles `\n` and `\r\n`)    |
| `readString(Charset)`                     | Read and decode with charset             |
| `readAll(Sink)`                           | Drain buffer into a Sink                 |
| `skip(byteCount)`                         | Discard bytes                            |
| `indexOf(byte, fromIndex)`                | Find first occurrence of a byte          |
| `peek()`                                  | Non-consuming lookahead source           |

**Buffer-specific operations:**

| Method                               | Description                                                  |
|--------------------------------------|--------------------------------------------------------------|
| `getByte(index)`                     | Non-consuming random access                                  |
| `clear()`                            | Discard all bytes, recycle segments                          |
| `copy()`                             | Zero-copy buffer clone via shared segments                   |
| `snapshot()` / `snapshot(byteCount)` | Non-consuming byte array extraction (default: entire buffer) |
| `copyTo(out, offset, byteCount)`     | Non-consuming copy to another buffer                         |
| `forEach(action)`                    | Iterate segments without modification                        |
| `readOnlyInputStream()`              | Non-consuming InputStream with independent cursor            |
| `inputStream()` / `outputStream()`   | Consuming java.io bridges                                    |
| `readFrom(InputStream)`              | Drain an InputStream into the buffer                         |
| `writeTo(OutputStream)`              | Write buffer contents to an OutputStream                     |
| `completeSegmentByteCount()`         | Bytes in full segments (for emit optimization)               |

### Source and Sink — Streaming Interfaces

```
Files: Source.kt, Sink.kt
```

These are the minimal streaming interfaces, analogous to `InputStream` and `OutputStream`
but operating on `Buffer` for bulk efficiency.

```kotlin
interface Source : Closeable {
    fun read(sink: Buffer, byteCount: Long): Long  // Returns bytes read or -1
    fun close()
}

interface Sink : Closeable, Flushable {
    fun write(source: Buffer, byteCount: Long)
    fun flush()
    fun close()
}
```

**Decorator bases:**

- `ForwardingSource(delegate)` — Override to intercept reads
- `ForwardingSink(delegate)` — Override to intercept writes

**Utility:**

- `blackholeSink()` — A Sink that discards all bytes (useful for measuring or draining)

### BufferedSource and BufferedSink — Rich Typed I/O

```
Files: BufferedSource.kt, BufferedSink.kt
```

These interfaces extend `Source` and `Sink` with typed convenience methods (read/write
integers, strings, lines, etc.) and expose the internal `buffer` for direct access.

`Buffer` implements both interfaces directly. `RealBufferedSource` and `RealBufferedSink`
wrap raw `Source`/`Sink` instances.

### RealBufferedSource and RealBufferedSink — Wrapping Raw Streams

```
Files: RealBufferedSource.kt, RealBufferedSink.kt
```

These wrap a raw `Source` or `Sink` with an internal `Buffer`:

- **RealBufferedSource**: Fills the buffer in `Segment.SIZE` (8 KiB) increments from the
  upstream source. Typed read methods consume from the buffer, triggering fills as needed.

- **RealBufferedSink**: Accumulates writes in the buffer. After each write, complete
  segments (full 8 KiB) are automatically flushed to the downstream sink via
  `emitCompleteSegments()`. Partial segments remain buffered until `emit()`, `flush()`,
  or `close()`.

**Close safety** (`RealBufferedSink`): The `close()` method flushes remaining bytes and
closes the downstream sink, using `addSuppressed` to preserve both exceptions if both
the flush and close fail.

### PeekSource — Non-Consuming Lookahead

```
File: PeekSource.kt
```

`PeekSource` reads from an upstream `BufferedSource` without consuming bytes. It uses
`Buffer.copyTo()` to copy data from the upstream's buffer into the sink.

**Invalidation detection**: PeekSource tracks the upstream buffer's head segment and
position. If the upstream is read (advancing the head segment), the peek source detects
the mismatch and throws `IllegalStateException`.

### Extensions — java.io Bridges

```
File: Extensions.kt  (@file:JvmName("Streams"))
```

Extension functions for interop between `java.io` types and the `Source`/`Sink` system:

```kotlin
// Wrapping
fun Source.buffered(): BufferedSource
fun Sink.buffered(): BufferedSink

// Bridging
fun InputStream.asSource(): Source
fun OutputStream.asSink(): Sink

// Convenience (bridge + buffer in one call)
fun InputStream.asBufferedSource(): BufferedSource
fun OutputStream.asBufferedSink(): BufferedSink
```

The internal `InputStreamSource` reads from `InputStream` into buffer segments.
The internal `OutputStreamSink` writes from buffer segments to `OutputStream`.

Both handle segment lifecycle correctly — `InputStreamSource` recycles unused segments
on EOF; `OutputStreamSink` recycles consumed segments after writing.

---

## Key Design Decisions

### Zero-Copy Segment Transfers

When writing from one `Buffer` to another (`buffer.write(source, byteCount)`), entire
segments are moved by relinking pointers — no bytes are copied:

```
Before:  source = [A][B][C]    dest = [X][Y]
After:   source = [C']         dest = [X][Y][A][B][C'']
                                       (A, B moved; C split)
```

This makes buffer-to-buffer transfers O(segments) instead of O(bytes).

### Shared Segments for Snapshots

`Buffer.copy()` and `Buffer.snapshot()` create shared references to existing segment
data arrays. The original segment is marked `shared = true`, preventing the pool from
reclaiming the array while copies exist:

```
buffer.snapshot()           // or buffer.snapshot(1024)
  → Iterates segments, copies bytes into a flat ByteArray
  → No segment sharing needed (data is copied out)

buffer.copy()
  → Creates new segments with sharedCopy()
  → Both buffers reference the same byte arrays
  → Neither buffer can modify the shared arrays

buffer.copyTo(other, offset, byteCount)
  → Copies segment references via sharedCopy()
  → Zero-copy for the destination buffer
```

### Lock-Free Pooling

The segment pool avoids all blocking:

- **No CAS spin loops**: A single `getAndSet(LOCK)` acquires the bucket. If LOCK is
  already set, the operation falls back immediately (allocate or drop).

- **No per-operation overhead**: Bucket selection uses `Thread.currentThread().id` with
  a bitmask — no hash computation, no `ThreadLocal` lookup.

- **Bounded memory**: Each bucket holds at most 64 KiB. Excess segments are silently
  dropped. The total pool size is `64 KiB * bucketCount`.

### Read-Only InputStream

`Buffer.readOnlyInputStream()` returns an `InputStream` that reads from the buffer
without consuming bytes. This is critical for `LoggableResponseBody`, which needs to
serve the body to callers via `byteStream()` while keeping the buffer intact for
`snapshot()`.

The read-only stream maintains an independent `position` cursor and uses `seek()` +
`copyToByteArray()` for bulk reads. Each call to `readOnlyInputStream()` returns an
independent stream.

---

## Thread Safety

| Type                 | Thread-safe?             | Notes                                                   |
|----------------------|--------------------------|---------------------------------------------------------|
| `Segment`            | No                       | Internal to a single buffer at a time                   |
| `SegmentPool`        | Yes                      | Lock-free atomic operations with contention fallback    |
| `Buffer`             | No                       | External synchronization required for concurrent access |
| `RealBufferedSource` | No                       | Single-threaded read pattern                            |
| `RealBufferedSink`   | No                       | Single-threaded write pattern                           |
| `PeekSource`         | No                       | Tied to upstream's thread model                         |
| `Source` / `Sink`    | Implementation-dependent | Interfaces do not mandate thread safety                 |

The logging layer (`LoggableResponseBody`) adds its own thread safety via `ReentrantLock`
and `@Volatile` — see [HTTP Body Logging and Concurrency](http-body-logging-and-concurrency.md).

---

## Integration with HTTP Logging

The `io` package provides the storage layer for the HTTP body logging system:

```
                    LoggableResponseBody
                           │
                    ┌──────┴──────┐
                    │   Buffer    │  ← delegate body drained here
                    │ (segments)  │
                    └──────┬──────┘
                           │
              ┌────────────┼──────────────┐
              │            │              │
              ▼            ▼              ▼
         snapshot()   byteStream()    forEach()
              │            │              │
              ▼            ▼              ▼
         ByteArray    readOnly       emitBody
         (zero-copy   InputStream    Segments()
          from segs)  (non-consuming)
```

**LoggableResponseBody** uses:

- `Buffer.readFrom(InputStream)` to drain the delegate body
- `Buffer.readOnlyInputStream()` for repeatable `byteStream()` calls
- `Buffer.snapshot()` for full-body snapshot capture
- `Buffer.forEach()` for segment emission to handlers
- `Buffer.clear()` on close to recycle segments

**LoggableRequestBody** uses:

- `Buffer.outputStream()` as the capture branch of `TeeOutputStream`
- `Buffer.snapshot()` for full-body snapshot after write completes
- `Buffer.forEach()` for segment emission to handlers
- `Buffer.clear()` to recycle captured segments

---

## API Reference

### Buffer API

**Construction:**

```kotlin
val buffer = Buffer()
```

**Writing:**

```kotlin
buffer.writeByte(0x42)
buffer.writeInt(12345)
buffer.writeUtf8("Hello, world!")
buffer.write(byteArrayOf(1, 2, 3))

// Zero-copy transfer from another buffer
buffer.write(otherBuffer, otherBuffer.size)

// Drain an InputStream
buffer.readFrom(inputStream)
```

**Reading:**

```kotlin
val byte = buffer.readByte()
val int = buffer.readInt()
val text = buffer.readUtf8(buffer.size)
val line = buffer.readUtf8Line()

// Read into existing array
val bytes = ByteArray(1024)
val count = buffer.read(bytes)

// Write to an OutputStream
buffer.writeTo(outputStream)
```

**Non-consuming access:**

```kotlin
val byte = buffer.getByte(42)                      // Random access
val snapshot = buffer.snapshot()                    // All bytes as ByteArray (or snapshot(1024) for first 1024)
buffer.copyTo(otherBuffer, offset = 0, byteCount = buffer.size)  // Copy to another buffer

val stream = buffer.readOnlyInputStream()           // Repeatable read
val peeked = buffer.peek()                          // Lookahead source
```

**java.io interop:**

```kotlin
val inputStream = buffer.inputStream()              // Consuming InputStream
val outputStream = buffer.outputStream()            // Write into buffer

buffer.readFrom(inputStream)                        // Drain InputStream into buffer
buffer.readFrom(inputStream, 1024)                  // Read exactly 1024 bytes
buffer.writeTo(outputStream)                        // Write all to OutputStream
buffer.writeTo(outputStream, 512)                   // Write 512 bytes
```

### Source / Sink API

```kotlin
// Wrapping raw streams
val source: Source = inputStream.asSource()
val sink: Sink = outputStream.asSink()

// Buffering for typed access
val bufferedSource: BufferedSource = source.buffered()
val bufferedSink: BufferedSink = sink.buffered()

// Convenience (bridge + buffer)
val bufferedSource: BufferedSource = inputStream.asBufferedSource()
val bufferedSink: BufferedSink = outputStream.asBufferedSink()

// Reading from a BufferedSource
while (!bufferedSource.exhausted()) {
    val line = bufferedSource.readUtf8Line() ?: break
    println(line)
}
bufferedSource.close()

// Writing to a BufferedSink
bufferedSink.writeUtf8("Hello\n")
bufferedSink.writeInt(42)
bufferedSink.flush()
bufferedSink.close()
```

### Extension Functions

All extensions are defined in `Extensions.kt` with `@file:JvmName("Streams")`, making
them callable from Java as `Streams.buffered(source)`, `Streams.asSource(inputStream)`, etc.

---

## File Index

| File                    | Visibility | Description                                                           |
|-------------------------|------------|-----------------------------------------------------------------------|
| `Segment.kt`            | `internal` | 8 KiB memory chunk with circular list, sharing, split/compact         |
| `SegmentPool.kt`        | `internal` | Lock-free segment recycling with per-thread hash buckets              |
| `Buffer.kt`             | `public`   | Central buffer type — `BufferedSource` + `BufferedSink` + `Cloneable` |
| `Source.kt`             | `public`   | Byte source interface + `ForwardingSource` decorator                  |
| `Sink.kt`               | `public`   | Byte sink interface + `ForwardingSink` decorator + `blackholeSink()`  |
| `BufferedSource.kt`     | `public`   | Rich typed read interface                                             |
| `BufferedSink.kt`       | `public`   | Rich typed write interface                                            |
| `RealBufferedSource.kt` | `internal` | Buffered wrapper for raw `Source`                                     |
| `RealBufferedSink.kt`   | `internal` | Buffered wrapper for raw `Sink`                                       |
| `PeekSource.kt`         | `internal` | Non-consuming lookahead `Source`                                      |
| `Extensions.kt`         | `public`   | `buffered()`, `asSource()`, `asSink()` bridge extensions              |
