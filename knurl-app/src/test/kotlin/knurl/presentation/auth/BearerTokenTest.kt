package knurl.presentation.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request

class BearerTokenTest :
    FunSpec({
        test("extract returns null when there is no Authorization header") {
            BearerToken.extract(Request(Method.GET, "/x")) shouldBe null
        }

        test("extract returns null when the header is not a Bearer scheme") {
            val request = Request(Method.GET, "/x").header("Authorization", "Basic dXNlcjpwYXNz")

            BearerToken.extract(request) shouldBe null
        }

        test("extract returns the token, trimmed, from a well-formed Bearer header") {
            val request = Request(Method.GET, "/x").header("Authorization", "Bearer  a-token  ")

            BearerToken.extract(request) shouldBe "a-token"
        }

        test("matches is true for the correct token") {
            BearerToken.matches("secret", "secret") shouldBe true
        }

        test("matches is false for the wrong token") {
            BearerToken.matches("wrong", "secret") shouldBe false
        }

        test("matches is false when there is no candidate") {
            BearerToken.matches(null, "secret") shouldBe false
        }
    })
