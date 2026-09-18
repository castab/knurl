package knurl.presentation.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knurl.domain.repositories.AccountRepository
import org.http4k.contract.contract
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.jdbi.v3.core.Jdbi
import java.io.PrintWriter
import java.sql.Connection
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * A `DataSource` whose every method throws - see `GalleryRoutePathBindingTest`'s identical fixture
 * (named distinctly here since Kotlin's file-private visibility still shares one top-level name
 * per package). Used the same way: a request that reaches [AccountRepository.register] surfaces as
 * a thrown error (proving validation/auth passed and the handler tried the database), while a
 * request rejected before that point returns a normal [org.http4k.core.Response].
 */
private object NeverConnectAccountsDataSource : DataSource {
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

private const val PROVISIONING_SECRET = "provisioning-secret"
private const val ACCOUNT = "17841457350963368"

private fun testApp(): HttpHandler {
    val jdbi = Jdbi.create(NeverConnectAccountsDataSource)
    val adminAccountRoutes = AdminAccountRoutes(AccountRepository(jdbi), PROVISIONING_SECRET)
    return contract { routes += adminAccountRoutes.routes() }
}

/** Whether [request] made it far enough to reach (the deliberately throwing) database. */
private fun reachedDatabase(
    app: HttpHandler,
    request: Request,
): Boolean = runCatching { app(request) }.isFailure

class AdminAccountRoutesTest :
    FunSpec({
        val app = testApp()

        fun request(
            token: String? = PROVISIONING_SECRET,
            body: String = """{"adminToken":"a","readToken":"b"}""",
        ): Request {
            val base = Request(Method.PUT, "/api/v1/admin/accounts/$ACCOUNT/tokens").body(body)
            return token?.let { base.header("Authorization", "Bearer $it") } ?: base
        }

        test("a missing provisioning bearer is a 401, never touching the database") {
            val response = app(request(token = null))

            response.status shouldBe Status.UNAUTHORIZED
            reachedDatabase(app, request(token = null)) shouldBe false
        }

        test("the wrong provisioning secret is a 401, never touching the database") {
            val response = app(request(token = "wrong-secret"))

            response.status shouldBe Status.UNAUTHORIZED
            reachedDatabase(app, request(token = "wrong-secret")) shouldBe false
        }

        test("a malformed body is a 400, never touching the database") {
            // http4k's contract route validates the body against the declared `receiving` lens before
            // dispatching to the handler, so a decode failure never reaches AdminAccountRoutes' own
            // runCatching - the 400 (with an empty body) comes from http4k itself.
            val response = app(request(body = "not json"))

            response.status shouldBe Status.BAD_REQUEST
            reachedDatabase(app, request(body = "not json")) shouldBe false
        }

        test("a blank adminToken is a 400, never touching the database") {
            val response = app(request(body = """{"adminToken":"","readToken":"b"}"""))

            response.status shouldBe Status.BAD_REQUEST
            response.bodyString() shouldContain "non-blank"
            reachedDatabase(app, request(body = """{"adminToken":"","readToken":"b"}""")) shouldBe false
        }

        test("a blank readToken is a 400, never touching the database") {
            val response = app(request(body = """{"adminToken":"a","readToken":""}"""))

            response.status shouldBe Status.BAD_REQUEST
            reachedDatabase(app, request(body = """{"adminToken":"a","readToken":""}""")) shouldBe false
        }

        test("a valid request with the correct secret reaches the database (registration is attempted)") {
            reachedDatabase(app, request()) shouldBe true
        }
    })
