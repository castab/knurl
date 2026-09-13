package knurl.presentation

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.PropertySource
import knurl.domain.config.DatabaseConfig
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.CatalogRepository
import knurl.domain.repositories.GalleryRepository
import knurl.presentation.auth.BearerAuth
import knurl.presentation.routes.AdminCatalogRoutes
import knurl.presentation.routes.AdminGalleryRoutes
import knurl.presentation.routes.GalleryRoutes
import knurl.presentation.s3.Presigner
import org.http4k.contract.contract
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.contract.ui.swaggerUiLite
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.format.Jackson
import org.http4k.routing.ResourceLoader
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.Undertow
import org.http4k.server.asServer
import java.time.Duration

/**
 * Plain `fun main()` entrypoint - no application framework. Everything below is manually
 * constructed and wired by hand; http4k is used purely as a functional HTTP toolkit.
 *
 * Architectural boundary: this service's database writes are bounded to the per-gallery analytics
 * counters (POST .../galleries/{galleryId}/track), the gallery CRUD and membership under
 * /api/v1/admin/accounts/{accountId}/galleries, and the deletion clock those maintain. Crucially it
 * issues no S3 call beyond presigning reads - every byte deletion stays ingestion-service's job. It
 * never runs Flyway migrations (ingestion-service owns the schema) and never calls the Meta Graph API.
 *
 * This is a genuinely multi-tenant deployment: one process serves every ingested Instagram
 * account, each identified by its (public, non-secret) account id in the URL path. Compare
 * ingestion-service, which stays one-process-per-account.
 */
fun main() {
    val config =
        ConfigLoaderBuilder
            .default()
            // Order is priority order and the FIRST source added wins. `application.conf` must come
            // first so that a deployed environment's real env vars (resolved into it by typesafe-config's
            // ${?VAR} substitution) outrank the dev defaults below. When a ${?VAR} is unset the key is
            // dropped from this source entirely, so local development still falls through to
            // application-local.conf. Do not reorder these two lines.
            .addPropertySource(PropertySource.resource("/application.conf"))
            .addPropertySource(PropertySource.resource("/application-local.conf", optional = true))
            .build()
            .loadConfigOrThrow<PresentationConfig>()

    val dataSource = DatabaseConfig.createDataSource(config.database)
    val jdbi = DatabaseConfig.createJdbi(dataSource)
    val accountRepository = AccountRepository(jdbi)
    val galleryRepository = GalleryRepository(jdbi)
    val catalogRepository = CatalogRepository(jdbi)

    val presigner = Presigner.create(config.s3, Duration.ofSeconds(config.presignedGetTtlSeconds))
    val bearerAuth = BearerAuth(config.apiBearerToken)
    val galleryRoutes = GalleryRoutes(galleryRepository, presigner)
    val adminCatalogRoutes = AdminCatalogRoutes(catalogRepository, accountRepository, presigner)
    val adminGalleryRoutes = AdminGalleryRoutes(galleryRepository, accountRepository)

    val app =
        contract {
            // Jackson here is solely for the OpenAPI schema-generation renderer, which - unlike
            // route bodies (still KotlinxSerialization.autoBody<T>() throughout) - can't reliably
            // reflect over kotlinx.serialization types; see AGENTS.md's OpenAPI section.
            renderer = OpenApi3(ApiInfo("Knurl", "v1.0"), Jackson)
            descriptionPath = "/openapi.json"
            routes += galleryRoutes.routes(bearerAuth)
            routes += adminCatalogRoutes.routes()
            routes += adminGalleryRoutes.routes()
        }
    val docs = "/docs" bind swaggerUiLite { url = "/openapi.json" }
    val routedApp =
        if (config.uiEnabled) {
            routes(app, docs, static(ResourceLoader.Classpath("public")))
        } else {
            routes(app, docs)
        }
    val appWithDocsRedirect: HttpHandler = { request ->
        if (request.uri.path == "/docs") {
            Response(Status.FOUND).header("Location", "/docs/")
        } else {
            routedApp(request)
        }
    }

    val server = appWithDocsRedirect.asServer(Undertow(config.port)).start()
    println("Knurl presentation-service listening on port ${config.port}")
    if (config.uiEnabled) {
        println("Gallery/Admin UI: http://localhost:${config.port}/")
    } else {
        println("Gallery/Admin UI: disabled (UI_ENABLED=false)")
    }
    println("Swagger UI: http://localhost:${config.port}/docs")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop()
            presigner.close()
            dataSource.close()
        },
    )
}
