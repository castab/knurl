package knurl.presentation.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knurl.domain.repositories.AccountTokenVerification
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status

/**
 * Direct coverage for [authorizeAccount] - the one function every admin route and every public
 * gallery route funnels through to enforce tenant isolation - exercising all three
 * [AccountTokenVerification] outcomes without a database, via a fake [verify][authorizeAccount].
 */
class AccountAuthTest :
    FunSpec({
        fun requestWithToken(token: String?) =
            token?.let { Request(Method.GET, "/x").header("Authorization", "Bearer $it") }
                ?: Request(Method.GET, "/x")

        test("an unknown account is a 404, not a 401 - account ids are public, non-secret") {
            val response =
                authorizeAccount(
                    accountId = "unknown-account",
                    request = requestWithToken("any-token"),
                    verify = { _, _ -> AccountTokenVerification.UnknownAccount },
                    onAuthorized = { error("must not run") },
                )

            response.status shouldBe Status.NOT_FOUND
            response.bodyString() shouldContain "unknown account"
        }

        test("a known account with the wrong token is a 401") {
            val response =
                authorizeAccount(
                    accountId = "account-1",
                    request = requestWithToken("wrong-token"),
                    verify = { _, _ -> AccountTokenVerification.Mismatch },
                    onAuthorized = { error("must not run") },
                )

            response.status shouldBe Status.UNAUTHORIZED
            response.bodyString() shouldContain "unauthorized"
        }

        test("a known account with no token at all is a 401, not a crash") {
            val verify = { _: String, candidateToken: String? ->
                if (candidateToken == null) AccountTokenVerification.Mismatch else error("unexpected")
            }
            val response =
                authorizeAccount(
                    accountId = "account-1",
                    request = requestWithToken(null),
                    verify = verify,
                    onAuthorized = { error("must not run") },
                )

            response.status shouldBe Status.UNAUTHORIZED
        }

        test("a matching token runs onAuthorized and returns its response") {
            val response =
                authorizeAccount(
                    accountId = "account-1",
                    request = requestWithToken("correct-token"),
                    verify = { _, _ -> AccountTokenVerification.Match },
                    onAuthorized = { Response(Status.OK).body("ok") },
                )

            response.status shouldBe Status.OK
            response.bodyString() shouldBe "ok"
        }

        test("passes the request's accountId, not some other value, to verify") {
            var seenAccountId: String? = null

            authorizeAccount(
                accountId = "the-real-account-id",
                request = requestWithToken("token"),
                verify = { accountId, _ ->
                    seenAccountId = accountId
                    AccountTokenVerification.Match
                },
                onAuthorized = { Response(Status.OK) },
            )

            seenAccountId shouldBe "the-real-account-id"
        }
    })
