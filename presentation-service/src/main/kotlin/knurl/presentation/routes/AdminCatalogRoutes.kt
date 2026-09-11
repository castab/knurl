package knurl.presentation.routes

import knurl.domain.models.CatalogEntry
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
)

@Serializable
data class AdminCatalogResponse(
    val items: List<AdminCatalogItemResponse>,
    val count: Int,
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
    )

/**
 * Admin-only surface for browsing one account's full Instagram media catalog and curating which
 * items get downloaded/stored as gallery posts. Unlike the rest of presentation-service, this is a
 * narrow, deliberate exception to "zero database writes": `PATCH .../selections` flips
 * `instagram_media_catalog.selected`, which `ingestion-service` reads on its next cycle.
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
    private val catalogResponseLens = autoBody<AdminCatalogResponse>().toLens()
    private val errorResponseLens = autoBody<ErrorResponse>().toLens()
    private val selectionUpdateRequestLens = autoBody<SelectionUpdateRequest>().toLens()
    private val selectionUpdateResponseLens = autoBody<SelectionUpdateResponse>().toLens()

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

    // TODO(REMEDIATION-PLAN.md P8): this returns the entire catalog with no limit, presigning one URL per
    // row. Needs server-side limit/offset (clamped like GalleryRoutes' coerceIn(1, 50)) plus matching
    // paging in public/app.js, which currently fetches everything and paginates client-side.
    private fun listCatalog(): ContractRoute =
        "/api/v1/admin/accounts" / accountIdPath / "catalog" meta {
            summary = "List catalog entries for an account, optionally filtered by selection state"
            security = adminBearerSecurity
            queries += selectedQuery
            returning(Status.OK, catalogResponseLens to AdminCatalogResponse(listOf(EXAMPLE_CATALOG_ITEM), 1))
        } bindContract Method.GET to { accountId, _ ->
            { request ->
                authorize(accountId, request) {
                    when (val raw = selectedQuery(request)) {
                        null, "true", "false" -> {
                            val entries = catalogRepository.findAll(accountId, raw?.toBoolean())
                            Response(Status.OK)
                                .with(catalogResponseLens of AdminCatalogResponse(entries.map { it.toResponse(presigner) }, entries.size))
                        }

                        else -> {
                            Response(Status.BAD_REQUEST).with(errorResponseLens of ErrorResponse("invalid selected value"))
                        }
                    }
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

    fun routes(): List<ContractRoute> = listOf(listCatalog(), updateSelections())
}
