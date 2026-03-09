package org.dexpace.sdk.core.http.request

import org.dexpace.sdk.core.http.Headers

fun interface BuilderTrait<out T> {
    fun build(): T
}

interface NewBuilderTrait<out T> {
    fun builder(): BuilderTrait<T>
}

@ConsistentCopyVisibility
data class Request private constructor(
    val headers: Headers = Headers.Builder().build(),
    val body: RequestBody? = null
) {

    class Builder :BuilderTrait<Request> {
        override fun build(): Request {
            return Request(
                headers = headers,
                body = body
            )
        }

        private var headers: Headers = Headers.Builder().build()
        private var body: RequestBody? = null

        fun headers(headers: Headers): BuilderTrait<Request> = apply { this.headers = headers }
        fun body(body: RequestBody): BuilderTrait<Request> = apply { this.body = body }
    }

    companion object : NewBuilderTrait<Request> {
        @JvmStatic
        override fun builder() = Builder()
    }
}
