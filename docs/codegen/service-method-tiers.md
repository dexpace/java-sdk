# Two-tier raw/cooked service methods

> Design spec. The Kotlin/Java in this document is **target generator output**, not code compiled
> in this repository.

## Problem

A caller of a typed operation wants two different things at different times:

- Most of the time, the parsed body: `client.models().retrieve("gpt-x")` should hand back a typed
  `Model`, with error responses already turned into exceptions and the body fully consumed and
  closed.
- Sometimes, the response metadata *without* paying for deserialization: the status code, an
  `ETag`, a `Retry-After`, the raw headers — for conditional requests, cache validation, or
  cheap existence checks. Forcing a full body parse to read a header is wasteful, and on a large
  or hostile payload it is an unbounded-allocation hazard.

A single method signature cannot serve both. Emitting only the cooked method strands the
metadata-only caller; emitting only a raw method makes the common case verbose. The reference
SDK (openai-java) answers this with a parallel `withRawResponse()` service tree
(`ModelServiceImpl.kt`) — every operation exists in a cooked tier and a raw tier.

## Constraints from `sdk-core`

The seam these tiers dispatch against already ships. Two types in
`org.dexpace.sdk.core.http.response` do the heavy lifting:

- **`ResponseHandler<out T>`** — a `fun interface` whose `handle(response: Response): T` maps a raw
  `Response` to a typed value. A handler that reads the body **owns consuming and closing it**.
  Built-ins: `ResponseHandler.string()` and `ResponseHandler.empty()`. Adapter modules supply a
  JSON handler backed by the `Serde` SPI.
- **`ParsedResponse<out T>`** — pairs a raw `Response` with a `ResponseHandler<T>` and parses
  **lazily and exactly once**. Its raw accessors (`status`, `headers`, `message`, `protocol`,
  `request`) read straight from the underlying `Response` and never touch the body; `value()`
  runs the handler on first call and memoizes the outcome (success *or* failure) behind a
  `ReentrantLock`. It is `Closeable`, so the metadata-only path can release the body without ever
  parsing.

The pipeline stays transport-pure: `HttpClient.execute(request): Response` returns a raw
`Response` and nothing about deserialization or error mapping lives in `http.pipeline`. **Error
mapping and deserialization compose at the generated-service layer**, as `Response -> X` handlers
— not as pipeline stages. This keeps the `http.pipeline` REDIRECT/RETRY/AUTH/LOGGING/SERDE pillar
contract untouched.

## Proposed generated shape

Each operation is generated in two tiers behind one service interface. The **raw tier** returns a
`ParsedResponse<T>`; the **cooked tier** returns `T` directly. The cooked tier is a thin
delegation onto the raw tier — it calls `.value()` and lets `use {}` close the body.

### Service interface (target output)

```kotlin
// GENERATED — illustrative target output, not compiled here.
public interface ModelService {
    // Cooked tier: parsed body, body consumed + closed, errors already thrown.
    public fun retrieve(params: ModelRetrieveParams): Model

    // Raw tier: lazy ParsedResponse — read status/headers without parsing,
    // or call value() to parse exactly once. Caller owns close().
    public fun withRawResponse(): WithRawResponse

    public interface WithRawResponse {
        public fun retrieve(params: ModelRetrieveParams): ParsedResponse<Model>
    }
}
```

### Service implementation (target output)

```kotlin
// GENERATED — illustrative target output, not compiled here.
internal class ModelServiceImpl(
    private val client: HttpClient,
    private val serde: Serde,
    private val errorMapper: ErrorMapper,
) : ModelService {

    private val rawTier = WithRawResponseImpl()

    override fun withRawResponse(): ModelService.WithRawResponse = rawTier

    override fun retrieve(params: ModelRetrieveParams): Model =
        rawTier.retrieve(params).use { it.value() }

    private inner class WithRawResponseImpl : ModelService.WithRawResponse {
        override fun retrieve(params: ModelRetrieveParams): ParsedResponse<Model> {
            val request: Request = params.toRequest(/* baseUrl, auth context, etc. */)
            val response: Response = client.execute(request)
            // Compose error-mapping + deserialization into one Response -> Model handler.
            val handler: ResponseHandler<Model> =
                ResponseHandler { raw ->
                    errorMapper.throwOnError(raw)          // 4xx/5xx -> typed exception
                    serde.deserializer.deserialize(        // body -> typed DTO
                        raw.body!!.source().inputStream(),
                        Model::class.java,
                    )
                }
            return ParsedResponse.of(response, handler)
        }
    }
}
```

The error-mapping/deserialization composition is the load-bearing piece. `ErrorMapper` reads the
raw `status` and headers (and, for a 4xx/5xx, deserializes a typed error envelope) and throws;
only on success does the handler deserialize the success type. Because the whole thing is a single
`ResponseHandler`, it runs **once**, inside `ParsedResponse.value()`'s memoized, locked section —
the body is touched exactly once no matter how many times `value()` is called.

### Caller experience (target usage)

```kotlin
// Cooked — the 90% case.
val model: Model = client.models().retrieve(params)

// Raw — read metadata, never parse.
client.models().withRawResponse().retrieve(params).use { raw ->
    val etag = raw.headers["ETag"]
    if (raw.status.code == 304) return@use  // body never deserialized
}

// Raw — read a header AND parse, single body read.
client.models().withRawResponse().retrieve(params).use { raw ->
    val requestId = raw.headers["x-request-id"]
    val model = raw.value()                  // handler runs here, once
}
```

## Design decisions and trade-offs

- **One `ResponseHandler` per operation, composed at the service layer.** Error mapping and
  deserialization are fused into a single `Response -> T` function rather than layered as two
  passes over the body. The body is single-use; a two-pass design would force a re-read or a
  per-response cache. `ParsedResponse` already memoizes a *thrown* outcome, so a mapped error is
  re-thrown verbatim on every later `value()` call without re-touching the consumed body.

- **Cooked delegates to raw, never the reverse.** The generator emits one real implementation (the
  raw tier) and derives the cooked method as `rawTier.op(params).use { it.value() }`. This halves
  the generated logic per operation and guarantees the two tiers can never drift. It also dovetails
  with the sub-service tree spec ([sub-service-tree.md](sub-service-tree.md)), where the root reuses
  the nested raw impl.

- **Closeable + `use {}` + KDoc, no `@MustBeClosed` lint.** The raw tier hands the caller an open
  `ParsedResponse` (a `Closeable`). We rely on Kotlin `use {}`, the Java try-with-resources idiom,
  and explicit KDoc — we deliberately do **not** introduce an Errorprone `@MustBeClosed`
  annotation or a new lint dependency. The cooked tier closes for the caller; only the raw tier
  transfers ownership, and that is exactly where the KDoc warns. Note the asymmetry: a raw caller
  who reads only metadata and forgets to close leaks the body, whereas the cooked path cannot leak.

- **Transport purity preserved.** Nothing here adds a pipeline stage. `http.pipeline` keeps
  returning a raw `Response`; the SERDE pillar stays about wire framing, and typed-body
  deserialization is a service-layer concern. This is the boundary the issue calls out and it is
  the boundary `ResponseHandler`/`ParsedResponse` were built for.

- **Async mirror.** The async service tier returns `CompletableFuture<ParsedResponse<T>>` (cooked:
  `CompletableFuture<T>`) by dispatching through `AsyncHttpClient.executeAsync` and mapping the
  completed `Response` through the same handler with `thenApply`. The handler is reused verbatim;
  only the dispatch differs. `ParsedResponse`'s own laziness still applies — the future completes
  with an *unparsed* `ParsedResponse`, and parsing happens when the caller calls `value()`.

## Ties into the runtime

- `org.dexpace.sdk.core.http.response.ResponseHandler` — the `Response -> T` seam the handler is.
- `org.dexpace.sdk.core.http.response.ParsedResponse` — lazy, memoized, `Closeable` raw/parsed
  pairing; `ParsedResponse.of(...)` and the `Response.parsedWith(...)` extension are the factories.
- `org.dexpace.sdk.core.client.HttpClient` / `AsyncHttpClient` — transport SPIs the tiers dispatch
  against; both stay deserialization-free.
- `org.dexpace.sdk.core.serde.Serde` (`serializer` / `deserializer`) — supplies the JSON handler
  the error/deserialize composition uses; `sdk-core` ships no embedded serializer.
- `org.dexpace.sdk.core.http.response.Status` (`code`, `isSuccess`) and `Headers` — what the raw
  tier reads without parsing.

## Acceptance mapping

- *Raw + cooked tiers generated* — the `ModelService` / `WithRawResponse` shape above.
- *Error-mapping composed at the service layer* — the single `ResponseHandler` fusing `ErrorMapper`
  + `serde.deserializer`, never a pipeline stage.
- *Test* — the generator's golden-file tests assert the emitted shape; a runtime fixture exercises
  cooked-parses-once, raw-reads-headers-without-parsing, and raw-then-`value()`-single-read using a
  stub `HttpClient`, mirroring the existing `ParsedResponse` tests.
