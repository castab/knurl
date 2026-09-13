package knurl.presentation.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knurl.domain.config.S3Settings
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.GalleryRepository
import knurl.presentation.auth.BearerAuth
import knurl.presentation.s3.Presigner
import org.http4k.contract.contract
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.jdbi.v3.core.Jdbi
import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * A `DataSource` whose every method throws. No handler under test is allowed to reach the database:
 * a request that does is a test failure, not a connection error. Mirrors the same trick
 * `GenerateOpenApiSpec` uses to build the contract without infrastructure.
 */
private object NeverConnectDataSource : DataSource {
    override fun getConnection(): Connection = error("the handler should not have reached the database")

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = getConnection()

    override fun getLogWriter(): PrintWriter = error("unused")

    override fun setLogWriter(out: PrintWriter?) = error("unused")

    override fun setLoginTimeout(seconds: Int) = error("unused")

    override fun getLoginTimeout(): Int = error("unused")

    override fun getParentLogger(): Logger = error("unused")

    override fun <T : Any?> unwrap(iface: Class<T>?): T = error("unused")

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}

private fun testApp(): org.http4k.core.HttpHandler {
    val jdbi = Jdbi.create(NeverConnectDataSource)
    val presigner =
        Presigner.create(
            S3Settings(
                bucketName = "test-bucket",
                region = "us-east-1",
                endpoint = "http://localhost:9000",
                accessKeyId = "k",
                secretAccessKey = "s",
                pathStyleAccess = true,
            ),
            java.time.Duration.ofSeconds(6.hours.inWholeSeconds),
        )
    val galleryRoutes = GalleryRoutes(GalleryRepository(jdbi), presigner)
    val adminGalleryRoutes = AdminGalleryRoutes(GalleryRepository(jdbi), AccountRepository(jdbi))
    return contract {
        routes += galleryRoutes.routes(BearerAuth("token"))
        routes += adminGalleryRoutes.routes()
    }
}

private const val ACCOUNT = "17841457350963368"
private val GALLERY = Uuid.random().toString()

/**
 * Guards the one mistake in these route definitions that compiles perfectly and fails silently.
 *
 * In http4k's contract DSL a *literal* path segment appearing after a path lens becomes its own
 * positional handler argument, typed `String`, interleaved in path order. So
 * `/accounts/{accountId}/galleries/{galleryId}/track` is arity four - `{ accountId, _, galleryId, _ }` -
 * and writing those four parameters in any other order still typechecks, because they are all
 * `String`. The symptom would be the literal `"galleries"` arriving where a gallery id was expected.
 *
 * These tests pin the binding by exploiting the fact that a gallery id is parsed as a UUID before
 * anything else happens: a well-formed uuid in the right position gets past the parse (and on to the
 * database, which is why the datasource here refuses to connect), while anything else - a literal
 * segment, or the numeric account id - fails it and produces a very recognisable 400.
 */
class GalleryRoutePathBindingTest :
    FunSpec({
        val app = testApp()

        fun invalidGalleryIdResponse(request: Request): Boolean =
            runCatching { app(request) }
                .fold(
                    onSuccess = { it.status == Status.BAD_REQUEST && it.bodyString().contains("invalid gallery id") },
                    // Reaching the database means the uuid parsed, so the binding was right.
                    onFailure = { false },
                )

        test("gallery content binds {galleryId} to the fourth segment, not the literal before it") {
            invalidGalleryIdResponse(Request(Method.GET, "/api/v1/accounts/$ACCOUNT/galleries/$GALLERY")) shouldBe false
        }

        test("gallery content rejects a malformed gallery id with 400, never a 500 from the driver") {
            val response = app(Request(Method.GET, "/api/v1/accounts/$ACCOUNT/galleries/not-a-uuid"))

            response.status shouldBe Status.BAD_REQUEST
            response.bodyString() shouldContain "invalid gallery id"
        }

        test("track binds {galleryId} correctly even with a trailing literal segment after it") {
            val request =
                Request(Method.POST, "/api/v1/accounts/$ACCOUNT/galleries/$GALLERY/track")
                    .header("Authorization", "Bearer token")
                    .body("""{"id":"${Uuid.random()}","event":"view"}""")

            invalidGalleryIdResponse(request) shouldBe false
        }

        test("track rejects a malformed gallery id before touching the body") {
            val request =
                Request(Method.POST, "/api/v1/accounts/$ACCOUNT/galleries/nope/track")
                    .header("Authorization", "Bearer token")
                    .body("""{"id":"not-a-uuid","event":"nonsense"}""")

            val response = app(request)

            response.status shouldBe Status.BAD_REQUEST
            response.bodyString() shouldContain "invalid gallery id"
        }

        test("the galleries list route exists separately from the content route") {
            // Would reach the database if bound correctly; a 404 here would mean the list route was
            // shadowed by the content route treating "galleries" as a gallery id.
            val result = runCatching { app(Request(Method.GET, "/api/v1/accounts/$ACCOUNT/galleries")) }
            result.fold(
                onSuccess = { it.status shouldBe Status.INTERNAL_SERVER_ERROR },
                onFailure = { },
            )
        }
    })
