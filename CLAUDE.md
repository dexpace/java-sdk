# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew build                       # Build all modules
./gradlew :sdk-core:build             # Build sdk-core only
./gradlew :sdk-core:compileKotlin     # Fast compile-only check
./gradlew :sdk-io-okio3:test          # Run the okio3 adapter test suite
./gradlew test --tests "<FQCN>.<method>"   # Run a single JUnit Platform test
```

No linter is wired up yet — `ktlint` and `kover` are TODOs in the root `build.gradle.kts`. `sdk-core/src/test` is empty.

## Repository Layout

Six Gradle modules (see `settings.gradle.kts`):

- **`sdk-core`** — the production SDK. Kotlin 2.3.21, **JVM target Java 8**, zero external deps beyond `slf4j-api` (compileOnly) and `kotlin-reflect`. Holds all the public API surface (`org.dexpace.sdk.core.*`). Contains a large `src/main/java` legacy/compat tree (~367 files) that backs generated service clients.
- **`sdk-io-okio3`** — the Okio 3.x implementation of `sdk-core`'s I/O contracts. **JVM target Java 8** (same as `sdk-core`). The **only** I/O adapter today; other adapters (Okio 2, plain java.io, custom) can be added by implementing one `IoProvider` interface. Do not pull Okio types into `sdk-core`.
- **`sdk-async-coroutines`** — Kotlin coroutines adapter (`suspend` extensions, `CoroutineScope.completableFutureOf`, MDC propagation). JVM target Java 8.
- **`sdk-async-reactor`** — Reactor `Mono`/`Flux` adapter, including SSE → `Flux` with backpressure. JVM target Java 8.
- **`sdk-async-netty`** — Netty `io.netty.util.concurrent.Future` adapter with bidirectional cancellation. JVM target Java 8.
- **`sdk-async-virtualthreads`** — JDK 21+ virtual-thread executor adapter (`AutoCloseable`). **JVM target Java 21** — consumers of this module must be on JDK 21+.

### Directory tree

```
java-sdk/
├── build.gradle.kts                 # Root build — applies kotlin("jvm") + java to all subprojects, pins JVM target 1.8
├── settings.gradle.kts              # Declares all 6 modules
├── gradle.properties, gradlew*      # Standard Gradle wrapper
├── docs/                            # Design docs — read before structural changes
│   ├── architecture.md              # Big picture, package map, data flow
│   ├── io.md                        # I/O contracts + IoProvider seam
│   ├── http.md                      # Request/Response/Headers/MediaType, context system
│   ├── pipelines.md                 # Pipeline + step composition
│   └── http-body-logging-and-concurrency.md
│
├── sdk-core/                        # Production SDK module (Java 8 target)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/org/dexpace/sdk/core/    # Primary public API
│       │   ├── client/              # HttpClient interface (transport SPI)
│       │   ├── http/
│       │   │   ├── request/         # Request, RequestBody, LoggableRequestBody, Method
│       │   │   ├── response/        # Response, ResponseBody, LoggableResponseBody, Status
│       │   │   ├── common/          # Headers, MediaType, CommonMediaTypes, Protocol
│       │   │   └── context/         # CallContext → DispatchContext → RequestContext → ExchangeContext
│       │   ├── io/                  # Source, Sink, BufferedSource, BufferedSink, Buffer, IoProvider, Io, TeeSink
│       │   ├── pipeline/            # RequestPipeline, ResponsePipeline, BuilderPipeline, ExecutionPipeline
│       │   │   └── step/            # PipelineStep, RequestPipelineStep, config + retry traits
│       │   ├── serde/               # Serde, Deserializer, SerializeTrait (abstractions only)
│       │   ├── instrumentation/     # InstrumentationContext, Span, TracingScope, Noop* defaults
│       │   ├── generics/            # Builder<T>
│       │   ├── model/               # (small) domain model abstractions
│       │   └── util/                # SDK-level annotations
│       │
│       ├── main/java/               # Java compat layer backing generated service clients (~367 files)
│       │   ├── annotations/         # @ServiceClient, @ServiceMethod, @Metadata
│       │   ├── binarydata/          # BinaryData impls
│       │   ├── credentials/         # KeyCredential, oauth/
│       │   ├── http/                # client/, models/, paging/, pipeline/, annotations/
│       │   ├── implementation/      # http/, instrumentation/ (otel/ + fallback/), utils/
│       │   ├── instrumentation/     # logging/, metrics/, tracing/
│       │   ├── models/              # binarydata/, geo/
│       │   ├── serialization/       # json/ (embedded Jackson core), xml/ (embedded Aalto)
│       │   ├── traits/              # HttpTrait, ProxyTrait, EndpointTrait, …
│       │   └── utils/               # configuration/
│       │
│       └── test/                    # Empty placeholder — no tests yet
│
├── sdk-io-okio3/                    # Okio 3.x adapter (Java 8 target)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/org/dexpace/sdk/io/
│       │   ├── OkioIoProvider.kt    # Public — install via Io.installProvider(OkioIoProvider)
│       │   └── internal/            # internal adapter classes wrapping okio.{Buffer,BufferedSource,BufferedSink}
│       └── test/                    # JUnit Platform + kotlin("test")
│
├── sdk-async-coroutines/            # Kotlin coroutines adapter (Java 8 target)
│   └── build.gradle.kts
├── sdk-async-reactor/               # Reactor Mono/Flux adapter (Java 8 target)
│   └── build.gradle.kts
├── sdk-async-netty/                 # Netty Future adapter (Java 8 target)
│   └── build.gradle.kts
└── sdk-async-virtualthreads/        # Virtual-thread executor adapter (Java 21 target)
    └── build.gradle.kts
```

## Architecture — Big Picture

The SDK is an **HTTP-client toolkit, not an HTTP client**. It provides abstractions, models, and pipelines; consuming libraries plug in a concrete transport via the `HttpClient` interface, and a concrete I/O implementation via the `IoProvider` interface.

Layered, from the bottom up:

1. **`io/` contracts** — `Source` / `Sink` (primitive byte channels), `BufferedSource` / `BufferedSink` (HTTP-pragmatic typed reads/writes: byte arrays, UTF-8 strings, lines, peek, java.io bridges), `Buffer` (both source and sink + `snapshot()` for body logging). All interfaces — `sdk-core` contains no concrete I/O implementation. `IoProvider` is the single factory seam; `Io.installProvider(provider)` wires it once at startup and `Io.provider` resolves it everywhere. Failure mode is loud: missing provider throws an `IllegalStateException` with the install instruction.
2. **`http.request` / `http.response` / `http.common`** — immutable models (`Request`, `Response`, `Headers`, `MediaType`, `Protocol`) built with private constructors + `Builder` + `newBuilder()`. `RequestBody` exposes `isReplayable()` and `toReplayable(provider)`; built-in factories cover byte arrays, strings, in-memory `Buffer`s, `BufferedSource`s, mark-supporting `InputStream`s (replay via `reset()`, no buffer needed), and files (`FileRequestBody` — transports can type-check it to dispatch `FileChannel.transferTo` for `sendfile(2)`).
3. **Logging bodies** — `LoggableRequestBody` (in `http/request/`) uses `TeeSink` to mirror written bytes into an internal `Buffer` without disturbing the primary write. `LoggableResponseBody` (in `http/response/`) eagerly drains the wrapped body into an internal `Buffer` on first access, then returns non-consuming `peek()` views for repeatable reads. Both expose `snapshot(): ByteArray` for log preview.
4. **`http.context`** — context promotion chain: `DispatchContext` → `RequestContext` → `ExchangeContext`, all carrying an `InstrumentationContext` for tracing.
5. **`pipeline/`** + **`pipeline/step/`** — composable request/response processing. `RequestPipeline` and `PipelineStep` are `fun interface`s; `BuilderPipeline<T>` applies builder-mutating steps and folds to a final `T`. `ResponsePipeline` and `ExecutionPipeline` are placeholders (interface / empty class) — wire them up rather than inventing a parallel mechanism.
6. **`client/HttpClient`** — single-method interface (`fun execute(Request): Response`). Transport is **not** provided by `sdk-core`.

### Adapter modules

`sdk-io-okio3` is the reference implementation of `IoProvider`. The only public type is `OkioIoProvider` (a Kotlin `object`); concrete adapter classes (`OkioBuffer`, `OkioBufferedSource`, `OkioBufferedSink`) are `internal` to the module. Adding a new adapter is: implement `IoProvider`, ship the module, call `Io.installProvider(YourProvider)` at startup. No `ServiceLoader`, no META-INF/services.

## Conventions (enforced — match these when adding code)

- **Java 8 bytecode in `sdk-core`.** Avoid `InputStream.transferTo` (Java 9+), `Thread.threadId()` (19+), `java.net.http.HttpClient` (11+), records, sealed `permits`, etc. The toolchain is pinned to 8.
- **`ReentrantLock` over `synchronized`** (`lock.withLock { … }`). Reason: synchronized pins carrier threads under Loom; ReentrantLock lets virtual threads unmount.
- **Blocking calls respect `Thread.interrupt()`.** Catches `InterruptedException`, calls `Thread.currentThread().interrupt()` to preserve status, throws `InterruptedIOException` (or the operation's natural exception with the interrupt added as suppressed). Documented in `docs/architecture.md` under Cancellation.
- **Immutable data + private constructor + `Builder` implementing `Builder<T>`.** `newBuilder()` returns a pre-filled builder. Add `@JvmOverloads` on public constructors/factories for Java callers.
- **Explicit visibility on every Kotlin declaration.** The root build sets `explicitApi = ExplicitApiMode.Strict` on every Kotlin module's main source set, so every top-level/class-member declaration must declare `public`, `internal`, or `private` — there is no "implicit public". Rules of thumb:
  - **`public`** for anything exported across a module boundary that consumers may rely on: public model classes (`Request`, `Response`, `Headers`, `MediaType`, …), public interfaces (`HttpClient`, `IoProvider`, `RequestPipeline`, …), companion-object factories, top-level extension functions in adapter modules, and the canonical `Io.installProvider(...)`-style entry points.
  - **`internal`** for implementation details that other classes in the same module reach across packages but external consumers must not see: cross-class helpers in `sdk-io-okio3.internal.*`, package-shared utility functions, and test-only seams.
  - **`@JvmSynthetic internal`** when an `internal` symbol's Java-mangled name (e.g. `foo$sdk_core`) is technically callable from Java; `@JvmSynthetic` hides it from the bytecode's accessible-from-Java surface so name mangling cannot be circumvented.
  - **`private`** for file- or class-local helpers that nothing outside the declaring file or class needs.
  Public API is intentionally narrow: in `sdk-io-okio3` only `OkioIoProvider` is public; every adapter class lives under `org.dexpace.sdk.io.internal` and is `internal`. Strict-mode also requires explicit return types on public declarations — builder methods using `= apply { … }` must declare the builder's class as the return type.
- **`sdk-core` has zero non-SLF4J runtime deps.** Do not add Okio (or any other I/O lib) to `sdk-core/build.gradle.kts`. Anything I/O-specific lives in an adapter module.
- **Logging uses SLF4J `compileOnly`.** Don't add a runtime logging dependency.
- **Commit style:** `chore:` prefix for refactors/cleanup — see `git log`.

## Things That Will Bite You

- The repo is mid-refactor (branch: `OmarAlJarrah/memory-streams-initial-building-blocks`). The original `io/` module had a segment-pool design (`Segment.kt`, `SegmentPool.kt`, `Real*BufferedSource/Sink`, `PeekSource.kt`); those files appear `D` in `git status`. They were replaced by the contract-only design described above. Don't try to resurrect them — concrete implementations belong in adapter modules.
- `sdk-io-okio3` is **Java 8** like `sdk-core` — it inherits the toolchain from the root build. The **only** module on JDK 21 is `sdk-async-virtualthreads`. Do not copy `sdk-async-virtualthreads`'s `jvmToolchain(21)` override back into any other module.
- `pr.diff` in the repo root is a generated artifact, not source — ignore it.
- `Io.installProvider(...)` must run before any code that calls `Io.provider`. Tests use `Io.installProvider(OkioIoProvider)` in `@BeforeTest`; production code should install in the application's startup path.
