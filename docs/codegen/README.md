# Codegen design specs

This directory holds **design specifications** for the future dexpace code generator — the
component that, given an API description, will emit the typed service/operation layer that sits
on top of `sdk-core`. Nothing here is generator code. There is no KotlinPoet, no emitter, and no
generated source in the repository; these documents define the *target shape* of the code the
generator will eventually produce and explain how that shape binds to the runtime that already
ships in `sdk-core`.

Every Kotlin/Java snippet in these specs is **illustrative target output** — what the generator
should emit — not code compiled in this repo. The snippets reference real `sdk-core` types
(`HttpClient`, `ResponseHandler`, `ParsedResponse`, `Paginator`, `PaginationStrategy`, `Serde`,
the `CallContext` chain, `Tristate`, `Request`/`Response`) so the design stays anchored to the
runtime that exists today.

For the broader survey that motivates building our own generator, see
[`../refs-comparison.md`](../refs-comparison.md). For the runtime layering these specs build on,
see [`../architecture.md`](../architecture.md), [`../http.md`](../http.md), and
[`../pipelines.md`](../pipelines.md).

## Specs

| Spec | Topic |
|---|---|
| [service-method-tiers.md](service-method-tiers.md) | Two-tier raw/cooked service methods over the `ResponseHandler` / `ParsedResponse` seam. |
| [typed-page-classes.md](typed-page-classes.md) | Page types whose `nextPage()` rebuilds a typed params object, not a URL string, tied to `Paginator` and the strategy set. |
| [operation-overloads.md](operation-overloads.md) | Curation rules for the per-operation overload set — one canonical method plus a small, fixed convenience set instead of the full parameter cross-product. |
| [sub-service-tree.md](sub-service-tree.md) | The lazily-instantiated sub-service accessor tree (`client.foo().bar()`) and how the root reuses the raw-response implementation. |

## How the specs fit together

The four specs describe one cohesive generated layer:

- The **sub-service tree** is the entry surface: `client.<resource>()` accessors, lazily built.
- Each leaf service exposes **operations**, each generated in **two tiers** (raw and cooked).
- Each operation has a **curated overload set** rather than the full cross-product.
- List operations additionally emit a **typed page class** that drives `Paginator` and rebuilds
  the next request from typed params.

They share two cross-cutting dependencies that are *not yet* in the tree and are tracked
separately:

- **OperationParams SPI** — the typed, builder-backed params object per operation, which both
  the overload set and typed-page rebuild lean on.
- The generator itself (KotlinPoet-based), per [`../refs-comparison.md`](../refs-comparison.md).
