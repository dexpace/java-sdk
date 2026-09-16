# Typed page classes that rebuild typed params, not URL strings

> Design spec. The Kotlin/Java in this document is **target generator output**, not code compiled
> in this repository.

## Problem

For a list operation, cursor-based next-page navigation has two ways to build the next request:

1. **Splice the URL** — take the previous request's URL and set/replace a query parameter
   (`?cursor=…`). This is what the runtime's `RequestRebuilder` does today (it is `internal`,
   `URLEncoder`-based, and the backbone of the bring-your-own strategy path).
2. **Rebuild a typed params object** — take the operation's params, set the cursor field, and ask
   the params object to produce the next request: `params.toBuilder().after(cursor).build()`.

The runtime's strategy-based path (1) is the right *generic* mechanism, but it is the wrong thing
to **generate** per operation. URL surgery in generated code is opaque (the cursor lives in a
stringly-typed query param), bypasses the operation's own param validation and encoding, and
cannot carry typed paging state (a structured cursor, a composite page token) cleanly. openai-java's
generated `*Page` types take approach (2): `nextPage()` calls the same operation again with a
rebuilt params object.

This spec specifies approach (2) for **generated** pages while keeping approach (1) — the existing
`PaginationStrategy` + `RequestRebuilder` — as the supported bring-your-own path.

## Constraints from `sdk-core`

The pagination runtime in `org.dexpace.sdk.core.pagination` is the foundation:

- **`Paginator<T>`** — strategy-driven, **stateless**, page-lazy (exactly one HTTP exchange per
  page yielded), with a `maxPages` safety cap. Exposes `iterateAll(): Iterable<T>` and
  `streamAll(): Stream<T>`. It executes an `initialRequest` against an `HttpClient`, hands each
  `Response` to a `PaginationStrategy`, **closes the response after `parse`**, and uses the returned
  `Page<T>` to decide whether and how to fetch the next page.
- **`PaginationStrategy<T>`** — `fun parse(response: Response, initialRequest: Request): Page<T>`.
- **`Page<T>`** — `items: List<T>`, `hasNext: Boolean`, `nextPageRequest(): Request?`. The
  `Paginator` calls `nextPageRequest()` *once* per page and retains the resulting `Request` so it
  never holds onto the closed `Response`.
- **`CursorPaginationStrategy<T>`** — the reference cursor strategy: a single `extractor:
  (Response) -> CursorResult<T>` reads items + next cursor in one pass (single-read discipline,
  since the body is single-use), then calls `RequestRebuilder.withQueryParam(initialRequest,
  cursorQueryParam, nextCursor)`. This is the **bring-your-own URL-splice path** we keep.

The key contract: `Paginator` only knows how to drive a `PaginationStrategy` that yields a `Page`
exposing a `nextPageRequest()`. So a generated typed page must still ultimately produce a
`Request` — but it produces it by *rebuilding typed params and asking them for a request*, never by
splicing a URL.

## Proposed generated shape

For each list operation the generator emits a typed `*Page<T>` and a tiny adapter that lets it run
under the existing `Paginator`. The typed page's `nextPage()` rebuilds the operation's params via
`params.toBuilder()`, sets the cursor into a **typed param field**, and re-invokes the operation —
no URL string is touched in generated code.

### Typed page (target output)

```kotlin
// GENERATED — illustrative target output, not compiled here.
public class ModelPage internal constructor(
    private val service: ModelService,
    private val params: ModelListParams,      // the typed params that produced THIS page
    private val response: ModelListResponse,  // typed, already-deserialized envelope
) {
    public fun items(): List<Model> = response.data

    /** True if the response carried a non-blank next cursor in a typed field. */
    public fun hasNextPage(): Boolean = !response.nextCursor.isNullOrBlank()

    /**
     * Rebuilds the TYPED params with the next cursor and calls the operation again.
     * No URL surgery — the cursor rides a typed param field, and ModelListParams.toRequest()
     * owns the encoding.
     */
    public fun nextPage(): ModelPage {
        val nextCursor = response.nextCursor
            ?: throw NoSuchElementException("No next page.")
        val nextParams = params.toBuilder()
            .after(nextCursor)   // typed cursor param, not ?cursor=… string splice
            .build()
        return service.list(nextParams)
    }
}
```

### Adapter onto the runtime `Paginator` (target output)

A generated `PaginationStrategy` bridges the typed page back to the stateless `Paginator`. It
deserializes the envelope once (single-read), then builds the next `Request` by rebuilding typed
params — calling `params.toRequest()`, **not** `RequestRebuilder`:

```kotlin
// GENERATED — illustrative target output, not compiled here.
internal class ModelListPaginationStrategy(
    private val serde: Serde,
    private val params: ModelListParams,
) : PaginationStrategy<Model> {

    override fun parse(response: Response, initialRequest: Request): Page<Model> {
        // Single read: items + next cursor out of one body pass.
        val envelope: ModelListResponse =
            serde.deserializer.deserialize(
                response.body!!.source().inputStream(),
                ModelListResponse::class.java,
            )
        val nextCursor = envelope.nextCursor
        val hasNext = !nextCursor.isNullOrBlank()

        val nextRequest: Request? =
            if (hasNext) {
                // Rebuild TYPED params, then ask them for a request.
                params.toBuilder().after(nextCursor).build().toRequest()
            } else {
                null
            }

        return object : Page<Model> {
            override val items: List<Model> = envelope.data
            override val hasNext: Boolean = hasNext
            override fun nextPageRequest(): Request? = nextRequest
        }
    }
}
```

The generated service exposes both surfaces over the same machinery:

```kotlin
// GENERATED — illustrative target output, not compiled here.
public fun ModelService.list(params: ModelListParams): ModelPage { /* one exchange, typed page */ }

public fun ModelService.listPaginated(params: ModelListParams): Paginator<Model> =
    Paginator(client, params.toRequest(), ModelListPaginationStrategy(serde, params))

// Caller — auto-pagination, page-lazy, capped:
for (model in client.models().listPaginated(params).iterateAll()) { /* … */ }

// Caller — manual page walk on the typed page:
var page = client.models().list(params)
while (page.hasNextPage()) { page = page.nextPage() }
```

## Design decisions and trade-offs

- **Typed param rebuild, never URL surgery in generated code.** `nextPage()` and the generated
  strategy both go through `params.toBuilder().<cursor>(…).build()` → `params.toRequest()`. The
  cursor is a typed param field, so it inherits the operation's own validation and encoding, and a
  structured/opaque cursor survives round-trips without manual percent-encoding. The string-splice
  `RequestRebuilder` stays the bring-your-own path for callers who write their own
  `PaginationStrategy`, exactly as `CursorPaginationStrategy` uses it today.

- **Single-read discipline carries over.** Response bodies are single-use and `Paginator` closes
  each response right after `parse`. The generated strategy deserializes the envelope **once** and
  pulls both the items and the next cursor from that one typed object — the same single-read
  reasoning behind `CursorPaginationStrategy` + `CursorResult`, but typed instead of via an
  extractor lambda.

- **Compute the next request inside `parse`, hold no `Response`.** `Paginator` retains the
  `Request` from `nextPageRequest()` and never the (closed) `Response`. The generated `Page` builds
  `nextRequest` eagerly inside `parse` so it honors that contract — no reference to the response or
  its body escapes.

- **Two surfaces, one mechanism.** The typed `*Page.nextPage()` is for callers who want explicit,
  one-page-at-a-time control; `listPaginated(...).iterateAll()` is for auto-pagination. Both rebuild
  typed params; they differ only in who drives the loop (the caller vs. the `Paginator`). The
  manual `nextPage()` does not get the `maxPages` cap (the caller controls the loop), whereas the
  `Paginator` path does — callers who want the safety cap should prefer `listPaginated`.

- **Async paging.** When the async tier is generated, the typed page's `nextPageAsync()` returns
  `CompletableFuture<ModelPage>` and the operation re-invocation goes through the async service. The
  runtime async paginator (tracked under the async-pagination work) drives it; the typed-param
  rebuild is identical.

## Ties into the runtime

- `org.dexpace.sdk.core.pagination.Paginator` — stateless, page-lazy driver with `maxPages` cap;
  `iterateAll()` / `streamAll()`.
- `org.dexpace.sdk.core.pagination.PaginationStrategy` / `Page` — the `parse` → `Page` →
  `nextPageRequest()` contract the generated strategy implements.
- `org.dexpace.sdk.core.pagination.CursorPaginationStrategy` + `RequestRebuilder` — the **retained
  bring-your-own** URL-splice path; generated code does not use `RequestRebuilder`.
- `org.dexpace.sdk.core.serde.Serde` — single-pass deserialization of the typed list envelope.
- `org.dexpace.sdk.core.http.request.Request` (`newBuilder()`) — produced by `params.toRequest()`,
  the typed rebuild's output.

## Dependency note

This spec assumes the **OperationParams SPI** — the typed, builder-backed params object with
`toBuilder()` and `toRequest()`. That SPI is tracked separately and is a hard prerequisite: without
a typed params object there is nothing to rebuild, and the design degenerates back to URL surgery.

## Acceptance mapping

- *Typed-param next-page generation* — `nextPage()` and the generated strategy both rebuild
  `params.toBuilder().<cursor>(…).build()`.
- *No URL string surgery in generated paging* — generated code never calls `RequestRebuilder`; it
  only calls `params.toRequest()`. `RequestRebuilder` remains exclusively the bring-your-own path.
