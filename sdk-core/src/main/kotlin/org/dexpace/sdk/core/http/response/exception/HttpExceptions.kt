/*
 * Copyright (c) 2026 dexpace and Omar Aljarrah
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 * SPDX-License-Identifier: MIT
 */

package org.dexpace.sdk.core.http.response.exception

import org.dexpace.sdk.core.http.response.Response

// All concrete HTTP exception subclasses. One class per canonical status code, plus two
// generic fallbacks (`ClientErrorException`, `ServerErrorException`) used by
// `HttpExceptionFactory.fromResponse` when the wire status is in 4xx / 5xx but is not one
// of the codes the SDK knows by name.
//
// Each subclass is `open` so service-client codegen can derive a per-operation typed subclass
// that stamps a deserialized error payload — see `docs/refs-comparison.md` Error Model §,
// action item 3.
//
// None of these subclasses sets its own retryable flag: `HttpException.isRetryable` is derived
// from `RetryUtils.isRetryable(status.code)` (the single source of truth), so the baked flag
// always mirrors the live retry policy — 408 / 429 and the 5xx range except 501 / 505 are
// retryable, everything else is not. Fallback subclasses (`ClientErrorException`,
// `ServerErrorException`) get the same per-code classification for free.

/**
 * `400 Bad Request`. The server cannot process the request because of a client error
 * (malformed syntax, invalid request framing, deceptive routing). Not retryable — repeating
 * the same malformed request will fail the same way.
 */
public open class BadRequestException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `401 Unauthorized`. The request lacks valid authentication credentials. Not retryable
 * without a credential refresh — the retry policy must coordinate with the auth layer to
 * obtain a fresh token before issuing another attempt.
 */
public open class UnauthorizedException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `403 Forbidden`. The server understood the request but refuses to authorize it. Not
 * retryable — the caller does not have permission and additional attempts will not change
 * that. Distinct from 401: a 403 indicates the credentials are valid but insufficient.
 */
public open class ForbiddenException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `404 Not Found`. The server has no current representation of the target resource. Not
 * retryable — the resource does not exist and re-asking will not summon it.
 */
public open class NotFoundException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `405 Method Not Allowed`. The request method is not supported by the target resource. Not
 * retryable — the method-resource pairing is wrong on the caller's side.
 */
public open class MethodNotAllowedException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `408 Request Timeout`. The server timed out waiting for the request. **Retryable** — the
 * server explicitly invites the client to repeat the request without modification (RFC 7231
 * §6.5.7), so the baked `retryable` flag is `true` for this code.
 */
public open class RequestTimeoutException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `409 Conflict`. The request conflicts with the current state of the target resource
 * (typical: optimistic concurrency mismatch, duplicate idempotency key). Not retryable
 * without rebuilding the request to reflect the latest state.
 */
public open class ConflictException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `410 Gone`. The target resource is no longer available at the origin server and the
 * condition is expected to be permanent. Not retryable.
 */
public open class GoneException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `413 Payload Too Large`. The server is refusing to process the request because the
 * payload exceeds its size limit. Not retryable without trimming the payload.
 */
public open class PayloadTooLargeException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `415 Unsupported Media Type`. The server refuses the request because the payload format
 * is unsupported. Not retryable without renegotiating the content-type.
 */
public open class UnsupportedMediaTypeException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `422 Unprocessable Entity`. The server understands the content type and the syntax of the
 * request is correct, but it was unable to process the contained instructions (semantic
 * validation failure). Not retryable without correcting the payload.
 */
public open class UnprocessableEntityException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `429 Too Many Requests`. The user has sent too many requests in a given amount of time
 * ("rate limiting"). **Retryable**, with the retry-policy honoring the `Retry-After` or
 * `X-RateLimit-Reset` header where present.
 */
public open class TooManyRequestsException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `500 Internal Server Error`. Generic server-side failure with no more specific code
 * applicable. **Retryable** — typically a transient condition (deployment in progress, GC
 * pause, downstream blip).
 */
public open class InternalServerErrorException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `502 Bad Gateway`. An intermediary received an invalid response from an upstream server.
 * **Retryable** — usually a transient routing or upstream-health issue.
 */
public open class BadGatewayException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `503 Service Unavailable`. The server is currently unable to handle the request due to
 * temporary overloading or maintenance. **Retryable** — the explicit "try again later"
 * signal from the server.
 */
public open class ServiceUnavailableException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * `504 Gateway Timeout`. An intermediary did not receive a timely response from an upstream
 * server. **Retryable** — typically a transient slow-path or upstream-saturation condition.
 */
public open class GatewayTimeoutException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * Fallback for 4xx statuses without a dedicated subclass (402, 406, 411, 414, 416, 417, 418,
 * 421, 423–428, 431, 451). The baked `retryable` flag mirrors
 * [RetryUtils.isRetryable][org.dexpace.sdk.core.util.RetryUtils.isRetryable] for the exact
 * status code, so these client errors are not retryable. (408 is *not* routed here — it has
 * its own [RequestTimeoutException] so its `retryable = true` is never lost to this fallback.)
 */
public open class ClientErrorException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )

/**
 * Fallback for 5xx statuses without a dedicated subclass (501, 505–511). The baked `retryable`
 * flag mirrors [RetryUtils.isRetryable][org.dexpace.sdk.core.util.RetryUtils.isRetryable] for
 * the exact status code: most 5xx codes are retryable, but **501** (Not Implemented) and
 * **505** (HTTP Version Not Supported) are not — the field agrees with the live retry policy
 * for those codes instead of contradicting it.
 */
public open class ServerErrorException
    @JvmOverloads
    constructor(
        response: Response,
        message: String? = null,
        cause: Throwable? = null,
        value: Any? = null,
    ) : HttpException(
            status = response.status,
            headers = response.headers,
            body = response.body,
            message = message,
            cause = cause,
            value = value,
        )
