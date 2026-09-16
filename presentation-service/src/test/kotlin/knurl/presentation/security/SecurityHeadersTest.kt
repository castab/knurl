package knurl.presentation.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then

class SecurityHeadersTest :
    FunSpec({
        val handler = SecurityHeaders.filter.then { Response(Status.OK) }

        test("sets X-Content-Type-Options on every response") {
            handler(Request(Method.GET, "/")).header("X-Content-Type-Options") shouldBe "nosniff"
        }

        test("sets X-Frame-Options on every response") {
            handler(Request(Method.GET, "/")).header("X-Frame-Options") shouldBe "DENY"
        }

        test("sets Strict-Transport-Security on every response") {
            handler(Request(Method.GET, "/")).header("Strict-Transport-Security") shouldNotBe null
        }

        test("sets a Content-Security-Policy on ordinary routes") {
            handler(Request(Method.GET, "/api/v1/accounts/123/galleries")).header("Content-Security-Policy") shouldNotBe null
        }

        test("omits Content-Security-Policy under /docs") {
            handler(Request(Method.GET, "/docs/")).header("Content-Security-Policy") shouldBe null
        }

        test("still sets the other headers under /docs") {
            val response = handler(Request(Method.GET, "/docs/"))

            response.header("X-Content-Type-Options") shouldBe "nosniff"
            response.header("X-Frame-Options") shouldBe "DENY"
        }
    })
