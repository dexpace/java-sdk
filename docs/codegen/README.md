# Codegen design specifications

This directory holds design specifications for the planned model-layer code generator. They are
**design documents only** — there is no generator code, no KotlinPoet templates, and no generated
sources in this repository yet. Every Kotlin/Java snippet in these specs is illustrative *target
output*: it shows the shape a future generator would emit, and is not compiled as part of the build.

The guiding principle across all of these specs is the same one that already governs `sdk-core`:
**logic lives in a hand-written runtime, generated code is thin.** A generated model is a field list
plus accessors; everything that is invariant across models — the four-state field representation,
serde wiring, validation scoring, dual typed/raw access — is written once in `sdk-core` (or an
adapter) and shared. This keeps generated files small, keeps the binary-compatibility baseline for
generated code stable, and keeps the coverage floor meaningful.

These specs build on the existing `sdk-core` serde surface — primarily
[`Tristate`](../../sdk-core/src/main/kotlin/org/dexpace/sdk/core/serde/Tristate.kt) and the
[`Serde`](../../sdk-core/src/main/kotlin/org/dexpace/sdk/core/serde/Serde.kt) SPI — and on the
Jackson adapter pattern established by
[`TristateModule`](../../sdk-serde-jackson/src/main/kotlin/org/dexpace/sdk/serde/jackson/TristateModule.kt).

## Specifications

| Spec | Covers |
|---|---|
| [The four-state JSON field model](json-field-model.md) | `JsonField<T>` + `RawJson`: a dependency-free four-state field wrapper and embedded JSON tree, with all Jackson conversion behind the `Serde` SPI. The foundation the rest build on. |
| [Thin model classes over a hand-written runtime](model-classes.md) | Generated models as a field map + typed accessors; runtime carries the invariant machinery. Coverage and binary-compatibility strategy for generated code. |
| [The validate()/isValid()/validity() triad](model-validation.md) | An opt-in, memoized, fail-soft validation triad on generated models — never run on the deserialize path; the fallback union-disambiguation strategy. |
| [Discriminator and const fields](discriminator-const-fields.md) | Const/discriminator fields generated as defaulted raw values with dual (typed + raw) accessors. |

## Dependency order

```
json-field-model.md   (the foundation: JsonField<T> + RawJson)
        |
        +-- model-classes.md              (thin models over the runtime)
        +-- model-validation.md           (validate/isValid/validity triad)
        +-- discriminator-const-fields.md (defaulted raw + dual accessors)
```

Read `json-field-model.md` first; the other three assume its vocabulary (`Known` / `Missing` /
`Null` / `Raw`, `RawJson`, the read-path vs PATCH-path boundary).
