package org.dexpace.sdk.transport.okhttp.internal

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.common.MediaType
import org.dexpace.sdk.core.http.common.Protocol
import org.dexpace.sdk.core.http.response.Status
import org.dexpace.sdk.core.io.Io
import okhttp3.Protocol as OkProtocol
import okhttp3.Response as OkResponse
import org.dexpace.sdk.core.http.request.Request as SdkRequest
import org.dexpace.sdk.core.http.response.Response as SdkResponse
import org.dexpace.sdk.core.http.response.ResponseBody as SdkResponseBody

/**
 * Adapts an OkHttp [OkResponse] into the SDK's [SdkResponse], preserving:
 *  - status (via [Status.fromCode])
 *  - protocol (mapped through [toSdkProtocol])
 *  - headers (each name/value pair copied through OkHttp's iterable headers view)
 *  - reason phrase (`message`)
 *  - body — the OkHttp `byteStream()` is wrapped via [Io.provider.source] so the active
 *    IoProvider supplies the `BufferedSource`. Closing the SDK response cascades into the
 *    okio source, which closes the OkHttp body, which releases the underlying connection.
 *
 * The body's `Content-Type` is parsed via [MediaType.parse]; an unparseable value is
 * silently downgraded to `null` (the SDK contract allows `mediaType()` to be `null`).
 *
 * No retention of the OkHttp response object is necessary beyond the body — the body's
 * own close releases everything.
 */
internal class ResponseAdapter {
    fun adapt(
        sdkRequest: SdkRequest,
        okhttpResponse: OkResponse,
    ): SdkResponse {
        val headersBuilder = Headers.Builder()
        for ((name, value) in okhttpResponse.headers) {
            headersBuilder.add(name, value)
        }
        // OkHttp 5.x exposes `Response.body` as a non-nullable `ResponseBody`; bodies for
        // status codes that have no payload (204, 304, etc.) are zero-length, not null. We
        // surface them via the SDK's `ResponseBody.create` for consistency — closing the
        // SDK body releases the underlying connection regardless of payload size.
        val okhttpBody = okhttpResponse.body
        val contentType = okhttpBody.contentType()?.toString()
        val mediaType =
            contentType?.let {
                try {
                    MediaType.parse(it)
                } catch (_: IllegalArgumentException) {
                    // Content-Type returned by the server is malformed; surface the body
                    // without a typed MediaType rather than fail the response.
                    null
                }
            }
        val length = okhttpBody.contentLength()
        val bufferedSource = Io.provider.source(okhttpBody.byteStream())
        val sdkBody: SdkResponseBody = SdkResponseBody.create(bufferedSource, mediaType, length)
        return SdkResponse.builder()
            .request(sdkRequest)
            .protocol(toSdkProtocol(okhttpResponse.protocol))
            .status(Status.fromCode(okhttpResponse.code))
            .message(okhttpResponse.message)
            .headers(headersBuilder.build())
            .body(sdkBody)
            .build()
    }

    /**
     * Maps an OkHttp [OkProtocol] onto the SDK's [Protocol]. OkHttp's `HTTP_2` covers both
     * normal ALPN-negotiated HTTP/2 and prior-knowledge mode in our pipeline's eyes — the
     * distinction is only meaningful when the SDK has to dial a fresh prior-knowledge
     * connection, which is not OkHttp's responsibility here. SPDY variants are obsolete and
     * fall through to HTTP/1.1.
     */
    private fun toSdkProtocol(p: OkProtocol): Protocol =
        when (p) {
            OkProtocol.HTTP_1_0 -> Protocol.HTTP_1_0
            OkProtocol.HTTP_1_1 -> Protocol.HTTP_1_1
            OkProtocol.HTTP_2 -> Protocol.HTTP_2
            OkProtocol.H2_PRIOR_KNOWLEDGE -> Protocol.H2_PRIOR_KNOWLEDGE
            OkProtocol.QUIC -> Protocol.QUIC
            else -> Protocol.HTTP_1_1
        }
}
