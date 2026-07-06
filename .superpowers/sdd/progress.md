# DX Improvements — Progress Ledger

Branch: worktree-dx-improvements  |  Base: b015fd3
Plan: docs/superpowers/plans/2026-07-03-dx-improvements.md

## Work units (file-cohesive)
- [x] U1  Response family (T1 Status preds, T2 isSuccessful+string/bytes, T3 throwOnError+bodyAs, T9 deserialize)
- [x] U2  Request family + MediaType (T4, T5, T6, T7, T8-core)
- [x] U3  Jackson serde (T10 Tristate fix, T8 jsonBody)
- [x] U5  *Options builders + config + JDK timeout (T12, T13)
- [x] U6  ServiceLoader IoProvider (T15)
- [x]  Pipeline steps (T16 throwOnHttpError, T17 standard, T22 pillar-fail, T25 HttpPipeline:HttpClient)
- [ ] U8  Async + transports (T14 NetworkException, T18 handleWith, T19 cancel, T23 proxyEnv)
- [x] U9  Serde TypeRef + pagination SAM (T20, T21)
- [x] U10 Value-class demotion (T24 ETag/HttpRange/RequestConditions)
- [x] U11 Per-request RequestOptions (T26)
- [x] U12 Recovery-stack rename (T27, T28)
- [x] U4  Docs (T11) — after ServiceLoader so samples are final
- [ ] FINAL apiDump + full gated build + final review + PR

## Completed
- U1: complete (commits 43a101e..a51d5d1, gates green: 2002 tests, detekt, apiCheck, ktlint)
- U2: complete (commits 0ac3035..e1283ee, 36 tests, gates green)
- U3: complete (commits db870b5..c5b456d, 38 tests, gates green)

## Wave-1 review findings (from wave1-review.md)
- FIX (Important) bodyAs: close body in finally (ResponseExtensions.kt ~60-68)
- FIX (Important) throwOnError: bound buffered body (cap ~1 MiB) + doc (ResponseExtensions.kt ~34-42)
- FIX (Important) T3 wording: "replayable" -> "buffered in memory, readable after throw"
- FIX (Minor) bodyAs: debug-log the swallowed exception (ref e)
- FIX (Minor) delete(): clear stale body for consistency with get()/head()
- DEFER to final: #6 jsonBody redundancy (plan-mandated, adjudicate), #8 create(value) nullability doc
- U5: complete (commits 3a1482f..275fc33, 11 tests, gates green)
- Wave-1 fixes: complete (commit c54e8cf; leak, OOM-cap, wording, log, delete-body — tests green)
- U6: complete (commit 7e66659, ServiceLoader fallback, full sdk-core suite green)
- U7a: complete (commits 8d54cee..c84cdc2; added Stage PRE_REDIRECT(50); NOTE: subagent briefly committed on MAIN then reverted it to b015fd3 — main verified clean, user kuri changes untouched)
- U7b: complete (commits b9196cb..c7a8cb5, pillar hard-fail + HttpPipeline:HttpClient; cwd guard held, main clean)
- U8a: complete (commits f3830bf..eba18e1, transports NetworkException + proxyFromEnvironment, 13 suites green)
- U8b: complete (commits e39ae69..0b22857, handleWith + unified cancel; 2nd main-slip reverted cleanly, main verified intact)
- U9: complete (commits 85f3307..7db7482, SAM extractors + TypeRef, gates green, path guard held)

## Final-review items (defer)
- jsonHandlerOrThrow leaves LIVE error body on throw; throwOnError BUFFERS — reconcile for consistency (buffer both, bounded 1 MiB)
- jsonBody(serde,value) redundancy vs RequestBody.create(value,serde) — decide keep/drop
- docs/pipelines.md stale "replace emits warning" line (U7b) — fix in U4 docs
- run aggregate koverVerify (80% floor) at finalization
- U10: complete (commit fcbe657, ETag/HttpRange demoted + String overloads, de-mangling proven)
- U11: complete (commits 2f707ac..5f8e494, RequestOptions timeout+maxRetries wired, full gate green)
- final-review: U11 negative per-call maxRetries used as-is (no clamp) — decide if OK; PipelineNext.options now public
- U12: complete (commit 2d8df24, recovery stack -> RecoveryChain/RetryRecovery/ThrowOnHttpErrorRecovery, grep-clean, gates green)
- U4: complete (commit d36b3dd, README+samples, sdk-example test+run green)
- ALL 13 UNITS COMPLETE — starting finalization

## Finalization
- apiDump reconciled (virtualthreads straggler committed)
- FULL GATED BUILD: BUILD SUCCESSFUL — all module tests, detekt, ktlint, allWarningsAsErrors, apiCheck, R8 shrink guard, kover 80% floor ALL GREEN
- Final whole-branch review: running
- TODO: triage final review + deferred items -> one fix wave -> PR
- Final review fix wave: complete (02bec41,5d95eea,e752dbc,cfce016,3ef4028) — all 5 findings fixed, gate green
- NOTE: intermediate commit cfce016 api-stale (same-file entanglement); branch HEAD green
- Running final full gated build before push

## DONE
- Final full gated build: BUILD SUCCESSFUL (kover+R8+all gates)
- Pushed worktree-dx-improvements -> origin/dx-improvements
- PR #208 opened (base main), clean (no styleguide/.superpowers/plan), CI build running
- 33 commits, +6277/-761 across 124 files
