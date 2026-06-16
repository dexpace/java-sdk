# Codebase Audit v2 — dexpace java-sdk (deep pass)

Audit v2 date: 2026-06-10. Audited at HEAD **`1f233bd`** ("fix: correctness and resource-safety fixes across the HTTP stack"), which landed in response to audit v1 (run at `a78a693`). This pass goes deeper into every v1 item: it verifies each fix's mechanism line-by-line, hunts for fix-introduced bugs, re-traces the residual paths, and reviews the new tests for coverage of each claim. No code was modified by the audit.

**Verification method:** static deep-trace of every changed file plus the surrounding call graph; review of all 16 new/extended test files for coverage of each fix claim. The test suite was **not executed** in this environment (sandbox has JDK 11 only, no Gradle distribution/dependency cache; the build needs provisioned 8/11/21 toolchains). Where a claim would need a runtime check, it is marked Needs-verification.

**Headline:** all 18 v1 dispositions verified against the diff — 15 fully fixed, M3 mitigated-as-designed, M5 half-fixed (values yes, names no), M7 resolved-as-documented-limitation. The deep pass found **8 new findings (V2-1 … V2-8)**, all in or adjacent to the fix commit itself; two are Medium. L6′ (the stray-token challenge parse) remains open by design.

---

## 1. System model (unchanged from v1, abbreviated)

HTTP-client toolkit: `sdk-core` owns immutable models, the `Io.installProvider` seam, two pipeline layers (stage-based `http.pipeline`, recovery-based `pipeline`); transports (OkHttp/JDK) and async adapters plug in via SPIs. Invariants: consume-once bodies unless replayable; response bodies must be closed; blocking calls honor interrupts (`InterruptedIOException`); retries only re-send replayable bodies; body logging is now **bounded** capture (post-fix).

---

## 2. Item-by-item deep verification of v1 findings at `1f233bd`

### H1 — unbounded body-logging capture / streaming hang → **FIXED, with residuals V2-1, V2-4, V2-5, V2-8**
- **Fix mechanism (verified):** `LoggableResponseBody` rewritten around a `maxCaptureBytes` bounded drain (`drainAndCache`, `LoggableResponseBody.kt:231-281`): within-cap bodies are fully captured + delegate closed (repeatable `peek()` views, old behavior); over-cap bodies retain the live delegate as `liveTail` and `source()` returns a one-shot `PrefixThenTailSource` (prefix replay + live tail, `:290-311`) guarded single-use by a CAS (`tailHandedOut`, `:142-147`). `TeeSink` gained a `tapLimit` budget (`mirrorPrefix`, `TeeSink.kt`) mirrored across the typed-write, `drainScratch`, and `outputStream()` paths — the full payload always reaches the primary sink. Both instrumentation steps now construct `bounded(...)` wrappers at `bodyPreviewMaxBytes` (`DefaultInstrumentationStep.kt:151-156`, `DefaultAsyncInstrumentationStep.kt:241-256`), and the async step **skips wrapping entirely for `contentLength() < 0`** so the completion thread can never block on a streaming producer (`:249`). Public constructors keep the unbounded default — no API break. `HttpInstrumentationOptions` and the body-logging doc were rewritten to match.
- **Test coverage:** `LoggableResponseBodyTest` (+73 lines: over-cap streams full body, prefix repeatability, partial-failure), `TeeSinkTest` (+87: budget across all three write paths), `InstrumentationStepTest`/`AsyncInstrumentationStepTest` (+77: unknown-length skip).
- **Residuals:** the **sync** step has no unknown-length skip (→ **V2-1**); the new drain loop lacks a `read == 0` guard (→ **V2-4**); over-cap consumption can double-close the delegate source (→ **V2-5**); log-field semantics changed silently (→ **V2-8**). Boundary note: a body exactly equal to the cap exits the loop without observing EOF, so it takes the over-cap one-shot path where it could have stayed repeatable — correct output, minor behavioral wart.

### H2 — retry re-sends one-shot bodies on idempotent methods → **FIXED, verified**
- Both gates now read `body == null → method ∈ idempotent-set; body != null → body.isReplayable()` (`RetryStep.kt` `canRetry`, `DefaultRetryStep.kt` `isRetrySafe` — single-line change each, same shape). Deep-checked the semantic corners: PUT+one-shot no longer retried (the bug); PUT/POST+replayable retried (unchanged, with the "caller owns idempotency-key" caveat now documented); body-less POST not retried; `NetworkException` retries also flow through the gate, correctly refusing physically-unsendable bodies even when the server never saw the request. `RetryStepTest` files grew +163/+36 lines including the exact "idempotent method + one-shot body + retryable status" scenario v1 flagged as untested.

### H3 — throwing response/recovery step leaks the open response → **FIXED; one contract gap remains**
- `ResponsePipeline.applyResponseSteps` captures the in-hand response before the step runs and `closeQuietly(inResponse, t)` on throw, attaching close failures as suppressed; `invokeRecovery` closes a `Success`'s response when the recovery step throws. Verified close idempotency makes the throw-path close safe even if the step already closed.
- **Remaining gap (carried, documented here):** a recovery step that *returns* a `Failure` for a `Success` input (transform, not throw) still drops the response unclosed — that is necessarily the step author's responsibility (the framework can't know whether the step transferred ownership), but the `ResponseRecoveryStep` KDoc does not yet say so. One-line doc fix.

### H4 — JDK streaming publisher: shared pipe + eager writer → **FIXED for replayable bodies; fix introduced V2-2 and V2-3**
- **Fix mechanism (verified):** `streamingPublisher` (`BodyPublishers.kt:164-184`) now returns `ofInputStream { newSubscriptionStream(replayable) }` — a **fresh pipe pair + fresh writer task per supplier invocation** (`:201-227`), so the JDK's per-subscription supplier calls (407 proxy-auth retry, H2 GOAWAY replay) each get a complete body. The returned stream is a `KillSwitchInputStream` (`:255-286`) whose `close()` cancels the writer `Future` with interrupt **before** closing both pipe ends — the cancel is what unblocks a writer parked in `PipedOutputStream.write` (closing `pipeOut` alone would not). The writer task restores the interrupt flag per repo convention. Tests: `streamingPublisherSurvivesResubscription`, `abandonedStreamingPublisherDoesNotStrandWriter`, `streamingBodyRoundTripsThroughTransport` (`JdkHttpTransportTest.kt:307-371`) cover the two v1 failure modes directly.
- **But:** to make every subscription re-readable, a non-replayable body is first coerced via `toReplayable()` — i.e. fully buffered in memory (→ **V2-2**), and the `IOException` fallback path is itself buggy (→ **V2-3**).

### H5 — uninterruptible `join()` in blocking bridges → **FIXED, verified**
- Both `asBlocking()` (`AsyncHttpClient.kt`) and `toBlocking()` (`AsyncPipelineBridges.kt`) now hold the future, block in `get()`, and on `InterruptedException`: restore the flag, `future.cancel(true)`, throw `InterruptedIOException` with cause — the exact `RetryStep.awaitDelay` pattern v1 recommended. `ExecutionException` is unwrapped via `Futures.unwrap`. `CancellationException` still propagates raw (same as pre-fix `join()`; acceptable). Tests in `AsyncHttpClientTest`/`AsyncHttpPipelineTest` (+43/+35).

### M1 — `java.net.URL` equals/hashCode DNS → **FIXED, verified**
- `Request` keeps `data class` (so `copy`/`componentN`/`toString` are binary-stable) but overrides `equals`/`hashCode` to compare `url.toExternalForm()` textually (`Request.kt:52-…`). No network I/O, no virtual-host conflation; transitively de-fangs `Response`/`ResponseOutcome` equality. Two accepted consequences, both documented: textual comparison is *stricter* (`http://a` ≠ `http://a/`), and `body` still compares by identity (as it did pre-fix). 91 lines of `RequestTest` added. The Phase-2 `URL → URI` migration remains the better long-term shape but is no longer urgent.

### M2 — `PagedIterable` leaks the in-flight page on abandonment → **FIXED, verified**
- The iterator now closes each page **immediately after** taking `next.value.iterator()` (the v1-recommended cheap fix — items are a fully-materialized list, so they survive the close). The `currentPage` tracking field is gone entirely; there is no window in which an open page waits on a consumer pull. `PagedIterableTest` +88 lines including partial-consume (`first()`/`take(n)`/short-circuit stream) scenarios. Minor note: `next.close()` failure now aborts iteration even though the items were already in hand — `closeQuietly` + log would be friendlier, but failing loud on a close error is defensible.
- `byPage()` direct callers still own page lifecycle (documented; unchanged by design).

### M3 — unbounded `ContextStore` → **MITIGATED as designed (latent hazard, bounded backstop)**
- `drainToCap()` after every insert bounds the map at `MAX_TRACKED_CONTEXTS = 4096`, mirroring the Digest nonce-counter pattern v1 pointed at; the KDoc now states the close contract, the pin-the-graph hazard, why eviction is an arbitrary victim, and why `WeakReference` was rejected (the store is the only strong reference on the live path — a weak ref could collect an in-flight context). Deep-checked the drain loop for the convergence property under concurrent inserts: each insert drains until under cap, so the map cannot ratchet upward. Honest residual, stated in the KDoc itself: under heavy leak pressure a *live* call's entry can be evicted (arbitrary victim). Acceptable for a backstop. `ContextStoreTest` +28.

### M4 — unbounded request-side logging tap → **FIXED, verified** (same mechanism as H1: `TeeSink` tap budget + `LoggableRequestBody.bounded`, cap preserved across `toReplayable` rewraps; `LoggableRequestBodyTest` +40.)

### M5 — transport-divergent illegal header handling → **HALF-FIXED → residual V2-6**
- Header **values** are now validated at the model layer: `Headers.Builder.add/set` (all four String/typed overloads, `Headers.kt:163-249`) reject `\r`/`\n` with a clear splitting-vector message; the policy choice (CR/LF only, not OkHttp's printable-ASCII rule) is reasoned in the KDoc and is the right transport-agnostic contract. `HeadersTest` +100. `addAll(Headers)` bypasses validation but only accepts already-validated built instances — closed loop, verified.
- Header **names** remain unvalidated (→ **V2-6**), and the new transport-adapter comments overstate the guarantee.

### M6 — predicate/delay-override throw leaks the retryable response → **FIXED, verified** (`DefaultRetryStep.decideRetryResponse` wraps predicate + `computeResponseDelay` in try/catch, `closeQuietly(response)` before rethrow; close idempotency makes the double-close-on-happy-path concern moot. Tests cover predicate-throw and `Error`-passthrough paths.)

### M7 — `ProxyOptions.challengeHandler` silently ignored → **RESOLVED as documented limitation; new doc nit V2-7**
- v1 offered "wire it or fail loudly + fix the docs"; the fix chose the latter: `ProxyOptions` KDoc now states the handler is "currently not honoured by any shipped transport", and **both** transports emit a WARNING event (`proxy.auth.challenge_handler.unsupported`) when it is set, each with an accurate per-transport rationale (OkHttp: not wired into `proxyAuthenticator`; JDK: no per-407 hook exists). `proxyChallengeHandlerIsAcceptedAndSurfacedAsUnsupported` test added. This is a legitimate resolution — except one new doc claim is suspect (→ **V2-7**).

### L1 — OkHttp async cancel/complete race drops the adapted response → **FIXED, verified (and the v1 "needs-verification" is now resolved)**
- `onResponse` now splits adapt from complete and `closeQuietly(adapted)` when `future.complete(adapted)` returns false — exactly mirroring the JDK bridge. The new test (`asyncResponseThatLosesTheRaceIsClosed`, `OkHttpTransportTest.kt`) reproduces the race **deterministically** with an interceptor that parks the completed exchange while the test settles the future — a genuinely clever construction that also confirms v1's uncertainty about OkHttp routing cancelled-but-completed exchanges to `onFailure`: the test deliberately avoids `cancel()` and uses a decoy `complete()` instead, which is the same lost-race code path. v1's Needs-verification is closed as Confirmed-and-fixed.

### L2 — `writeAllInto` silent truncation on `read == 0` → **FIXED, verified** (now throws `IOException` naming the contract violation, mirroring `TeeSink.writeAll`; `WriteAllIntoTest` updated. Ironically the *new* drain loop in `LoggableResponseBody` reintroduces the unguarded pattern — see V2-4.)

### L3 — `stripUserInfo` re-encodes the Location URI → **FIXED, verified**
- Rebuilt textually from `rawPath`/`rawQuery`/`rawFragment` (`DefaultRedirectStep.kt`), so `%2F`/`%26` survive byte-exact. Deep-checked the edge: `uri.host` for an IPv6 literal includes its brackets in the Java URI API, so `scheme://host:port` reassembly is correct there; `userInfo != null` implies a server-based authority, so `host` is non-null on every path that reaches this function. `RedirectStepTest` +25 including an encoded-path round-trip.

### L4 — broken KDoc package link → **FIXED** (both `ResponseBody.kt` references now plain `[LoggableResponseBody]`).

### L5 — redundant per-instance lock in `RetryStep` → **FIXED** (lock deleted; `resolveScheduler` reads the companion `by lazy` directly; misleading comment corrected — the class now genuinely has no mutable instance state).

### L6 — phantom challenge from malformed continuation param → **RETRACTED in v1 review; regression test confirmed present** (`AuthChallengeParserTest.kt:398-411`). Stands retracted; the no-phantom behavior is now pinned.

### L6′ — stray trailing token after `key= <token> <token>` → **OPEN (unchanged, deliberate)**
- No main-source change to `AuthChallengeParser` in `1f233bd`; the reviewer explicitly scoped it out. Still bounded (composite handler ignores unsatisfiable challenges; nothing shipped consumes the parser — see M7). Recommend a small follow-up with its own regression test, mostly for pinning value.

### L7 — fixtures violate SDK conventions → **FIXED, verified** (`FakeHttpClient` restores the interrupt flag and throws `InterruptedIOException`; `RequestRecorder` uses `ReentrantLock.withLock`.)

---

## 3. New findings from the deep pass (all introduced or exposed by `1f233bd`)

### MEDIUM

#### V2-1. Sync instrumentation step still wraps unknown-length bodies — bounded drain stalls the caller until the preview cap fills
- **Where:** `DefaultInstrumentationStep.kt` `wrapResponseForLogging` (no `contentLength() < 0` guard) vs. `DefaultAsyncInstrumentationStep.kt:249` (guard present).
- **What's wrong:** the async step skips capture for streaming bodies precisely because the bounded drain "could block on a slow/idle producer" — but the *sync* step has the identical exposure on the caller's thread and wasn't given the skip. The bounded drain loops until **cap reached or EOF**, so for an SSE/long-poll/trickle stream under `BODY_AND_HEADERS`, the caller doesn't see the response until 8 KiB (default) of stream has accumulated.
- **Failure mode:** enable body logging against an SSE endpoint emitting ~50 B keep-alives: time-to-first-event goes from milliseconds to "however long 8 KiB takes" — minutes to never. v1's H1 hang is *bounded* now, not gone.
- **Fix:** apply the same `contentLength() < 0L → return response` guard to the sync step (and say so in `HttpInstrumentationOptions`, which currently describes the skip as an async-only difference as if the sync eager drain were safe).
- **Confidence:** Confirmed (code asymmetry is plain; both files were edited in the same commit, which is also fresh evidence for the dedup item — the two copies have now *actually* diverged in behavior, not just text).

#### V2-2. JDK streaming path now fully buffers non-replayable bodies in memory — unbounded heap traded for resend correctness
- **Where:** `sdk-transport-jdkhttp/.../internal/BodyPublishers.kt:164-184` (`streamingPublisher` → `body.toReplayable()`), `:240-244` (`bufferToByteArray`).
- **What's wrong:** the per-subscription design requires re-readable bytes, so any non-replayable body — which on this path is by definition **larger than 64 KiB or of unknown length**, exactly the bodies the streaming path exists to avoid materializing — is drained whole into an in-memory `Buffer` by `toReplayable()`. A 5 GB one-shot `InputStream` upload now means ~5 GB of heap; beyond `Buffer.MAX_BYTE_ARRAY_SIZE` (~2 GiB) the related eager fallback's `snapshot()` throws `IllegalStateException`. This re-imports the H1-class hazard on the request path of one transport.
- **Failure mode:** large streaming upload (non-replayable) through `JdkHttpTransport` → heap blow-up or `IllegalStateException`, where pre-fix it streamed (albeit with the resend bug) and where `OkHttpTransport` still streams it fine (its `isOneShot()` contract lets OkHttp fail a resend cleanly without buffering).
- **Fix:** stream the **first** subscription directly from the one-shot body and make the supplier's second invocation throw `IllegalStateException("one-shot body cannot be re-sent")` — matching the consume-once discipline used everywhere else in the SDK (and OkHttp's `isOneShot` behavior). Buffering should at most apply below some bounded threshold.
- **Confidence:** Confirmed (mechanism); severity assumes consumers send large one-shot bodies through the JDK transport.

### LOW

#### V2-3. `streamingPublisher`'s `IOException` fallback re-drives a consumed body — masks the original error with `IllegalStateException`
- **Where:** `BodyPublishers.kt:169-182`.
- When `toReplayable()` throws `IOException` mid-buffer, the catch falls back to `bufferToByteArray(body)` — but `toReplayable()` already flipped the body's consume-once guard (e.g. `OneShotInputStreamRequestBody.consumed`), so the fallback's `writeTo` throws `IllegalStateException`, masking the real I/O failure — the precise masking pattern H2 was fixed for. The comment ("emitting whatever was captured") describes bytes the code does not actually have: the partial buffer was local to `toReplayable` and is lost. **Fix:** rethrow the `IOException` (wrapped with context) instead of falling back. **Confirmed.**

#### V2-4. `LoggableResponseBody`'s new drain loop lacks the `read == 0` contract-violation guard
- **Where:** `LoggableResponseBody.kt:239-248` (`val n = capturedSource.read(buf, chunk)`; only `-1` and positive values are handled).
- A misbehaving `Source` returning `0` for a positive `byteCount` leaves `remaining` unchanged → infinite loop. The same commit *added* this exact guard to `writeAllInto` (L2 fix) and it already exists in `TeeSink.writeAll` — this is the third copy of the loop and the only unguarded one. **Fix:** `n == 0L → throw IOException(...)` like its siblings. **Confirmed** (misbehaving-source trigger only, same class as L2).

#### V2-5. Over-cap path can double-close the delegate's source
- **Where:** `PrefixThenTailSource.close` (`LoggableResponseBody.kt:303-310`) closes the live tail (the delegate's source) but does not set `delegateClosed`; a subsequent `LoggableResponseBody.close()` (`:184-200`) then calls `delegate.close()`, which for `ResponseBody.create`-style bodies closes the same source again.
- Safe today: both transports' bodies route to `OkioBufferedSource`, whose `close()` is CAS-guarded idempotent, and `ResponseBody.close()`'s own contract demands idempotency. Fragile for exotic delegate implementations whose close has side effects beyond `source().close()` — the exact "some sockets throw on double-close" concern this class's own comments cite. **Fix:** have the one-shot source's `close()` route through the wrapper (set `delegateClosed`, call `delegate.close()`), so ownership stays single-threaded through one path. **Confirmed** (by reading; benign with shipped transports).

#### V2-6. Header **names** still bypass validation — the M5 divergence survives for names, and new comments overstate the fix
- **Where:** `Headers.kt:311` (`sanitizeName` = lowercase + `trim()` — strips only *leading/trailing* whitespace; embedded `\r`/`\n` survive), vs. `validateValues` (`:325`) which covers values only. The new comment in the OkHttp `RequestAdapter` ("Header names/values are validated upstream by Headers.Builder") claims more than is true; the JDK adapter's equivalent comment is accurate (it scopes itself to restricted *names*).
- **Failure mode:** `add("X-Evil\r\nInjected", "v")` is accepted by the model layer; OkHttp then throws an unchecked `IllegalArgumentException` out of `execute()` (bypassing `catch (IOException)`) while the JDK adapter silently drops the header — the exact per-transport divergence M5 was about, now only for names. Attacker-controlled header *names* are rarer than values, hence Low.
- **Fix:** add `validateName` (reject CR/LF at minimum; arguably restrict to RFC 7230 `tchar`) beside `validateValues`, and correct the OkHttp adapter comment. **Confirmed.**

#### V2-7. New `ProxyOptions` KDoc claims the JDK stack negotiates "Basic **or Digest**" proxy auth
- **Where:** `ProxyOptions.kt:32-33` (added by the fix).
- The JDK `java.net.http` client's `Authenticator` integration (internal `AuthenticationFilter`) supports **Basic only**; Digest via `Authenticator` is a documented JDK limitation. If that's right, the new doc sends Digest-proxy users to a transport that can't do it, while the JDK transport's *own* new KDoc ("use the OkHttp transport for Digest proxy auth") simultaneously points the other way — and OkHttp's authenticator here is also Basic-only, so that pointer is wrong too. The two new doc blocks contradict each other; at most one is right, plausibly neither.
- **Fix:** verify against the target JDK (one integration test with a Digest-challenging proxy), then make both KDocs consistent with reality (likely: "Basic only, on both shipped transports").
- **Confidence:** Needs-verification (JDK behavior not testable in this sandbox); the *mutual contradiction* between the two new doc blocks is Confirmed regardless.

#### V2-8. Observability semantics changed silently: `request.body.size` / `response.body.size` now report the preview-capped size
- **Where:** `DefaultInstrumentationStep.kt` / `DefaultAsyncInstrumentationStep.kt` `emitResponseEvent` (unchanged code, changed inputs — `snapshot(bodyPreviewMaxBytes)` over a tap/capture that now holds at most the cap).
- Pre-fix these fields reported the actual body size; post-fix they report `min(size, bodyPreviewMaxBytes)` (default 8 KiB), with nothing in the event distinguishing "body was 8 KiB" from "body was 8 GB". Dashboards or alerting keyed on body-size fields will silently flatline at the cap. **Fix:** emit `…body.size` from `contentLength()` when known and add a `…body.preview_truncated` boolean (or rename the field to `…body.preview_size`). **Confirmed** (consequence of the H1/M4 fix; arguably intentional but undocumented — the commit message and docs don't mention the field-semantics change).

---

## 4. Updated improvement opportunities (ranked)

1. **Deduplicate the two instrumentation steps — now a correctness matter, not hygiene.** V2-1 exists *because* the same fix was hand-applied to two copies and landed asymmetrically; the `TODO(omar 2026-08-01)` extraction marker is still present (`DefaultAsyncInstrumentationStep.kt:237`). Extract the shared emitter/wrapping logic before the next divergence.
2. **JDK streaming-body design follow-up** (V2-2/V2-3): first-subscription streaming + loud one-shot resubscribe failure restores streaming for one-shot bodies without re-importing the resend bug.
3. **Unify the three drain/pump loops** (`TeeSink.writeAll`, `writeAllInto`, `LoggableResponseBody.drainAndCache`) behind one helper with the `0`-read guard — V2-4 is the third hand-rolled copy of the same loop with the same forgotten edge.
4. **Sync/async parity for the unknown-length skip** (V2-1) — falls out of item 1.
5. **Header-name validation** (V2-6) — one small function beside `validateValues`.
6. **`URL → URI` in models** — M1's textual-equality fix removed the urgency; still the right end state (also kills `URL`'s other landmines: `toExternalForm` re-serialization cost in hot equality, stream-handler coupling).
7. **Doc truthing pass on proxy auth** (V2-7) and on the body-size log fields (V2-8).
8. **L6′** parser follow-up with a pinned regression test.
9. **Repo hygiene:** `.claude/worktrees/agent-aa2f74e153671cbbf/` stale tree (with its phantom `sdk-auth` module) still present; `QueryParam` `TODO()` stub, unwired `AuthMetadata`/`HttpTracer`/`ServerSentEventListener` surfaces unchanged from v1 item 11.
10. **`ResponseRecoveryStep` KDoc:** document that a step transforming `Success → Failure` owns closing the response it discards (H3's remaining contract gap).

---

## 5. Coverage note (v2)

**Read for this pass:** the complete `1f233bd` diff (48 files, +2241/−269) — every changed main-source file re-read in full post-fix (`LoggableResponseBody`, `TeeSink`, `LoggableRequestBody`, both instrumentation steps + options, both retry steps, `ResponsePipeline`, `PagedIterable`, `ContextStore`, `Headers`, `Request`, `DefaultRedirectStep`, `ProxyOptions`, `AsyncHttpClient`, `AsyncPipelineBridges`, `WriteAllInto`, `BodyPublishers` (full re-read), `JdkHttpTransport`, both `RequestAdapter`s, `OkHttpTransport`, `ResponseBody`, both fixtures) plus the surrounding unchanged call sites needed to validate each fix (`RequestBody.toReplayable`, `OkioBufferedSource.close`, `HttpExceptionFactory` consumers, `PagedResponse`, `Paginator`). New/changed tests reviewed by content for the five High fixes and by name/structure for the rest. v1's full-codebase coverage (every main-source file in all nine modules + test fixtures) carries over; nothing outside the diff was re-read line-by-line in v2 except as call-graph context.
**Not done in this environment:** executing the test suite (JDK 11-only sandbox, no Gradle/toolchain cache) — all "tests cover X" statements are from reading the test code, not from a green run; and the V2-7 JDK Digest question needs a live probe.

**Bottom line:** `1f233bd` is a high-quality fix pass — every v1 High is genuinely closed for the mainstream paths, with deterministic tests for the racy ones. The residual risk has moved from "production-facing defects in core paths" to: one behavioral asymmetry between duplicated files (V2-1), one deliberate-but-costly trade-off in the JDK transport (V2-2), and a tail of small hardening items (V2-3 … V2-8). The single most valuable next change is the instrumentation-step deduplication, which retires V2-1's class of bug permanently.
