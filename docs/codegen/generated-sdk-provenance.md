# Generated-SDK provenance file

Status: design spec. Closes #68.

## Problem

A generated SDK is a build artifact: the same input contract run through a different generator
version can produce different output. When a bug is reported against a generated SDK, the first two
questions are always "which generator produced this?" and "from which input contract?". Without a
recorded answer, the only way to reproduce is to guess the generator version and re-derive — which is
exactly the situation provenance metadata exists to avoid.

This spec defines a small, machine-readable **provenance file** stamped into generated output.

## Scope: generated output only

The provenance file is written **only into generated SDKs, never into the hand-written toolkit.**
`sdk-core` and the other published toolkit modules are authored by hand and version themselves
through `gradle.properties` (`group` / `version`); stamping a generator provenance file into them
would be meaningless and is forbidden. The generator writes the file into the SDK module it emits and
touches nothing else.

## What metadata

| Field | Meaning |
|---|---|
| `generatorName` | Stable identifier of the generator (e.g. `org.dexpace:sdk-codegen`). |
| `generatorVersion` | Exact released version of the generator that produced this SDK. |
| `inputContractHash` | Content hash of the **normalized** input contract (OpenAPI / JSON-schema), `sha256:` prefixed. Normalization (sort keys, strip insignificant whitespace) so semantically-identical contracts hash identically regardless of formatting. |
| `inputContractName` | Human-readable contract identifier (e.g. the API title + version from the OpenAPI `info` block). |
| `generatedAt` | ISO-8601 UTC timestamp of generation. Informational only — it is **excluded** from any reproducibility comparison (see below). |
| `schemaVersion` | Version of this provenance-file format itself, so consumers can parse older stamps. |

`generatorVersion` + `inputContractHash` together are the reproducibility key: same generator
version + same normalized contract hash ⇒ byte-identical SDK (modulo the timestamp). `generatedAt` is
deliberately **not** part of that key, so reproducibility checks compare everything except the
timestamp.

## Format

JSON, so it is trivially machine-readable and round-trips through the existing `Serde` /
`Deserializer` SPI at runtime without any new dependency — a tool can read it back with
`Deserializer.deserialize(json, GeneratedProvenance::class.java)` using the toolkit's own serde, with
no schema/provenance library involved.

Target output:

```json
{
  "schemaVersion": "1",
  "generatorName": "org.dexpace:sdk-codegen",
  "generatorVersion": "0.4.2",
  "inputContractName": "Example API 2025-11",
  "inputContractHash": "sha256:9f2b1c…",
  "generatedAt": "2026-06-17T09:30:00Z"
}
```

## Location

Two copies, serving two different consumers:

1. **On the classpath, as a resource:**
   `META-INF/dexpace/<artifactId>/generated-provenance.json` in the generated module's
   `src/main/resources` (so it ships inside the published jar). Programmatic access:

   ```kotlin
   // GENERATED accessor — illustrative target output, not compiled here.
   public object GeneratedProvenance {
       public fun read(): String =
           checkNotNull(
               javaClass.getResourceAsStream(
                   "/META-INF/dexpace/example-api/generated-provenance.json",
               ),
           ).bufferedReader().use { it.readText() }
   }
   ```

   Namespacing under `META-INF/dexpace/<artifactId>/` keeps multiple generated SDKs on one classpath
   from clobbering each other's stamp.

2. **At the generated source root**, as a checked-in `PROVENANCE.json`, so the metadata is visible in
   the SDK's repository / diff without unpacking a jar. Both copies are written from the same values
   in one pass, so they cannot drift.

## Decisions / trade-offs

- **Normalized hash, not raw-bytes hash.** A reformatted-but-equivalent contract should not look like
  a different input. Hashing the normalized form makes the reproducibility key robust to cosmetic
  changes.
- **Timestamp present but excluded from the key.** Engineers want to know *when* an SDK was cut;
  reproducibility checks must not be defeated by that timestamp. Keeping `generatedAt` informational
  satisfies both.
- **JSON over `.properties` or a manifest entry.** JSON nests cleanly (room for future fields like a
  list of source files or a transport matrix) and reuses the toolkit's existing serde, so no new
  parsing dependency is introduced.
- **Resource + source copy.** The resource serves runtime/diagnostic code; the source copy serves
  humans reading the generated repo. One write pass, two destinations, no drift.

## Acceptance mapping

- *Provenance stamped in generated output* — `generatorVersion` + `inputContractHash` (plus
  supporting fields) written to `META-INF/dexpace/<artifactId>/generated-provenance.json` and a
  source-root `PROVENANCE.json`, in generated output only.
