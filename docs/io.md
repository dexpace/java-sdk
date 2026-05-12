# I/O Module — Pluggable Stream Contracts

This document covers the design of the `org.dexpace.sdk.core.io` package: a small set of
interface contracts in `sdk-core` plus an `IoProvider` seam that lets the SDK sit on top of
any streams library. The current implementation is `sdk-io-okio3` (Okio 3.x); additional
adapters (Okio 2, plain `java.io`, custom) can be added by implementing one interface.

## Table of Contents

- [Why This Shape](#why-this-shape)
- [Contracts](#contracts)
    - [Source / Sink](#source--sink)
    - [BufferedSource / BufferedSink](#bufferedsource--bufferedsink)
    - [Buffer](#buffer)
    - [IoProvider](#ioprovider)
    - [Io](#io)
- [Lifecycle Integration](#lifecycle-integration)
- [Body Logging](#body-logging)
- [Writing an Adapter](#writing-an-adapter)
- [Design Decisions](#design-decisions)

---

## Why This Shape

The SDK does not implement memory streams itself. Building a from-scratch segment pool gets
in the way of three goals:

1. **Keep `sdk-core` zero-dep.** A purpose-built segment pool means a lot of internal code
   that has to ship in every consumer's jar whether they want it or not.
2. **Let consumers pick the I/O lib.** Some consumers want Okio 3.x. Some are stuck on Okio
   2.x because of transitive constraints. Some want plain `java.io` for the smallest
   classpath. The SDK should not force this choice.
3. **Keep contracts small.** HTTP needs byte arrays, UTF-8 strings, lines, peek, and `java.io`
   bridges. It does not need varints, hex, or binary numerics. The contract reflects what
   the HTTP layer actually calls.

So `sdk-core/io/` ships **interfaces only**. The HTTP layer consumes the interfaces; an
adapter module (`sdk-io-okio3`) wires a real implementation in once at startup.

## Contracts

### Source / Sink

The primitive byte-channel layer. Adapters implement these; callers rarely interact
directly.

```kotlin
interface Source : Closeable {
    fun read(sink: Buffer, byteCount: Long): Long  // -1 on EOF
}

interface Sink : Closeable {
    fun write(source: Buffer, byteCount: Long)
    fun flush()
}
```

Both methods are `Buffer`-mediated — the only abstraction every adapter must implement.

### BufferedSource / BufferedSink

The HTTP-pragmatic typed surface. This is what request/response body code actually calls.

```kotlin
interface BufferedSource : Source {
    val buffer: Buffer
    fun exhausted(): Boolean
    fun readByte(): Byte
    fun readByteArray(): ByteArray
    fun readByteArray(byteCount: Long): ByteArray
    fun readUtf8(): String
    fun readUtf8(byteCount: Long): String
    fun readUtf8Line(): String?
    fun readString(charset: Charset): String
    fun peek(): BufferedSource
    fun inputStream(): InputStream
    fun skip(byteCount: Long)
}

interface BufferedSink : Sink {
    val buffer: Buffer
    fun write(source: ByteArray): BufferedSink
    fun write(source: ByteArray, offset: Int, byteCount: Int): BufferedSink
    fun writeAll(source: Source): Long
    fun writeUtf8(string: String): BufferedSink
    fun writeUtf8(string: String, beginIndex: Int, endIndex: Int): BufferedSink
    fun writeString(string: String, charset: Charset): BufferedSink
    fun outputStream(): OutputStream
    fun emit(): BufferedSink
}
```

The surface is intentionally small — fluent chains return `BufferedSink`, reads are
exception-on-EOF for typed forms (`readByte`, `readUtf8(byteCount)`) and null-on-EOF for
line reads, matching the Okio convention so adapters can pass through cheaply.

### Buffer

The canonical in-memory queue — both a source and a sink, with snapshot for logging.

```kotlin
interface Buffer : BufferedSource, BufferedSink {
    val size: Long
    fun snapshot(): ByteArray            // immutable copy
    fun clear()
    fun copyTo(out: Buffer, offset: Long = 0, byteCount: Long = size): Buffer
    override val buffer: Buffer get() = this
}
```

Buffers are cheap; the adapter decides whether to pool internally. Calling
`IoProvider.buffer()` always returns a fresh instance from the adapter's perspective.

### IoProvider

The single factory seam between `sdk-core` and the adapter.

```kotlin
interface IoProvider {
    fun buffer(): Buffer
    fun source(input: InputStream): BufferedSource
    fun source(bytes: ByteArray): BufferedSource
    fun sink(output: OutputStream): BufferedSink
    fun bufferedSource(source: Source): BufferedSource
    fun bufferedSink(sink: Sink): BufferedSink
}
```

Replaces what was previously two `ServiceLoader`-backed companion factories. One interface,
explicit installation, no global magic.

### Io

```kotlin
object Io {
    val provider: IoProvider               // throws if not installed
    fun installProvider(provider: IoProvider)
    fun <T> withProvider(provider: IoProvider, block: () -> T): T   // test seam
}
```

The provider is installed once at startup (`Io.installProvider(OkioIoProvider)`). After
installation, every call site that needs a stream reads `Io.provider`. Failure mode is
loud: `Io.provider` throws an `IllegalStateException` with the install instruction when
no provider has been installed.

`withProvider` swaps the installed provider for the duration of a block — used by tests
to inject fakes without disturbing global state.

## Replayability

`RequestBody` exposes `isReplayable()` and `toReplayable(provider)`. The pipeline's retry
machinery — and any caller that may need to resend a body — calls `toReplayable()` to get
a body whose `writeTo` can be invoked any number of times producing identical bytes.

Built-in bodies and their replayability:

| Factory                                  | `isReplayable()` | Replay strategy                                                |
|------------------------------------------|------------------|----------------------------------------------------------------|
| `create(bytes: ByteArray, …)`            | true             | Reuses the same array                                          |
| `create(content: String, …)`             | true             | Encodes once, reuses the byte array                            |
| `create(formData: Map, …)`               | true             | Pre-encodes the form payload as bytes                          |
| `create(buffer: Buffer, …)`              | true             | Non-consuming `peek()` per write                               |
| `create(file: Path, …)` / `FileRequestBody` | true          | Re-reads the file via `FileChannel.transferTo` per write       |
| `create(input: InputStream, length, …)` when `markSupported()` and `length <= MAX_BYTE_ARRAY_SIZE` | true | `mark()` at construction; `reset()` per write — zero memory copy |
| `create(input: InputStream, length, …)` otherwise | false   | Single-use; `toReplayable` drains into an in-memory buffer     |
| `create(source: BufferedSource, …)`      | false            | Single-use; `toReplayable` drains into an in-memory buffer     |

The default `toReplayable` on the base class drains `writeTo` once into `provider.buffer()`
and returns a buffer-backed body. Already-replayable bodies short-circuit to `return this`,
so the cost of calling `toReplayable` on a byte-array body is zero.

After `toReplayable` returns on a non-replayable body, **the original body is consumed** —
its underlying source has been drained. Continue with the returned value.

## FileRequestBody and sendfile

`FileRequestBody` is its own public type so transports can `instanceof` / `is`-check it and
dispatch a kernel `sendfile(2)` via `FileChannel.transferTo(position, count, socketChannel)`
when the destination is a `SocketChannel`. The default `writeTo` in this class uses
`FileChannel.transferTo` against `Channels.newChannel(sink.outputStream())` — that path
skips at least one user-space buffer copy but does not reach the syscall fast path through
a generic `BufferedSink`. Transports that need the syscall fast path should pattern-match
the body type before falling back to the generic `RequestBody.writeTo`.

## MAX_BYTE_ARRAY_SIZE

`Buffer.MAX_BYTE_ARRAY_SIZE` (`Int.MAX_VALUE - 8`) is the JVM's effective single-byte-array
limit. `Buffer.snapshot()` throws `IllegalStateException` with an actionable message when
the buffer exceeds it; callers should stream via `inputStream()` or `copyTo(out)` instead.
`LoggableRequestBody.snapshot(maxBytes)` and `LoggableResponseBody.snapshot(maxBytes)`
remain safe at any body size because they cap the materialized byte array.

## Lifecycle Integration

**Request side.** `RequestBody.writeTo(sink: BufferedSink)` is the integration point.
Body implementations call typed write methods (`writeUtf8`, `write(byteArray)`, `writeAll`)
on the sink — they never touch the provider directly. The transport layer constructs the
sink via `Io.provider.sink(outputStream)` and passes it in. The form-body factory
(`RequestBody.create(formData, charset, provider = Io.provider)`) builds its underlying
source through the provider; pass an explicit provider for testing.

**Response side.** `ResponseBody.source(): BufferedSource` is the integration point. The
transport layer wraps the response `InputStream` via `Io.provider.source(inputStream)`
and constructs a `ResponseBody.create(source, mediaType, contentLength)`. Callers read
typed values from the source.

## Body Logging

Two wrappers live alongside the immutable body types:

- **`LoggableRequestBody`** (in `http/request/`) wraps a `RequestBody` and a `TeeSink`.
  During `writeTo`, every byte is mirrored into an internal `Buffer` while still being
  forwarded to the primary sink. After write, `snapshot(): ByteArray` returns the captured
  bytes for log preview.

- **`LoggableResponseBody`** (in `http/response/`) wraps a `ResponseBody`. On first access
  it eagerly drains the wrapped body into an internal `Buffer`. `source()` returns a fresh
  non-consuming `peek()` view each call, giving repeatable reads. `snapshot()` returns the
  captured bytes.

Both wrappers take `IoProvider` in their constructor (default `Io.provider`) so tests can
swap in a fake. `TeeSink` lives in `io/` as an `internal` helper — implementation detail of
the logging story.

## Writing an Adapter

To add a new I/O implementation:

1. Create a new Gradle module.
2. Depend on `:sdk-core` and your I/O library of choice.
3. Implement `IoProvider`. The whole surface is six methods.
4. Expose a single public type (recommended: a Kotlin `object` named `<Library>IoProvider`).
5. Mark every adapter class `internal` so callers see only the contracts.
6. Document `Io.installProvider(YourProvider)` in your README.

`sdk-io-okio3` is the reference implementation — see `OkioIoProvider.kt` and the
`internal/` package next to it.

## Performance

The contract is shaped so adapters can implement the hot paths with **no per-call byte
copies** when both sides of an operation use the same adapter. With the bundled
`sdk-io-okio3` provider:

- **`BufferedSink.write(source: Buffer, byteCount: Long)`** — segment ownership transfer.
  Bytes leave the source buffer and enter the sink buffer in O(segments), not O(bytes).
- **`Buffer.copyTo(out: Buffer, offset, byteCount)`** — segment reference share. The
  destination buffer gains read access to the source's segments by ref-count increment.
- **`BufferedSink.writeAll(source: Source)`** — when the source is also Okio-backed,
  delegates directly to `okio.BufferedSink.writeAll(okio.Source)` which drains in a single
  segment-transferring loop.
- **`BufferedSource.peek()`** — non-consuming view backed by segment refs. Logging multiple
  previews of the same body costs O(viewers), not O(bytes × viewers).

The HTTP integration code is built to use these fast paths:

- `LoggableResponseBody` drains the wrapped body with a single `writeAll`, then serves
  `source()` calls via `peek()` for repeatable reads at near-zero cost.
- `LoggableRequestBody` uses [`TeeSink`][TeeSink], which encodes each typed write **once**
  into a reused scratch buffer, then `copyTo(tap)` (segment-share) plus
  `primary.write(scratch, n)` (segment-move). One encoding step plus two
  segment-level operations, regardless of payload size.
- `RequestBody.create(formData, …)` accepts an explicit `IoProvider` to skip the global
  getter when the caller already has a reference.

### Bounded snapshots

Logging multi-MB bodies as a single `ByteArray` is wasteful when you only want a 256-byte
preview. Both `Loggable*Body` types expose `snapshot(maxBytes: Int)` which caps the
materialized byte array via `peek().readByteArray(maxBytes)` — the captured buffer is
untouched, and Okio's segment-sharing means the cap is enforced before bytes are copied
out.

### Adapter implementation guidance

If you're writing a new `IoProvider`:

- Implement `Source.read(sink: Buffer, byteCount)` with an `is`-check fast path for your
  own concrete `Buffer` type. Use a byte-array fallback only when the destination is a
  foreign adapter.
- Same for `Sink.write(source: Buffer, byteCount)` — fast path when source is your buffer.
- Cache adapter wrappers across calls when bridging foreign primitives — Okio reuses the
  same buffer reference across a buffered consumer's lifetime, so the wrapper allocation
  amortizes to zero (see `ForeignSourceAdapter` in `sdk-io-okio3`).
- Mark every adapter class `internal`. The only public type should be your
  `IoProvider` singleton.

[TeeSink]: ../sdk-core/src/main/kotlin/org/dexpace/sdk/core/io/TeeSink.kt

## Design Decisions

### Explicit install over ServiceLoader

The previous design used `ServiceLoader` to discover the implementation. That has three
problems:

1. **Failure is silent.** Missing `META-INF/services` resolves to `null` (or `.first()`
   throws a `NoSuchElementException` deep in a stack trace), not a clear actionable
   message.
2. **Two implementations on the classpath pick non-deterministically.** `.first()` orders
   by URL order.
3. **Tests can't swap.** No standard way to install a fake without crafting class-loader
   tricks.

`Io.installProvider(...)` plus `Io.withProvider(...)` solves all three.

### Single IoProvider over separate source/sink factories

The previous design split into `BufferedSourceFactory` and `BufferedSinkFactory`. A single
provider has lower binding overhead (one install call, one global), and an adapter can
share state across source and sink construction (a buffer pool, a thread-local arena).

### Drop NIO inheritance

The previous interfaces extended `ReadableByteChannel` / `WritableByteChannel` / `ByteChannel`.
Nothing in the SDK calls the `ByteBuffer` overloads — they were dead surface that every
adapter still had to stub. Dropping them makes adapter implementations smaller. An adapter
that wants NIO interop can implement `ReadableByteChannel` separately on its concrete
class.

### `val buffer: Buffer` on Buffered* interfaces

Every `BufferedSource` and `BufferedSink` exposes a `buffer` property. This is the
adapter's *internal* staging buffer — for `Buffer` it's `this`; for an Okio-backed source
it wraps `okio.BufferedSource.buffer`. The legitimate use is fast-path transfer in
`writeAll(source)` when both sides happen to be the same kind of adapter (avoids extra
copies). Mutating the returned buffer directly is undefined behavior.
