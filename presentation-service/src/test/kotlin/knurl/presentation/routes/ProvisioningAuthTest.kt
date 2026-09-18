package knurl.presentation.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status

/**
 * Direct coverage for [authorizeProvisioning] - the single deployment-wide secret check that gates
 * the account-token provisioning endpoint, exercised without any HTTP server or database.
 */
class ProvisioningAuthTest :
    FunSpec({
        fun requestWithToken(token: String?) =
            token?.let { Request(Method.PUT, "/x").header("Authorization", "Bearer $it") }
                ?: Request(Method.PUT, "/x")

        test("the correct secret runs onAuthorized and returns its response") {
            val response =
                authorizeProvisioning(
                    request = requestWithToken("correct-secret"),
                    expectedSecret = "correct-secret",
                    onAuthorized = { Response(Status.OK).body("ok") },
                )

            response.status shouldBe Status.OK
            response.bodyString() shouldBe "ok"
        }

        test("the wrong secret is a 401") {
            val response =
                authorizeProvisioning(
                    request = requestWithToken("wrong-secret"),
                    expectedSecret = "correct-secret",
                    onAuthorized = { error("must not run") },
                )

            response.status shouldBe Status.UNAUTHORIZED
            response.bodyString() shouldContain "unauthorized"
        }

        test("a missing bearer token is a 401, not a crash") {
            val response =
                authorizeProvisioning(
                    request = requestWithToken(null),
                    expectedSecret = "correct-secret",
                    onAuthorized = { error("must not run") },
                )

            response.status shouldBe Status.UNAUTHORIZED
        }
    })
