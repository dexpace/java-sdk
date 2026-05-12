package org.dexpace.sdk.core.http.request

/**
 * HTTP request methods recognized by the SDK. Each constant carries the canonical token used
 * on the wire; [toString] returns that same token so the enum can be written directly into a
 * request line without translation.
 *
 * @property method Canonical uppercase method token sent in the request line.
 */
@Suppress("unused")
enum class Method(
    val method: String
) {
    GET("GET"),
    POST("POST"),
    PUT("PUT"),
    DELETE("DELETE"),
    PATCH("PATCH"),
    HEAD("HEAD"),
    OPTIONS("OPTIONS"),
    TRACE("TRACE"),
    CONNECT("CONNECT")
    ;

    override fun toString(): String = method
}