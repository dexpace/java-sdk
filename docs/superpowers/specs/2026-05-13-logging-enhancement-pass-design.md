# Logging enhancement pass — design

**Status**: approved 2026-05-13. Ready to plan + implement.

## Goal

Improve the observability the SDK ships with, so operators using a structured logging backend (and optionally an OTel collector) can trace a single request across pipeline stages and async boundaries, see what every adapter is doing in production, get richer context in existing events for faster diagnosis, and trust that every log site goes through the same structured facade.

## Non-goals

- Log sampling / rate limiting. Useful for very high-throughput callers but adds complexity; skip until someone asks.
- New redaction beyond the existing `UrlRedactor`. Headers like `Authorization` are already filtered upstream by the instrumentation step.
- Backward-incompatible event renames. Existing field names stay; we only add fields.

## Ship plan (five commits, in order)

### Commit 1 — Consistency

Migrate the two raw-SLF4J usages to `ClientLogger`:

- `HttpPipelineBuilder` pillar-replaced warning.
- `AsyncHttpPipelineBuilder` pillar-replaced warning.

Both become:
```kotlin
LOG.atWarning()
    .event("pipeline.pillar.replaced")
    .field("stage", stage.name)
    .field("previous", prev::class.simpleName ?: "<anonymous>")
    .field("replacement", next::class.simpleName ?: "<anonymous>")
    .log()
```

Zero behavior change for downstream operators; gains the NOOP fast path, `globalContext` attachment, and structured fields instead of formatted strings. The existing `StagedSteps reload fires onPillarReplaced` test still passes — it asserts the callback fires, not the wire format.

### Commit 2 — Correlation via MDC

Two pieces:

1. **Tracer-side**: the `Tracer` impl that backs `Span.makeCurrent()` pushes `trace.id` and `span.id` onto SLF4J MDC when the scope opens, and pops them when the scope closes. Keys are lowercase-dotted to match the rest of the SDK's structured field naming. `NoopSpan.makeCurrent()` remains a no-op (no MDC push) — non-recording spans don't pollute the context.
2. **Consumer-side**: `LoggingEvent.log()` reads `MDC.getCopyOfContextMap()` and folds entries into the SLF4J builder via `addKeyValue`, **before** the per-event fields. Per-event fields with the same key win — predictable override semantics. Lookup happens only on the enabled path (after the `consumed` CAS); the NOOP fast path is undisturbed.

**Limitation we own explicitly** (in KDoc on `TracingScope` and a short note in `docs/architecture.md`): MDC is per-thread. It does NOT propagate across `CompletableFuture` continuations, coroutine suspensions, or executor handoffs. For coroutine adapters, callers should use `kotlinx-coroutines-slf4j`'s `MDCContext` element. For raw `CompletableFuture` chains, callers must capture/restore manually (we are not shipping a wrapper for this — the futures API doesn't have a clean seam and the SDK has no caller-visible CF chain today).

### Commit 3 — Coverage

Add log events at currently-silent paths in the four async adapters:

| Module / file | Event | Level |
|---|---|---|
| `sdk-async-coroutines/Coroutines.kt::asAsyncCoroutines` | `async.adapter.wrapped` with `adapter.type=coroutines` | VERBOSE |
| `sdk-async-netty/Netty.kt::bridgeToNetty` cancellation listener | `async.adapter.cancel_propagated` with `adapter.type=netty` | VERBOSE |
| `sdk-async-reactor/Reactor.kt::executeMono` subscription + cancel | `async.adapter.subscribed` and `async.adapter.cancel_propagated` with `adapter.type=reactor` | VERBOSE |
| `sdk-async-virtualthreads/VirtualThreads.kt::close` | `executor.closed` with `adapter.type=virtualthreads` | INFO |

Each adapter Kotlin file introduces one `private val LOG = ClientLogger(...)` at top level. Hot-path cost on disabled levels: zero, via `LoggingEvent.NOOP`.

`sdk-io-okio3` is **excluded** from this round. A provider-install log would have to live in `OkioIoProvider`, which is an `object` — an `init {}` block fires on class load (any reference, not just install) which is leaky, and a one-shot guard on first `buffer()` call adds runtime cost on the hot path for a one-time signal. Operators who want to confirm a provider is installed can call `Io.provider` directly. Skip.

### Commit 4 — Richness

Add fields to existing events. Backward-compatible — only additions, no renames.

- `http.redirect` (DefaultRedirectStep): add `redirect.from_url` and `redirect.target_url`, both redacted via the existing `UrlRedactor`. Currently the event has only `attempt` and `target.status`; operators investigating a chain have to correlate by timestamp.
- `http.retry` (DefaultRetryStep): add `retry.total_elapsed_ms` (cumulative wall-time since attempt 1, tracked on a per-call accumulator already alive in the step) and `retry.cause_class` (exception class simple-name; null for status-code-only retries).
- `http.instrumentation.error` (DefaultInstrumentationStep): add `error.phase` ∈ {`request_event`, `response_event`, `body_capture`}. The step currently emits one event for all three failure sites — operators can't tell which.

### Commit 5 — DefaultAsyncInstrumentationStep

Mirror `DefaultInstrumentationStep` for `AsyncHttpPipeline`. Same options class (`HttpInstrumentationOptions`), same events (`http.request`, `http.response`, `http.instrumentation.error`), same metric handles, same span lifecycle — adapted to return a `CompletableFuture<Response>` and end the span via `.whenComplete`. The class-doc shows callers `pipeline.append(DefaultAsyncInstrumentationStep(options))` symmetrically to the sync side.

Tricky bits the implementation must handle:
- Span end on completion: `f.whenComplete { resp, err -> ... }` runs on whatever thread completes the future. The MDC push from `span.makeCurrent()` is per-thread; if the completion thread differs, MDC won't carry. Document this in the class KDoc; for log correlation the per-event fields in this step's own events are sufficient (the step explicitly captures the span's trace_id/span_id and passes them in).
- Response body close on cancellation: per the `AsyncHttpClient.executeAsync` contract, cancelling the future does NOT close the response if it's already delivered. The async step matches the sync step's contract: if the caller cancels after the future completes, the caller still owns close.
- Body wrapping must happen pre-send, just like the sync step. The future returned from `next.processAsync(outgoing)` is what gets composed with `.whenComplete` to emit the response event.

## Testing

- Commit 1: existing tests still pass.
- Commit 2: new test in `LoggingEventTest` verifying MDC keys appear on emitted events; new test verifying per-event fields override MDC keys with the same name; existing tests still pass.
- Commit 3: each adapter gets a smoke test verifying its log event fires (using SLF4J's `ListAppender` or the existing test helpers, whichever pattern the codebase already uses).
- Commit 4: extend existing `RedirectStepTest`, `RetryStepTest`, `InstrumentationStepTest` to assert the new fields land on the existing events.
- Commit 5: new `AsyncInstrumentationStepTest` mirroring the sync counterpart's coverage. Reuses the same `RecordingTracer` and meter test doubles.

## Implementation order

Commits 1, 2, 3, 4 are independent and can be implemented in that order without cross-coupling. Commit 5 (DefaultAsyncInstrumentationStep) depends on the field additions from commit 4 if we want symmetry — implement commit 5 last so it gets the richer field set from the start.

## Risks

- **MDC propagation surprises** (Commit 2): if a caller assumes MDC carries across `CompletableFuture.thenApply` and we don't fix it, they'll be confused. Documented explicitly in TracingScope KDoc + a paragraph in docs/architecture.md.
- **Async step span timing** (Commit 5): the `whenComplete` callback may run on a thread the user doesn't expect. We document; we don't try to confine.
- **Field-name churn** (Commit 4): if anyone has dashboards keyed on the *absence* of a field, this technically breaks them. Adding fields is the safe direction; the risk is theoretical.
