package knurl.presentation.openapi

import knurl.domain.config.S3Settings
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.CatalogRepository
import knurl.domain.repositories.InstagramPostRepository
import knurl.presentation.auth.BearerAuth
import knurl.presentation.routes.AdminCatalogRoutes
import knurl.presentation.routes.GalleryRoutes
import knurl.presentation.s3.Presigner
import org.http4k.contract.contract
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.format.Jackson
import org.jdbi.v3.core.Jdbi
import java.io.File
import java.sql.Connection
import java.time.Duration
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Entry point for the `:presentation-service:generateOpenApiSpec` Gradle task. Builds the same
 * `contract` route wiring as [knurl.presentation.main] and dumps its `/openapi.json` response to a
 * file, without opening a database connection, an S3 client, or an HTTP listener - the OpenAPI
 * renderer only needs the route *definitions*, never a working [Jdbi]/[Presigner], since no
 * handler is ever invoked. This lets a caller (human or agent) get the spec offline, without
 * standing up Postgres/MinIO or running the full server.
 */
fun main(args: Array<String>) {
    val jdbi = Jdbi.create(NeverConnectDataSource)
    val accountRepository = AccountRepository(jdbi)
    val postRepository = InstagramPostRepository(jdbi)
    val catalogRepository = CatalogRepository(jdbi)

    val presigner =
        Presigner.create(
            S3Settings(
                bucketName = "placeholder",
                region = "us-east-1",
                accessKeyId = "placeholder",
                secretAccessKey = "placeholder",
            ),
            Duration.ofSeconds(21600),
        )
    val bearerAuth = BearerAuth("placeholder")
    val galleryRoutes = GalleryRoutes(postRepository, presigner)
    val adminCatalogRoutes = AdminCatalogRoutes(catalogRepository, accountRepository, presigner)

    val app =
        contract {
            renderer = OpenApi3(ApiInfo("Knurl", "v1.0"), Jackson)
            descriptionPath = "/openapi.json"
            routes += galleryRoutes.routes(bearerAuth)
            routes += adminCatalogRoutes.routes()
        }

    val outputPath = args.firstOrNull() ?: "build/openapi/openapi.json"
    val outputFile = File(outputPath).also { it.parentFile?.mkdirs() }
    outputFile.writeText(app(Request(Method.GET, "/openapi.json")).bodyString())
    presigner.close()

    println("Wrote OpenAPI spec to ${outputFile.absolutePath}")
}

/** Constructing [Jdbi] only stores this - none of its methods run unless a handler executes a query. */
private object NeverConnectDataSource : DataSource {
    private fun unsupported(): Nothing = throw UnsupportedOperationException("NeverConnectDataSource is for OpenAPI spec generation only")

    override fun getConnection(): Connection = unsupported()

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = unsupported()

    override fun getLogWriter() = unsupported()

    override fun setLogWriter(out: java.io.PrintWriter?) = unsupported()

    override fun setLoginTimeout(seconds: Int) = unsupported()

    override fun getLoginTimeout() = unsupported()

    override fun getParentLogger(): Logger = unsupported()

    override fun <T : Any?> unwrap(iface: Class<T>?): T = unsupported()

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
