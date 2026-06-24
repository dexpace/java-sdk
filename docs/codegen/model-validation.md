# The validate() / isValid() / validity() triad

> **Status:** design specification. The snippets show the *target shape* of the generated triad and
> the hand-written runtime that scores validity. Nothing here is compiled in this repository.

Builds on [the four-state JSON field model](json-field-model.md) and
[thin model classes](model-classes.md).

## Problem

Two features need to ask "how well does this payload match this model?":

1. **Union disambiguation.** When a schema says a value is one of several shapes and there is no
   discriminator to key on (see [discriminator-const-fields.md](discriminator-const-fields.md) for
   the case where there *is* one), the only way to pick the right member is to score how well the
   payload fits each candidate.
2. **Explicit caller-side validation.** A caller building a request, or inspecting a response, may
   want to know whether a model is fully populated and well-typed before acting on it.

But scoring is **expensive** — it walks the whole tree, attempts typed coercions of every `Raw`
field, and recurses into nested models — and it must **never run implicitly**. If validation ran on
the deserialize path, every response would pay full-tree validation cost whether or not anyone asked,
and forward-compatibility would be defeated: an unknown field that decode tolerated as `Raw` would
suddenly make deserialization "fail." Validation has to be opt-in and off the hot path.

## Proposed shape: a memoized, fail-soft triad

Generate three members on each model, all delegating to a hand-written runtime scorer:

```kotlin
// TARGET OUTPUT — generated on each model; bodies delegate to the runtime. Not compiled here.
public class User /* ... : JsonModel() ... */ {

    /** Force full validation, returning the structured result. Memoized. Never called on the
     *  deserialize path. */
    public fun validity(): Validity = VALIDATION.validate(this)

    /** Convenience: validity().isValid. */
    public fun isValid(): Boolean = validity().isValid

    /** Throws if invalid, returns this otherwise — for callers that want fail-fast ergonomics on
     *  top of the fail-soft core. */
    public fun validate(): User = also { check(isValid()) { validity().describe() } }

    private companion object {
        // The per-model validation plan: which fields are required, their expected types, and which
        // are themselves models to recurse into. Generated as data, scored by the runtime.
        private val VALIDATION = ModelValidation.of<User>(/* required keys, field type table, nested-model refs */)
    }
}
```

### The structured result (target output, hand-written runtime)

```kotlin
// TARGET OUTPUT — hand-written runtime in sdk-core. Not compiled here.
public class Validity internal constructor(
    /** A score in [0.0, 1.0]: fraction of fields that matched their declared shape, recursively. */
    public val score: Double,
    /** Per-field problems: missing-required, wrong-type (a Raw where a typed value was required),
     *  or a nested model's own problems. Empty iff fully valid. */
    public val problems: List<Problem>,
) {
    public val isValid: Boolean get() = problems.isEmpty()
    public fun describe(): String = problems.joinToString("; ") { it.path + ": " + it.reason }

    public data class Problem(public val path: String, public val reason: String)
}
```

### The scorer (target output, hand-written runtime)

```kotlin
// TARGET OUTPUT — hand-written runtime in sdk-core. Not compiled here.
public class ModelValidation<M : JsonModel> internal constructor(/* generated plan */) {

    // Memoized per instance — see "Memoization" below.
    public fun validate(model: M): Validity { /* walk fields against the plan */ }
}
```

The scorer is **fail-soft**: it never throws on a bad shape. A required field that is `Missing` or a
field that arrived as `Raw` where a typed value was required becomes a `Problem`, not an exception.
The result is *structured* — a score plus an itemized problem list with JSON-pointer-ish paths — so
both the union strategy (which wants the score) and a human caller (who wants the reasons) are served
by one pass.

## Recursion

Validation is recursive: a nested model field is validated by recursing into *its* triad, and its
problems are folded into the parent's `problems` with the path prefixed. Because the per-model plan
knows which fields are themselves `JsonModel` subtypes, the runtime walks the object graph without
the generated model carrying any traversal code — the generated side is still just the plan-as-data
from [model-classes.md](model-classes.md).

Cycles (a model that can transitively contain itself) are handled in the runtime by tracking visited
instances, so recursion terminates regardless of schema shape.

## Memoization

`validity()` is **memoized per model instance** — the first call scores the tree, subsequent calls
return the cached `Validity`. This matters because the union strategy may call `validity()` on the
same candidate more than once, and because a caller doing `isValid()` then `validity().describe()`
should pay for one pass, not two. Models are immutable (the backing field map never changes after
`build()`), so the memoized result can never go stale.

The cache lives in the runtime, keyed off the instance, so the generated model carries no mutable
state and stays a clean immutable value.

## Validity-scoring as the *fallback* union strategy

Validation scoring is the **last resort** for union disambiguation, not the first:

1. **Prefer a discriminator.** If the union has a discriminator field
   ([discriminator-const-fields.md](discriminator-const-fields.md)), read it once, look up the member
   by its const value, and deserialize exactly that one member. This is O(1) member selection plus a
   single deserialization — no scoring.
2. **Fall back to validity scoring only when there is no discriminator.** Deserialize the payload
   against each candidate member (each tolerant, producing `Raw` for fields it cannot bind), call
   `validity()` on each, and pick the highest `score`. This is N deserializations plus N validations,
   which is exactly why it is the fallback and why scoring must be cheap to *avoid* (discriminator
   first) rather than cheap to *run*.

```kotlin
// TARGET OUTPUT — union resolution sketch in the runtime. Not compiled here.
public fun <U> resolveUnion(raw: RawJson, members: List<UnionMember<U>>): U {
    val discriminated = members.firstNotNullOfOrNull { it.matchByDiscriminator(raw) }
    if (discriminated != null) return discriminated // path 1: no scoring

    // path 2: fall back to scoring, highest validity wins
    return members
        .map { it.deserializeTolerant(raw) to it }
        .maxByOrNull { (model, _) -> model.validity().score }
        ?.first ?: error("no union member matched: " + raw)
}
```

## How it ties into the existing runtime

- **`JsonField` / `RawJson`.** A `Problem` of kind "wrong type" is precisely a field that decoded to
  `JsonField.Raw` where the plan required a typed value; the scorer reads the four-state field map
  from [json-field-model.md](json-field-model.md) directly. Projecting a `Known<T>` back to `RawJson`
  for re-emission uses the `asRaw(serde)` overload, which is why scoring needs a
  [`Serde`](../../sdk-core/src/main/kotlin/org/dexpace/sdk/core/serde/Serde.kt) handle when it must
  re-serialize a value.
- **Off the deserialize path.** The Jackson `JsonField` module (see the field-model spec) never calls
  the triad; deserialization stays tolerant and fast. The triad is reachable only through the three
  generated members, which a caller — or the union strategy — invokes explicitly.
- **Thin generated side.** Per [model-classes.md](model-classes.md), the generated model contributes
  only the three one-line members plus a `VALIDATION` plan constant; all walking, scoring,
  memoization, and cycle-handling is hand-written runtime, tested and covered once.

## Design decisions and trade-offs

- **Fail-soft core, fail-fast wrapper.** The scorer never throws (`validity()` / `isValid()` are
  total), and `validate()` adds the throwing ergonomics on top. This serves both the union strategy
  (which must compare invalid candidates without exceptions) and callers who want `validate()` to be a
  guard clause.
- **Structured result, not a boolean.** Returning a score plus itemized problems means one pass
  serves disambiguation (needs the number) and debugging (needs the reasons). A boolean would force a
  second pass for diagnostics.
- **Discriminator-first.** Making scoring the explicit fallback — and documenting it as such — is what
  keeps the common case (discriminated unions) at one deserialization instead of N. Scoring is the
  safety net for schemas that genuinely lack a discriminator, not the default path.
- **Memoize because immutable.** Immutability is what makes per-instance memoization correct; we lean
  on the existing immutable-model invariant rather than adding cache-invalidation logic.

## Acceptance mapping

- Opt-in triad generated — the three generated members (`validate()` / `isValid()` / `validity()`),
  delegating to the hand-written `ModelValidation` runtime, memoized per instance.
- Not invoked during deserialize — the deserialize path (the Jackson `JsonField` module) never calls
  the triad; it stays tolerant and produces `Raw` rather than scoring. Validation is reachable only
  through the explicit members and the union fallback.
