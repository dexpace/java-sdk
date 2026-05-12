package org.dexpace.sdk.core.http.sse

/**
 * Callback-style consumer for a Server-Sent Event stream.
 *
 * For a sequence-style consumer, see [readServerSentEvents]. The listener is the
 * lower-level form: typical use is a driver loop that reads events from a
 * [ServerSentEventReader] and dispatches them to a listener, calling [onError] on
 * exceptions and [onClose] when the stream terminates.
 *
 * The default implementations of [onError] and [onClose] are no-ops so simple
 * listeners can supply just [onEvent].
 */
interface ServerSentEventListener {
    fun onEvent(event: ServerSentEvent)

    /** Default: no-op. Override to log or recover. */
    fun onError(t: Throwable) {}

    /** Default: no-op. Override to release resources or notify observers. */
    fun onClose() {}
}
