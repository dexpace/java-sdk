# Fail-soft recursive validator skeleton

Status: design spec, **deferred**. Closes #67.

> This captures the *design* of a validator idiom for generator output. There is **no runtime type
> for it today** and none is added by this document. It is built only when the code generator
> exists; until then it lives here as a shape to implement against. Like all codegen runtime, it
> would land in a generator/adapter module, never in `sdk-core`.

## Problem

Validators over a spec or schema tree — "is this input contract well-formed?", "is this derived
strict schema in the allowed subset?" (see
[strict-structured-output-schema.md](strict-structured-output-schema.md)) — want a consistent
**fail-soft** shape:

- collect **all** problems with a path prefix, rather than throwing on the first one, so a user
  fixing a contract sees every error in one pass;
- guard against **cyclic** trees (a `$ref` chain that loops) so recursion terminates;
- name definitions **deterministically** so two distinct nodes with the same simple name do not
  alias each other in error messages or in a visited-set.

The idiom is tiny — on the order of fifteen lines of recursion — but it has to be the *same* tiny
idiom everywhere a validator is written, so that error output and cycle handling are uniform.

## The skeleton

Parameterised over **our own tree type**, not a third-party node model. The generator already has a
normalized in-memory representation of the input contract; the validator walks that. The shape:

```kotlin
// DESIGN ONLY — not built today; would live in a generator module, never sdk-core.

/** One problem, addressed by a slash-joined path from the tree root. */
data class ValidationError(val path: String, val message: String)

class Validator(private val errors: MutableList<ValidationError> = mutableListOf()) {

    // Recursion guard keyed by deterministic node id — see "Deterministic definition names".
    private val visiting = HashSet<String>()

    /** Verify [cond]; on failure record a path-prefixed error and signal "stop this branch". */
    private inline fun verify(cond: Boolean, path: String, message: () -> String): Boolean {
        if (!cond) errors += ValidationError(path, message())
        return cond
    }

    fun validate(node: Node, path: String = "") {
        val id = node.definitionName            // FQN/deterministic, never the simple name
        if (!visiting.add(id)) return           // one-shot recursion guard: already on this branch
        try {
            // early-return verify helper: a failed precondition stops descent into a broken node,
            // but earlier siblings' errors are already collected.
            if (!verify(node.isWellFormed, path) { "malformed node '$id'" }) return
            for ((key, child) in node.children) {
                validate(child, if (path.isEmpty()) key else "$path/$key")
            }
        } finally {
            visiting.remove(id)                 // pop on the way out so siblings can revisit shared defs
        }
    }

    fun result(): List<ValidationError> = errors.toList()
}
```

Three load-bearing pieces, matching the issue's acceptance criteria:

1. **One-shot recursion guard.** `visiting.add(id)` returns `false` if `id` is already on the
   current descent path; we return immediately, so a cyclic `$ref` cannot loop forever. The guard is
   popped in `finally` so the same shared definition can be re-entered down a *different* branch
   (we guard against cycles, not against repeated visits).
2. **Path-prefixed error list.** Every `verify` failure is recorded as `(path, message)` and
   appended; nothing throws mid-walk. The caller gets the full list from `result()` and decides
   whether a non-empty list is fatal.
3. **Early-return `verify` helper.** `verify` both records the error *and* returns the boolean, so a
   call site can `if (!verify(...)) return` to stop descending into a node that is too broken to walk
   while still having collected the error and everything found before it.

## Deterministic definition names

`definitionName` must be a **fully-qualified, deterministic** identifier — the package-qualified
type name plus a stable mangling for generic arguments — never the bare simple name. This matters in
two places:

- **The recursion guard.** Two unrelated nodes that happen to share a simple name (`Metadata` in two
  packages) must hash to *different* ids, or the guard would wrongly treat the second as a cycle of
  the first and skip it.
- **Error messages.** `"malformed node 'com.example.a.Metadata'"` is actionable; `"malformed node
  'Metadata'"` is ambiguous across a large API surface.

This is the same deterministic-name rule the strict-schema spec applies to `$defs` keys
([strict-structured-output-schema.md](strict-structured-output-schema.md) §R6), so a single naming
function serves both the schema derivation and its validator.

## Why fail-soft (decisions / trade-offs)

- **All errors in one pass.** Throwing on the first problem forces an edit-rerun-edit loop over a
  contract; collecting every error lets a user fix a batch at once. The cost is that the walk must be
  defensive — hence the early-return helper, so a broken node does not cause a cascade of spurious
  child errors.
- **Our tree type, not a generic JSON model.** Validating the generator's own normalized model keeps
  the validator decoupled from whatever parser produced the contract and lets the same skeleton check
  both input contracts and derived strict schemas.
- **No library.** The skeleton is small enough to hand-write and keeps shipped modules free of a
  schema/validation dependency, consistent with the rest of the codegen design.

## Acceptance mapping

- *Validator skeleton* — the `Validator` shape above (recursion guard + path-prefixed list +
  early-return `verify`).
- *Deterministic definition names* — `definitionName` is FQN/deterministic, used for both the guard
  and error messages; shared with the strict-schema `$defs` naming.
