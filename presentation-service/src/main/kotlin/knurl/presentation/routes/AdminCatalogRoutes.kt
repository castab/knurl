package knurl.presentation.routes

import knurl.domain.models.CatalogEntry
import knurl.domain.models.CatalogFilter
import knurl.domain.models.PurgeRequestResult
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.CatalogRepository
import knurl.presentation.auth.BearerToken
import knurl.presentation.s3.Presigner
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Filter
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.autoBody
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.lens.int
import org.http4k.lens.string
import org.http4k.security.BearerAuthSecurity

@Serializable
data class AdminCatalogItemResponse(
    val shortcode: String,
    val mediaType: String,
    val caption: String?,
    val permalink: String,
    val timestamp: String,
    val selected: Boolean,
    /**
     * Null for a normal, downloadable item. Non-null (currently only `"copyright"`) means
     * ingestion-service determined the underlying media can never be fetched via the Graph API -
     * e.g. Instagram permanently omits `media_url` for video/Reels content flagged with
     * copyrighted audio - so selecting this item would never actually produce a gallery post.
     */
    val notDigestibleReason: String? = null,
    /** Presigned GET URL for the cheap catalog-browse thumbnail, or null if none has been fetched yet. */
    val thumbnailUrl: String? = null,
    /**
     * When this item was deselected, or null if it is selected (or never was). Non-null means its
     * downloaded media is on a deletion clock and will be removed once the configured retention
     * period has elapsed - until then the decision is still free to reverse, because reselecting
     * clears this and costs no re-download.
     */
    val deselectedAt: String? = null,
    /**
     * When an admin requested an outright purge, or null if none is pending. Non-null means the
     * grace period above is being skipped and the media goes on the next ingestion cycle. The
     * catalog entry itself always survives either way, so the item stays listed here and can be
     * selected again (which re-downloads it fresh).
     */
    val purgeRequestedAt: String? = null,
)

@Serializable
data class AdminCatalogResponse(
    val data: List<AdminCatalogItemResponse>,
    val pagination: PaginationMeta,
)

@Serializable
data class SelectionUpdateRequest(
    val select: List<String> = emptyList(),
    val deselect: List<String> = emptyList(),
)

@Serializable
data class SelectionUpdateResponse(
    val selected: List<String>,
    val deselected: List<String>,
    val notFound: List<String>,
)

@Serializable
data class MediaPurgeRequest(
    val shortcodes: List<String> = emptyList(),
)

/**
 * Outcome for one requested shortcode.
 *
 * - `ACCEPTED` - the item had downloaded media; it has left the gallery already and its S3 objects
 *   are deleted by the next ingestion cycle.
 * - `NOT_IN_GALLERY` - the item exists in the catalog but nothing was ever downloaded for it (or it
 *   was purged previously), so there is nothing to delete. It is deselected anyway, defensively, so
 *   a stale selection cannot cause a download next cycle.
 * - `NOT_FOUND` - no catalog entry with that shortcode for this account.
 */
@Serializable
data class MediaPurgeItemResult(
    val shortcode: String,
    val status: String,
)

/**
 * `acceptedCount` rather than `deletedCount`: `presentation-service` never touches the bucket, so
 * the bytes are still there when this response is written - ingestion deletes them on its next
 * cycle. Naming the field for a deletion that has not happened yet would be a false claim, which is
 * also why the endpoint answers `202 Accepted` rather than `200 OK`. What *is* immediate is the
 * item leaving the gallery, since gallery visibility is derived from `selected`.
 */
@Serializable
data class MediaPurgeResponse(
    val requestedCount: Int,
    val acceptedCount: Int,
    val results: List<MediaPurgeItemResult>,
)

/**
 * The media types Instagram's Graph API reports, and therefore the only values the `mediaType`
 * filter accepts. Validated up front so a typo narrows nothing silently - an unrecognised value
 * would otherwise simply match no rows and look like an empty catalog.
 */
private val VALID_MEDIA_TYPES = setOf("IMAGE", "VIDEO", "CAROUSEL_ALBUM")

/**
 * Upper bound on one purge request. Bulk mutations get a cap for the same reason list endpoints do
 * (see [MAX_PAGE_SIZE]) - an unbounded batch is an unbounded statement - and a destructive one
 * deserves it more, since a runaway client should not be able to clear an account in one call.
 */
internal const val MAX_PURGE_BATCH = 100

/**
 * Builds the per-item results for a purge request, in the order the client asked for them so the
 * two lists can be zipped positionally. Anything the repository did not report back had no catalog
 * row for this account. Pure/no I/O so it's unit-testable on its own.
 */
internal fun purgeResults(
    requested: List<String>,
    result: PurgeRequestResult,
): List<MediaPurgeItemResult> =
    requested.distinct().map { shortcode ->
        val status =
            when (shortcode) {
                in result.accepted -> "ACCEPTED"
                in result.notInGallery -> "NOT_IN_GALLERY"
                else -> "NOT_FOUND"
            }
        MediaPurgeItemResult(shortcode = shortcode, status = status)
    }

/**
 * http4k's OpenAPI schema generator infers a JSON schema by reflecting on the runtime class of
 * example values; `emptyList()` returns Kotlin's internal `EmptyList` singleton, which isn't
 * `@Serializable` and crashes that reflection - so example lists must be non-empty. A `null`
 * example value for a nested field hits the same class of reflection failure (there's no runtime
 * class to reflect on), so every field here - including `notDigestibleReason` and `thumbnailUrl`,
 * both nullable in real responses - gets a real example value instead (see GalleryRoutes.kt's
 * `EXAMPLE_GALLERY_ITEM` for the same convention).
 */
private val EXAMPLE_CATALOG_ITEM =
    AdminCatalogItemResponse(
        shortcode = "Cabc123XYZ",
        mediaType = "IMAGE",
        caption = "An example caption",
        permalink = "https://www.instagram.com/p/Cabc123XYZ/",
        timestamp = "2024-01-01T00:00:00Z",
        selected = false,
        notDigestibleReason = "copyright",
        thumbnailUrl = "https://example-bucket.s3.amazonaws.com/catalog/example/thumbnail.webp",
        deselectedAt = "2024-01-02T00:00:00Z",
        purgeRequestedAt = "2024-01-03T00:00:00Z",
    )

internal fun CatalogEntry.toResponse(presigner: Presigner): AdminCatalogItemResponse =
    AdminCatalogItemResponse(
        shortcode = shortcode,
        mediaType = mediaType,
        caption = caption,
        permalink = permalink,
        timestamp = timestamp.toString(),
        selected = selected,
        notDigestibleReason = notDigestibleReason,
        thumbnailUrl = thumbnailPath?.let { presigner.presignGet(it).url },
        deselectedAt = deselectedAt?.toString(),
        purgeRequestedAt = purgeRequestedAt?.toString(),
    )

/**
 * Admin-only surface for browsing one account's full Instagram media catalog and curating which
 * items get downloaded/stored as gallery posts. Unlike the rest of presentation-service, this is a
 * narrow, deliberate exception to "zero database writes": `PATCH .../selections` flips
 * `instagram_media_catalog.selected` and `DELETE .../catalog/media` arms an immediate purge on the
 * same table, both of which `ingestion-service` acts on during its next cycle.
 *
 * Neither makes an S3 call. Deletion of media stays entirely `ingestion-service`'s job - these
 * routes only record intent in the database, which is what keeps the bucket credentials' destructive
 * surface out of the internet-facing service.
 *
 * Authorization here is per-account, not the single global [knurl.presentation.auth.BearerAuth]
 * used by gallery tracking - a single presentation-service deployment can serve many accounts, so
 * each account's own admin token (set/rotated by that account's `ingestion-service` instance,
 * looked up here via [accountRepository]) is required, never a shared secret across accounts.
 */
class AdminCatalogRoutes(
    private val catalogRepository: CatalogRepository,
    private val accountRepository: AccountRepository,
    private val presigner: Presigner,
) {
    // Authorization for this scheme remains dynamic inside [authorize], because each account has
    // its own token. The no-op filter exists only to describe that bearer scheme to OpenAPI.
    private val adminBearerSecurity = BearerAuthSecurity(Filter { next -> next }, "accountAdminBearer")
    private val accountIdPath = Path.of("accountId")
    private val selectedQuery = Query.string().optional("selected")
    private val includeNotDigestibleQuery = Query.string().optional("includeNotDigestible")
    private val mediaTypeQuery = Query.string().multi.optional("mediaType")
    private val limitQuery = Query.int().defaulted("limit", MAX_PAGE_SIZE)
    private val pageQuery = Query.int().defaulted("page", 1)
    private val catalogResponseLens = autoBody<AdminCatalogResponse>().toLens()
    private val errorResponseLens = autoBody<ErrorResponse>().toLens()
    private val selectionUpdateRequestLens = autoBody<SelectionUpdateRequest>().toLens()
    private val selectionUpdateResponseLens = autoBody<SelectionUpdateResponse>().toLens()
    private val mediaPurgeRequestLens = autoBody<MediaPurgeRequest>().toLens()
    private val mediaPurgeResponseLens = autoBody<MediaPurgeResponse>().toLens()

    /**
     * `404` for an unknown account is safe to reveal (account ids are public, non-secret), and
     * distinguishes "no such account" from "wrong token for a real account" (`401`).
     */
    private fun authorize(
        accountId: String,
        request: Request,
        onAuthorized: () -> Response,
    ): Response {
        val expectedToken =
            accountRepository.findAdminToken(accountId)
                ?: return Response(Status.NOT_FOUND).with(errorResponseLens of ErrorResponse("unknown account"))

        return if (BearerToken.matches(BearerToken.extract(request), expectedToken)) {
            onAuthorized()
        } else {
            Response(Status.UNAUTHORIZED).with(errorResponseLens of ErrorResponse("unauthorized"))
        }
    }

    /**
     * Filtering happens in SQL, before paging, so a filter describes the whole catalog rather than
     * whichever page happened to load - the reason these are query parameters and not something the
     * client applies to the rows it received.
     *
     * Every value is validated up front and an unrecognised one is a 400, matching how `selected`
     * has always behaved. Throwing [IllegalArgumentException] rather than returning an error type
     * mirrors `SortOrder.fromQueryParam`'s contract in [GalleryRoutes]; the caller turns it into
     * the response body.
     */
    private fun catalogFilter(request: Request): CatalogFilter {
        val selected =
            when (selectedQuery(request)) {
                null -> null
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("invalid selected value")
            }
        val includeNotDigestible =
            when (includeNotDigestibleQuery(request)) {
                null, "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("invalid includeNotDigestible value")
            }
        val mediaTypes = mediaTypeQuery(request).orEmpty().toSet()
        val unknown = mediaTypes - VALID_MEDIA_TYPES
        require(unknown.isEmpty()) { "invalid mediaType value: ${unknown.sorted().joinToString()}" }

        return CatalogFilter(selected = selected, mediaTypes = mediaTypes, includeNotDigestible = includeNotDigestible)
    }

    private fun listCatalog(): ContractRoute =
        "/api/v1/admin/accounts" / accountIdPath / "catalog" meta {
            summary = "List a page of catalog entries for an account, optionally filtered"
            security = adminBearerSecurity
            queries += selectedQuery
            queries += includeNotDigestibleQuery
            queries += mediaTypeQuery
            queries += limitQuery
            queries += pageQuery
            returning(
                Status.OK,
                catalogResponseLens to
                    AdminCatalogResponse(
                        listOf(EXAMPLE_CATALOG_ITEM),
                        examplePagination("/api/v1/admin/accounts/{accountId}/catalog"),
                    ),
            )
        } bindContract Method.GET to { accountId, _ ->
            { request ->
                authorize(accountId, request) {
                    runCatching { catalogFilter(request) }.fold(
                        onSuccess = { filter ->
                            val pageSize = limitQuery(request).coerceIn(1, MAX_PAGE_SIZE)
                            val page = catalogRepository.findPage(accountId, filter, pageSize, pageQuery(request))
                            Response(Status.OK).with(
                                catalogResponseLens of
                                    AdminCatalogResponse(
                                        data = page.items.map { it.toResponse(presigner) },
                                        pagination = paginationMeta(request, page),
                                    ),
                            )
                        },
                        onFailure = {
                            Response(Status.BAD_REQUEST)
                                .with(errorResponseLens of ErrorResponse(it.message ?: "invalid filter"))
                        },
                    )
                }
            }
        }

    private fun updateSelections(): ContractRoute =
        "/api/v1/admin/accounts" / accountIdPath / "catalog" / "selections" meta {
            summary = "Bulk-select or deselect catalog entries by shortcode"
            security = adminBearerSecurity
            receiving(
                selectionUpdateRequestLens to
                    SelectionUpdateRequest(select = listOf("Cabc123XYZ"), deselect = listOf("Cdef456UVW")),
            )
            returning(
                Status.OK,
                selectionUpdateResponseLens to
                    SelectionUpdateResponse(
                        selected = listOf("Cabc123XYZ"),
                        deselected = listOf("Cdef456UVW"),
                        notFound = listOf("Cghi789RST"),
                    ),
            )
        } bindContract Method.PATCH to { accountId, _, _ ->
            { request ->
                authorize(accountId, request) {
                    val body = selectionUpdateRequestLens(request)
                    val selectSet = body.select.toSet()
                    val deselectSet = body.deselect.toSet()
                    val overlap = selectSet intersect deselectSet

                    if (overlap.isNotEmpty()) {
                        Response(Status.BAD_REQUEST)
                            .with(
                                errorResponseLens of
                                    ErrorResponse(
                                        "shortcodes cannot be both selected and deselected: ${overlap.sorted()}",
                                    ),
                            )
                    } else {
                        val result = catalogRepository.updateSelection(accountId, selectSet, deselectSet)
                        val requested = selectSet + deselectSet
                        val notFound = requested - result.selectedShortcodes - result.deselectedShortcodes
                        Response(Status.OK).with(
                            selectionUpdateResponseLens of
                                SelectionUpdateResponse(
                                    selected = result.selectedShortcodes.toList(),
                                    deselected = result.deselectedShortcodes.toList(),
                                    notFound = notFound.toList(),
                                ),
                        )
                    }
                }
            }
        }

    /**
     * Outright delete: skips the retention grace period for the named items.
     *
     * Pathed under `catalog`, not `gallery`, because what it deletes is an item's *downloaded
     * media* - the catalog entry and its browse thumbnail survive, so the item stays listed here and
     * can be selected again later. (Deleting the catalog row would achieve nothing anyway: it is an
     * unconditional mirror of the Instagram feed, so the next sync re-inserts it.)
     *
     * Like `PATCH .../selections`, this writes only `instagram_media_catalog` columns and issues no
     * S3 call - `ingestion-service` still owns every deletion. The item leaves the gallery
     * immediately because gallery visibility derives from `selected`; the bytes go on the next
     * ingestion cycle. Hence `202`, and hence `acceptedCount` rather than `deletedCount`.
     *
     * The request body travels on a DELETE, which is legal and which http4k handles. A client or
     * proxy that strips DELETE bodies would need this re-exposed as a POST.
     */
    private fun purgeMedia(): ContractRoute =
        "/api/v1/admin/accounts" / accountIdPath / "catalog" / "media" meta {
            summary = "Delete the downloaded media of catalog entries outright, skipping the retention grace period"
            security = adminBearerSecurity
            receiving(mediaPurgeRequestLens to MediaPurgeRequest(shortcodes = listOf("Cabc123XYZ", "Cdef456UVW")))
            returning(
                Status.ACCEPTED,
                mediaPurgeResponseLens to
                    MediaPurgeResponse(
                        requestedCount = 2,
                        acceptedCount = 1,
                        results =
                            listOf(
                                MediaPurgeItemResult("Cabc123XYZ", "ACCEPTED"),
                                MediaPurgeItemResult("Cdef456UVW", "NOT_IN_GALLERY"),
                            ),
                    ),
            )
        } bindContract Method.DELETE to { accountId, _, _ ->
            { request ->
                authorize(accountId, request) {
                    val requested = mediaPurgeRequestLens(request).shortcodes
                    when {
                        requested.isEmpty() -> {
                            Response(Status.BAD_REQUEST)
                                .with(errorResponseLens of ErrorResponse("shortcodes must not be empty"))
                        }

                        requested.size > MAX_PURGE_BATCH -> {
                            Response(Status.BAD_REQUEST).with(
                                errorResponseLens of
                                    ErrorResponse("at most $MAX_PURGE_BATCH shortcodes per request, got ${requested.size}"),
                            )
                        }

                        else -> {
                            val result = catalogRepository.requestPurge(accountId, requested.toSet())
                            val results = purgeResults(requested, result)
                            Response(Status.ACCEPTED).with(
                                mediaPurgeResponseLens of
                                    MediaPurgeResponse(
                                        requestedCount = results.size,
                                        acceptedCount = result.accepted.size,
                                        results = results,
                                    ),
                            )
                        }
                    }
                }
            }
        }

    fun routes(): List<ContractRoute> = listOf(listCatalog(), updateSelections(), purgeMedia())
}
