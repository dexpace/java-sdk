package org.dexpace.sdk.core.http.sse

import java.time.Duration

/**
 * Callback-style consumer for a Server-Sent Event stream.
 *
 * For a sequence-style consumer, see [readServerSentEvents]. The listener is the
 * lower-level form: typical use is a driver loop that reads events from a
 * [ServerSentEventReader] and dispatches them to a listener, calling [onError] on
 * exceptions and [onClose] when the stream terminates.
 *
 * The default implementations of [onError], [onClose], and [onRetry] are no-ops so
 * simple listeners can supply just [onEvent].
 */
public interface ServerSentEventListener {
    /** Invoked once per parsed event. */
    public fun onEvent(event: ServerSentEvent)

    /** Default: no-op. Override to log or recover. */
    public fun onError(t: Throwable) {}

    /** Default: no-op. Override to release resources or notify observers. */
    public fun onClose() {}

    /**
     * Invoked with the server-advised reconnect interval ([ServerSentEvent.retry]). The SDK
     * surfaces the hint but does not auto-reconnect; listeners that drive reconnect logic
     * should override this to apply the [delay] before re-opening the stream.
     *
     * Default: no-op.
     */
    public fun onRetry(delay: Duration) {}
}
