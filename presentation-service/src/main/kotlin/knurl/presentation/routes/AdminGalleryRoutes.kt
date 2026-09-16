package knurl.presentation.routes

import knurl.domain.repositories.GalleryNameTaken
import knurl.domain.repositories.GalleryRepository
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
import org.http4k.lens.string
import kotlin.uuid.Uuid

@Serializable
data class GalleryWriteRequest(
    val name: String,
)

@Serializable
data class GalleryItemsRequest(
    val add: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
)

/**
 * `added` is the shortcodes this request actually put in the gallery; `alreadyPresent` were asked
 * for but were members already. Separating them means a client can tell "nothing happened because
 * it was already there" from "nothing happened because I typo'd the shortcode", which lands in
 * `notFound`.
 */
@Serializable
data class GalleryItemsResponse(
    val added: List<String>,
    val alreadyPresent: List<String>,
    val removed: List<String>,
    val notFound: List<String>,
)

/**
 * `itemsReleased` is how many items lost their *last* gallery membership and therefore just started
 * a retention countdown. Reported because deleting a gallery is otherwise a very quiet way to
 * schedule a lot of media for deletion - an item in two galleries is unaffected, one in only this
 * gallery is now on the clock, and the number is the difference.
 */
@Serializable
data class GalleryDeleteResponse(
    val name: String,
    val itemsRemoved: Int,
    val itemsReleased: Int,
)

/**
 * Cap on one membership request. A bulk mutation gets a cap for the same reason a list endpoint does
 * (see [MAX_PAGE_SIZE]) - an unbounded batch is an unbounded statement - and the same reasoning that
 * gives [MAX_PURGE_BATCH] its bound.
 */
internal const val MAX_GALLERY_ITEMS_BATCH = 100

/**
 * Admin CRUD for an account's galleries, plus the membership curation that replaced the old single
 * `selected` flag.
 *
 * This is a wider write surface than presentation-service had before - full lifecycle management of
 * a first-class entity rather than one boolean - and that is a deliberate extension of the existing
 * boundary, not an erosion of it. The reason is unchanged: `ingestion-service` never opens an HTTP
 * listener, so admin intent has to be recorded through this service and picked up from the shared
 * database on the next cycle. What still holds is the part that matters - **none of these routes
 * touches S3**. Every byte deletion remains `ingestion-service`'s job.
 */
class AdminGalleryRoutes(
    private val galleryRepository: GalleryRepository,
    private val verifyAdminToken: VerifyAccountToken,
) {
    private val accountIdPath = Path.of("accountId")
    private val galleryIdPath = Path.of("galleryId")
    private val forceQuery = Query.string().optional("force")
    private val galleryWriteRequestLens = autoBody<GalleryWriteRequest>().toLens()
    private val gallerySummaryLens = autoBody<GallerySummaryResponse>().toLens()
    private val galleryItemsRequestLens = autoBody<GalleryItemsRequest>().toLens()
    private val galleryItemsResponseLens = autoBody<GalleryItemsResponse>().toLens()
    private val galleryDeleteResponseLens = autoBody<GalleryDeleteResponse>().toLens()
    private val errorResponseLens = autoBody<ErrorResponse>().toLens()

    private val exampleGallery =
        GallerySummaryResponse(
            id = "01912f4e-1a2b-7c3d-8e4f-5a6b7c8d9e0f",
            name = "Travel",
            itemCount = 24,
            publishedCount = 22,
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-02T00:00:00Z",
        )

    /**
     * Names are trimmed before use so `"Travel"` and `"  Travel  "` are the same name - matching the
     * `lower(btrim(name))` unique index exactly. An all-whitespace name is rejected here rather than
     * left to the database CHECK, so the client gets a readable message instead of a 500.
     */
    private fun validatedName(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= 100 }

    private fun badRequest(message: String): Response = Response(Status.BAD_REQUEST).with(errorResponseLens of ErrorResponse(message))

    private fun notFound(): Response = Response(Status.NOT_FOUND).with(errorResponseLens of ErrorResponse("gallery not found"))

    /** A malformed uuid is a bad request; a well-formed one that isn't this account's is a 404 (see [GalleryRoutes]). */
    private fun withGalleryId(
        rawGalleryId: String,
        onParsed: (Uuid) -> Response,
    ): Response = Uuid.parseOrNull(rawGalleryId)?.let(onParsed) ?: badRequest("invalid gallery id")

    private fun createGallery(): ContractRoute =
        "/api/v1/admin/accounts" / accountIdPath / "galleries" meta {
            summary = "Create a named gallery"
            security = adminBearerSecurity
            receiving(galleryWriteRequestLens to GalleryWriteRequest("Travel"))
            returning(Status.CREATED, gallerySummaryLens to exampleGallery)
        } bindContract Method.POST to { accountId, _ ->
            { request ->
                authorizeAccount(accountId, request, verifyAdminToken) {
                    val name = validatedName(galleryWriteRequestLens(request).name)
                    if (name == null) {
                        badRequest("name must be 1-100 characters after trimming")
                    } else {
                        val created = galleryRepository.create(accountId, name)
                        if (created == null) {
                            Response(Status.CONFLICT)
                                .with(errorResponseLens of ErrorResponse("a gallery named '$name' already exists"))
                        } else {
                            Response(Status.CREATED).with(gallerySummaryLens of created.toResponse())
                        }
                    }
                }
            }
        }

    /**
     * Renaming never changes the gallery's id, so every link already pointing at it keeps working -
     * which is the entire reason the id and the name are separate things.
     */
    private fun renameGallery(): ContractRoute =
        "/api/v1/admin/accounts" / accountIdPath / "galleries" / galleryIdPath meta {
            summary = "Rename a gallery, keeping its id and content"
            security = adminBearerSecurity
            receiving(galleryWriteRequestLens to GalleryWriteRequest("Travel 2024"))
            returning(Status.OK, gallerySummaryLens to exampleGallery)
        } bindContract Method.PATCH to { accountId, _, rawGalleryId ->
            { request ->
                authorizeAccount(accountId, request, verifyAdminToken) {
                    withGalleryId(rawGalleryId) { galleryId ->
                        val name = validatedName(galleryWriteRequestLens(request).name)
                        when {
                            name == null -> {
                                badRequest("name must be 1-100 characters after trimming")
                            }

                            else -> {
                                runCatching { galleryRepository.rename(accountId, galleryId, name) }
                                    .fold(
                                        onSuccess = { renamed ->
                                            if (renamed == null) {
                                                notFound()
                                            } else {
                                                Response(Status.OK).with(gallerySummaryLens of renamed.toResponse())
                                            }
                                        },
                                        onFailure = { failure ->
                                            if (failure is GalleryNameTaken) {
                                                Response(Status.CONFLICT).with(
                                                    errorResponseLens of
                                                        ErrorResponse("a gallery named '${failure.name}' already exists"),
                                                )
                                            } else {
                                                throw failure
                                            }
                                        },
                                    )
                            }
                        }
                    }
                }
            }
        }

    /**
     * Deleting a non-empty gallery requires `?force=true`.
     *
     * Not ceremony: every item that was only in this gallery starts a retention countdown the moment
     * it goes, so a bare `204` would be a very quiet way to schedule a few hundred items' media for
     * deletion. The forced response reports exactly how many that was.
     */
    private fun deleteGallery(): ContractRoute =
        "/api/v1/admin/accounts" / accountIdPath / "galleries" / galleryIdPath meta {
            summary = "Delete a gallery; a non-empty one requires force=true"
            security = adminBearerSecurity
            queries += forceQuery
            returning(
                Status.OK,
                galleryDeleteResponseLens to GalleryDeleteResponse(name = "Travel", itemsRemoved = 24, itemsReleased = 7),
            )
        } bindContract Method.DELETE to { accountId, _, rawGalleryId ->
            { request ->
                authorizeAccount(accountId, request, verifyAdminToken) {
                    withGalleryId(rawGalleryId) { galleryId ->
                        val gallery = galleryRepository.find(accountId, galleryId)
                        val forced = forceQuery(request) == "true"
                        when {
                            gallery == null -> {
                                notFound()
                            }

                            gallery.itemCount > 0 && !forced -> {
                                Response(Status.CONFLICT).with(
                                    errorResponseLens of
                                        ErrorResponse(
                                            "gallery '${gallery.name}' still holds ${gallery.itemCount} item(s); " +
                                                "repeat with ?force=true to delete it anyway",
                                        ),
                                )
                            }

                            else -> {
                                val result = galleryRepository.delete(accountId, galleryId)
                                if (result == null) {
                                    notFound()
                                } else {
                                    Response(Status.OK).with(
                                        galleryDeleteResponseLens of
                                            GalleryDeleteResponse(
                                                name = result.name,
                                                itemsRemoved = result.itemsRemoved,
                                                itemsReleased = result.itemsReleased,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    /**
     * Adds and removes members by shortcode - the human-facing identifier admins work with, matching
     * every other admin surface.
     *
     * A shortcode in both lists is a 400 rather than a silently-resolved race: the request does not
     * say what was meant, and guessing an order would make the outcome depend on an implementation
     * detail.
     */
    private fun updateItems(): ContractRoute =
        "/api/v1/admin/accounts" / accountIdPath / "galleries" / galleryIdPath / "items" meta {
            summary = "Add or remove a gallery's items by shortcode"
            security = adminBearerSecurity
            receiving(
                galleryItemsRequestLens to
                    GalleryItemsRequest(add = listOf("Cabc123XYZ"), remove = listOf("Cdef456UVW")),
            )
            returning(
                Status.OK,
                galleryItemsResponseLens to
                    GalleryItemsResponse(
                        added = listOf("Cabc123XYZ"),
                        alreadyPresent = listOf("Cghi789RST"),
                        removed = listOf("Cdef456UVW"),
                        notFound = listOf("Cjkl012MNO"),
                    ),
            )
        } bindContract Method.PATCH to { accountId, _, rawGalleryId, _ ->
            { request ->
                authorizeAccount(accountId, request, verifyAdminToken) {
                    withGalleryId(rawGalleryId) { galleryId ->
                        val body = galleryItemsRequestLens(request)
                        val addSet = body.add.toSet()
                        val removeSet = body.remove.toSet()
                        val overlap = addSet intersect removeSet
                        val total = addSet.size + removeSet.size

                        when {
                            overlap.isNotEmpty() -> {
                                badRequest("shortcodes cannot be both added and removed: ${overlap.sorted()}")
                            }

                            total == 0 -> {
                                badRequest("add and remove cannot both be empty")
                            }

                            total > MAX_GALLERY_ITEMS_BATCH -> {
                                badRequest("at most $MAX_GALLERY_ITEMS_BATCH shortcodes per request, got $total")
                            }

                            else -> {
                                val result = galleryRepository.updateItems(accountId, galleryId, addSet, removeSet)
                                if (result == null) {
                                    notFound()
                                } else {
                                    Response(Status.OK).with(
                                        galleryItemsResponseLens of
                                            GalleryItemsResponse(
                                                added = result.added.sorted(),
                                                alreadyPresent = result.alreadyPresent.sorted(),
                                                removed = result.removed.sorted(),
                                                notFound = result.notFound.sorted(),
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    fun routes(): List<ContractRoute> = listOf(createGallery(), renameGallery(), deleteGallery(), updateItems())
}
