# Lazy sub-service accessor tree

> Design spec. The Kotlin/Java in this document is **target generator output**, not code compiled
> in this repository.

## Problem

A real API surface is a tree: `client.models()`, `client.files()`, `client.fineTuning().jobs()`,
and so on, often several levels deep. Two naive generation strategies both go wrong:

- **Eager instantiation** — the root client constructs every service (and every nested service) in
  its constructor. For a deep tree, that allocates the entire object graph up front even though a
  given program touches a handful of services. It also forces every service's dependencies to be
  resolvable at client-construction time.
- **Duplicated raw/cooked impls** — naively pairing the two-tier design
  ([service-method-tiers.md](service-method-tiers.md)) with a service tree doubles the generated
  type count: a cooked `ModelServiceImpl` *and* a separate raw `ModelServiceRawImpl`, repeated for
  every node. The tree's type count is the dominant term in generated-code size.

openai-java's client impl (`OpenAIClientImpl.kt`) solves both: sub-services are `by lazy`, and the
root client reuses the nested raw-response implementation instead of emitting a parallel cooked
tree.

## Proposed generated shape

The root client and every interior node expose **lazily-instantiated** sub-service accessors. Each
node is constructed at most once, on first access, and memoized. The root reuses the raw-response
implementation to avoid a parallel cooked tree.

### Root client (target output)

```kotlin
// GENERATED — illustrative target output, not compiled here.
public class DexpaceClientImpl internal constructor(
    private val client: HttpClient,
    private val serde: Serde,
    private val callContext: CallContext,   // base context promoted per call
    private val errorMapper: ErrorMapper,
) : DexpaceClient {

    // Each sub-service built at most once, on first access, then memoized.
    private val models: ModelService by lazy { ModelServiceImpl(client, serde, callContext, errorMapper) }
    private val fineTuning: FineTuningService by lazy { FineTuningServiceImpl(client, serde, callContext, errorMapper) }

    override fun models(): ModelService = models
    override fun fineTuning(): FineTuningService = fineTuning
}
```

### Interior node — nested accessors, same laziness (target output)

```kotlin
// GENERATED — illustrative target output, not compiled here.
internal class FineTuningServiceImpl(
    private val client: HttpClient,
    private val serde: Serde,
    private val callContext: CallContext,
    private val errorMapper: ErrorMapper,
) : FineTuningService {

    private val jobs: FineTuningJobService by lazy {
        FineTuningJobServiceImpl(client, serde, callContext, errorMapper)
    }

    override fun jobs(): FineTuningJobService = jobs
}
// Caller: client.fineTuning().jobs().retrieve(params)
//   — fineTuning() built on first call, jobs() built on first call under it, both memoized.
```

### Reusing the raw impl instead of a parallel cooked tree (target output)

The cooked tier is derived from the raw tier (per [service-method-tiers.md](service-method-tiers.md)):
the generator emits **one** implementation per service node — the raw one — and the cooked methods
delegate into it. The tree therefore contains one impl type per node, not two.

```kotlin
// GENERATED — illustrative target output, not compiled here.
internal class ModelServiceImpl(
    private val client: HttpClient,
    private val serde: Serde,
    private val callContext: CallContext,
    private val errorMapper: ErrorMapper,
) : ModelService {

    // The single, real implementation: the raw tier. Built once, memoized.
    private val rawTier: ModelService.WithRawResponse by lazy { WithRawResponseImpl() }

    override fun withRawResponse(): ModelService.WithRawResponse = rawTier

    // Cooked methods reuse the raw impl — no parallel cooked tree, no duplicated dispatch.
    override fun retrieve(params: ModelRetrieveParams): Model =
        rawTier.retrieve(params).use { it.value() }

    private inner class WithRawResponseImpl : ModelService.WithRawResponse {
        override fun retrieve(params: ModelRetrieveParams): ParsedResponse<Model> { /* dispatch */ }
    }
}
```

So across the whole tree the generated impl count is **one per service node** (each carrying an
inner raw tier), not two — halving the dominant term, exactly as the issue asks.

## Design decisions and trade-offs

- **`by lazy` for sub-service accessors.** Kotlin's `by lazy { … }` is `LazyThreadSafetyMode.
  SYNCHRONIZED` by default: the first reader constructs the node, concurrent readers block until it
  is ready, and every later read returns the memoized instance. That matches the desired semantics
  (build-once, share-safely) with no hand-written double-checked locking. The whole `HttpClient` is
  thread-safe per its SPI contract, so a shared, lazily-built service graph is safe to use
  concurrently.

- **`by lazy` vs. a memoized supplier — Java-target note.** `by lazy` compiles to a synthetic
  `Lazy` field and is a Kotlin-runtime construct. If a future **Java** generation target is added,
  the equivalent is a memoized `Supplier` (e.g. a double-checked-locked or `Suppliers.memoize`-style
  holder) exposed behind the same `fooService()` accessor — same observable contract (build-once,
  thread-safe, memoized), different mechanism. The accessor method shape (`fun models():
  ModelService`) is identical either way, so the public surface does not depend on which mechanism
  backs it. Note one behavioral detail to preserve: with `by lazy` SYNCHRONIZED a *thrown*
  initializer failure is **not** memoized — a failed init re-runs on the next access — so a Java
  memoized supplier must match whichever retry semantics we standardize on.

- **Accessors are methods, not properties.** Emitting `fun models(): ModelService` (rather than a
  `val models`) keeps the Java call site `client.models()` natural and leaves room for the accessor
  to take per-call arguments later without a source break. The backing `by lazy` field stays
  `private`.

- **Lazy, not eager, even for shallow trees.** Uniform laziness keeps the generator mechanical (no
  "eager if shallow" heuristic) and means client construction cost is O(1) regardless of tree depth
  or breadth. A program that touches three services out of forty allocates three service objects.

- **Shared dependencies threaded down, not re-resolved.** `HttpClient`, `Serde`, the base
  `CallContext`, and the `ErrorMapper` are constructed once at the root and passed by reference into
  each lazily-built node. Nodes hold references; they do not re-resolve or re-wrap these. This keeps
  the per-node constructor trivial and the whole graph backed by one transport and one serde.

- **Context chain.** Each node carries the base `CallContext`
  (`org.dexpace.sdk.core.http.context`), which is promoted per call into `DispatchContext` →
  `RequestContext` → `ExchangeContext`. The service tree holds only the immutable base context; the
  promotion chain runs at call time inside the operation, so lazy node construction never bakes in
  per-call state.

## Ties into the runtime

- `org.dexpace.sdk.core.client.HttpClient` / `AsyncHttpClient` — the single shared transport threaded
  through every node; thread-safe per contract, so the shared lazy graph is safe.
- `org.dexpace.sdk.core.serde.Serde` — single shared serde reference passed down the tree.
- `org.dexpace.sdk.core.http.context.CallContext` (→ `DispatchContext` → `RequestContext` →
  `ExchangeContext`) — the base context each node holds; per-call promotion happens in the operation,
  not at node construction.
- `org.dexpace.sdk.core.http.response.ParsedResponse` — what the reused raw tier returns; the cooked
  methods call `.use { it.value() }`, the single-impl reuse that halves the type count.

## Acceptance mapping

- *Lazy sub-service accessors* — `by lazy` backing fields behind `fun foo(): FooService` accessors,
  at the root and every interior node.
- *Raw impl reused* — one impl type per node (the raw tier); cooked methods delegate into it via
  `rawTier.op(params).use { it.value() }`, so no parallel cooked tree is generated.
