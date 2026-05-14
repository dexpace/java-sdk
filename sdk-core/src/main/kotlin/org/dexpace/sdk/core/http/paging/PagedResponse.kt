package org.dexpace.sdk.core.http.paging

import org.dexpace.sdk.core.http.common.Headers
import org.dexpace.sdk.core.http.request.Request
import org.dexpace.sdk.core.http.response.Response
import java.io.Closeable
import java.io.IOException

/**
 * A single page of results from a paginated REST API.
 *
 * Wraps the underlying [Response] and exposes the deserialized [value] list plus paging
 * metadata. Composition (rather than inheritance) is used because [Response] is a
 * `data class` with a private constructor, which cannot be subclassed cleanly. The
 * underlying response is exposed via [response] and the common accessors ([statusCode],
 * [headers], [request]) for direct use.
 *
 * Paging metadata covers two complementary patterns:
 *
 * - **HATEOAS links** ([nextLink], [previousLink], [firstLink], [lastLink]) — RFC 5988
 *   `Link:` header style; each is a full URL the client can `GET` directly.
 * - **Continuation token** ([continuationToken]) — opaque cursor that the client
 *   re-submits as a query parameter to fetch the next page.
 *
 * Servers typically use one or the other; [PagedIterable] gives precedence to [nextLink]
 * when both are present.
 *
 * The response body is owned by this `PagedResponse`. Call [close] to release it once the
 * page is no longer needed, or use Kotlin's `use {}` / Java try-with-resources.
 *
 * @param T Element type carried in [value].
 * @property response Underlying HTTP response the page was parsed from.
 * @property value Deserialized items on this page. May be empty even when more pages
 *   exist (servers sometimes return empty pages with a non-null [nextLink]).
 * @property continuationToken Opaque cursor for the next page, or `null` if absent.
 * @property nextLink Full URL of the next page, or `null` if this is the last page.
 * @property previousLink Full URL of the previous page, or `null` if this is the first.
 * @property firstLink Full URL of the first page, or `null` if unsupported.
 * @property lastLink Full URL of the last page, or `null` if unsupported.
 */
public class PagedResponse<T> @JvmOverloads constructor(
    public val response: Response,
    public val value: List<T>,
    public val continuationToken: String? = null,
    public val nextLink: String? = null,
    public val previousLink: String? = null,
    public val firstLink: String? = null,
    public val lastLink: String? = null,
) : Closeable {
    /** Status code of the underlying [response]. */
    public val statusCode: Int get() = response.status.code

    /** Headers of the underlying [response]. */
    public val headers: Headers get() = response.headers

    /** Request that produced the underlying [response]. */
    public val request: Request get() = response.request

    /**
     * Closes the underlying [response] and its body.
     *
     * @throws IOException If the underlying response close fails.
     */
    @Throws(IOException::class)
    override fun close() {
        response.close()
    }
}
