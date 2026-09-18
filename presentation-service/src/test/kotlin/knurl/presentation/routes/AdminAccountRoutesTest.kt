package knurl.presentation.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import knurl.presentation.credentials.CredentialRefresher
import org.http4k.contract.contract
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.io.IOException

private const val CREDENTIALS_SECRET = "credentials-secret"
private const val PATH = "/api/v1/admin/credentials/refresh"

private fun request(token: String? = CREDENTIALS_SECRET): Request {
    val base = Request(Method.PUT, PATH)
    return token?.let { base.header("Authorization", "Bearer $it") } ?: base
}

class AdminAccountRoutesTest :
    FunSpec({
        fun appWith(refresher: CredentialRefresher) = contract { routes += AdminAccountRoutes(refresher) { CREDENTIALS_SECRET }.routes() }

        test("a missing bearer is a 401 and never triggers a re-fetch") {
            val refresher = mockk<CredentialRefresher>()

            val response = appWith(refresher)(request(token = null))

            response.status shouldBe Status.UNAUTHORIZED
            verify(exactly = 0) { refresher.refresh() }
        }

        test("the wrong secret is a 401 and never triggers a re-fetch") {
            val refresher = mockk<CredentialRefresher>()

            val response = appWith(refresher)(request(token = "wrong-secret"))

            response.status shouldBe Status.UNAUTHORIZED
            verify(exactly = 0) { refresher.refresh() }
        }

        test("the correct secret triggers exactly one re-fetch and returns 200") {
            val refresher = mockk<CredentialRefresher>()
            every { refresher.refresh() } returns true

            val response = appWith(refresher)(request())

            response.status shouldBe Status.OK
            verify(exactly = 1) { refresher.refresh() }
        }

        test("a body on the signal is ignored - this service pulls rather than trusting a push") {
            val refresher = mockk<CredentialRefresher>()
            every { refresher.refresh() } returns true

            val response = appWith(refresher)(request().body("""{"adminToken":"a","readToken":"b"}"""))

            response.status shouldBe Status.OK
            verify(exactly = 1) { refresher.refresh() }
        }

        test("a failing credential endpoint is a 502, leaving the previous credentials live") {
            val refresher = mockk<CredentialRefresher>()
            every { refresher.refresh() } throws IOException("credential endpoint down")

            val response = appWith(refresher)(request())

            response.status shouldBe Status.BAD_GATEWAY
            response.bodyString() shouldContain "credential endpoint unavailable"
        }

        test("the failure response carries no detail from the underlying error") {
            val refresher = mockk<CredentialRefresher>()
            every { refresher.refresh() } throws IOException("bearer token super-secret-value rejected")

            val response = appWith(refresher)(request())

            response.bodyString() shouldContain "credential endpoint unavailable"
            response.bodyString().contains("super-secret-value") shouldBe false
        }
    })
