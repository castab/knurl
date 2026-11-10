package knurl.app

import knurl.app.storage.AccountStorageManager
import knurl.app.storage.StorageBusyException
import knurl.domain.repositories.AccountConflict
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.AccountUnavailable
import knurl.domain.repositories.CatalogRepository
import knurl.domain.repositories.GalleryRepository
import knurl.domain.repositories.IngestionJobRepository
import knurl.presentation.routes.AccountManagementRoutes
import knurl.presentation.routes.AdminCatalogRoutes
import knurl.presentation.routes.AdminGalleryRoutes
import knurl.presentation.routes.ErrorResponse
import knurl.presentation.routes.GalleryRoutes
import knurl.presentation.routes.HealthRoutes
import knurl.presentation.routes.IngestionRoutes
import org.http4k.contract.contract
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.core.Filter
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.Jackson
import org.http4k.format.KotlinxSerialization.autoBody
import org.http4k.lens.LensFailure
import org.jdbi.v3.core.Jdbi

/** Shared by production and offline OpenAPI generation, preventing documentation/runtime drift. */
fun applicationContract(
    jdbi: Jdbi,
    accounts: AccountRepository,
    jobs: IngestionJobRepository,
    storage: AccountStorageManager,
    managementSecret: () -> String,
): org.http4k.routing.RoutingHttpHandler {
    val sign: (
        String,
        (knurl.presentation.s3.Presigner) -> Response,
    ) -> Response = { id, action -> storage.acquire(id).use { action(it.presigner) } }
    val galleries = GalleryRepository(jdbi)
    return contract {
        renderer = OpenApi3(ApiInfo("Knurl", "v1.0"), Jackson)
        descriptionPath = "/openapi.json"
        routes += GalleryRoutes(galleries, null, accounts::verifyReadToken, sign).routes()
        routes += AdminCatalogRoutes(CatalogRepository(jdbi), accounts::verifyAdminToken, null, sign).routes()
        routes += AdminGalleryRoutes(galleries, accounts::verifyAdminToken).routes()
        routes += AccountManagementRoutes(accounts, managementSecret, storage::updateAccount).routes()
        routes += IngestionRoutes(jobs, accounts::verifyAdminToken).routes()
        routes +=
            HealthRoutes(
                {
                    jdbi.useHandle<Exception> {
                        it
                            .createQuery("SELECT 1")
                            .setQueryTimeout(2)
                            .mapTo(Int::class.java)
                            .one()
                    }
                },
            ).routes()
    }
}

/** No exception text or bound parameters can reach an HTTP response, including malformed secret input. */
val applicationErrors =
    Filter { next ->
        { request ->
            val lens = autoBody<ErrorResponse>().toLens()
            try {
                // Every body-bearing route is small JSON; cap before any lens buffers it, including public-read tracking.
                if (request.method.name in setOf("POST", "PATCH", "PUT", "DELETE")) {
                    val body = request.body.stream.readNBytes(65537)
                    if (body.size > 65536) {
                        Response(Status.REQUEST_ENTITY_TOO_LARGE).with(lens of ErrorResponse("request body too large"))
                    } else {
                        next(request.body(body.toString(Charsets.UTF_8)))
                    }
                } else {
                    next(request)
                }
            } catch (
                _: AccountUnavailable,
            ) {
                Response(Status.NOT_FOUND).with(lens of ErrorResponse("unknown or disabled account"))
            } catch (
                e: AccountConflict,
            ) {
                Response(Status.CONFLICT).with(lens of ErrorResponse(e.message ?: "conflict"))
            } catch (
                _: StorageBusyException,
            ) {
                Response(Status.SERVICE_UNAVAILABLE).with(lens of ErrorResponse("storage resources busy"))
            } catch (
                _: LensFailure,
            ) {
                Response(Status.BAD_REQUEST).with(lens of ErrorResponse("invalid request"))
            } catch (
                _: IllegalArgumentException,
            ) {
                Response(Status.BAD_REQUEST).with(lens of ErrorResponse("invalid request"))
            } catch (
                _: Exception,
            ) {
                Response(Status.INTERNAL_SERVER_ERROR).with(lens of ErrorResponse("request failed"))
            }
        }
    }
