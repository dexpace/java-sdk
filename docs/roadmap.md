# SDK Roadmap

This document tracks confirmed-but-deferred enhancements, open design decisions, and the
code-generation effort. Each item links to its tracking issue and records a recommended
direction and its dependencies, so the work can be sequenced rather than picked up ad hoc.

Bug fixes, documentation, CI, and the smaller self-contained enhancements are handled directly
as pull requests and are not listed here. The items below are larger features, decisions that
should be made before the relevant code is written, or design work for a generator that does not
yet exist.

## Status legend

- **Planned** — confirmed worth doing; deferred only for scope/sequencing.
- **Decision needed** — a real fork with material trade-offs; pick a direction before writing code.
- **Design** — forward-looking design for the (not-yet-started) code generator.

---

## Track A — Async parity

The synchronous pipeline ships retry, bearer auth, and pagination; the asynchronous side does
not yet have equivalents. These bring the async path up to parity.

### Async retry step at the RETRY stage — [#31] (Planned, large)
No `AsyncHttpStep` occupies `Stage.RETRY`, so async pipelines get no retry. Add a public
`AsyncRetryStep : AsyncHttpStep` with `final override val stage = Stage.RETRY` mirroring the sync
`RetryStep`, plus a concrete `DefaultAsyncRetryStep` that reuses `HttpRetryOptions` and a
`ScheduledExecutorService` for non-blocking backoff (no thread parked during the delay). Reuse the
existing classification/`Retry-After` logic. Should land after the retry-defaults reconciliation
(#38) so both async and sync share one backoff source of truth.

### Async bearer-auth with background token refresh — [#32] (Planned, medium)
Add an async bearer-auth `AsyncHttpStep` and a non-blocking refresh path on the token provider
(`fetchAsync(...) : CompletableFuture<BearerToken>`, defaulting to wrapping the blocking `fetch`),
so a valid-but-near-expiry token is returned immediately while the refresh runs off-thread.
Coordinate with the bearer-token eviction work (#33) so the sync and async steps share eviction
and refresh semantics.

### Async pagination — [#34] (Planned, large)
Both pagination surfaces are blocking today. After the pagination unification decision (#30),
add an async sibling that drives an iterative `executeAsync` re-arm, closing each page on
completion, with `Flow`/`Flux` bridges living in the coroutines/reactor adapter modules (not in
`sdk-core`). Blocked on #30.

---

## Track B — Core capabilities

Confirmed features deferred for scope. None is a defect; each adds a genuinely missing capability.

### Shared instrumentation emitter — [#26] (Planned, medium)
The sync and async instrumentation steps duplicate the emit/redact/preview/metrics logic, with a
standing extraction marker. The two copies have already diverged once. Extract the stateless logic
into an internal `InstrumentationEmitters` constructed from the values both steps need
(`HttpInstrumentationOptions`, `ClientLogger`, `Clock`, the lazy metric instruments). Pure internal
refactor — high value for preventing future drift between the two steps.

### Per-call options channel on the request context — [#27] (Planned, medium)
`RequestContext` carries no per-call override channel. Add an immutable `RequestOptions`
(per-phase timeout overlay, response-validation toggle, ad-hoc credential override) with
`applyDefaults` merge semantics, keeping the transport SPIs single-method. Depends on the
`Timeout` value type from #41.

### Per-phase `Timeout` value type — [#41] (Planned, medium)
No core timeout type exists; per-phase timeouts are configured ad hoc per transport. Add an
immutable `Timeout` (connect / read / write / request `Duration`s, read/write defaulting to
request) that adapters translate to native settings, kept distinct from the retry total-timeout.
Prerequisite for #27.

### AutoCloseable SSE stream — [#35] (Planned, medium)
The SSE surface is a bare `Sequence`, so a partially-consumed stream can leak the response. Add an
`SseStream : AutoCloseable, Iterable<ServerSentEvent>` that owns the `Response` and closes it on
stream close or partial consumption — mirroring the close-on-partial-consume invariant pagination
already enforces.

### Multipart request body — [#61] (Planned, large)
No multipart support today. Add a public `MultipartRequestBody : RequestBody` (immutable + Builder)
with one shared frame-size function driving both `writeTo` and `contentLength`, file parts
streaming zero-copy via `FileRequestBody` and non-file parts encoded through the `Serde` SPI (no
Jackson in `sdk-core`).

### Opt-in resource leak detector — [#45] (Planned, medium)
No leak detection exists. Add an internal, opt-in, log-only `LeakDetector` that WARNs when a
caller-owned closeable becomes phantom-reachable unclosed, using a reflectively-obtained
`java.lang.ref.Cleaner` so Java-8 bytecode no-ops on JDK 8 and the whole thing is gated behind a
system property. Never auto-closes.

---

## Track C — Decisions to make

These need a recorded decision (and sometimes a small spike) before any code is written.

### URL model: resolved `java.net.URL` vs deconstructed — [#29] (Decision needed)
**Recommended:** keep a single opaque URL field on `Request` (do not explode into
scheme/host/port/segments/query on the immutable model), but migrate the stored type from
`java.net.URL` to `java.net.URI` — `URI` is parse-only, DNS-free, and already what the JDK
transport needs. Preserve the existing textual, DNS-free equality. Record the decision in
`docs/architecture.md`. Gates #28 and the typed-page generation in #56.

### First-class `QueryParams` multimap — [#28] (Planned, medium; gated on #29)
`QueryParam` is a `TODO()` stub and `RequestRebuilder` does query-string surgery by splitting on
`&` with single-value semantics. Add a public `QueryParams` multimap modeled on `Headers`
(insertion-ordered, multi-value, explicit encoding rules). Lands after the URL-model decision so it
projects into the chosen type.

### Unify the two pagination stacks — [#30] (Decision needed, large)
Two parallel pagination surfaces exist and are both public API: `http.paging`
(`PagedIterable`/`PagedResponse`, BYO fetcher) and `pagination` (`Paginator`/`Page` + strategies,
self-driving). **Recommended:** make the strategy-driven `Paginator` + `Page` the canonical
surface (it owns the client, matches peer SDKs, and is the model the typed-page generation in #56
builds on) and deprecate the other. A public-API redesign — decide before coding. Gates #34.

### Deep-array value-equality utility — [#49] (Decision needed, small)
A `contentEquals`/`contentHashCode` helper has no consumer in the current tree; it is tied to the
future generated DTOs (with `ByteArray` fields). **Recommended:** defer until codegen needs it, to
avoid adding public surface that immediately churns the API snapshot and the coverage floor with no
caller. Fold into the codegen runtime when that lands.

### VirtualThreads close-event log level — [#10] (Decision needed, trivial)
The `executor.closed` event is emitted at DEBUG (not INFO). **Recommended:** keep DEBUG (it matches
every other async-adapter event and keeps clean-shutdown noise off INFO) and close the issue,
optionally noting the intent in the `close()` KDoc. No code change beyond the optional doc line.

### Release automation — [#75] (Decision needed, gated on CI)
Versioning, changelog, and publishing are manual, and the version string is duplicated across ~10
build scripts. **Recommended (once CI lands):** record a decision on adopting release-please; if
adopted, collapse the duplicated version to a single anchor and add a release workflow + Sonatype
publishing. Gated on CI (#70) and complements the publishing-convention-plugin work (#71).

---

## Track D — Code generation

The SDK is deliberately a hand-written HTTP-client toolkit; **there is no generator in the tree
yet.** Issues #50–#69 are design for a future KotlinPoet-based generator that would emit typed
clients over this toolkit. None is a defect, and most cannot become code until the generator and
its keystone runtime type (#50) exist. They are captured here (and several warrant short design-doc
sections now) so the effort can start coherently.

### Keystone: dependency-free four-state JSON field model — [#50] (Design, large)
The foundation the rest of the model generation builds on: a dep-free sealed `JsonField<T>`
(`Known` / `Missing` / `Null` / `Raw`) plus a `RawJson` tree in `sdk-core`, with all
Jackson↔`RawJson` conversion confined to the `sdk-serde-jackson` adapter — mirroring how `Tristate`
is split today. Almost every other codegen item depends on this. **Land a design doc first**
(`docs/codegen-json-field.md`), then the runtime type, then the generator template.

### Generated model shape (all depend on #50)
- **Thin models over a hand-written runtime — [#51]** (Design): generated models stay <100 lines
  (fields + accessors) while the runtime owns the forward-compat machinery.
- **`additionalProperties` pass-through — [#52]** (Design): capture unknown fields into an immutable
  `Map<String, RawJson>` so read-modify-write round-trips don't drop server-added fields.
- **Unions as private-ctor + per-variant accessors + visitor — [#53]** (Design): Java-8-safe
  `oneOf` emission with a retained raw node and an `unknown(raw)` fallback.
- **Forward-compatible enums — [#54]** (Design): open value + known/value pair so deserialization
  never throws on an unrecognized server value.
- **Discriminator/const fields as defaulted raw values with dual accessors — [#65]** (Design):
  fold into the #50 design effort.
- **Optional `validate()`/`isValid()`/`validity()` triad — [#64]** (Design): opt-in, memoized,
  off the deserialize path; used only as last-resort union disambiguation behind a discriminator.

### Generated service/client shape
- **Two-tier raw/cooked service methods — [#55]** (Design): a "cooked" method returning the parsed
  body and a "raw" method returning a lazy `ParsedResponse<T>`. Builds on the `ResponseHandler`
  seam (#36, now in review) and the operation-params SPI (#57).
- **Minimal `OperationParams` SPI — [#57]** (Design): projects an operation's inputs into
  headers/query/path/body and feeds the context chain. Gated on the `QueryParams` multimap (#28).
- **Curated operation overload set — [#58]** (Design): one canonical method per operation plus a
  small curated overload set leaning on Kotlin default arguments, rather than the full overload
  cross-product.
- **Lazy sub-service accessor tree — [#59]** (Design): `by lazy` sub-service accessors on a
  generated root client, reusing a nested raw-response impl.
- **`withOptions(Consumer<Builder>)` returning a new immutable client — [#60]** (Design): gated on
  first deciding whether to introduce a single cloneable client-config with `toBuilder()`.
- **Typed page classes that rebuild typed params — [#56]** (Design, xlarge): `nextPage()`
  re-invokes the operation with a typed param object, not a spliced URL string. Gated on #57, #28,
  and the pagination unification (#30).
- **Per-endpoint SSE adapter — [#62]** (Design): maps an `SseStream` to a lazily-decoded
  `Iterable<TModel>`. Gated on #35 and #36.
- **Per-operation auth descriptors with a precedence ladder — [#63]** (Design): the generator emits
  an `AuthMetadata` per operation; `sdk-core`'s auth step consumes it scheme-agnostically. The
  scheme-agnostic primitives partly exist already.

### Generator plumbing & outputs
- **Strict structured-output JSON-schema encoding rules — [#66]** (Design, small): capture the
  encoding contract (all-required + `additionalProperties:false` + optional-as-nullable-union) as a
  design-doc section now; it is adapter-only, never `sdk-core`.
- **Reusable fail-soft recursive validator skeleton — [#67]** (Design, small): a small generic
  recursion-guarded, path-prefixed validator idiom for the generator's own IR; build in codegen
  week 1–2, once the IR exists.
- **Provenance file stamped into generated SDKs — [#68]** (Design, small): generator version +
  input-contract hash, emitted into generated output only, never into the hand-written toolkit.
- **Spring Boot starter per generated API — [#69]** (Design, medium): an optional sibling
  `<api>-spring-boot-starter` with `@ConfigurationProperties`, a customizer `fun interface`, and an
  `@AutoConfiguration` assembling {IoProvider + transport + HttpPipeline}, keeping Spring out of
  `sdk-core` and the generated client.

### Suggested codegen sequencing

1. Design docs: #50 (keystone), #66, #67, #68 — these can be written now, before any generator code.
2. Land #50's runtime type in `sdk-core`; decide #29 (URL model) and #28 (`QueryParams`).
3. Stand up the generator IR + the fail-soft validator (#67), then model emission (#51–#54, #64, #65).
4. Service/client emission (#55, #57, #58, #59, #60), then pagination/SSE/auth generation (#56, #62, #63).
5. Packaging outputs (#68 provenance, #69 Spring starter).
