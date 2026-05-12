package org.dexpace.sdk.core.http.sse

import java.time.Duration

/**
 * A single Server-Sent Event parsed from a stream, per the WHATWG SSE spec
 * (https://html.spec.whatwg.org/multipage/server-sent-events.html).
 *
 * The [data] field is a list because the spec allows multiple `data:` lines per event;
 * the application layer typically joins them with `\n`, but exposing the lines verbatim
 * lets callers reconstruct, count, or transform them as needed.
 *
 * [comment] holds the most recent comment line (lines starting with `:`) seen in this
 * event block. The spec does not require comments to be exposed, but doing so lets
 * callers detect server-side keep-alives.
 *
 * [retry] is the reconnect interval the server advised. Reconnect logic itself is the
 * caller's concern — this SDK only surfaces the value.
 */
data class ServerSentEvent @JvmOverloads constructor(
    val id: String? = null,
    val event: String? = null,
    val data: List<String> = emptyList(),
    val comment: String? = null,
    val retry: Duration? = null,
) {
    /**
     * True if this event has no meaningful payload. Useful when filtering out
     * empty dispatches that might slip through if a producer ever sends one.
     */
    val isEmpty: Boolean
        get() = id == null && event == null && data.isEmpty() && comment == null && retry == null
}
