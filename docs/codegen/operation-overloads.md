# Curated operation overload set

> Design spec. The Kotlin/Java in this document is **target generator output**, not code compiled
> in this repository.

## Problem

A naive generator emits an overload for every shape a caller might want to call an operation: with
and without a body, with and without optional path/query params, with and without a per-call
request-options argument, params-object vs. positional-primitives. The reference SDK has on the
order of a dozen `retrieve` overloads per operation. Multiply that by the **raw × cooked × sync ×
async** matrix from [service-method-tiers.md](service-method-tiers.md) and the count explodes.

In this codebase that explosion is not free. The repo enforces:

- **explicit-API strict mode** — every public overload needs an explicit visibility and return
  type, and is reviewed surface;
- **binary-compatibility-validator** (`apiCheck`) — every public overload is pinned in an `api/*.api`
  snapshot, so each one is a permanent binary-compat obligation that `apiDump` must regenerate and
  that can never be removed without a breaking change.

A wide cross-product is therefore a *permanent* explicit-API + binary-compat tax, paid on every
operation, in every tier. The reference SDK targets Java, where overloads are the only ergonomic
lever; we target Kotlin first, where **default arguments** collapse most of the cross-product into a
single method.

## Proposed policy: one canonical method + a small curated set

For each operation in each tier, the generator emits **one canonical method** plus a **fixed, small
curated overload set** — never the full cross-product.

### The canonical method

The canonical method takes the operation's typed params object and a request-options argument with
a default:

```kotlin
// GENERATED — illustrative target output, not compiled here.
public fun retrieve(
    params: ModelRetrieveParams,
    options: RequestOptions = RequestOptions.none(),
): Model
```

All optionality inside the request lives in the **params object's builder**, not in overloads. A
param that may be set, explicitly null, or omitted uses `Tristate<T>`
(`org.dexpace.sdk.core.serde.Tristate`) inside the params type, so "send `null`" and "omit" stay
distinct without spawning two overloads.

### The curated overload set (the only ones generated)

| # | Overload | When generated | Rationale |
|---|---|---|---|
| 1 | **Canonical** `op(params, options = …)` | always | The one true signature; `options` default covers the no-options call in Kotlin. |
| 2 | **Java no-options** `op(params)` | when a Java target is emitted | `@JvmOverloads` on the canonical method materializes this for Java callers, who have no default-argument support. Not a hand-written second overload. |
| 3 | **Bare-identifier convenience** `op(id: String, options = …)` | only when the operation has exactly **one required param** and it is a scalar path/identifier | Lets `retrieve("gpt-x")` work without a builder for the overwhelmingly common single-id case. Forwards to the canonical method with `params { … }`. |
| 4 | **Body-first convenience** `op(body: BodyType, options = …)` | only when the operation has **exactly one required param and it is the request body** (no required path/query params) | Lets `create(body)` work for create-style operations. Forwards to canonical. |

That is the whole set: at most **four** generated entry points per operation per tier, and #2 is
produced by `@JvmOverloads` rather than a separate declaration. Everything else is reachable by
building the params object.

### Curation rules (deterministic, so the generator is mechanical)

1. **Always emit the canonical params-object method.** It is the floor; every operation has it.
2. **`options` is always a defaulted trailing argument**, never its own overload. `@JvmOverloads`
   gives Java the no-options form.
3. **Emit the bare-identifier overload (#3) iff** the operation has exactly one required param,
   that param is a scalar path/query identifier (not a body), and it has no other required inputs.
   More than one required scalar → no positional overload; callers use the builder. (Avoids
   argument-order ambiguity, the classic `retrieve(a, b)` trap.)
4. **Emit the body-first overload (#4) iff** the operation's only required input is the request body.
   Mutually exclusive with #3 in practice (an operation rarely has a single required id *and* a
   single required body as its only inputs; if it has both, neither convenience overload is emitted
   and the builder is the entry point).
5. **Never** emit overloads that vary optional params positionally. Optional params live in the
   builder, full stop. This is the rule that kills the cross-product.
6. **Apply the identical set in every tier.** Raw, cooked, sync, async all get the same curated set
   — the raw tier returns `ParsedResponse<T>`, cooked returns `T`, async wraps in
   `CompletableFuture`. Tiers never *add* overloads of their own.

### Target output

```kotlin
// GENERATED — illustrative target output, not compiled here.
public interface ModelService {
    // 1 + 2: canonical, @JvmOverloads materializes the Java no-options form.
    @JvmOverloads
    public fun retrieve(
        params: ModelRetrieveParams,
        options: RequestOptions = RequestOptions.none(),
    ): Model

    // 3: single required scalar id → bare-identifier convenience, forwards to canonical.
    @JvmOverloads
    public fun retrieve(
        id: String,
        options: RequestOptions = RequestOptions.none(),
    ): Model =
        retrieve(ModelRetrieveParams.builder().id(id).build(), options)
}
```

A counter-example the rules **reject** — an operation with two required scalars gets no positional
overload:

```kotlin
// NOT GENERATED — rule 3 forbids positional overloads for multi-required-scalar ops.
// public fun retrieve(org: String, id: String): Model   // ambiguous arg order; use the builder.
```

## Design decisions and trade-offs

- **Default arguments over overloads.** Kotlin default arguments collapse the `options`-present /
  `options`-absent axis (and any optional-param axis) into one declaration. This is the single
  biggest lever against the cross-product and the main reason a Kotlin-first generator can stay far
  leaner than a Java-first one. The cost is borne by Java callers, who get exactly the forms
  `@JvmOverloads` materializes — which is why the curated set is defined in terms of *required*
  inputs only.

- **`Tristate<T>` instead of presence overloads.** Optional-with-null params do not fork into
  "set to null" vs. "omit" overloads; they use `Tristate.Present` / `Tristate.Null` /
  `Tristate.Absent` in the params type. One builder method, three semantics, zero extra overloads.

- **Bounded, deterministic generation.** The rules above are mechanical: given an operation's
  required-param profile, the generator emits a known, small set. No heuristic "emit if it seems
  convenient." This keeps the generator simple and the public surface predictable — important
  because every public method is an `apiCheck`-pinned commitment.

- **Builder is the escape hatch, and it is always present.** Anything the curated overloads don't
  cover is reachable through `Params.builder()`. There is no operation a caller cannot fully drive;
  they may just need the builder. That trade — slightly more verbose tail cases for a dramatically
  smaller pinned surface — is the whole point.

- **Uniform across tiers.** Because the set is identical in every tier, the four tiers stay in
  lockstep and the cross-tier matrix multiplies a *small constant* (≤4), not a dozen.

## Ties into the runtime

- `org.dexpace.sdk.core.serde.Tristate` — three-state optional params inside the params object,
  removing presence-driven overloads.
- explicit-API strict mode and `apiCheck` (binary-compatibility-validator) — the enforcement that
  makes a wide surface costly and a curated surface cheap; see the root `CLAUDE.md`.
- The OperationParams SPI (tracked separately) — the builder-backed params type the canonical method
  takes and the convenience overloads forward into.

## Acceptance mapping

- *Overload policy documented* — the four-row curated set and the six curation rules above.
- *Generated surface stays minimal* — at most four entry points per operation per tier (one of which
  is `@JvmOverloads`-materialized), no positional optional-param cross-product, builder as the escape
  hatch.
