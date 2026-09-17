package knurl.presentation.routes

import knurl.domain.models.Gallery
import knurl.domain.models.GalleryPost
import knurl.domain.models.PostMediaItem
import knurl.domain.models.SortOrder
import knurl.domain.repositories.GalleryRepository
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
data class GalleryMediaItemResponse(
    val mediaType: String,
    val smallUrl: String,
    val smallFileSizeBytes: Long,
    val smallWidth: Int,
    val smallHeight: Int,
    val largeUrl: String,
    val largeFileSizeBytes: Long,
    val largeWidth: Int,
    val largeHeight: Int,
    val videoUrl: String?,
    val videoFileSizeBytes: Long?,
    val videoWidth: Int?,
    val videoHeight: Int?,
    val mediaUrlExpiresAt: String,
)

@Serializable
data class GalleryItemResponse(
    val id: String,
    val mediaType: String,
    val caption: String?,
    val permalink: String,
    val timestamp: String,
    val mediaItems: List<GalleryMediaItemResponse>,
    val viewCount: Int,
    val clickCount: Int,
)

/** A gallery's identity and metadata, without its content. */
@Serializable
data class GallerySummaryResponse(
    val id: String,
    val name: String,
    /** How many items an admin has curated into this gallery. */
    val itemCount: Int,
    /**
     * How many of those actually have downloaded media and therefore appear in the gallery. Lower
     * than [itemCount] while ingestion is still fetching a recently-added item.
     */
    val publishedCount: Int,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class GalleryListResponse(
    val data: List<GallerySummaryResponse>,
    val pagination: PaginationMeta,
)

@Serializable
data class GalleryResponse(
    /** Which gallery this page came from - echoed so a client following a link knows what it got. */
    val gallery: GallerySummaryResponse,
    val data: List<GalleryItemResponse>,
    val pagination: PaginationMeta,
)

@Serializable
data class TrackRequest(
    val id: String,
    val event: String,
)

@Serializable
data class TrackResponse(
    val status: String,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

private val VALID_EVENTS = setOf("view", "click")

/**
 * http4k's OpenAPI schema generator infers a JSON schema by reflecting on the runtime class of
 * example values; `emptyList()` returns Kotlin's internal `EmptyList` singleton, which isn't
 * `@Serializable` and crashes that reflection - so example lists must be non-empty. A `null`
 * example value for a nested field hits the same class of reflection failure (there's no runtime
 * class to reflect on), so every field here - including `videoUrl`, nullable in real responses -
 * gets a real example value instead.
 */
private val EXAMPLE_GALLERY_ITEM =
    GalleryItemResponse(
        id = "01234567-89ab-cdef-0123-456789abcdef",
        mediaType = "IMAGE",
        caption = "An example caption",
        permalink = "https://www.instagram.com/p/Cabc123XYZ/",
        timestamp = "2024-01-01T00:00:00Z",
        mediaItems =
            listOf(
                GalleryMediaItemResponse(
                    mediaType = "IMAGE",
                    smallUrl = "https://example-bucket.s3.amazonaws.com/posts/example/0/small.webp",
                    smallFileSizeBytes = 18_432,
                    smallWidth = 400,
                    smallHeight = 400,
                    largeUrl = "https://example-bucket.s3.amazonaws.com/posts/example/0/large.webp",
                    largeFileSizeBytes = 84_736,
                    largeWidth = 800,
                    largeHeight = 600,
                    videoUrl = "https://example-bucket.s3.amazonaws.com/posts/example/0/original",
                    videoFileSizeBytes = 1_048_576,
                    videoWidth = 1_920,
                    videoHeight = 1_080,
                    mediaUrlExpiresAt = "2024-01-01T06:00:00Z",
                ),
            ),
        viewCount = 0,
        clickCount = 0,
    )

private fun PostMediaItem.toResponse(presigner: Presigner): GalleryMediaItemResponse {
    val small = presigner.presignGet(smallPath)
    return GalleryMediaItemResponse(
        mediaType = mediaType,
        smallUrl = small.url,
        smallFileSizeBytes = smallFileSizeBytes,
        smallWidth = smallWidth,
        smallHeight = smallHeight,
        largeUrl = presigner.presignGet(largePath).url,
        largeFileSizeBytes = largeFileSizeBytes,
        largeWidth = largeWidth,
        largeHeight = largeHeight,
        videoUrl = videoPath?.let { presigner.presignGet(it).url },
        videoFileSizeBytes = videoFileSizeBytes,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        mediaUrlExpiresAt = small.expiresAt.toString(),
    )
}

/** The counters come from the membership row, so they are this gallery's, not the post's overall. */
internal fun GalleryPost.toResponse(presigner: Presigner): GalleryItemResponse =
    GalleryItemResponse(
        id = post.id.toString(),
        mediaType = post.mediaType,
        caption = post.caption,
        permalink = post.permalink,
        timestamp = post.timestamp.toString(),
        mediaItems = post.mediaItems.sortedBy { it.position }.map { it.toResponse(presigner) },
        viewCount = viewCount,
        clickCount = clickCount,
    )

internal fun Gallery.toResponse(): GallerySummaryResponse =
    GallerySummaryResponse(
        id = id.toString(),
        name = name,
        itemCount = itemCount,
        publishedCount = publishedCount,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private val EXAMPLE_GALLERY =
    GallerySummaryResponse(
        id = "01912f4e-1a2b-7c3d-8e4f-5a6b7c8d9e0f",
        name = "Travel",
        itemCount = 24,
        publishedCount = 22,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-02T00:00:00Z",
    )

/**
 * Bearer-protected, read-only gallery surface - one presentation-service deployment serves every
 * ingested account, so `accountId` (public, non-secret Graph API business account id) is a path
 * segment rather than something configured once at startup.
 *
 * Authorization is per-account via [authorizeAccount] and [verifyReadToken] (normally
 * `AccountRepository::verifyReadToken`) - each account has its own read token (distinct from its
 * admin token), so a token that leaks from one account's browser client can only ever read that
 * one account's galleries, not every account this deployment serves.
 *
 * An account has any number of named galleries and no default one, so content is always addressed
 * by gallery id. That id is immutable: renaming a gallery never breaks a link already pointing at
 * it, which is the whole reason the name is not the identity.
 *
 * `sort` is validated manually (rather than via a typed lens) so an invalid value produces a clear
 * 400 instead of a lens-failure default. Results use the shared `{ data, pagination }` envelope
 * (see [Pagination.kt]); `limit` and `page` are both silently clamped rather than rejected - `limit`
 * into `1..MAX_PAGE_SIZE`, `page` into `1..totalPages` by [knurl.domain.models.Page.of] - so a
 * client walking off either end still gets a usable page and coherent links.
 */
class GalleryRoutes(
    private val galleryRepository: GalleryRepository,
    private val presigner: Presigner,
    private val verifyReadToken: VerifyAccountToken,
) {
    private val accountIdPath = Path.of("accountId")
    private val galleryIdPath = Path.of("galleryId")
    private val namePath = Path.of("name")
    private val sortQuery = Query.string().defaulted("sort", "recent")
    private val limitQuery = Query.int().defaulted("limit", 12)
    private val pageQuery = Query.int().defaulted("page", 1)
    private val galleryListResponseLens = autoBody<GalleryListResponse>().toLens()
    private val galleryResponseLens = autoBody<GalleryResponse>().toLens()
    private val errorResponseLens = autoBody<ErrorResponse>().toLens()
    private val trackRequestLens = autoBody<TrackRequest>().toLens()
    private val trackResponseLens = autoBody<TrackResponse>().toLens()

    /**
     * Resolves a `{galleryId}` path segment to one of this account's galleries, or to the response
     * that should be returned instead.
     *
     * A malformed uuid is a 400 rather than a 404 - it is a bad request, not a missing thing, and
     * binding the raw string straight to a uuid column would surface as a driver exception and a
     * 500. A well-formed id belonging to another account is a 404, the same as one that does not
     * exist, so this never confirms the existence of another tenant's gallery.
     */
    private fun withGallery(
        accountId: String,
        rawGalleryId: String,
        onFound: (Gallery) -> Response,
    ): Response {
        val galleryId =
            Uuid.parseOrNull(rawGalleryId)
                ?: return Response(Status.BAD_REQUEST).with(errorResponseLens of ErrorResponse("invalid gallery id"))
        val gallery =
            galleryRepository.find(accountId, galleryId)
                ?: return Response(Status.NOT_FOUND).with(errorResponseLens of ErrorResponse("gallery not found"))
        return onFound(gallery)
    }

    /**
     * Resolves a `{name}` path segment to one of this account's galleries by (case- and
     * whitespace-insensitive) name.
     *
     * Unlike [withGallery] there is no 400 case: every string is a name that could plausibly exist,
     * so a miss is always "not found", never "malformed request".
     */
    private fun withGalleryByName(
        accountId: String,
        name: String,
        onFound: (Gallery) -> Response,
    ): Response {
        val gallery =
            galleryRepository.findByName(accountId, name)
                ?: return Response(Status.NOT_FOUND).with(errorResponseLens of ErrorResponse("gallery not found"))
        return onFound(gallery)
    }

    /** Shared by the uuid- and name-resolved content routes once each has a [Gallery] in hand. */
    private fun galleryContentResponse(
        request: Request,
        gallery: Gallery,
    ): Response {
        val sortResult = runCatching { SortOrder.fromQueryParam(sortQuery(request)) }
        return sortResult.fold(
            onSuccess = { sort ->
                val pageSize = limitQuery(request).coerceIn(1, MAX_PAGE_SIZE)
                val page = galleryRepository.findContentPage(gallery.id, sort, pageSize, pageQuery(request))
                val body =
                    GalleryResponse(
                        gallery = gallery.toResponse(),
                        data = page.items.map { it.toResponse(presigner) },
                        pagination = paginationMeta(request, page),
                    )
                Response(Status.OK).with(galleryResponseLens of body)
            },
            onFailure = {
                Response(Status.BAD_REQUEST).with(errorResponseLens of ErrorResponse("invalid sort value"))
            },
        )
    }

    private fun listGalleries(): ContractRoute =
        "/api/v1/accounts" / accountIdPath / "galleries" meta {
            summary = "List an account's galleries"
            security = readBearerSecurity
            queries += limitQuery
            queries += pageQuery
            returning(
                Status.OK,
                galleryListResponseLens to
                    GalleryListResponse(
                        listOf(EXAMPLE_GALLERY),
                        examplePagination("/api/v1/accounts/{accountId}/galleries"),
                    ),
            )
        } bindContract Method.GET to { accountId, _ ->
            { request ->
                authorizeAccount(accountId, request, verifyReadToken) {
                    val pageSize = limitQuery(request).coerceIn(1, MAX_PAGE_SIZE)
                    val page = galleryRepository.findPage(accountId, pageSize, pageQuery(request))
                    Response(Status.OK).with(
                        galleryListResponseLens of
                            GalleryListResponse(
                                data = page.items.map { it.toResponse() },
                                pagination = paginationMeta(request, page),
                            ),
                    )
                }
            }
        }

    private fun listGalleryContent(): ContractRoute =
        "/api/v1/accounts" / accountIdPath / "galleries" / galleryIdPath meta {
            summary = "List a page of one gallery's posts, sorted by recency or view count"
            security = readBearerSecurity
            queries += sortQuery
            queries += limitQuery
            queries += pageQuery
            returning(
                Status.OK,
                galleryResponseLens to
                    GalleryResponse(
                        EXAMPLE_GALLERY,
                        listOf(EXAMPLE_GALLERY_ITEM),
                        examplePagination("/api/v1/accounts/{accountId}/galleries/{galleryId}"),
                    ),
            )
        } bindContract Method.GET to { accountId, _, rawGalleryId ->
            { request ->
                authorizeAccount(accountId, request, verifyReadToken) {
                    withGallery(accountId, rawGalleryId) { gallery -> galleryContentResponse(request, gallery) }
                }
            }
        }

    /** Same content page as [listGalleryContent], resolved by name instead of id. */
    private fun getGalleryByName(): ContractRoute =
        "/api/v1/accounts" / accountIdPath / "galleries" / "by-name" / namePath meta {
            summary = "Look up a page of one gallery's posts by name, sorted by recency or view count"
            security = readBearerSecurity
            queries += sortQuery
            queries += limitQuery
            queries += pageQuery
            returning(
                Status.OK,
                galleryResponseLens to
                    GalleryResponse(
                        EXAMPLE_GALLERY,
                        listOf(EXAMPLE_GALLERY_ITEM),
                        examplePagination("/api/v1/accounts/{accountId}/galleries/by-name/{name}"),
                    ),
            )
        } bindContract Method.GET to { accountId, _, _, rawName ->
            { request ->
                authorizeAccount(accountId, request, verifyReadToken) {
                    withGalleryByName(accountId, rawName) { gallery -> galleryContentResponse(request, gallery) }
                }
            }
        }

    /**
     * Counters are per gallery, so the same post tracked from two galleries increments two
     * independent rows - which is the point: a photo's popularity in one gallery says nothing about
     * how it performs in another.
     */
    private fun trackEvent(): ContractRoute =
        "/api/v1/accounts" / accountIdPath / "galleries" / galleryIdPath / "track" meta {
            summary = "Record a view or click analytics event for a post within a gallery"
            security = readBearerSecurity
            receiving(trackRequestLens to TrackRequest("01234567-89ab-cdef-0123-456789abcdef", "view"))
            returning(Status.OK, trackResponseLens to TrackResponse("ok"))
        } bindContract Method.POST to { accountId, _, rawGalleryId, _ ->
            { request ->
                authorizeAccount(accountId, request, verifyReadToken) {
                    withGallery(accountId, rawGalleryId) { gallery ->
                        val trackRequest = trackRequestLens(request)
                        val postId = Uuid.parseOrNull(trackRequest.id)

                        when {
                            postId == null -> {
                                Response(Status.BAD_REQUEST).with(errorResponseLens of ErrorResponse("invalid id"))
                            }

                            trackRequest.event !in VALID_EVENTS -> {
                                Response(Status.BAD_REQUEST).with(errorResponseLens of ErrorResponse("invalid event"))
                            }

                            else -> {
                                // `accountId` scoping here is a defensive tenant check, not required for
                                // correctness - the gallery id already implies the account - so a post
                                // UUID belonging to a *different* account cleanly falls through to the
                                // same "not found" response as a truly-unknown UUID.
                                val affected =
                                    when (trackRequest.event) {
                                        "view" -> galleryRepository.incrementViewCount(gallery.id, postId, accountId)
                                        else -> galleryRepository.incrementClickCount(gallery.id, postId, accountId)
                                    }
                                if (affected > 0) {
                                    Response(Status.OK).with(trackResponseLens of TrackResponse("ok"))
                                } else {
                                    Response(Status.NOT_FOUND)
                                        .with(errorResponseLens of ErrorResponse("post not found in this gallery"))
                                }
                            }
                        }
                    }
                }
            }
        }

    fun routes(): List<ContractRoute> = listOf(listGalleries(), listGalleryContent(), getGalleryByName(), trackEvent())
}
