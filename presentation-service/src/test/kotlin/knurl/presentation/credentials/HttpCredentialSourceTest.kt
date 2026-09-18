package knurl.presentation.credentials

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import knurl.domain.http.CredentialHttpException
import knurl.domain.security.TokenHasher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

private const val BEARER = "pre-shared-secret"

private fun withServer(block: (MockWebServer) -> Unit) {
    val server = MockWebServer()
    server.start()
    try {
        block(server)
    } finally {
        server.shutdown()
    }
}

private fun sourceFor(
    server: MockWebServer,
    bearerTokenSource: () -> String = { BEARER },
) = HttpCredentialSource(
    okHttpClient = OkHttpClient(),
    endpointUrl = server.url("/v1/presentation/credentials").toString(),
    bearerTokenSource = bearerTokenSource,
    allowPlaintext = true,
)

private fun jsonResponse(body: String) =
    MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

class HttpCredentialSourceTest :
    FunSpec({
        test("fetches every account and hashes the plaintext tokens it is given") {
            withServer { server ->
                server.enqueue(
                    jsonResponse(
                        """
                        {"accounts":[
                          {"instagramAccountId":"account-a","adminToken":"admin-a","readToken":"read-a"},
                          {"instagramAccountId":"account-b","adminToken":"admin-b","readToken":"read-b"}
                        ]}
                        """.trimIndent(),
                    ),
                )

                val credentials = sourceFor(server).fetch()

                credentials.keys shouldBe setOf("account-a", "account-b")
                credentials.getValue("account-a").adminTokenHash shouldBe TokenHasher.sha256("admin-a")
                credentials.getValue("account-b").readTokenHash shouldBe TokenHasher.sha256("read-b")
            }
        }

        test("presents the pre-shared secret as a bearer token") {
            withServer { server ->
                server.enqueue(jsonResponse("""{"accounts":[]}"""))

                sourceFor(server).fetch()

                val recorded = server.takeRequest()
                recorded.method shouldBe "GET"
                recorded.path shouldBe "/v1/presentation/credentials"
                recorded.getHeader("Authorization") shouldBe "Bearer $BEARER"
            }
        }

        test("re-reads its bearer token per fetch, so a rotated secret takes effect without a restart") {
            withServer { server ->
                server.enqueue(jsonResponse("""{"accounts":[]}"""))
                server.enqueue(jsonResponse("""{"accounts":[]}"""))
                val tokens = ArrayDeque(listOf("first-token", "second-token"))
                val source = sourceFor(server) { tokens.removeFirst() }

                source.fetch()
                source.fetch()

                server.takeRequest().getHeader("Authorization") shouldBe "Bearer first-token"
                server.takeRequest().getHeader("Authorization") shouldBe "Bearer second-token"
            }
        }

        test("an empty account list is valid - it means this deployment currently serves nobody") {
            withServer { server ->
                server.enqueue(jsonResponse("""{"accounts":[]}"""))

                sourceFor(server).fetch() shouldBe emptyMap()
            }
        }

        test("a non-2xx response fails without echoing the response body") {
            withServer { server ->
                server.enqueue(MockResponse().setResponseCode(401).setBody("token super-secret-value is invalid"))

                val exception = shouldThrow<CredentialHttpException> { sourceFor(server).fetch() }

                exception.message!! shouldNotContain "super-secret-value"
                exception.message shouldBe "Credential endpoint returned HTTP 401"
            }
        }

        test("malformed JSON fails without echoing what was returned") {
            withServer { server ->
                server.enqueue(jsonResponse("""{"accounts":[{"instagramAccountId":"a","adminToken":"leaked-token"}"""))

                val exception = shouldThrow<CredentialHttpException> { sourceFor(server).fetch() }

                exception.message!! shouldNotContain "leaked-token"
                exception.message shouldBe "Credential endpoint returned malformed JSON"
            }
        }

        test("an oversized response is rejected rather than buffered") {
            withServer { server ->
                server.enqueue(jsonResponse("""{"accounts":[],"padding":"${"x".repeat(17 * 1024)}"}"""))

                val exception = shouldThrow<CredentialHttpException> { sourceFor(server).fetch() }

                exception.message shouldBe "Credential endpoint response exceeded 16 KiB"
            }
        }

        test("a blank token in the response is rejected rather than hashed into an accepted credential") {
            withServer { server ->
                server.enqueue(
                    jsonResponse("""{"accounts":[{"instagramAccountId":"a","adminToken":"","readToken":"r"}]}"""),
                )

                shouldThrow<CredentialHttpException> { sourceFor(server).fetch() }
            }
        }

        test("a plaintext non-loopback endpoint is rejected at construction, before any secret is sent") {
            val exception =
                shouldThrow<IllegalArgumentException> {
                    HttpCredentialSource(OkHttpClient(), "http://control-plane.example.com/v1", { BEARER }, false)
                }

            exception.message!! shouldNotContain BEARER
            exception.message!!.contains("HTTPS") shouldBe true
        }

        test("an endpoint carrying userinfo is rejected - the URL would leak into logs") {
            shouldThrow<IllegalArgumentException> {
                HttpCredentialSource(OkHttpClient(), "https://user:pass@control-plane.example.com/v1", { BEARER }, false)
            }
        }

        test("HTTPS needs no plaintext opt-in") {
            HttpCredentialSource(OkHttpClient(), "https://control-plane.example.com/v1", { BEARER }, false)
        }
    })
