package org.dexpace.sdk.core.http.response.exception

import org.dexpace.sdk.core.http.response.Response

/**
 * Maps a non-2xx [Response] to the matching [HttpException] subclass.
 *
 * Status-code dispatch:
 *
 * | Range / code | Subclass |
 * |---|---|
 * | 400 | [BadRequestException] |
 * | 401 | [UnauthorizedException] |
 * | 403 | [ForbiddenException] |
 * | 404 | [NotFoundException] |
 * | 405 | [MethodNotAllowedException] |
 * | 409 | [ConflictException] |
 * | 410 | [GoneException] |
 * | 413 | [PayloadTooLargeException] |
 * | 415 | [UnsupportedMediaTypeException] |
 * | 422 | [UnprocessableEntityException] |
 * | 429 | [TooManyRequestsException] |
 * | 500 | [InternalServerErrorException] |
 * | 502 | [BadGatewayException] |
 * | 503 | [ServiceUnavailableException] |
 * | 504 | [GatewayTimeoutException] |
 * | other 4xx | [ClientErrorException] |
 * | other 5xx | [ServerErrorException] |
 *
 * Passing a 1xx / 2xx / 3xx response is a caller bug — those status ranges describe
 * successful, informational, or redirection outcomes and should not be funneled through
 * the exception path. The factory throws [IllegalArgumentException] in that case rather
 * than silently producing a "successful exception".
 */
public object HttpExceptionFactory {
    // Status-code constants. Spelled out as named consts per the project's MagicNumber
    // discipline (`config/detekt.yml` style.MagicNumber).
    private const val SC_BAD_REQUEST = 400
    private const val SC_UNAUTHORIZED = 401
    private const val SC_FORBIDDEN = 403
    private const val SC_NOT_FOUND = 404
    private const val SC_METHOD_NOT_ALLOWED = 405
    private const val SC_CONFLICT = 409
    private const val SC_GONE = 410
    private const val SC_PAYLOAD_TOO_LARGE = 413
    private const val SC_UNSUPPORTED_MEDIA_TYPE = 415
    private const val SC_UNPROCESSABLE_ENTITY = 422
    private const val SC_TOO_MANY_REQUESTS = 429
    private const val SC_INTERNAL_SERVER_ERROR = 500
    private const val SC_BAD_GATEWAY = 502
    private const val SC_SERVICE_UNAVAILABLE = 503
    private const val SC_GATEWAY_TIMEOUT = 504

    private const val SC_CLIENT_ERROR_MIN = 400
    private const val SC_CLIENT_ERROR_MAX = 499
    private const val SC_SERVER_ERROR_MIN = 500
    private const val SC_SERVER_ERROR_MAX = 599

    /**
     * Returns the [HttpException] subclass that corresponds to [response]'s status code.
     *
     * @throws IllegalArgumentException if `response.status.code` is not in the 400..599 range —
     *   1xx/2xx/3xx outcomes are not exceptions and must not be funneled through this factory.
     */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    public fun fromResponse(response: Response): HttpException {
        return when (response.status.code) {
            SC_BAD_REQUEST -> BadRequestException(response)
            SC_UNAUTHORIZED -> UnauthorizedException(response)
            SC_FORBIDDEN -> ForbiddenException(response)
            SC_NOT_FOUND -> NotFoundException(response)
            SC_METHOD_NOT_ALLOWED -> MethodNotAllowedException(response)
            SC_CONFLICT -> ConflictException(response)
            SC_GONE -> GoneException(response)
            SC_PAYLOAD_TOO_LARGE -> PayloadTooLargeException(response)
            SC_UNSUPPORTED_MEDIA_TYPE -> UnsupportedMediaTypeException(response)
            SC_UNPROCESSABLE_ENTITY -> UnprocessableEntityException(response)
            SC_TOO_MANY_REQUESTS -> TooManyRequestsException(response)
            SC_INTERNAL_SERVER_ERROR -> InternalServerErrorException(response)
            SC_BAD_GATEWAY -> BadGatewayException(response)
            SC_SERVICE_UNAVAILABLE -> ServiceUnavailableException(response)
            SC_GATEWAY_TIMEOUT -> GatewayTimeoutException(response)
            in SC_CLIENT_ERROR_MIN..SC_CLIENT_ERROR_MAX -> ClientErrorException(response)
            in SC_SERVER_ERROR_MIN..SC_SERVER_ERROR_MAX -> ServerErrorException(response)
            else ->
                throw IllegalArgumentException(
                    "HttpExceptionFactory.fromResponse called with non-error status ${response.status.code}; " +
                        "use this factory only for 4xx / 5xx responses.",
                )
        }
    }
}
