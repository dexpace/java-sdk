package org.dexpace.sdk.core.http.request

/**
 * Enumeration of HTTP methods.
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