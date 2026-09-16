# Discriminator and const fields

> **Status:** design specification. The snippets show the *target shape* a future generator would
> emit. Nothing here is compiled in this repository.

Builds on [the four-state JSON field model](json-field-model.md) and
[thin model classes](model-classes.md).

## Problem

Two closely related field kinds fall out of the four-state field model and need a dedicated codegen
template:

- **Const fields.** A schema pins a field to a fixed value (`"object": "user"`, `"version": 2`). The
  generated model should default it to that value so a caller never has to set it, while still
  surviving a server that sends something *other* than the expected constant (forward compatibility —
  a const today may gain new allowed values tomorrow).
- **Discriminator fields.** A union keys member selection off a field's value (`"type": "circle"` vs
  `"type": "square"`). The discriminator must be readable *both* as the typed enum the model expects
  *and* as the raw wire value, because union resolution reads the raw value before any member has been
  chosen, and forward compat requires tolerating a discriminator value we do not yet have a member
  for.

Both want the same thing: a field that has a **sensible default** and exposes **both a typed and a
raw view**. That is the dual-accessor pattern.

## Proposed shape: defaulted raw value + dual accessors

A const/discriminator field is generated as a `JsonField<T>` (from
[json-field-model.md](json-field-model.md)) that **defaults to the const's raw value** and exposes
two getters and two setters.

```kotlin
// TARGET OUTPUT — generated const field on a model. Illustrative; not compiled here.
public class User /* private constructor(...) : JsonModel() */ {

    // ---- const field "object" pinned to "user" ----

    /** Typed accessor: the const projected to its declared type. Returns the default const when the
     *  field was absent, and the typed value when the server sent the expected shape. A server value
     *  that does not match T comes back via the raw accessor instead (forward-compat). */
    public fun objectType(): JsonField<String> = field("object").orDefault(DEFAULT_OBJECT)

    /** Raw accessor: the underlying wire value, whatever it was — including an unexpected constant a
     *  newer server sent that this model has no typed mapping for. */
    public fun _objectType(): RawJson = field<String>("object").asRaw(DEFAULT_OBJECT_RAW)

    public companion object {
        private const val DEFAULT_OBJECT: String = "user"
        private val DEFAULT_OBJECT_RAW: RawJson = RawJson.Str("user")
    }

    public class Builder /* : Builder<User> */ {
        private val acc = LinkedHashMap<String, JsonField<*>>()

        /** Typed setter: set the const/discriminator to a typed value. */
        public fun objectType(value: String): Builder = apply { acc["object"] = JsonField.known(value) }

        /** Raw setter: forward an arbitrary wire value verbatim — used to round-trip an unknown
         *  discriminator value the SDK does not model yet. */
        public fun objectType(raw: RawJson): Builder = apply { acc["object"] = JsonField.raw(raw) }
    }
}
```

### The default is applied at the accessor, not baked into the stored field

The stored field map ([model-classes.md](model-classes.md)) keeps the *actual* state — `Missing` when
the server omitted the key, `Known`/`Raw` when it sent something. The const default is applied by the
accessor (`orDefault(...)` / `asRaw(default)`), not written into the map on construction. This keeps
two properties:

- **Round-trip fidelity.** A model that was deserialized from a payload that omitted the const
  re-serializes without inventing a key (`additionalProperties()` and the serializer see `Missing`),
  unless a caller explicitly set it. The default is a *read-time* convenience, not a *write-time*
  fabrication.
- **Forward compatibility.** A server that sends an unexpected const value stores it as `Raw`; the
  typed accessor still has a sane default to fall back on, and the raw accessor surfaces the real
  value so nothing is lost.

### Discriminator fields are the same template, plus a const value the union keys on

A discriminator is a const field whose value is what union resolution matches against. The dual
accessor is what makes resolution work *before* a member is chosen:

```kotlin
// TARGET OUTPUT — union member matching by discriminator. Not compiled here.
public fun matchByDiscriminator(raw: RawJson): Shape? {
    // Read the RAW discriminator off the undecoded tree — no member committed yet.
    val tag = (raw as? RawJson.Object)?.entries?.get("type") as? RawJson.Str ?: return null
    return when (tag.value) {
        "circle" -> Circle.fromRaw(raw)
        "square" -> Square.fromRaw(raw)
        else     -> null // unknown tag: let the caller fall back to validity scoring
    }
}
```

The raw accessor is load-bearing here: resolution must read the discriminator from `RawJson` before
any typed model exists, and an unknown tag returns `null` so the union strategy can fall back to
[validity scoring](model-validation.md) rather than throwing. This is exactly why the discriminator
is the **first**, cheap path in `resolveUnion` (one raw read, O(1) member lookup) and scoring is the
fallback.

## Why two getters and two setters per field

The dual-accessor pattern means every const/discriminator field contributes **two getters
(`objectType()` typed, `_objectType()` raw) and two setters (typed `objectType(String)`, raw
`objectType(RawJson)`)** to the public surface. This is deliberate, and it reinforces the binary-
compatibility decision from [model-classes.md](model-classes.md):

- The generated surface is **wide and regular** — exactly the kind of large, mechanically-generated
  API that should live behind its **own `.api` baseline**, separate from the curated `sdk-core`
  surface, and be regenerated (`apiDump`) as part of a schema-update change rather than mixed into a
  hand-written API change.
- Because every field follows the identical two-getter/two-setter template, the baseline churns
  predictably with the schema and never with the runtime.

## How it ties into the existing runtime

- **`JsonField` / `RawJson`.** The whole template is expressed in the four-state field types from
  [json-field-model.md](json-field-model.md): the typed accessor reads `Known`/falls back to the
  const default, the raw accessor reads `asRaw(...)`, and an unexpected server constant lands in
  `Raw`. No new field machinery is introduced.
- **`Serde` SPI.** Re-serializing a const field round-trips through the
  [`Serde`](../../sdk-core/src/main/kotlin/org/dexpace/sdk/core/serde/Serde.kt) serializer; an
  explicitly-set typed value serializes as the value, a `Raw` serializes verbatim, and a `Missing`
  const is omitted (the Jackson `JsonField` module's property writer skips it, the same way
  [`TristatePropertyWriter`](../../sdk-serde-jackson/src/main/kotlin/org/dexpace/sdk/serde/jackson/TristateModule.kt)
  skips `Tristate.Absent`).
- **Union resolution and validation.** The discriminator template is the cheap first path of the
  union strategy described in [model-validation.md](model-validation.md); validity scoring is only
  reached when the discriminator is absent or carries an unknown value.

## Design decisions and trade-offs

- **Default at read time, not construction time.** Applying the const default in the accessor keeps
  re-serialization faithful (no fabricated keys) and keeps the stored field map a truthful record of
  the wire. The trade-off is that the default lives in a generated constant per field rather than in
  the map; that is cheap and keeps models immutable and round-trip-safe.
- **Dual accessors instead of a single typed-or-throw accessor.** A typed-only accessor would have to
  throw or lie when a server sends an unmodelled const/discriminator value. The raw sibling makes
  forward compatibility explicit and gives union resolution the pre-decode read it needs.
- **Accepting the wide surface.** Two getters and two setters per field is more public API than a
  single typed accessor, but it is the cost of honest forward compatibility, and the separate `.api`
  baseline absorbs the churn so it never destabilizes the curated `sdk-core` surface.

## Acceptance mapping

- Const/discriminator template — const and discriminator fields generated as `JsonField<T>` defaulted
  to the const's raw value, with the default applied at the accessor and the discriminator readable
  pre-decode for union matching.
- Dual accessors generated — a typed getter + raw getter and a typed setter + raw setter per field,
  forming the wide, regular surface that lives behind the separate generated-code `.api` baseline.
