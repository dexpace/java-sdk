# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
./gradlew build                            # Build all modules — runs tests, ktlint, detekt, apiCheck, kover gate
./gradlew :sdk-core:build                  # Build one module
./gradlew :sdk-core:compileKotlin          # Fast compile-only check
./gradlew test --tests "<FQCN>.<method>"   # Run a single JUnit Platform test
./gradlew apiCheck                         # Binary-compatibility check against committed .api snapshots
./gradlew apiDump                          # Regenerate .api snapshots after INTENTIONAL public-API changes
./gradlew koverHtmlReport                  # Aggregate coverage report at build/reports/kover/html/
```

All quality gates break the build: ktlint + detekt (`config/detekt.yml`), `allWarningsAsErrors`, explicit-API
strict mode, binary-compatibility-validator, and an 80% aggregate line-coverage floor (Kover, wired into the
root `check` task — see `build.gradle.kts`). Detekt is skipped on the two non-Java-8 modules,
`sdk-transport-jdkhttp` (11) and `sdk-async-virtualthreads` (21) — detekt 1.23.x crashes on the JDK-25+
system toolchain when a module targets a non-8 toolchain; see those build scripts for the upstream issue and
re-enable conditions. It runs everywhere else, including `sdk-transport-okhttp`.

`check` (so a plain `./gradlew build`) also runs the R8 shrink-survival guard in the test-only
`sdk-shrink-test` module. That step **requires a JDK 11 toolchain** (Gradle auto-provisions one if absent)
and network access to **Google's Maven repo** to fetch `com.android.tools:r8`. An offline build, or one
that cannot provision JDK 11, will fail on `:sdk-shrink-test:r8Run`; scope the build (e.g. build specific
modules) to skip it. See that module's `build.gradle.kts` for the pipeline.

## Repository Layout

Eleven Gradle modules (see `settings.gradle.kts`). `gradle/libs.versions.toml` is the single source of
truth for dependency and plugin versions. Group `org.dexpace`, version `0.0.1-alpha.1`. (Two are
unpublished and not listed below: `sdk-shrink-test`, a test-only R8 shrink-survival guard, and
`sdk-example`, a runnable end-to-end usage sample.)

| Module | Purpose | JVM target |
|---|---|---|
| `sdk-core` | All public contracts: HTTP models, sync + async pipelines and steps, auth, SSE, paging, pagination, serde abstractions, instrumentation, I/O seam. Zero runtime deps beyond SLF4J API (compileOnly) + Kotlin stdlib. | 8 |
| `sdk-io-okio3` | Okio 3.x implementation of `IoProvider` — the only I/O adapter today. | 8 |
| `sdk-async-coroutines` | Kotlin coroutines adapter: `suspend` extensions, MDC propagation. | 8 |
| `sdk-async-reactor` | Reactor `Mono`/`Flux` adapter, incl. SSE → `Flux` with backpressure. | 8 |
| `sdk-async-netty` | Netty `Future` adapter with bidirectional cancellation. | 8 |
| `sdk-async-virtualthreads` | Virtual-thread executor adapter (`AutoCloseable`). | **21** |
| `sdk-transport-okhttp` | OkHttp 5.x `HttpClient` + `AsyncHttpClient` transport. | 8 |
| `sdk-transport-jdkhttp` | `java.net.http.HttpClient` (JEP 321) transport. | **11** |
| `sdk-serde-jackson` | Jackson 2.18 `Serde` implementation + `Tristate<T>` ser/de. | 8 |

Key `sdk-core` packages (`org.dexpace.sdk.core.*`): `client` (the `HttpClient`/`AsyncHttpClient` transport
SPIs), `http.request` / `http.response` / `http.common` (immutable models), `http.context` (context promotion
chain), `http.sse` (WHATWG Server-Sent Events),
`http.pipeline` (+`.steps` — stage-based sync/async pipeline runtime),
`auth` (credentials, RFC 7235 challenges, Digest), `pipeline` (+`.step`, `.step.retry` — recovery-aware
Request/Response/Execution pipeline primitives),
`pagination` (unified paging: `Page` (raw per-page `Response`, `Closeable`) / `PageInfo`,
`Paginator`/`AsyncPaginator` with item- and page-level views, the auto-closing `CloseablePages`
view returned by `byPage()`, `PagedIterable`, 3 strategies, internal `PageWalker` driver),
`serde` (incl. `Tristate`),
`instrumentation` (+`.metrics`), `io`,
`config`, `util`, `generics`. The full package map with highlights is in `README.md`.

`docs/` (read before structural changes): `architecture.md`, `http.md`, `io.md`, `pipelines.md`,
`http-body-logging-and-concurrency.md`, `implementation-plan.md` (phased work-unit plan),
`refs-comparison.md` (survey of peer SDKs). `styleguide/` holds the Kotlin / Kotlin-JVM style guides this
codebase follows.

## Architecture — Big Picture

The SDK is an **HTTP-client toolkit, not an HTTP client**. `sdk-core` provides abstractions, models, and
pipelines; transports plug in via `HttpClient` / `AsyncHttpClient` (two reference transports ship as
modules), and concrete I/O plugs in via `IoProvider`.

Layered, from the bottom up:

1. **`io/` contracts** — `Source`/`Sink`, `BufferedSource`/`BufferedSink`, `Buffer`, `TeeSink`. All
   interfaces; no concrete I/O in `sdk-core`. `Io.installProvider(provider)` wires the single `IoProvider`
   seam once at startup; a missing provider throws `IllegalStateException` with the install instruction.
2. **HTTP models** — immutable `Request`/`Response`/`Headers`/`MediaType` etc., private constructor +
   `Builder` + `newBuilder()`. `RequestBody.isReplayable()`/`toReplayable()`; `FileRequestBody` lets
   transports dispatch `FileChannel.transferTo`.
3. **Logging bodies** — `LoggableRequestBody` (TeeSink mirror on write) and `LoggableResponseBody`
   (drain-once + `peek()` views), both with `snapshot()` previews and race-safe consumed-once guards.
4. **`http.context`** — `CallContext` → `DispatchContext` → `RequestContext` → `ExchangeContext`, each
   carrying an `InstrumentationContext`.
5. **Pipelines** — two cooperating layers, both real (no placeholders):
   - `http.pipeline` — stage-based runtime: `HttpPipelineBuilder` + `HttpStep` ordered by `Stage` with
     pillar stages (exactly one REDIRECT / RETRY / AUTH / LOGGING / SERDE step per pipeline), plus the
     async mirror (`AsyncHttpPipeline`, `AsyncHttpStep`) and sync→async bridges.
   - `pipeline` — recovery-aware primitives: `RequestPipeline`, `ResponsePipeline`, `ExecutionPipeline`,
     `ResponseOutcome`, with steps like `RetryStep` (backoff + `Retry-After`), `IdempotencyKeyStep`,
     `ClientIdentityStep`.
   See `docs/pipelines.md` before touching either.
6. **Transports** — `sdk-transport-okhttp` (Java 8) and `sdk-transport-jdkhttp` (Java 11). Both implement
   sync + async SPIs, propagate cancellation into the native client, and own `close()` for SDK-managed
   resources only (BYO clients are never closed by the SDK).

## Conventions (enforced — match these when adding code)

- **Java 8 bytecode everywhere except** `sdk-transport-jdkhttp` (11) and `sdk-async-virtualthreads` (21).
  Avoid `InputStream.transferTo` (9+), `Thread.threadId()` (19+), records, sealed `permits` in Java-8
  modules. A module that genuinely needs a newer JDK must override **all three** of `jvmToolchain(N)`,
  the `java { sourceCompatibility / targetCompatibility = VERSION_N + toolchain }` block, and
  `compilerOptions { jvmTarget.set(JvmTarget.JVM_N) }` in its own build script — overriding only the
  toolchain produces Java-8-format bytecode referencing newer stdlib symbols (`NoSuchMethodError` on JDK 8),
  and omitting the `java {}` block trips Gradle's `compileJava`/`compileKotlin` JVM-target validation. See
  `docs/architecture.md` (Cross-Compile Toolchain Discipline).
- **MIT license header in every source file.** Each `.kt`, `.java`, and `.kts` file starts with the 6-line
  `Copyright (c) 2026 dexpace and Omar Aljarrah` / `SPDX-License-Identifier: MIT` block — copy it from any
  existing file when creating new ones. Nothing enforces this automatically; it is a review convention.
- **`ReentrantLock` over `synchronized`** (`lock.withLock { … }`) — synchronized pins carrier threads under
  Loom.
- **Blocking calls respect `Thread.interrupt()`** — catch `InterruptedException`, restore the interrupt
  flag, throw `InterruptedIOException` (or add the interrupt as suppressed). See `docs/architecture.md`
  (Cancellation).
- **Immutable data + private constructor + `Builder` implementing `Builder<T>`**, `newBuilder()` pre-filled,
  `@JvmOverloads`/`@JvmStatic` for Java callers.
- **Explicit-API strict mode** on every main source set: every declaration needs an explicit visibility and
  public declarations need explicit return types (`= apply { … }` builder methods must declare the return
  type). Tests are exempt. Use `internal` for cross-package implementation details, `@JvmSynthetic internal`
  when the mangled name would still be Java-callable, `private` otherwise. Adapter modules expose a single
  public entry point (e.g. only `OkioIoProvider` is public in `sdk-io-okio3`).
- **`sdk-core` has zero non-SLF4J runtime deps** — I/O, Jackson, and concurrency libraries live only in
  adapter modules. SLF4J is `compileOnly` (added by the root build to every Kotlin module).
- **Published modules apply `id("dexpace.published-module")`** — the convention plugin in the `build-logic`
  included build (`build-logic/src/main/kotlin/dexpace.published-module.gradle.kts`) carries the
  `maven-publish` + `signing` setup, shared POM, staging repo, and CI-gated signing. Do not re-inline a
  `publishing {}`/`signing {}` block in a module; a new publishable module just applies the plugin, and a
  module that must not be published simply omits it. Coordinates (`group`/`version`) come from
  `gradle.properties` and apply to every project.
- **Commit style:** `feat:` / `test:` / `docs:` / `chore:` prefixes; `merge:` for work-unit merge commits.

## Things That Will Bite You

- **Public API changes fail `apiCheck`.** Any visible signature change needs `./gradlew apiDump` and the
  regenerated `api/*.api` files committed alongside the change. Never run `apiDump` to silence an
  *unintentional* break.
- **`Io.installProvider(...)` must run before any code touches `Io.provider`.** Tests install
  `OkioIoProvider` in `@BeforeTest`; production installs in the application startup path.
- **Coverage floor is aggregate 80% line coverage.** The `minBound(80)` rule lives on the root-aggregate
  `:koverVerify`, which the root `check` task depends on, so a plain `./gradlew build` enforces it. New
  under-tested code can trip the gate even when its own module builds clean — check `koverHtmlReport` to see
  which classes are dragging the aggregate down.
- **Transport tests use `mockwebserver3`** (OkHttp's). Follow the existing patterns in
  `sdk-transport-okhttp`/`sdk-transport-jdkhttp` test suites rather than spinning up real sockets.
- **`allWarningsAsErrors` is on** — a deprecation warning in any Kotlin module breaks the build.
- **There is no generated/compat code and no `sdk-core/src/main/java/` tree** — `sdk-core` is pure Kotlin.
  The root Kover filter excludes only test fixtures (`org.dexpace.sdk.core.testing.*`); do not reintroduce
  a Java source tree or broad package-glob excludes.
