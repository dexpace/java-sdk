# MDC propagation per concurrency model — design

**Status**: approved 2026-05-13. Ready to plan + implement.

**Follow-up to**: `2026-05-13-logging-enhancement-pass-design.md`. That pass shipped MDC as the format (`Span.makeCurrentWithLoggingContext` pushes; `LoggingEvent.log` folds), explicitly leaving propagation across async boundaries as a known gap. This spec closes that gap at each adapter module.

## Goal

When a request enters the SDK through one of the four async adapters (coroutines, Reactor, Netty, virtual threads) inside an MDC scope, log events emitted by the adapter *and* by code that runs across the adapter's async boundary should see the same `trace.id` / `span.id` MDC entries as code emitted before the boundary.

Concretely: the existing `.doOn*` and listener hooks the adapters added in Commit 3 of the prior pass log on the wrong thread for MDC purposes. The cancel listener in Netty runs on the event-loop thread. The Reactor `.doOnCancel` runs on whatever scheduler holds the cancel signal. The coroutine future callback runs on a dispatcher worker. Today those log lines miss `trace.id` / `span.id`. This spec fixes that.

## Non-goals

- Globally enabling `Hooks.enableAutomaticContextPropagation()` in Reactor. That's an application-level decision; we'll document the recommended setup but won't impose it.
- Propagating MDC across user-supplied operators in Reactor / continuation chains in CompletableFuture / arbitrary `executor.execute(...)` from user code outside our adapter. We only fix our own adapter boundaries.
- Replacing `Span.makeCurrentWithLoggingContext()` with something else. The MDC contract from the prior pass stays.
- Adding new log events. This pass only ensures existing events have the right MDC.

## Ship plan (five commits, in order)

### Commit 1 — `MdcSnapshot` shared utility (sdk-core)

New file: `sdk-core/src/main/kotlin/org/dexpace/sdk/core/instrumentation/MdcSnapshot.kt`.

A small inline-class-or-data-class value that captures `MDC.getCopyOfContextMap()` at construction and provides two operations:

```kotlin
class MdcSnapshot private constructor(private val snapshot: Map<String, String>?) {

    /** Replaces the current thread's MDC with the captured snapshot. */
    fun restore() {
        if (snapshot == null) MDC.clear() else MDC.setContextMap(snapshot)
    }

    /**
     * Captures the current MDC, restores the snapshot for the duration of [block], then
     * restores the captured-before state on exit (even on exception). Use across async
     * boundaries where the executing thread does not share MDC with the caller.
     */
    inline fun <T> withMdc(block: () -> T): T {
        val previous = MDC.getCopyOfContextMap()
        if (snapshot == null) MDC.clear() else MDC.setContextMap(snapshot)
        try {
            return block()
        } finally {
            if (previous == null) MDC.clear() else MDC.setContextMap(previous)
        }
    }

    companion object {
        /** Captures the current thread's MDC; safe to invoke when no adapter is installed (returns an empty snapshot). */
        fun capture(): MdcSnapshot = MdcSnapshot(MDC.getCopyOfContextMap())
    }
}
```

Public visibility (downstream callers may want to use it directly when bridging their own async work). Unit-tested for the four states: no MDC adapter, MDC adapter with empty map, MDC with values, exception propagation through `withMdc`.

### Commit 2 — Coroutines adapter

Add `org.jetbrains.kotlinx:kotlinx-coroutines-slf4j` as an `implementation` dependency in `sdk-async-coroutines/build.gradle.kts`. Pin to the version matching the existing `kotlinx-coroutines-core` (already on the classpath).

In `Coroutines.kt`:

- `fun HttpClient.asAsyncCoroutines(scope: CoroutineScope): AsyncHttpClient` — change the inner `scope.future { runInterruptible(Dispatchers.IO) { execute(request) } }` to `scope.future(MDCContext()) { runInterruptible(Dispatchers.IO) { execute(request) } }`. `MDCContext()` (no args) snapshots the current MDC at coroutine launch and restores it on each dispatch.

- `fun <T> CoroutineScope.completableFutureOf(context, block)` — its current body is `this.future(context, block = block)`. Change to `this.future(context + MDCContext(), block = block)` so callers get MDC propagation by default; if a caller passes their own `MDCContext()` in `context`, the right-side wins per coroutine-context-element merge semantics.

Test: a new test that sets MDC, calls `asAsyncCoroutines(scope).executeAsync(req).get()`, and verifies the synchronous transport call sees the same MDC. The transport is a `HttpClient` lambda that reads `MDC.get("trace.id")` and stuffs it on the response so the test can assert.

### Commit 3 — Reactor adapter

In `Reactor.kt`, `executeMono` and `sendMono`:

```kotlin
fun AsyncHttpClient.executeMono(request: Request): Mono<Response> {
    val mdc = MdcSnapshot.capture()
    return Mono.fromFuture { executeAsync(request) }
        .doOnSubscribe {
            mdc.withMdc {
                LOG.atVerbose().event("async.adapter.subscribed").field("adapter.type", "reactor").log()
            }
        }
        .doOnCancel {
            mdc.withMdc {
                LOG.atVerbose().event("async.adapter.cancel_propagated").field("adapter.type", "reactor").log()
            }
        }
}
```

Same shape for `sendMono`. The `MdcSnapshot.capture()` happens once per `executeMono(...)` call, BEFORE the Mono is built, so it sees the caller's MDC.

Test: set MDC, build `executeMono(...)`, assert that `LOG.records` for the subscribe event includes the MDC keys.

Document in the file's KDoc the recommended app-level setup for full operator-chain propagation: `Hooks.enableAutomaticContextPropagation()` plus `ContextRegistry.getInstance().registerThreadLocalAccessor(...)` if the consumer wants MDC to flow through their own `.map`/`.flatMap` chains.

### Commit 4 — Netty adapter

In `Netty.kt`, `bridgeToNetty`:

```kotlin
private fun CompletableFuture<Response>.bridgeToNetty(executor: EventExecutor): Future<Response> {
    val source = this
    val promise = executor.newPromise<Response>()
    val mdc = MdcSnapshot.capture()
    source.whenComplete { response, error ->
        if (error != null) promise.setFailure(Futures.unwrap(error)) else promise.setSuccess(response)
    }
    promise.addListener {
        if (it.isCancelled && !source.isDone) {
            source.cancel(true)
            mdc.withMdc {
                LOG.atVerbose().event("async.adapter.cancel_propagated").field("adapter.type", "netty").log()
            }
        }
    }
    return promise
}
```

`MdcSnapshot.capture()` runs on the caller's thread (where MDC is right). The listener runs on the executor's event-loop thread (where MDC may be empty or stale). `withMdc` bridges that.

Test: set MDC, run a Netty promise round-trip with cancellation, assert the cancel-propagated log event has the MDC keys.

### Commit 5 — Virtual threads adapter

New private class `MdcAwareExecutor` inside `VirtualThreads.kt` (or as its own file in the same package):

```kotlin
internal class MdcAwareExecutor(private val delegate: ExecutorService) : ExecutorService by delegate {
    override fun execute(command: Runnable) {
        val snapshot = MdcSnapshot.capture()
        delegate.execute { snapshot.withMdc(command::run) }
    }
    override fun <T> submit(task: Callable<T>): Future<T> {
        val snapshot = MdcSnapshot.capture()
        return delegate.submit(Callable { snapshot.withMdc(task::call) })
    }
    override fun submit(task: Runnable): Future<*> {
        val snapshot = MdcSnapshot.capture()
        return delegate.submit { snapshot.withMdc(task::run) }
    }
    override fun <T> submit(task: Runnable, result: T): Future<T> {
        val snapshot = MdcSnapshot.capture()
        return delegate.submit({ snapshot.withMdc(task::run) }, result)
    }
    // invokeAll / invokeAny: wrap each Callable individually; capture snapshot once per overload.
}
```

Update `fun HttpClient.asAsyncVirtualThreads(): VirtualThreadAsyncHttpClient` to wrap the executor:

```kotlin
fun HttpClient.asAsyncVirtualThreads(): VirtualThreadAsyncHttpClient {
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    val mdcAware = MdcAwareExecutor(executor)
    val async = asAsync(mdcAware)
    return VirtualThreadAsyncHttpClient(async, executor)
}
```

Note: `VirtualThreadAsyncHttpClient.close()` still closes the original `executor` (not the wrapper), so shutdown semantics are unchanged.

Test: set MDC on the caller, run `asyncClient.executeAsync(req).get()`, assert that the `HttpClient` lambda (running on a virtual thread) reads the same MDC.

## Testing strategy across all commits

Each adapter test uses a pattern like:

```kotlin
@Test
fun `mdc propagates across <adapter> async boundary`() {
    installBasicMdcAdapter()
    MDC.put("trace.id", "abc123")
    try {
        val observedTraceId = AtomicReference<String?>()
        val sync = HttpClient { _ -> observedTraceId.set(MDC.get("trace.id")); /* return canned response */ }
        val async = sync.asAsync<Adapter>()  // adapter-specific call
        async.executeAsync(request).get()
        assertEquals("abc123", observedTraceId.get())
    } finally {
        MDC.clear()
    }
}
```

For the `MdcSnapshot` tests in Commit 1, use the same `installBasicMdcAdapter()` helper already proven in Commit 2 of the prior pass.

## Risks

- **Coroutine context merge**: `MDCContext()` is a `ThreadContextElement<Map<String, String>?>`. Combining it with a caller-supplied `context` via `+` is well-defined — the right-side element wins. If a user explicitly passes `MDCContext(specificMap)`, our default `MDCContext()` is replaced rather than merged. That is the intended kotlinx-coroutines semantics. Document briefly.

- **Reactor scheduler hopping inside our own Mono**: `Mono.fromFuture { ... }` runs the supplier on the subscribe thread and the downstream signals on whatever scheduler completes the future. Our `.doOn*` hooks attach to those signals. Capturing MDC at `Mono` construction time and restoring inside the hook body covers our hooks. We do NOT cover user-supplied operators downstream of our return — that's their responsibility and is documented.

- **`MdcAwareExecutor` overhead**: every `execute`/`submit` snapshots MDC (one `getCopyOfContextMap` allocation). For high-throughput virtual-thread callers, this is acceptable (the cost is microseconds compared to the HTTP request it enables). If profiling later shows this is hot, optimise to lazy capture or skip when MDC is empty.

- **`MdcSnapshot.withMdc` exception safety**: the `try/finally` restores the previous map even on `Throwable`. Confirmed by unit test.

- **Adapter-test slf4j-nop**: same workaround used in the prior pass — install `BasicMDCAdapter` via reflection in `@BeforeTest` and clear in `@AfterTest`.

## Out of scope explicitly

- Hooking `Schedulers.onScheduleHook("mdc", ...)` globally in Reactor for the user's operators. Application-level decision.
- Scoping `MdcAwareExecutor` to user-supplied executors via `asAsync(executor)`. Only the virtual-thread adapter's auto-created executor is wrapped; if a user passes their own executor to `asAsync(...)`, they are responsible for its propagation.
- A "propagate all thread-locals" helper. MDC only; if other thread-locals matter, they're per-application.
