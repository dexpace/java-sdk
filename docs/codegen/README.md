# Code-generation design specs

This directory holds **design specifications** for a future code generator that emits per-API
SDKs on top of the hand-written `sdk-core` toolkit. Nothing here is built yet, and nothing here
ships as part of `sdk-core`.

Two ground rules apply to every spec in this directory:

1. **`sdk-core` stays a toolkit, not a generator.** No KotlinPoet, no generator runtime, and no
   schema/validation library ever lands in `sdk-core` or any published toolkit module. Anything a
   generated SDK needs at runtime is expressed in terms of types `sdk-core` already exposes
   (`Serde`, `Tristate`, `RequestBody`, `Paginator`, the context chain, `HttpClient` /
   `AsyncHttpClient`, `HttpPipeline`, `Io` / `IoProvider`).
2. **Generated artifacts are physically separate from hand-written code.** A generator writes into
   a generated SDK's own module(s); it never edits the toolkit. Any code-shaped snippet in these
   docs is *target generator output*, labelled as such — it is not compiled in this repository.

## Specs

| Spec | Topic |
|---|---|
| [strict-structured-output-schema.md](strict-structured-output-schema.md) | Strict JSON-schema encoding rules for structured outputs: all-required + `additionalProperties:false` + optional-as-nullable-union. Adapter-only derivation, hand-rolled subset validator. |
| [fail-soft-validator-skeleton.md](fail-soft-validator-skeleton.md) | Design of a reusable fail-soft recursive validator skeleton for generator output (path-prefixed error collection, recursion guard, deterministic definition names). Deferred — design only, no runtime type today. |
| [generated-sdk-provenance.md](generated-sdk-provenance.md) | Provenance file stamped into generated SDKs: generator version + input-contract hash, format, and location. Generated output only. |
| [spring-boot-starter.md](spring-boot-starter.md) | Per-API Spring Boot starter shape: `@ConfigurationProperties`, a `fun interface` customizer, and an `@AutoConfiguration` bean assembling `{IoProvider + transport + HttpPipeline}`. Spring deps confined to the generated starter. |

## Grounding in `sdk-core`

Each spec cites the real runtime types it builds on so that the eventual generator targets the
current API rather than an invented one. The most-referenced anchors:

- **`org.dexpace.sdk.core.serde`** — `Serde`, `Serializer`, `Deserializer`, and `Tristate<T>`
  (the three-state container for `absent` / `null` / `present` PATCH fields).
- **`org.dexpace.sdk.core.client`** — `HttpClient` / `AsyncHttpClient` transport SPIs.
- **`org.dexpace.sdk.core.http.pipeline`** — `HttpPipeline` / `HttpPipelineBuilder` and the
  stage-ordered `HttpStep` model.
- **`org.dexpace.sdk.core.io`** — the `Io.installProvider(...)` seam and `IoProvider`.
- **`org.dexpace.sdk.core.pagination`** — `Paginator` and its `PaginationStrategy`.
- **`org.dexpace.sdk.core.http.context`** — the `CallContext` → `DispatchContext` →
  `RequestContext` → `ExchangeContext` promotion chain.
