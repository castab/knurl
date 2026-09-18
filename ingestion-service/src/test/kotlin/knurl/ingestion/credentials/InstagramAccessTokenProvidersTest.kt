package knurl.ingestion.credentials

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import knurl.domain.config.CredentialsHttpSettings
import knurl.domain.config.CredentialsMode
import knurl.domain.config.CredentialsSettings
import knurl.domain.http.CredentialHttpException
import knurl.domain.http.createBearerTokenSource
import knurl.domain.models.AuthConfig
import knurl.domain.repositories.AuthConfigStore
import knurl.ingestion.InstagramSettings
import knurl.ingestion.client.TokenRefreshResult
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val NOW: Instant = Instant.parse("2026-09-13T12:00:00Z")
private val FIXED_CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)

private const val BROKER_URL = "https://credentials.example/v1/credentials"

/** An [AuthConfigStore] holding [stored] for `account-1` and nothing for any other account. */
private fun storeHolding(stored: AuthConfig?): AuthConfigStore =
    mockk<AuthConfigStore>(relaxed = true).also {
        every { it.get(any()) } returns null
        stored?.let { config -> every { it.get(config.instagramAccountId) } returns config }
    }

private fun localSettings(accessToken: String? = "initial-token") =
    InstagramSettings(accessToken = accessToken, businessAccountId = "account-1")

private fun httpCredentials(
    url: String = BROKER_URL,
    token: String? = "job-token",
    tokenFile: String? = null,
    allowPlaintext: Boolean = false,
) = CredentialsSettings(
    mode = CredentialsMode.HTTP,
    http =
        CredentialsHttpSettings(
            url = url,
            authorizationToken = token,
            authorizationTokenFile = tokenFile,
            allowPlaintext = allowPlaintext,
        ),
)

class InstagramAccessTokenProvidersTest :
    FunSpec({
        test("database provider returns an unexpired stored token without refreshing or writing") {
            val stored = AuthConfig("account-1", "stored-token", NOW.plusSeconds(25 * 60 * 60), NOW.minusSeconds(60))
            val store = storeHolding(stored)
            val provider =
                DatabaseInstagramAccessTokenProvider(
                    authConfigStore = store,
                    seedAccountId = "account-1",
                    seedAccessToken = "initial-token",
                    refreshToken = { error("must not refresh") },
                    clock = FIXED_CLOCK,
                )

            provider.getAccessToken("account-1") shouldBe "stored-token"
            verify(exactly = 0) { store.upsert(any()) }
        }

        test("database provider treats exactly 24 hours remaining as unexpired") {
            val store = storeHolding(AuthConfig("account-1", "stored-token", NOW.plusSeconds(24 * 60 * 60), NOW))
            val provider =
                DatabaseInstagramAccessTokenProvider(
                    store,
                    "account-1",
                    "initial-token",
                    { error("must not refresh") },
                    FIXED_CLOCK,
                )

            provider.getAccessToken("account-1") shouldBe "stored-token"
        }

        test("database provider refreshes from the configured seed when no stored token exists") {
            val store = storeHolding(null)
            var refreshedFrom: String? = null
            val refreshedExpiry = NOW.plusSeconds(60 * 24 * 60 * 60)
            val provider =
                DatabaseInstagramAccessTokenProvider(
                    store,
                    "account-1",
                    "initial-token",
                    {
                        refreshedFrom = it
                        TokenRefreshResult("refreshed-token", refreshedExpiry)
                    },
                    FIXED_CLOCK,
                )

            provider.getAccessToken("account-1") shouldBe "refreshed-token"
            refreshedFrom shouldBe "initial-token"

            val written = slot<AuthConfig>()
            verify(exactly = 1) { store.upsert(capture(written)) }
            written.captured shouldBe AuthConfig("account-1", "refreshed-token", refreshedExpiry, NOW)
        }

        test("database provider refreshes a near-expiry stored token instead of the configured seed") {
            val store = storeHolding(AuthConfig("account-1", "stored-token", NOW.plusSeconds(60), NOW.minusSeconds(60)))
            var refreshedFrom: String? = null
            val provider =
                DatabaseInstagramAccessTokenProvider(
                    store,
                    "account-1",
                    "initial-token",
                    {
                        refreshedFrom = it
                        TokenRefreshResult("refreshed-token", NOW.plusSeconds(100_000))
                    },
                    FIXED_CLOCK,
                )

            provider.getAccessToken("account-1") shouldBe "refreshed-token"
            refreshedFrom shouldBe "stored-token"
        }

        test("database provider leaves stored state unchanged when refresh fails") {
            val store = storeHolding(AuthConfig("account-1", "stored-token", NOW.plusSeconds(60), NOW.minusSeconds(60)))
            val provider =
                DatabaseInstagramAccessTokenProvider(
                    store,
                    "account-1",
                    "initial-token",
                    { throw IllegalStateException("refresh failed") },
                    FIXED_CLOCK,
                )

            shouldThrow<IllegalStateException> { provider.getAccessToken("account-1") }
            verify(exactly = 0) { store.upsert(any()) }
        }

        test("database provider resolves a second account independently via its own auth_config row") {
            val store = mockk<AuthConfigStore>(relaxed = true)
            every { store.get("account-1") } returns
                AuthConfig("account-1", "account-1-token", NOW.plusSeconds(25 * 60 * 60), NOW)
            every { store.get("account-2") } returns
                AuthConfig("account-2", "account-2-token", NOW.plusSeconds(25 * 60 * 60), NOW)
            val provider =
                DatabaseInstagramAccessTokenProvider(
                    store,
                    seedAccountId = "account-1",
                    seedAccessToken = "initial-token",
                    refreshToken = { error("must not refresh - both tokens are unexpired") },
                    clock = FIXED_CLOCK,
                )

            provider.getAccessToken("account-1") shouldBe "account-1-token"
            provider.getAccessToken("account-2") shouldBe "account-2-token"
        }

        test("database provider fails clearly for an account with no auth_config row and no matching seed") {
            val provider =
                DatabaseInstagramAccessTokenProvider(
                    storeHolding(null),
                    seedAccountId = "account-1",
                    seedAccessToken = "initial-token",
                    refreshToken = { error("must not refresh") },
                    clock = FIXED_CLOCK,
                )

            shouldThrow<IllegalStateException> { provider.getAccessToken("account-2") }
        }

        test("http provider sends its credential and returns an account-bound token") {
            withBrokerServer { server ->
                server.enqueue(validBrokerResponse())
                val provider = brokerProvider(server, bearerTokenSource = { "job-token" })

                provider.getAccessToken("account-1") shouldBe "instagram-token"
                val request = server.takeRequest()
                request.method shouldBe "GET"
                request.path shouldBe "/v1/credentials?accountId=account-1"
                request.getHeader("Authorization") shouldBe "Bearer job-token"
            }
        }

        test("http provider requests a second account independently with the same bearer token") {
            withBrokerServer { server ->
                server.enqueue(validBrokerResponse())
                server.enqueue(validBrokerResponse(accountId = "account-2"))
                val provider = brokerProvider(server, bearerTokenSource = { "job-token" })

                provider.getAccessToken("account-1") shouldBe "instagram-token"
                provider.getAccessToken("account-2") shouldBe "instagram-token"

                server.takeRequest().path shouldBe "/v1/credentials?accountId=account-1"
                server.takeRequest().path shouldBe "/v1/credentials?accountId=account-2"
            }
        }

        test("http provider never reads or writes auth_config - the credential service owns the token") {
            withBrokerServer { server ->
                server.enqueue(validBrokerResponse())
                val store = mockk<AuthConfigStore>(relaxed = true)

                val provider =
                    createInstagramAccessTokenProvider(
                        instagram = InstagramSettings(businessAccountId = "account-1"),
                        credentials = httpCredentials(url = server.url("/v1/credentials").toString(), allowPlaintext = true),
                        authConfigStore = store,
                        refreshToken = { error("HTTP mode must not refresh through MetaGraphClient") },
                        okHttpClient = OkHttpClient(),
                        clock = FIXED_CLOCK,
                    )
                provider.getAccessToken("account-1") shouldBe "instagram-token"

                verify(exactly = 0) { store.get(any()) }
                verify(exactly = 0) { store.upsert(any()) }
            }
        }

        test("bearer-token file is reread for every request") {
            val tokenFile = Files.createTempFile("knurl-credentials-token-", ".txt")
            try {
                Files.writeString(tokenFile, "first-job-token\n")
                val source = createBearerTokenSource(null, tokenFile.toString())
                withBrokerServer { server ->
                    server.enqueue(validBrokerResponse())
                    server.enqueue(validBrokerResponse())
                    val provider = brokerProvider(server, bearerTokenSource = source)

                    provider.getAccessToken("account-1")
                    Files.writeString(tokenFile, "second-job-token\n")
                    provider.getAccessToken("account-1")

                    server.takeRequest().getHeader("Authorization") shouldBe "Bearer first-job-token"
                    server.takeRequest().getHeader("Authorization") shouldBe "Bearer second-job-token"
                }
            } finally {
                Files.deleteIfExists(tokenFile)
            }
        }

        test("http provider rejects a response for another account") {
            withBrokerServer { server ->
                server.enqueue(validBrokerResponse(accountId = "account-2"))

                shouldThrow<CredentialHttpException> {
                    brokerProvider(server).getAccessToken("account-1")
                }.message shouldBe "Credential endpoint returned credentials for an unexpected Instagram account"
            }
        }

        test("http provider rejects a token expiring within 24 hours") {
            withBrokerServer { server ->
                server.enqueue(validBrokerResponse(expiresAt = NOW.plusSeconds(24 * 60 * 60).toString()))

                shouldThrow<CredentialHttpException> {
                    brokerProvider(server).getAccessToken("account-1")
                }.message shouldBe "Credential endpoint returned a token expiring within 24 hours"
            }
        }

        test("http provider does not expose an error response body") {
            withBrokerServer { server ->
                server.enqueue(MockResponse().setResponseCode(401).setBody("secret-response-body"))

                val exception =
                    shouldThrow<CredentialHttpException> {
                        brokerProvider(server).getAccessToken("account-1")
                    }
                exception.message shouldBe "Credential endpoint returned HTTP 401"
                exception.toString() shouldNotContain "secret-response-body"
            }
        }

        test("http provider does not expose malformed JSON containing a token") {
            withBrokerServer { server ->
                server.enqueue(MockResponse().setBody("{\"accessToken\":\"secret-instagram-token\""))

                val exception =
                    shouldThrow<CredentialHttpException> {
                        brokerProvider(server).getAccessToken("account-1")
                    }
                exception.message shouldBe "Credential endpoint returned malformed JSON"
                exception.toString() shouldNotContain "secret-instagram-token"
            }
        }

        test("http provider rejects a response larger than 16 KiB") {
            withBrokerServer { server ->
                server.enqueue(MockResponse().setBody("x".repeat(16 * 1024 + 1)))

                shouldThrow<CredentialHttpException> {
                    brokerProvider(server).getAccessToken("account-1")
                }.message shouldBe "Credential endpoint response exceeded 16 KiB"
            }
        }

        test("credential URL rejects public plaintext HTTP unless explicitly enabled") {
            shouldThrow<IllegalArgumentException> {
                HttpBrokerInstagramAccessTokenProvider(
                    OkHttpClient(),
                    "http://credentials.internal/v1/credentials",
                    { "job-token" },
                    allowPlaintextHttp = false,
                    clock = FIXED_CLOCK,
                )
            }

            runCatching {
                HttpBrokerInstagramAccessTokenProvider(
                    OkHttpClient(),
                    "http://credentials.internal/v1/credentials",
                    { "job-token" },
                    allowPlaintextHttp = true,
                    clock = FIXED_CLOCK,
                )
            }.isSuccess shouldBe true

            runCatching {
                HttpBrokerInstagramAccessTokenProvider(
                    OkHttpClient(),
                    "http://localhost:8080/v1/credentials",
                    { "job-token" },
                    allowPlaintextHttp = false,
                    clock = FIXED_CLOCK,
                )
            }.isSuccess shouldBe true
        }

        test("HTTP mode requires exactly one bearer-token source") {
            shouldThrow<IllegalArgumentException> {
                validateCredentialsSettings(localSettings(), httpCredentials(token = null))
            }
            shouldThrow<IllegalArgumentException> {
                validateCredentialsSettings(localSettings(), httpCredentials(token = "inline", tokenFile = "token.txt"))
            }
        }

        test("LOCAL is the default mode and requires a seed Instagram token") {
            val store = storeHolding(null)

            createInstagramAccessTokenProvider(
                instagram = localSettings(),
                credentials = CredentialsSettings(),
                authConfigStore = store,
                refreshToken = { TokenRefreshResult("refreshed", NOW.plusSeconds(100_000)) },
                okHttpClient = OkHttpClient(),
                clock = FIXED_CLOCK,
            )::class shouldBe DatabaseInstagramAccessTokenProvider::class

            shouldThrow<IllegalArgumentException> {
                createInstagramAccessTokenProvider(
                    instagram = localSettings(accessToken = null),
                    credentials = CredentialsSettings(),
                    authConfigStore = store,
                    refreshToken = { TokenRefreshResult("refreshed", NOW.plusSeconds(100_000)) },
                    okHttpClient = OkHttpClient(),
                    clock = FIXED_CLOCK,
                )
            }
        }

        test("HTTP mode selects the http provider without a configured Instagram token") {
            val provider =
                createInstagramAccessTokenProvider(
                    instagram = InstagramSettings(businessAccountId = "account-1"),
                    credentials = httpCredentials(),
                    authConfigStore = mockk(relaxed = true),
                    refreshToken = { error("HTTP mode must not refresh through MetaGraphClient") },
                    okHttpClient = OkHttpClient(),
                    clock = FIXED_CLOCK,
                )

            provider::class shouldBe HttpBrokerInstagramAccessTokenProvider::class
        }

        test("validation accepts private-network HTTP only with explicit opt-in") {
            val plaintext = httpCredentials(url = "http://credentials.internal/v1/credentials")

            shouldThrow<IllegalArgumentException> {
                validateCredentialsSettings(localSettings(accessToken = null), plaintext)
            }
            runCatching {
                validateCredentialsSettings(
                    localSettings(accessToken = null),
                    plaintext.copy(http = plaintext.http.copy(allowPlaintext = true)),
                )
            }.isSuccess shouldBe true
        }
    })

private suspend fun withBrokerServer(block: suspend (MockWebServer) -> Unit) {
    val server = MockWebServer()
    server.start()
    try {
        block(server)
    } finally {
        server.shutdown()
    }
}

private fun brokerProvider(
    server: MockWebServer,
    bearerTokenSource: () -> String = { "job-token" },
): HttpBrokerInstagramAccessTokenProvider =
    HttpBrokerInstagramAccessTokenProvider(
        okHttpClient = OkHttpClient(),
        endpointUrl = server.url("/v1/credentials").toString(),
        bearerTokenSource = bearerTokenSource,
        allowPlaintextHttp = true,
        clock = FIXED_CLOCK,
    )

private fun validBrokerResponse(
    accountId: String = "account-1",
    expiresAt: String = NOW.plusSeconds(25 * 60 * 60).toString(),
): MockResponse =
    MockResponse()
        .setHeader("Content-Type", "application/json")
        .setHeader("Cache-Control", "no-store")
        .setBody(
            """
            {
              "instagramAccountId": "$accountId",
              "accessToken": "instagram-token",
              "expiresAt": "$expiresAt"
            }
            """.trimIndent(),
        )
