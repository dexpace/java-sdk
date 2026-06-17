# Strict structured-output JSON-schema encoding

Status: design spec. Closes #66.

## Problem

Some providers accept a JSON schema that *constrains* a model's structured output: the model is
forced to emit a document that validates against the schema. These "strict" modes are far less
forgiving than ordinary schema validation. They typically reject schemas unless:

- every property of every object is listed in `required`, and
- every object sets `additionalProperties: false`, and
- optionality is expressed as a **type union with `null`**, not by omission from `required`.

If the generator ever derives such a schema from an OpenAPI / JSON-schema input contract, it has to
emit this strict shape exactly. The naive encoding — "optional field ⇒ leave it out of `required`"
— is rejected by strict mode and silently mis-models PATCH-style optionality at runtime. This spec
fixes the mapping from a generated DTO to its strict schema, and pins where that derivation is
allowed to live.

## Where the derivation lives

**Adapter module only — never `sdk-core`.** `sdk-core` ships no embedded serializer and no
schema machinery (see `Serde`'s KDoc: "Concrete implementations live outside `sdk-core` since
`sdk-core` deliberately ships no embedded serializer."). Schema derivation is structurally the same
kind of concern: it reads a Jackson type model and emits a JSON document. It therefore belongs in a
Jackson-backed adapter alongside `sdk-serde-jackson`, not in the toolkit core.

We also **do not pull a JSON-schema library.** The strict subset we emit is small and fully
determined by the DTO shape, so the adapter hand-rolls:

1. a **derivation** pass (DTO → strict schema JSON), and
2. a **subset validator** that checks an arbitrary schema document is in the strict subset before it
   is sent (see [fail-soft-validator-skeleton.md](fail-soft-validator-skeleton.md) for the validator
   idiom this reuses).

A full JSON-schema validator is out of scope; we only validate the slice we generate.

## Encoding rules

Given a generated DTO, the derived schema obeys all of the following. Each rule is non-negotiable
for strict mode.

### R1 — every object is closed

Every generated `object` schema carries `"additionalProperties": false`. No open maps are emitted
for a fixed DTO. Free-form maps (a Kotlin `Map<String, V>`) are modelled with an explicit
`additionalProperties` *schema* (the `V` schema), which is a different construct and is allowed.

### R2 — every property is required

`required` lists **every** property key, including the ones that are logically optional. Optionality
never shows up as absence from `required`.

### R3 — optional ⇒ nullable union

A logically optional field is encoded as a union of its value type with `null`:

```json
"type": ["string", "null"]
```

or, for a `$ref`'d sub-schema, `"anyOf": [ { "$ref": "..." }, { "type": "null" } ]`.

### R4 — nullable and optional collapse to the same wire shape

Strict mode cannot distinguish "absent" from "present-and-null" — both arrive as `null`. This maps
directly onto `sdk-core`'s `Tristate<T>`:

- `Tristate.Absent` and `Tristate.Null` both serialize, under strict mode, to `null`.
- `Tristate.Present(v)` serializes to `v`.

The generator therefore renders a `Tristate<T>` field and a plain nullable `T?` field to the **same**
strict schema fragment (`["<T>", "null"]`, in `required`). The richer absent-vs-null distinction is
recovered at the runtime serde boundary by `sdk-serde-jackson`'s `TristateModule`, not in the schema.

### R5 — enums are closed

A Kotlin `enum class` becomes `"enum": [...]` with every constant, plus `null` in the value list if
the field is optional (R3). No `default` is emitted into a strict schema.

### R6 — deterministic `$defs` names

Nested object types are hoisted into `$defs` keyed by a **fully-qualified, deterministic** name
(package-qualified type name with a stable mangling for generics), never the bare simple name. Two
DTOs called `Metadata` in different packages must not collide. This mirrors the deterministic-name
rule in [fail-soft-validator-skeleton.md](fail-soft-validator-skeleton.md) §"Deterministic
definition names".

## Worked example (target output)

Given this generated DTO (illustrative target output, not compiled here):

```kotlin
// GENERATED — illustrative; not part of this repo.
public data class UserPatch(
    val id: String,                       // required value
    val nickname: String?,                // optional, plain nullable
    val avatar: Tristate<String>,         // optional, PATCH tri-state
    val role: Role,                       // enum, required
)

public enum class Role { ADMIN, MEMBER, GUEST }
```

the adapter derives (target output):

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["id", "nickname", "avatar", "role"],
  "properties": {
    "id":       { "type": "string" },
    "nickname": { "type": ["string", "null"] },
    "avatar":   { "type": ["string", "null"] },
    "role": {
      "anyOf": [
        { "$ref": "#/$defs/com.example.api.model.Role" }
      ]
    }
  },
  "$defs": {
    "com.example.api.model.Role": {
      "type": "string",
      "enum": ["ADMIN", "MEMBER", "GUEST"]
    }
  }
}
```

Note `nickname` (plain nullable) and `avatar` (`Tristate`) collapse to the identical fragment per
R4: both are required-and-nullable in the schema; only the runtime serde keeps `absent` and `null`
apart.

## Trade-offs and decisions

- **Strictness over expressiveness.** All-required + closed objects is a deliberately narrow subset.
  It rejects schema features (open objects, conditional subschemas, `default`) that strict providers
  reject anyway, so the narrowness is a feature: the subset validator can be a few dozen lines.
- **No schema library.** Pulling a general JSON-schema implementation would buy validation power we
  do not need and a dependency the toolkit philosophy forbids in shipped modules. Hand-rolling keeps
  the derivation auditable and the dependency surface flat.
- **`Tristate` is the bridge, not a schema concept.** The schema cannot encode three states, so the
  generator does not try. It emits two states (`value | null`) and relies on
  `sdk-serde-jackson`'s existing `Tristate` ser/de to carry the third state on the wire.
- **Deterministic names protect against silent collisions** — the most common foot-gun when many
  small DTOs share simple names across an API surface.

## Acceptance mapping

- *Encoding rules documented* — R1–R6 above.
- *Confirmed adapter-only (no core dep)* — see "Where the derivation lives"; derivation and the
  subset validator live in a Jackson adapter, `sdk-core` gains nothing.
