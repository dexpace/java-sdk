# The four-state JSON field model

> **Status:** design specification. No code in this document is compiled. The Kotlin snippets show
> the *target shape* of types that would live in `sdk-core` (`JsonField`, `RawJson`) and in the
> `sdk-serde-jackson` adapter (the conversion module). They are illustrative, not the real API.

This is the foundation the rest of the codegen specs build on
([model classes](model-classes.md), [validation](model-validation.md),
[discriminator/const fields](discriminator-const-fields.md)). Read it first.

## Problem

A model generator needs a per-field wrapper that distinguishes **four** states, not three:

1. **present and well-typed** — the key is in the payload and decoded cleanly to the declared `T`.
2. **present but the wrong shape** — the key is in the payload but does not match `T` (a server
   sent an object where we expected a string, or a newer enum case we do not know). We must not
   throw on the deserialize path; we must keep the raw shape for round-tripping and let an explicit
   opt-in step decide what to do with it.
3. **explicit null** — the key is present with a JSON `null`.
4. **absent** — the key is missing from the payload entirely.

The existing [`Tristate<T>`](../../sdk-core/src/main/kotlin/org/dexpace/sdk/core/serde/Tristate.kt)
covers states 3, 4, and the well-typed half of 1 (`Absent` / `Null` / `Present`). It deliberately
has no "present but wrong type" escape hatch and carries no embedded JSON tree — by design, because
its job is the PATCH-write boundary, where the only question is *did the caller touch this field*
(absent vs null vs a value). `Tristate.Present` is even bounded `T : Any` specifically to make the
illegal "present-null" fourth state unconstructable.

Forward compatibility — surviving an unknown or wrong-typed value without throwing and without
losing it on re-serialize — is a different problem from the PATCH-write boundary, and it needs a
different type. That type is `JsonField<T>`.

### Prior art and the constraint it must not repeat

openai-java's `JsonField` / `JsonValue` (its `Values.kt`) solves the same four-state problem, but it
welds Jackson onto the value type: `JsonValue` is a Jackson tree, and `JsonField` carries Jackson
annotations and `JsonNode`s directly. That violates the `sdk-core` rule that the core has **zero
non-SLF4J runtime dependencies**. We must split exactly the way `Tristate` already splits: the sum
type and the JSON tree live dependency-free in `sdk-core`, and *all* Jackson ↔ core-type conversion
lives in `sdk-serde-jackson`, reachable only through the [`Serde`](../../sdk-core/src/main/kotlin/org/dexpace/sdk/core/serde/Serde.kt)
SPI.

## Proposed shape

### `RawJson` — a dependency-free JSON tree (target output)

`RawJson` is a small immutable sum type modelling the seven JSON shapes. It is the "escape hatch"
container: when a value is present but does not match the declared `T`, we keep it as `RawJson` so it
can be inspected, validated, or re-serialized byte-faithfully. It is the embedded tree that
`Tristate` deliberately lacks.

```kotlin
// TARGET OUTPUT — lives in sdk-core, package org.dexpace.sdk.core.serde. Not compiled here.
public sealed class RawJson {
    public object Null : RawJson()
    public data class Bool(public val value: Boolean) : RawJson()

    /** Numbers are kept as their lexical form so 1e3, 1000, and 1000.0 round-trip unchanged and
     *  no precision is lost coercing through Double. Typed reads parse on demand. */
    public data class Number(public val literal: String) : RawJson()
    public data class Str(public val value: String) : RawJson()
    public data class Array(public val elements: List<RawJson>) : RawJson()

    /** Insertion-ordered to preserve wire order on re-serialize. */
    public data class Object(public val entries: Map<String, RawJson>) : RawJson()
}
```

`RawJson` compiles to Java-8 bytecode: a Kotlin `sealed class` lowers to an abstract class plus
subclasses with no `permits` clause, so it is safe in the Java-8 `sdk-core` module (the same property
that lets `Tristate` be sealed there). It has no dependency on any parser; it is just data.

### `JsonField<T>` — the four-state wrapper (target output)

```kotlin
// TARGET OUTPUT — lives in sdk-core, package org.dexpace.sdk.core.serde. Not compiled here.
public sealed class JsonField<out T> {

    /** State 1: present and decoded to the declared T. */
    public data class Known<out T : Any>(public val value: T) : JsonField<T>()

    /** State 4: key absent from the payload. Singleton (like Tristate.Absent). */
    public object Missing : JsonField<Nothing>()

    /** State 3: key present, explicit JSON null. Singleton (like Tristate.Null). */
    public object Null : JsonField<Nothing>()

    /** State 2: present but not matching T — kept as the raw tree for round-trip and validation. */
    public data class Raw(public val raw: RawJson) : JsonField<Nothing>()

    public val isKnown: Boolean get() = this is Known<*>
    public val isMissing: Boolean get() = this is Missing
    public val isNull: Boolean get() = this is Null
    public val isRaw: Boolean get() = this is Raw

    /** Typed value if and only if state 1; null for Missing / Null / Raw. A Raw value is NOT
     *  silently coerced here — coercion is an explicit, opt-in step (see model-validation.md). */
    public fun getOrNull(): T? = (this as? Known)?.value

    /** The underlying raw tree for ANY state, for byte-faithful re-serialization. */
    public fun asRaw(): RawJson = when (this) {
        is Raw    -> raw
        is Known  -> error("requires Serde to project T back to RawJson; see asRaw(Serde)")
        Null      -> RawJson.Null
        Missing   -> RawJson.Null // callers must check isMissing to omit the key entirely
    }

    public inline fun <R> fold(
        onMissing: () -> R,
        onNull: () -> R,
        onRaw: (RawJson) -> R,
        onKnown: (T) -> R,
    ): R = when (this) {
        Missing  -> onMissing()
        Null     -> onNull()
        is Raw   -> onRaw(raw)
        is Known -> onKnown(value)
    }

    public companion object {
        @JvmStatic public fun <T> missing(): JsonField<T> = Missing
        @JvmStatic @JvmName("nullValue") public fun <T> nullValue(): JsonField<T> = Null
        @JvmStatic public fun <T : Any> known(value: T): JsonField<T> = Known(value)
        @JvmStatic public fun <T> raw(raw: RawJson): JsonField<T> = Raw(raw)
    }
}
```

This mirrors `Tristate`'s shape on purpose: singleton objects for the value-less states (`Missing`,
`Null`) so they satisfy any `JsonField<T>` target via `Nothing`, a `data class` for the carriers, and
`@JvmStatic` factories so Java callers and generated code can construct fields without touching the
constructors. `Known` is bounded `T : Any` for the same reason `Tristate.Present` is — a
`Known(null)` would be an illegal alias of `Null`.

### Jackson conversion lives only in the adapter

The conversion between Jackson's `JsonNode`/parser tokens and `RawJson`/`JsonField` goes in
`sdk-serde-jackson`, structured exactly like
[`TristateModule`](../../sdk-serde-jackson/src/main/kotlin/org/dexpace/sdk/serde/jackson/TristateModule.kt):

- A `ContextualDeserializer` resolves the declared `T` per call-site (the same
  `createContextual` + `containedType(0)` trick `TristateDeserializer` already uses to extract the
  `T` from `Tristate<T>`). On a present, well-typed token it produces `Known<T>`; on a present token
  that fails to bind to `T` it does **not** throw — it captures the subtree as `RawJson` and produces
  `Raw`. `getNullValue` produces `Null`; `getEmptyValue` / the field default produces `Missing`.
- A `BeanSerializerModifier` + custom `BeanPropertyWriter` omits the property entirely for `Missing`
  (the same mechanism `TristatePropertyWriter` uses for `Tristate.Absent`) and emits the captured
  raw tree verbatim for `Raw`.

Generated models therefore declare `JsonField<T>` fields without importing anything from Jackson, and
a different `Serde` implementation (a future XML or CBOR adapter) can supply its own conversion. The
[`Serde`](../../sdk-core/src/main/kotlin/org/dexpace/sdk/core/serde/Serde.kt) interface stays the
single injection point: a model never reaches for a concrete mapper.

The one place this needs the `Serde` SPI inside `sdk-core` is projecting a `Known<T>` *back* to
`RawJson` (the `asRaw(serde)` overload above) — that round-trips `T` through
`serde.serializer.serializeToByteArray` and re-parses, which is why the no-arg `asRaw()` cannot do it
for `Known`. Validation ([model-validation.md](model-validation.md)) is the main caller.

## The read-path vs PATCH-path boundary

This is the rule that keeps `JsonField` and `Tristate` from being wrongly merged, and it must be
honored by the generator and the runtime:

- **Read path (responses, forward-compat): `JsonField<T>`.** Decoding a server response must never
  throw on an unexpected shape and must preserve unknowns for re-serialization. State 2 (`Raw`) is
  the whole point. A response model's fields are `JsonField<T>`.
- **Write path (PATCH bodies, three-state intent): `Tristate<T>`.** A PATCH body asks a different
  question — *did the caller intend to set, clear, or leave alone this field*. There is no
  "wrong-typed" state on the write side; the caller is constructing well-typed values. A PATCH
  request model's fields stay `Tristate<T>`.

Mapping between them is one-directional and lossy on purpose:

```kotlin
// TARGET OUTPUT — interop helper, sdk-core. Not compiled here.
public fun <T : Any> JsonField<T>.toTristate(): Tristate<T> = when (this) {
    is JsonField.Known -> Tristate.Present(value)
    JsonField.Null     -> Tristate.Null
    JsonField.Missing  -> Tristate.Absent
    // A read-side Raw has no well-typed value to PATCH with. Collapsing it to Absent ("leave
    // alone") is the only safe default; a caller that wants to forward an unknown verbatim must
    // do so explicitly rather than have it silently become a typed PATCH.
    is JsonField.Raw   -> Tristate.Absent
}
```

There is intentionally **no** total `Tristate<T> -> JsonField<T>` that fabricates a `Raw`: a
three-state write value can only ever land in `Known` / `Null` / `Missing`, never `Raw`. Keeping the
`Raw` case unreachable from the write side is what prevents a forward-compat unknown from leaking into
a PATCH payload as if the caller had set it.

## Design decisions and trade-offs

- **Why a separate type instead of a fourth `Tristate` case.** Adding `Raw` to `Tristate` would force
  every PATCH-write call-site to handle a state it can never legitimately produce, and would weaken
  the `T : Any` guarantee that makes `Tristate.Present` safe. Two narrow types each enforce their own
  invariant; one wide type enforces neither.
- **Numbers as lexical strings.** Keeping `RawJson.Number` as its source literal avoids the classic
  `Double` round-trip bugs (`20000000000000001` → `2.0E16`, trailing-zero loss) and lets typed reads
  choose `Int`/`Long`/`BigDecimal` on demand. The cost is that numeric equality is lexical unless a
  caller parses; that is the right default for a faithful-round-trip container.
- **`Raw` does not auto-coerce to `T`.** `getOrNull()` returns null for `Raw` rather than attempting a
  late parse. Coercion is expensive and can fail; it belongs in the explicit, opt-in validation step
  ([model-validation.md](model-validation.md)), never on an accessor that callers expect to be cheap.
- **Insertion-ordered `RawJson.Object`.** Preserves wire order on re-serialize, which matters for
  signatures, golden-file tests, and human-diffable payloads.

## Acceptance mapping

- Dependency-free `JsonField` + `RawJson` in `sdk-core` — the `RawJson` / `JsonField` snippets above,
  with no parser dependency, Java-8-safe sealed classes.
- Jackson conversion in the adapter only — the `ContextualDeserializer` + `BeanSerializerModifier`
  module in `sdk-serde-jackson`, mirroring `TristateModule`, behind the `Serde` SPI.
- `Tristate` interop documented, including the read-path (forward-compat) vs PATCH-path (three-state)
  boundary — the section above, with the one-directional `toTristate()` and the deliberately-absent
  reverse mapping.
- `apiDump` — adding `JsonField` / `RawJson` to `sdk-core` is a public-API change and requires a
  regenerated `sdk-core/api/*.api` snapshot committed with the implementation (out of scope for this
  design doc, noted for the implementing change).
