# Thin model classes over a hand-written runtime

> **Status:** design specification. The Kotlin/Java snippets show the *target shape* a future
> generator would emit and the *target shape* of the hand-written runtime they sit on. Nothing here
> is compiled in this repository.

Builds on [the four-state JSON field model](json-field-model.md) — read that first for the
`JsonField` / `RawJson` vocabulary used throughout.

## Problem

The reference SDK we are modelling against is roughly **850k lines across ~1,100 model files**. If
every generated model inlines its own copy of the forward-compatibility machinery — the
additional-properties map, the [validate/validity triad](model-validation.md), the
[dual typed/raw accessors](discriminator-const-fields.md), null/absent bookkeeping — three things
break:

1. **The coverage floor.** `sdk-core` enforces an **aggregate 80% line-coverage floor** (`minBound(80)`
   on the root-aggregate `:koverVerify`, wired into `check`). A thousand model files each carrying a
   few hundred lines of near-identical, largely-untested boilerplate would swamp the aggregate and
   make the floor meaningless — and would be impossible to test meaningfully per-class.
2. **The binary-compatibility baseline.** `apiCheck` validates every public signature against a
   committed `.api` snapshot. Inlined machinery means every model contributes dozens of public
   members to the baseline; a single change to the shared shape (say, adding a `validity()` overload)
   would require regenerating a thousand `.api` entries.
3. **Build and review cost.** Detekt, ktlint, and `allWarningsAsErrors` run over every line. A
   deprecation or style nit replicated a thousand times is a thousand failures.

## Proposed shape: thin model, fat runtime

Push the invariant machinery into a hand-written runtime and emit only **the field list plus
accessors** per model — target ≈ under 100 lines each.

### The runtime base (target output, hand-written)

```kotlin
// TARGET OUTPUT — hand-written runtime in sdk-core, package org.dexpace.sdk.core.model.
// Not compiled here.
public abstract class JsonModel {

    /** The full field map, including unknown keys the server sent that this model has no accessor
     *  for. Insertion-ordered so re-serialization preserves wire order. This is the single source
     *  of truth; every typed accessor reads through it. */
    protected abstract val fields: Map<String, JsonField<*>>

    /** Forward-compat: keys present on the wire with no declared accessor. Backed by the same map. */
    public fun additionalProperties(): Map<String, RawJson> =
        fields.filterKeys { it !in declaredKeys() }
              .mapValues { (_, f) -> f.asRaw() }

    /** Declared keys for this model — generated as a constant set per subclass (see below). */
    protected abstract fun declaredKeys(): Set<String>

    /** Shared accessor helper: read a declared field, four-state-aware, no coercion of Raw. */
    protected fun <T : Any> field(name: String): JsonField<T> {
        @Suppress("UNCHECKED_CAST")
        return (fields[name] as? JsonField<T>) ?: JsonField.Missing
    }

    override fun equals(other: Any?): Boolean =
        other is JsonModel && other::class == this::class && other.fields == fields
    override fun hashCode(): Int = fields.hashCode()
}
```

The runtime owns: the field map, `additionalProperties()` (forward-compat round-tripping),
equality/hashing, and the typed-read helper. It is hand-written, fully tested **once**, and counts
toward coverage **once**.

### The generated model (target output, generated — thin)

```kotlin
// TARGET OUTPUT — generated, one file per model. Illustrative; not compiled here.
public class User private constructor(
    override val fields: Map<String, JsonField<*>>,
) : JsonModel() {

    // Typed accessors. Each is a one-liner delegating to the runtime helper. No logic here.
    public fun id(): JsonField<String> = field("id")
    public fun email(): JsonField<String> = field("email")
    public fun roles(): JsonField<List<String>> = field("roles")

    // Raw accessors (the dual-accessor pattern — see discriminator-const-fields.md) are likewise
    // one-liners: public fun _id(): RawJson = field<String>("id").asRaw()

    override fun declaredKeys(): Set<String> = DECLARED_KEYS

    public companion object {
        private val DECLARED_KEYS = setOf("id", "email", "roles")
        @JvmStatic public fun builder(): Builder = Builder()
    }

    public class Builder internal constructor() : org.dexpace.sdk.core.util.Builder<User> {
        private val acc = LinkedHashMap<String, JsonField<*>>()
        public fun id(value: String): Builder = apply { acc["id"] = JsonField.known(value) }
        public fun email(value: String): Builder = apply { acc["email"] = JsonField.known(value) }
        // raw setter sibling: public fun id(raw: RawJson): Builder = apply { acc["id"] = JsonField.raw(raw) }
        override fun build(): User = User(acc.toMap())
    }
}
```

Everything model-specific is data: the accessor names, their `JsonField<T>` return types, and the
`DECLARED_KEYS` constant. There is no per-class machinery — no inlined validation, no inlined
additional-properties handling, no inlined serde. A 40-field model is ~80 accessor lines plus a
builder, not several hundred lines of replicated logic.

The model follows the house conventions already in `sdk-core`: private constructor + `Builder`
implementing `Builder<T>` (`apply { … }` setters with explicit return types under explicit-API strict
mode), `@JvmStatic` factories for Java callers, immutable backing map.

## How it ties into the existing runtime

- **Serde SPI.** Deserialization produces the `Map<String, JsonField<*>>` via the
  [`Serde`](../../sdk-core/src/main/kotlin/org/dexpace/sdk/core/serde/Serde.kt) deserializer; the
  Jackson adapter's `JsonField` module (see [json-field-model.md](json-field-model.md)) decodes each
  field into `Known` / `Null` / `Raw` / `Missing`. The model class itself imports nothing from
  Jackson.
- **`Tristate` for writes.** Request/PATCH models stay
  [`Tristate`](../../sdk-core/src/main/kotlin/org/dexpace/sdk/core/serde/Tristate.kt)-typed per the
  read-path vs PATCH-path boundary in the field-model spec. The generator emits `JsonModel`-based
  read models and `Tristate`-field write models from the same schema, never mixing the two.
- **Pipelines and paging.** A generated model is an ordinary immutable value; it flows through the
  existing request/response pipeline and is what a
  [`Paginator<T>`](../../sdk-core/src/main/kotlin/org/dexpace/sdk/core/pagination/Paginator.kt)
  yields as its `T` (`iterateAll()` / `streamAll()`). Nothing in the model layer reaches into the
  pipeline; the model is just the payload type.
- **Request bodies.** A write model serializes through `Serde` and is wrapped in a
  [`RequestBody`](../../sdk-core/src/main/kotlin/org/dexpace/sdk/core/http/request/RequestBody.kt)
  (`RequestBody.create(...)`); because the serialized form is a buffered byte payload it is naturally
  `isReplayable()` for retries.

## Coverage and binary-compatibility strategy for generated code

This is an explicit, up-front decision, not something to discover later:

### Coverage — exclude generated modules from the aggregate floor

Generated model code is thin delegation with no branching logic to test; the logic it delegates to
(the runtime base, the serde module) is hand-written and tested directly. Running the aggregate 80%
floor over generated code would either force meaningless generated tests or drag the floor down.

**Decision:** generated model code lives in its own module(s), and those modules are **excluded from
the root aggregate Kover floor**. The runtime base (`JsonModel` and friends) stays in `sdk-core` and
remains fully covered by the existing floor. Concretely: the generated module applies Kover but is
not summed into the root-aggregate `:koverVerify`, mirroring how the root Kover filter already
excludes test fixtures (`org.dexpace.sdk.core.testing.*`) — except here the exclusion is module-scoped
rather than a package glob, so we never reintroduce a broad package-glob exclude in `sdk-core`.

This keeps the invariant in `CLAUDE.md` honest: the 80% floor still means something for hand-written
code, and the generated-module exclusion is narrow and deliberate.

### Binary compatibility — a separate `.api` baseline for generated code

The generated public surface is large and churns with the upstream schema. Validating it against the
same baseline as the curated `sdk-core` surface would mean a schema bump regenerates a thousand `.api`
entries inside the same review as a hand-written change.

**Decision:** generated modules get their **own `.api` baseline**, separate from `sdk-core`'s. The
binary-compatibility-validator runs per-module, so the generated module's `api/*.api` snapshot lives
with the generated module and is regenerated (`apiDump`) as part of a schema-update change — never
mixed into a hand-written `sdk-core` API change. The two-getters-plus-two-setters dual-accessor
pattern from [discriminator-const-fields.md](discriminator-const-fields.md) is exactly the kind of
wide, regular surface that benefits from living in its own baseline.

## Design decisions and trade-offs

- **One backing map vs. one field per property.** A single `Map<String, JsonField<*>>` is what makes
  models thin and makes `additionalProperties()` free. The cost is a map lookup per accessor and a
  cast; both are negligible next to the JSON parse that produced the model, and the cast is contained
  in one runtime helper.
- **Accessors return `JsonField<T>`, not `T?`.** Callers see the four-state distinction (known /
  null / raw / missing) rather than a lossy `T?`. A convenience `…OrNull()` sibling can be generated
  for callers that only want the happy path, but the four-state accessor is primary so forward-compat
  information is never silently discarded at the accessor boundary.
- **Generated module separation is structural, not cosmetic.** Putting generated code in its own
  module is what makes both the coverage exclusion and the separate `.api` baseline clean — they fall
  out of module boundaries rather than needing per-package allowlists.

## Acceptance mapping

- Generated models are thin — the `User` snippet: field list + one-line accessors + builder, the
  invariant machinery pushed into the hand-written `JsonModel` runtime.
- Coverage + binary-compat strategy for generated code documented — the two "Decision" subsections:
  module-scoped Kover exclusion for the aggregate floor, and a separate per-module `.api` baseline
  regenerated on schema bumps.
