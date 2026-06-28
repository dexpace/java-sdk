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
import java.util.concurrent.Executor
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
 * the consumer and the strategy reports a non-null next request. Empty pages still count
 * toward the [maxPages] budget.
 *
 * ## Termination
 *
 * The walk completes when any of:
 *
 * - the strategy returns a `null` next request (end of stream), or
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
 * Each [Page] holds the live transport [Response]. The paginator closes that response exactly
 * once per page: after the page has been drained to the consumer (page- or item-level), or, if
 * the page is dropped undrained because the walk was settled externally, when it is discarded.
 * A page whose strategy `parse(...)` throws is never built — its response is closed inline on
 * that exceptional path before the result future completes exceptionally. Strategies MUST read
 * everything they need synchronously inside `parse(...)` and MUST NOT retain the response or its
 * body past the call. A page's raw [Response]/body is valid only while that page is being
 * delivered (see [forEachPageAsync]); [Page.items] and the derived metadata outlive close.
 *
 * ## Consumer threading
 *
 * By default the [Consumer] passed to [forEachAsync] is invoked on whichever thread completes
 * the page future — typically a transport callback thread. It runs inline in the completion
 * graph, so a slow or blocking consumer holds up the walk and ties up that transport thread.
 * For a consumer that does blocking or expensive work, prefer the [forEachAsync] /
 * [collectAllAsync] overloads that take an [Executor]: the page-draining driver — and therefore
 * every consumer invocation — then runs on that executor, leaving the transport's callback
 * threads free. (The strategy `parse` and response `close()` are not dispatched to the executor;
 * they run inline in each transport future's completion — usually on a transport callback thread,
 * but on the driver thread when the transport completes synchronously.) Either way the consumer
 * is never invoked concurrently for a single walk, items are delivered one at a time in server
 * order, and the consumer MUST NOT assume any particular thread. A consumer that throws aborts
 * the walk and surfaces through the result future.
 *
 * ## Cancellation
 *
 * Completing the future returned by [forEachAsync] / [collectAllAsync] from the outside —
 * `cancel(true)`, `complete(...)`, `completeExceptionally(...)`, `orTimeout(...)` — halts the
 * walk: the driver fetches no further pages, and the page exchange currently in flight is
 * best-effort aborted by cancelling its transport future (which propagates into the underlying
 * client per the [AsyncHttpClient] cancellation contract). A page request already dispatched
 * may still complete before the abort takes effect; when it completes successfully, the
 * paginator closes and discards that response. One narrow race is inherent to the
 * [AsyncHttpClient] SPI and the paginator cannot close around it: if the cancel settles the
 * transport future before the transport delivers its [Response], that response never reaches the
 * paginator's close path — releasing it is the transport's responsibility, since cancelling a
 * `CompletableFuture` cannot reach back into an already-built response.
 *
 * Cancellation takes effect at page granularity. If the result is settled while a page is
 * mid-drain, the items already being delivered from that page still reach the consumer — the
 * driver stops at the next page boundary rather than interrupting an in-progress drain. A page
 * that has been fetched but not yet drained when the result settles is dropped undrained.
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
         * server-defined order, on the thread that completes each page (typically a transport
         * callback thread). The returned future completes (with `null`) once the walk has
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
        public fun forEachAsync(consumer: Consumer<in T>): CompletableFuture<Void> =
            startWalk({ page -> page.items.forEach(consumer::accept) }, null)

        /**
         * Like [forEachAsync], but runs the page-draining driver — and therefore every
         * [consumer] invocation — on [executor] instead of inline on the page-completion thread.
         * Use this when the consumer does blocking or expensive work, so it does not tie up the
         * transport's callback threads. Items are still delivered one at a time in server order,
         * and the consumer is never invoked concurrently for a single walk.
         *
         * If [executor] rejects work (it is shut down, or a bounded queue is saturated) the walk
         * terminates and the returned future completes exceptionally with the rejection.
         *
         * @param consumer Invoked once per item, on [executor].
         * @param executor Executor on which the driver and consumer run.
         * @return A future that completes when the walk finishes.
         */
        public fun forEachAsync(
            consumer: Consumer<in T>,
            executor: Executor,
        ): CompletableFuture<Void> = startWalk({ page -> page.items.forEach(consumer::accept) }, executor)

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
        public fun collectAllAsync(): CompletableFuture<List<T>> = collectInto(null)

        /**
         * Like [collectAllAsync], but runs the driver on [executor] — see the threading contract
         * on [forEachAsync]`(consumer, executor)`.
         *
         * @param executor Executor on which the driver runs.
         * @return A future that completes with all items.
         */
        public fun collectAllAsync(executor: Executor): CompletableFuture<List<T>> = collectInto(executor)

        /**
         * Walks every page, invoking [consumer] for each [Page] in server-defined order. The page
         * passed to [consumer] is **live**: its raw [Page.response]/body is open and readable for
         * the duration of the callback. The driver closes that response immediately after `accept`
         * returns, so the consumer MUST NOT retain the page or read its raw body afterwards;
         * [Page.items] and the derived metadata (status code, headers, request) survive close.
         *
         * Cancellation, executor, and ordering semantics are identical to [forEachAsync].
         *
         * @param consumer Invoked once per page, with the page live for the callback's duration.
         * @return A future that completes when the walk finishes.
         */
        public fun forEachPageAsync(consumer: Consumer<in Page<T>>): CompletableFuture<Void> =
            startWalk({ page -> consumer.accept(page) }, null)

        /**
         * Like [forEachPageAsync], but runs the page-draining driver on [executor].
         *
         * @param consumer Invoked once per page, on [executor].
         * @param executor Executor on which the driver and consumer run.
         * @return A future that completes when the walk finishes.
         */
        public fun forEachPageAsync(
            consumer: Consumer<in Page<T>>,
            executor: Executor,
        ): CompletableFuture<Void> = startWalk({ page -> consumer.accept(page) }, executor)

        private fun startWalk(
            pageSink: (Page<T>) -> Unit,
            executor: Executor?,
        ): CompletableFuture<Void> {
            val result = CompletableFuture<Void>()
            Walk(pageSink, executor, result).start()
            return result
        }

        private fun collectInto(executor: Executor?): CompletableFuture<List<T>> {
            val items = ArrayList<T>()
            return startWalk({ page -> items.addAll(page.items) }, executor).thenApply { items }
        }

        /**
         * Drives one independent walk. Single-call state; never reused across [forEachAsync]
         * invocations.
         */
        private inner class Walk(
            private val pageSink: (Page<T>) -> Unit,
            private val executor: Executor?,
            private val result: CompletableFuture<Void>,
        ) {
            private var nextRequest: Request? = initialRequest
            private var pagesFetched: Long = 0L

            // Guards against re-entrant driving: a page future that completes synchronously
            // would otherwise recurse into the loop that scheduled it. We trampoline instead —
            // whichever stack frame owns the loop picks up the staged work.
            private val driving = AtomicBoolean(false)
            private var pendingPage: ParsedPage<T>? = null

            // The transport future for the page currently being fetched: null until the first
            // fetch, thereafter the most recently dispatched exchange (it is never reset to null,
            // so after a fetch settles it retains that now-completed future — cancelling it is a
            // harmless no-op). Written by the loop owner (in [fetchPage]); read by the
            // cancellation hook on a possibly different thread, hence @Volatile.
            @Volatile
            private var inFlight: CompletableFuture<Response>? = null

            fun start() {
                // If the caller cancels/completes the result future, abort the in-flight page
                // exchange (best-effort) so a long walk does not keep an exchange open. The
                // driver also re-checks result.isDone before each fetch, so no further pages
                // are requested once the result is settled.
                result.whenComplete { _, _ -> inFlight?.cancel(true) }
                // Enter the driver — on [executor] when one was supplied, so even a synchronously
                // completed first page drains to the consumer off the caller's thread.
                resume()
            }

            /**
             * Enters the trampoline loop, dispatching to [executor] when one was supplied. Both
             * the initial entry and every async re-entry route through here, so the consumer runs
             * on the executor rather than on a transport callback thread. A rejected dispatch
             * (executor shut down or saturated) fails the walk instead of leaking the rejection
             * into the completion machinery or hanging the result future.
             */
            private fun resume() {
                val ex = executor
                if (ex == null) {
                    drive()
                } else {
                    try {
                        ex.execute { drive() }
                    } catch (t: Throwable) {
                        result.completeExceptionally(t)
                    }
                }
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
                    // emitting it to the consumer — but close it so its Response is not leaked.
                    if (result.isDone) {
                        staged.page.close() // dropped undrained → release its Response
                        return Step.DONE
                    }
                    return if (drainPage(staged)) Step.CONTINUE else Step.DONE
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
                // The callback stages the page, releases the flag, and re-enters the driver via
                // resume() — on the executor when one was supplied. If pageFuture completes during
                // registration the callback runs inline on the current thread; the SUSPEND frame
                // still never clears `driving`, so the re-entry stays safe.
                pageFuture.whenComplete { _, _ ->
                    if (stagePage(pageFuture)) {
                        driving.set(false)
                        resume()
                    }
                }
                return Step.SUSPEND
            }

            /**
             * Stages a completed page future for draining. Returns `true` to continue driving,
             * `false` if the future failed (the walk is then terminated exceptionally).
             */
            private fun stagePage(future: CompletableFuture<ParsedPage<T>>): Boolean {
                val page: ParsedPage<T> =
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
             * Delivers a parsed page to the page sink (which may emit the page's items or the
             * whole page), then schedules the next request read from the [ParsedPage]. The page's
             * [Response] is closed in a `finally`, so it is released whether the sink succeeds or
             * throws. Returns `true` to continue driving, `false` if the sink threw (walk aborted).
             */
            private fun drainPage(page: ParsedPage<T>): Boolean {
                try {
                    pageSink(page.page)
                    nextRequest = page.nextRequest
                } catch (t: Throwable) {
                    result.completeExceptionally(t)
                    return false
                } finally {
                    page.page.close() // release this page's Response (drained or threw)
                }
                return true
            }

            /**
             * Executes [request], parses the response into a [PageInfo], and builds a [Page] that
             * **retains** the live response — mirroring [Paginator]'s per-page lifecycle. The page's
             * response is closed later, on whichever path consumes it (drained in [drainPage], or
             * dropped-and-closed in [step]); only a `parse` failure closes the response here, since
             * such a page is never built. The returned future completes with the resulting
             * [ParsedPage] or exceptionally if the transport or strategy fails.
             */
            private fun fetchPage(request: Request): CompletableFuture<ParsedPage<T>> {
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
                        // rather than NPE on the parse below.
                        error("AsyncHttpClient.executeAsync completed with a null Response")
                    }
                    val info =
                        try {
                            strategy.parse(response, initialRequest)
                        } catch (t: Throwable) {
                            response.close() // parse failed → this page never becomes a Page; release it now
                            throw t
                        }
                    ParsedPage(Page(response, info.items), info.nextRequest)
                }
            }
        }
    }

/** Outcome of a single step of [AsyncPaginator]'s trampoline loop. */
private enum class Step { CONTINUE, DONE, SUSPEND }

/** A parsed page plus the request that fetches the next one (null at end of stream). */
private class ParsedPage<T>(
    val page: Page<T>,
    val nextRequest: Request?,
)
