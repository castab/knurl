package knurl.presentation.auth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then

class BearerAuthTest :
    FunSpec({
        val auth = BearerAuth("secret-token")
        val handler = auth.filter.then { Response(Status.OK) }

        test("rejects requests with no Authorization header") {
            handler(Request(Method.POST, "/x")).status shouldBe Status.UNAUTHORIZED
        }

        test("rejects requests with the wrong token") {
            val request = Request(Method.POST, "/x").header("Authorization", "Bearer wrong")
            handler(request).status shouldBe Status.UNAUTHORIZED
        }

        test("accepts requests with the correct bearer token") {
            val request = Request(Method.POST, "/x").header("Authorization", "Bearer secret-token")
            handler(request).status shouldBe Status.OK
        }
    })
