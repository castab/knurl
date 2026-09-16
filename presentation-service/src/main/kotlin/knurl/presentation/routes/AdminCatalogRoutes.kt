package knurl.presentation.routes

import knurl.domain.models.CatalogEntry
import knurl.domain.models.CatalogFilter
import knurl.domain.models.PurgeRequestResult
import knurl.domain.repositories.CatalogRepository
import knurl.presentation.s3.Presigner
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
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
import kotlin.uuid.Uuid

@Serializable
data class AdminCatalogItemResponse(
    val shortcode: String,
    val mediaType: String,
    val caption: String?,
    val permalink: String,
    val timestamp: String,
    /** Every gallery this item currently belongs to, by id. Empty means it is in none. */
    val galleryIds: List<String>,
    /**
     * Null for a normal, downloadable item. Non-null (currently only `"copyright"`) means
     * ingestion-service determined the underlying media can never be fetched via the Graph API -
     * e.g. Instagram permanently omits `media_url` for video/Reels content flagged with
     * copyrighted audio - so adding this item to a gallery would never actually produce a post.
     */
    val notDigestibleReason: String? = null,
    /** Presigned GET URL for the cheap catalog-browse thumbnail, or null if none has been fetched yet. */
    val thumbnailUrl: String? = null,
    /**
     * When this item left its last gallery, or null if it is still in one (or never was in any).
     * Non-null means its downloaded media is on a deletion clock and will be removed once the
     * configured retention period has elapsed - until then the decision is still free to reverse,
     * because adding it back to any gallery clears this and costs no re-download.
     */
    val deselectedAt: String? = null,
    /**
     * When an admin requested an outright purge, or null if none is pending. Non-null means the
     * grace period above is being skipped and the media goes on the next ingestion cycle. The
     * catalog entry itself always survives either way, so the item stays listed here and can be
     * added to a gallery again (which re-downloads it fresh).
     */
    val purgeRequestedAt: String? = null,
)

@Serializable
data class AdminCatalogResponse(
    val data: List<AdminCatalogItemResponse>,
    val pagination: PaginationMeta,
)

@Serializable
data class MediaPurgeRequest(
    val shortcodes: List<String> = emptyList(),
)

/**
 * Outcome for one requested shortcode.
 *
 * - `ACCEPTED` - the item had downloaded media; it has already left every gallery holding it and
 *   its S3 objects are deleted by the next ingestion cycle.
 * - `NOT_DOWNLOADED` - the item exists in the catalog but nothing was ever downloaded for it (or it
 *   was purged previously), so there is nothing to delete. It is removed from every gallery anyway,
 *   defensively, so a stale membership cannot cause a download next cycle.
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
 * item leaving every gallery that held it, since a gallery shows exactly its members.
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
                in result.notDownloaded -> "NOT_DOWNLOADED"
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
        galleryIds = listOf("01912f4e-1a2b-7c3d-8e4f-5a6b7c8d9e0f"),
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
        galleryIds = galleryIds.map { it.toString() },
        notDigestibleReason = notDigestibleReason,
        thumbnailUrl = thumbnailPath?.let { presigner.presignGet(it).url },
        deselectedAt = deselectedAt?.toString(),
        purgeRequestedAt = purgeRequestedAt?.toString(),
    )

/**
 * Admin-only surface for browsing one account's full Instagram media catalog and curating which
 * items exist to curate into galleries. Membership itself is managed per gallery by
 * [AdminGalleryRoutes]; what lives here is the browse surface and the outright purge.
 *
 * Unlike the rest of presentation-service, this is a deliberate exception to "zero database
 * writes": `DELETE .../catalog/media` arms an immediate purge, which `ingestion-service` acts on
 * during its next cycle. It makes no S3 call - deletion of media stays entirely
 * `ingestion-service`'s job, and these routes only record intent in the database, which is what
 * keeps the bucket credentials' destructive surface out of the internet-facing service.
 *
 * Authorization is per-account via [authorizeAccount] and `AccountRepository.verifyAdminToken` - a
 * single presentation-service deployment serves many accounts, so each account's own admin token
 * is required and one account's token must never work against another's content. Gallery reads
 * ([GalleryRoutes]) go through the same [authorizeAccount], scoped instead to each account's
 * lower-privilege read token via `AccountRepository.verifyReadToken`.
 */
class AdminCatalogRoutes(
    private val catalogRepository: CatalogRepository,
    private val verifyAdminToken: VerifyAccountToken,
    private val presigner: Presigner,
) {
    private val accountIdPath = Path.of("accountId")
    private val galleryIdQuery = Query.string().optional("galleryId")
    private val inAnyGalleryQuery = Query.string().optional("inAnyGallery")
    private val includeNotDigestibleQuery = Query.string().optional("includeNotDigestible")
    private val mediaTypeQuery = Query.string().multi.optional("mediaType")
    private val limitQuery = Query.int().defaulted("limit", MAX_PAGE_SIZE)
    private val pageQuery = Query.int().defaulted("page", 1)
    private val catalogResponseLens = autoBody<AdminCatalogResponse>().toLens()
    private val errorResponseLens = autoBody<ErrorResponse>().toLens()
    private val mediaPurgeRequestLens = autoBody<MediaPurgeRequest>().toLens()
    private val mediaPurgeResponseLens = autoBody<MediaPurgeResponse>().toLens()

    /**
     * Filtering happens in SQL, before paging, so a filter describes the whole catalog rather than
     * whichever page happened to load - the reason these are query parameters and not something the
     * client applies to the rows it received.
     *
     * Every value is validated up front and an unrecognised one is a 400. A `galleryId` naming a
     * gallery that is not this account's is treated as no match rather than an error, so the filter
     * cannot be used to probe for another tenant's gallery ids. Throwing [IllegalArgumentException] rather than returning an error type
     * mirrors `SortOrder.fromQueryParam`'s contract in [GalleryRoutes]; the caller turns it into
     * the response body.
     */
    private fun catalogFilter(request: Request): CatalogFilter {
        val galleryId =
            galleryIdQuery(request)?.let { raw ->
                Uuid.parseOrNull(raw) ?: throw IllegalArgumentException("invalid galleryId value")
            }
        val inAnyGallery =
            when (inAnyGalleryQuery(request)) {
                null -> null
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("invalid inAnyGallery value")
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

        return CatalogFilter(
            galleryId = galleryId,
            inAnyGallery = inAnyGallery,
            mediaTypes = mediaTypes,
            includeNotDigestible = includeNotDigestible,
        )
    }

    private fun listCatalog(): ContractRoute =
        "/api/v1/admin/accounts" / accountIdPath / "catalog" meta {
            summary = "List a page of catalog entries for an account, optionally filtered"
            security = adminBearerSecurity
            queries += galleryIdQuery
            queries += inAnyGalleryQuery
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
                authorizeAccount(accountId, request, verifyAdminToken) {
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

    /**
     * Outright delete: skips the retention grace period for the named items.
     *
     * Pathed under `catalog`, not `gallery`, because what it deletes is an item's *downloaded
     * media* - the catalog entry and its browse thumbnail survive, so the item stays listed here and
     * can be added to a gallery again later. (Deleting the catalog row would achieve nothing anyway: it is an
     * unconditional mirror of the Instagram feed, so the next sync re-inserts it.)
     *
     * Like `PATCH .../selections`, this writes only `instagram_media_catalog` columns and issues no
     * S3 call - `ingestion-service` still owns every deletion. The item leaves the gallery
     * immediately because a gallery shows exactly its members; the bytes go on the next
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
                                MediaPurgeItemResult("Cdef456UVW", "NOT_DOWNLOADED"),
                            ),
                    ),
            )
        } bindContract Method.DELETE to { accountId, _, _ ->
            { request ->
                authorizeAccount(accountId, request, verifyAdminToken) {
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

    fun routes(): List<ContractRoute> = listOf(listCatalog(), purgeMedia())
}
