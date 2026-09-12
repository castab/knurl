package knurl.presentation.routes

import knurl.domain.models.Page
import kotlinx.serialization.Serializable
import org.http4k.core.Request
import org.http4k.core.Uri
import org.http4k.core.query
import org.http4k.core.removeQuery

/**
 * Hard ceiling on rows per page, shared by every paginated list endpoint so clients see one
 * consistent limit across the API. Requests above it are silently clamped rather than rejected.
 */
const val MAX_PAGE_SIZE = 50

/** Query parameter name carrying the 1-based page number, and the only one a pagination link rewrites. */
private const val PAGE_PARAM = "page"

/**
 * RFC 8288-style navigation links for one page of results. Relative (path + query, no scheme or
 * host) because this service has no configured public base URL and is served same-origin with its
 * UI; that also keeps them consistent with the OpenAPI spec's `servers: [{ url: "/" }]`.
 *
 * [prev] and [next] are null on the first and last page respectively. [first], [self] and [last]
 * are always present - an empty result set still reports a coherent page 1 of 1.
 */
@Serializable
data class PaginationLinks(
    val first: String,
    val prev: String?,
    val self: String,
    val next: String?,
    val last: String,
)

/** Pagination envelope metadata accompanying the `data` array of a paginated list response. */
@Serializable
data class PaginationMeta(
    val totalRecords: Int,
    val currentPage: Int,
    val totalPages: Int,
    val links: PaginationLinks,
)

/**
 * Builds the envelope metadata for [page] from the incoming [request].
 *
 * Links are derived from `request.uri`, which http4k populates with the path and query string but
 * no scheme/host - exactly the relative form wanted. Every query parameter the client sent is
 * preserved and only `page` is rewritten, so sort order and filters survive navigation; `page` is
 * removed before being re-added so following a link repeatedly cannot accumulate duplicates.
 *
 * [Page.currentPage] is the *effective* page (clamped by [Page.of]), not what the client asked
 * for, so `self` always addresses the data actually returned.
 */
internal fun paginationMeta(
    request: Request,
    page: Page<*>,
): PaginationMeta =
    PaginationMeta(
        totalRecords = page.totalRecords,
        currentPage = page.currentPage,
        totalPages = page.totalPages,
        links =
            PaginationLinks(
                first = request.uri.withPage(1),
                prev = if (page.currentPage > 1) request.uri.withPage(page.currentPage - 1) else null,
                self = request.uri.withPage(page.currentPage),
                next = if (page.currentPage < page.totalPages) request.uri.withPage(page.currentPage + 1) else null,
                last = request.uri.withPage(page.totalPages),
            ),
    )

private fun Uri.withPage(page: Int): String = removeQuery(PAGE_PARAM).query(PAGE_PARAM, page.toString()).toString()

/**
 * A fully-populated [PaginationMeta] for a route's OpenAPI `returning(...)` example.
 *
 * http4k's schema generator reflects on the runtime class of example values, so a null there is a
 * generation-time crash rather than an "optional field" - hence [PaginationLinks.prev]/[next] get
 * real values here even though both are genuinely null at the range boundaries. See
 * `EXAMPLE_GALLERY_ITEM` in [GalleryRoutes.kt] for the same constraint on item examples.
 */
internal fun examplePagination(path: String): PaginationMeta =
    PaginationMeta(
        totalRecords = 100,
        currentPage = 2,
        totalPages = 10,
        links =
            PaginationLinks(
                first = "$path?page=1",
                prev = "$path?page=1",
                self = "$path?page=2",
                next = "$path?page=3",
                last = "$path?page=10",
            ),
    )
