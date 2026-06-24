/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.pagination

import org.dexpace.sdk.core.client.AsyncHttpClient
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import org.dexpace.sdk.core.util.Futures
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * Asynchronous, strategy-driven paginator over an [AsyncHttpClient] — the non-blocking
 * counterpart of [Paginator].
 *
 * `AsyncPaginator` executes [initialRequest] against [asyncHttpClient], delegates each
 * response to [strategy], and drives the page-to-page walk through a chain of
 * [CompletableFuture]s. No thread blocks waiting on a page: each page is fetched, parsed,
 * drained to the caller's consumer, and the next page is re-armed inside the future's
 * completion graph. The same wire conventions ([PaginationStrategy], [Page]) back both the
 * sync and async paginators, so a strategy written for [Paginator] works here unchanged.
 *
 * ## Laziness and the fetch budget
 *
 * Iteration is page-lazy in the same sense as [Paginator]: exactly one HTTP exchange happens
 * per page consumed. A new page is fetched only after the previous page has been drained to
 * the consumer and reports `hasNext` with a non-null next request. Empty pages still count
 * toward the [maxPages] budget.
 *
 * ## Termination
 *
 * The walk completes when any of:
 *
 * - the current page reports `hasNext == false`, or
 * - the current page's `nextPageRequest()` returns `null`, or
 * - [maxPages] pages have been fetched (the safety cap), or
 * - the consumer throws, or a transport/parse failure occurs (the result future completes
 *   exceptionally).
 *
 * ## Safety cap
 *
 * [maxPages] defaults to `Long.MAX_VALUE` (effectively unbounded). A misbehaving server that
 * never advances its paging cursor would otherwise drive an unbounded fetch loop. **Production
 * callers should set a finite cap.** The cap counts pages fetched (HTTP exchanges), not items.
 *
 * ## Response lifecycle
 *
 * Each [Response] is closed by the paginator after the strategy has parsed it — including on
 * the exceptional path, where the response is closed before the result future is completed
 * exceptionally. Strategies MUST read everything they need synchronously inside `parse(...)`
 * and MUST NOT retain the response or its body past the call. Items in the returned [Page]
 * outlive the response.
 *
 * ## Consumer threading
 *
 * The [Consumer] passed to [forEachAsync] is invoked on whichever thread completes the page
 * future — typically a transport callback thread. It runs inline in the completion graph, so
 * a slow consumer holds up the walk. The consumer is never invoked concurrently for a single
 * [forEachAsync] call, but it MUST NOT assume any particular thread. A consumer that throws
 * aborts the walk and surfaces through the result future.
 *
 * ## Cancellation
 *
 * Completing the future returned by [forEachAsync] / [collectAllAsync] from the outside —
 * `cancel(true)`, `complete(...)`, `completeExceptionally(...)`, `orTimeout(...)` — halts the
 * walk: the driver fetches no further pages, and the page exchange currently in flight is
 * best-effort aborted by cancelling its transport future (which propagates into the underlying
 * client per the [AsyncHttpClient] cancellation contract). A page request already dispatched
 * may still complete before the abort takes effect; its response is closed and discarded.
 *
 * ## Stack safety
 *
 * The driver is trampolined: synchronously completed page futures (e.g. from an in-memory
 * fake transport, or a cache hit) are processed in a loop rather than via recursive
 * `thenCompose`, so a long run of already-complete pages does not overflow the stack.
 *
 * ## Thread-safety
 *
 * `AsyncPaginator` itself holds only immutable fields and is safe to share. Each call to
 * [forEachAsync] / [collectAllAsync] starts an independent walk with its own state.
 *
 * ## Java interop
 *
 * ```
 * AsyncPaginator<Item> paginator = new AsyncPaginator<>(client, request, strategy);
 * paginator.forEachAsync(item -> handle(item))
 *          .thenRun(() -> done());
 * paginator.collectAllAsync().thenAccept(items -> ...);
 * ```
 *
 * @param T Element type yielded by the paginator.
 * @property asyncHttpClient Async transport used to execute each page request.
 * @property initialRequest Request used to fetch the first page; also passed to the strategy
 *   as a template for building subsequent page requests.
 * @property strategy Strategy that parses each response into a [Page].
 * @property maxPages Safety cap on the total number of pages (HTTP exchanges) the walk will
 *   fetch. Defaults to `Long.MAX_VALUE` (unbounded). Must be positive.
 */
public class AsyncPaginator<T>
    @JvmOverloads
    constructor(
        private val asyncHttpClient: AsyncHttpClient,
        private val initialRequest: Request,
        private val strategy: PaginationStrategy<T>,
        private val maxPages: Long = Long.MAX_VALUE,
    ) {
        init {
            require(maxPages > 0L) { "maxPages must be positive, was $maxPages" }
        }

        /**
         * Walks every item across every page, invoking [consumer] for each item in
         * server-defined order. The returned future completes (with `null`) once the walk has
         * terminated normally, or completes exceptionally if the transport fails, a strategy
         * `parse` throws, or [consumer] throws.
         *
         * Each call starts a fresh walk from [initialRequest]. Cancelling (or otherwise
         * completing) the returned future halts the walk and best-effort aborts the in-flight
         * page exchange — see the class-level "Cancellation" KDoc.
         *
         * @param consumer Invoked once per item. See the class-level "Consumer threading" KDoc.
         * @return A future that completes when the walk finishes.
         */
        public fun forEachAsync(consumer: Consumer<in T>): CompletableFuture<Void> {
            val result = CompletableFuture<Void>()
            Walk(consumer, result).start()
            return result
        }

        /**
         * Collects every item across every page into a single list, in server-defined order.
         * The returned future completes with the accumulated list, or completes exceptionally
         * on transport/parse/consumer failure.
         *
         * Buffers all items in memory — prefer [forEachAsync] for large or unbounded result
         * sets.
         *
         * @return A future that completes with all items.
         */
        public fun collectAllAsync(): CompletableFuture<List<T>> {
            val items = ArrayList<T>()
            return forEachAsync { items.add(it) }.thenApply { items }
        }

        /**
         * Drives one independent walk. Single-call state; never reused across [forEachAsync]
         * invocations.
         */
        private inner class Walk(
            private val consumer: Consumer<in T>,
            private val result: CompletableFuture<Void>,
        ) {
            private var nextRequest: Request? = initialRequest
            private var pagesFetched: Long = 0L

            // Guards against re-entrant driving: a page future that completes synchronously
            // would otherwise recurse into the loop that scheduled it. We trampoline instead —
            // whichever stack frame owns the loop picks up the staged work.
            private val driving = AtomicBoolean(false)
            private var pendingPage: Page<T>? = null

            // The transport future for the page currently being fetched, or null between
            // fetches. Written by the loop owner (in [fetchPage]); read by the cancellation
            // hook on a possibly different thread, hence @Volatile.
            @Volatile
            private var inFlight: CompletableFuture<Response>? = null

            fun start() {
                // If the caller cancels/completes the result future, abort the in-flight page
                // exchange (best-effort) so a long walk does not keep an exchange open. The
                // driver also re-checks result.isDone before each fetch, so no further pages
                // are requested once the result is settled.
                result.whenComplete { _, _ -> inFlight?.cancel(true) }
                drive()
            }

            /**
             * Trampoline loop. Each [step] advances the walk by one unit of synchronously
             * available work and reports back via [Step]: keep looping ([Step.CONTINUE]),
             * terminate ([Step.DONE]), or yield the loop to an async completion callback
             * ([Step.SUSPEND]). On `SUSPEND` the `driving` flag is left set — the callback owns
             * it and clears it before re-entering [drive] — so this frame must not clear it.
             */
            private fun drive() {
                if (!driving.compareAndSet(false, true)) {
                    // Another stack frame owns the loop; it will observe the work we queued.
                    return
                }
                var suspended = false
                try {
                    var outcome = step()
                    while (outcome == Step.CONTINUE) {
                        outcome = step()
                    }
                    suspended = outcome == Step.SUSPEND
                } finally {
                    if (!suspended) {
                        driving.set(false)
                    }
                }
            }

            /**
             * Performs one unit of synchronously available work: drain a staged page, finish the
             * walk, or fetch the next page. A synchronously completed fetch yields [Step.CONTINUE];
             * a genuinely pending fetch arms a completion callback and yields [Step.SUSPEND].
             */
            private fun step(): Step {
                val staged = pendingPage
                if (staged != null) {
                    pendingPage = null
                    // If the result was settled from the outside (cancelled, timed out, or
                    // completed by the caller), drop the staged page undrained instead of
                    // emitting it to the consumer.
                    return if (!result.isDone && drainPage(staged)) Step.CONTINUE else Step.DONE
                }
                val request = nextRequest
                if (request == null || result.isDone || pagesFetched >= maxPages) {
                    // No next request, the result was settled externally, or the cap is reached
                    // before fetching a page we would otherwise yield: the walk is complete.
                    // complete(null) is a no-op when the result is already settled.
                    result.complete(null)
                    return Step.DONE
                }
                nextRequest = null
                val pageFuture = fetchPage(request)
                if (pageFuture.isDone) {
                    // Synchronous completion (fake transport, cache hit): handle inline to keep
                    // the stack flat instead of recursing through a callback.
                    return if (stagePage(pageFuture)) Step.CONTINUE else Step.DONE
                }
                // Genuinely async: hand the `driving` flag to the callback and suspend the loop.
                // If pageFuture completes during registration, this callback runs inline on the
                // current thread and re-enters drive() while this frame still returns SUSPEND —
                // safe, because the SUSPEND frame never clears `driving`.
                pageFuture.whenComplete { _, _ ->
                    if (stagePage(pageFuture)) {
                        driving.set(false)
                        drive()
                    }
                }
                return Step.SUSPEND
            }

            /**
             * Stages a completed page future for draining. Returns `true` to continue driving,
             * `false` if the future failed (the walk is then terminated exceptionally).
             */
            private fun stagePage(future: CompletableFuture<Page<T>>): Boolean {
                val page: Page<T> =
                    try {
                        future.join()
                    } catch (t: Throwable) {
                        result.completeExceptionally(Futures.unwrap(t))
                        return false
                    }
                pendingPage = page
                return true
            }

            /**
             * Emits a page's items to the consumer, then schedules the next request. Returns
             * `true` to continue driving, `false` if the consumer threw (walk aborted).
             */
            private fun drainPage(page: Page<T>): Boolean {
                try {
                    val items = page.items
                    var i = 0
                    val size = items.size
                    while (i < size) {
                        consumer.accept(items[i])
                        i++
                    }
                    // Compute the next request inside the guard: a Page whose hasNext /
                    // nextPageRequest() throws (e.g. a malformed cursor) must surface through
                    // the result future, not escape the driver and strand the walk.
                    nextRequest = if (page.hasNext) page.nextPageRequest() else null
                } catch (t: Throwable) {
                    result.completeExceptionally(t)
                    return false
                }
                return true
            }

            /**
             * Executes [request], parses the response into a [Page], and closes the response —
             * mirroring [Paginator]'s per-page lifecycle. The returned future completes with the
             * parsed page or exceptionally if the transport or strategy fails.
             */
            private fun fetchPage(request: Request): CompletableFuture<Page<T>> {
                pagesFetched++
                val transportFuture: CompletableFuture<Response> =
                    try {
                        asyncHttpClient.executeAsync(request)
                    } catch (t: Throwable) {
                        // An eager throw from executeAsync (a contract violation, but be
                        // defensive) becomes an exceptional future so the driver stays uniform.
                        return Futures.failed(t)
                    }
                // Publish the in-flight exchange so external cancellation can abort it, then
                // re-check: if the walk was settled between step()'s fetch guard and here, the
                // one-shot cancel hook may have already fired against the previous (now-stale)
                // inFlight, so abort the exchange we just dispatched ourselves.
                inFlight = transportFuture
                if (result.isDone) {
                    transportFuture.cancel(true)
                }
                return transportFuture.handle { response, error ->
                    if (error != null) {
                        throw Futures.unwrap(error)
                    }
                    if (response == null) {
                        // AsyncHttpClient forbids a null success completion; fail cleanly
                        // rather than NPE on the parse/close below.
                        error("AsyncHttpClient.executeAsync completed with a null Response")
                    }
                    try {
                        strategy.parse(response, initialRequest)
                    } finally {
                        response.close()
                    }
                }
            }
        }
    }

/** Outcome of a single step of [AsyncPaginator]'s trampoline loop. */
private enum class Step { CONTINUE, DONE, SUSPEND }
