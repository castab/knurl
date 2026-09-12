package knurl.presentation.routes

import knurl.domain.models.InstagramPost
import knurl.domain.models.PostMediaItem
import knurl.domain.models.SortOrder
import knurl.domain.repositories.InstagramPostRepository
import knurl.presentation.auth.BearerAuth
import knurl.presentation.s3.Presigner
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Method
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
    val largeUrl: String,
    val videoUrl: String?,
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

@Serializable
data class GalleryResponse(
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
                    largeUrl = "https://example-bucket.s3.amazonaws.com/posts/example/0/large.webp",
                    videoUrl = "https://example-bucket.s3.amazonaws.com/posts/example/0/original",
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
        largeUrl = presigner.presignGet(largePath).url,
        videoUrl = videoPath?.let { presigner.presignGet(it).url },
        mediaUrlExpiresAt = small.expiresAt.toString(),
    )
}

internal fun InstagramPost.toResponse(presigner: Presigner): GalleryItemResponse =
    GalleryItemResponse(
        id = id.toString(),
        mediaType = mediaType,
        caption = caption,
        permalink = permalink,
        timestamp = timestamp.toString(),
        mediaItems = mediaItems.sortedBy { it.position }.map { it.toResponse(presigner) },
        viewCount = viewCount,
        clickCount = clickCount,
    )

/**
 * Public, read-only gallery listing - one presentation-service deployment serves every ingested
 * account, so `accountId` (public, non-secret Graph API business account id) is a path segment
 * rather than something configured once at startup. `sort` is validated manually (rather than via a
 * typed lens) so an invalid value produces a clear 400 instead of a lens-failure default.
 *
 * Results are paginated into the shared `{ data, pagination }` envelope (see [Pagination.kt]).
 * `limit` and `page` are both silently clamped rather than rejected - `limit` into
 * `1..MAX_PAGE_SIZE`, `page` into `1..totalPages` by [knurl.domain.models.Page.of] - so a client
 * walking off either end of the range still gets a usable page and coherent links.
 */
class GalleryRoutes(
    private val postRepository: InstagramPostRepository,
    private val presigner: Presigner,
) {
    private val accountIdPath = Path.of("accountId")
    private val sortQuery = Query.string().defaulted("sort", "recent")
    private val limitQuery = Query.int().defaulted("limit", 12)
    private val pageQuery = Query.int().defaulted("page", 1)
    private val galleryResponseLens = autoBody<GalleryResponse>().toLens()
    private val errorResponseLens = autoBody<ErrorResponse>().toLens()
    private val trackRequestLens = autoBody<TrackRequest>().toLens()
    private val trackResponseLens = autoBody<TrackResponse>().toLens()

    private fun listGallery(): ContractRoute =
        "/api/v1/accounts" / accountIdPath / "gallery" meta {
            summary = "List a page of gallery posts for an account, sorted by recency or view count"
            queries += sortQuery
            queries += limitQuery
            queries += pageQuery
            returning(
                Status.OK,
                galleryResponseLens to
                    GalleryResponse(
                        listOf(EXAMPLE_GALLERY_ITEM),
                        examplePagination("/api/v1/accounts/{accountId}/gallery"),
                    ),
            )
        } bindContract Method.GET to { accountId, _ ->
            { request ->
                val sortResult = runCatching { SortOrder.fromQueryParam(sortQuery(request)) }
                sortResult.fold(
                    onSuccess = { sort ->
                        val pageSize = limitQuery(request).coerceIn(1, MAX_PAGE_SIZE)
                        val page = postRepository.findPage(accountId, sort, pageSize, pageQuery(request))
                        val body =
                            GalleryResponse(
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
        }

    private fun trackEvent(bearerAuth: BearerAuth): ContractRoute =
        "/api/v1/accounts" / accountIdPath / "gallery" / "track" meta {
            summary = "Record a view or click analytics event for a post"
            security = bearerAuth.security
            receiving(trackRequestLens to TrackRequest("id", "view"))
            returning(Status.OK, trackResponseLens to TrackResponse("ok"))
        } bindContract Method.POST to { accountId, _, _ ->
            { request ->
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
                        // correctness - the UUID alone already uniquely identifies the row - so a
                        // post UUID belonging to a *different* account cleanly falls through to
                        // the same "not found" response as a truly-unknown UUID.
                        val affected =
                            when (trackRequest.event) {
                                "view" -> postRepository.incrementViewCount(postId, accountId)
                                else -> postRepository.incrementClickCount(postId, accountId)
                            }
                        if (affected > 0) {
                            Response(Status.OK).with(trackResponseLens of TrackResponse("ok"))
                        } else {
                            Response(Status.NOT_FOUND).with(errorResponseLens of ErrorResponse("post not found"))
                        }
                    }
                }
            }
        }

    fun routes(bearerAuth: BearerAuth): List<ContractRoute> = listOf(listGallery(), trackEvent(bearerAuth))
}
