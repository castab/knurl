package knurl.ingestion.credentials

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import knurl.domain.models.AuthConfig
import knurl.domain.repositories.AuthConfigStore
import knurl.ingestion.CredentialBrokerSettings
import knurl.ingestion.InstagramCredentialProviderType
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

private class FakeAuthConfigStore(
    var stored: AuthConfig? = null,
) : AuthConfigStore {
    val writes = mutableListOf<AuthConfig>()

    override fun get(accountId: String): AuthConfig? = stored?.takeIf { it.instagramAccountId == accountId }

    override fun upsert(config: AuthConfig) {
        writes += config
        stored = config
    }
}

class InstagramAccessTokenProvidersTest :
    FunSpec({
        test("database provider returns an unexpired stored token without refreshing or writing") {
            val stored =
                AuthConfig(
                    instagramAccountId = "account-1",
                    accessToken = "stored-token",
                    expiresAt = NOW.plusSeconds(25 * 60 * 60),
                    updatedAt = NOW.minusSeconds(60),
                )
            val store = FakeAuthConfigStore(stored)
            var refreshCount = 0
            val provider =
                DatabaseInstagramAccessTokenProvider(
                    authConfigStore = store,
                    accountId = "account-1",
                    initialAccessToken = "initial-token",
                    refreshToken = {
                        refreshCount++
                        TokenRefreshResult("unexpected", NOW.plusSeconds(100_000))
                    },
                    clock = FIXED_CLOCK,
                )

            provider.getAccessToken() shouldBe "stored-token"
            refreshCount shouldBe 0
            store.writes shouldContainExactly emptyList()
        }

        test("database provider treats exactly 24 hours remaining as unexpired") {
            val store =
                FakeAuthConfigStore(
                    AuthConfig("account-1", "stored-token", NOW.plusSeconds(24 * 60 * 60), NOW),
                )
            val provider =
                DatabaseInstagramAccessTokenProvider(
                    store,
                    "account-1",
                    "initial-token",
                    { error("must not refresh") },
                    FIXED_CLOCK,
                )

            provider.getAccessToken() shouldBe "stored-token"
        }

        test("database provider refreshes from the configured seed when no stored token exists") {
            val store = FakeAuthConfigStore()
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

            provider.getAccessToken() shouldBe "refreshed-token"
            refreshedFrom shouldBe "initial-token"
            store.writes.shouldContainExactly(
                AuthConfig("account-1", "refreshed-token", refreshedExpiry, NOW),
            )
        }

        test("database provider refreshes a near-expiry stored token instead of the configured seed") {
            val store =
                FakeAuthConfigStore(
                    AuthConfig("account-1", "stored-token", NOW.plusSeconds(60), NOW.minusSeconds(60)),
                )
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

            provider.getAccessToken() shouldBe "refreshed-token"
            refreshedFrom shouldBe "stored-token"
        }

        test("database provider leaves stored state unchanged when refresh fails") {
            val original = AuthConfig("account-1", "stored-token", NOW.plusSeconds(60), NOW.minusSeconds(60))
            val store = FakeAuthConfigStore(original)
            val provider =
                DatabaseInstagramAccessTokenProvider(
                    store,
                    "account-1",
                    "initial-token",
                    { throw IllegalStateException("refresh failed") },
                    FIXED_CLOCK,
                )

            shouldThrow<IllegalStateException> { provider.getAccessToken() }
            store.stored shouldBe original
            store.writes shouldContainExactly emptyList()
        }

        test("broker provider sends its job credential and returns an account-bound token") {
            withBrokerServer { server ->
                server.enqueue(validBrokerResponse())
                val provider = brokerProvider(server, bearerTokenSource = { "job-token" })

                provider.getAccessToken() shouldBe "instagram-token"
                val request = server.takeRequest()
                request.method shouldBe "GET"
                request.path shouldBe "/internal/v1/instagram/access-token"
                request.getHeader("Authorization") shouldBe "Bearer job-token"
            }
        }

        test("broker bearer-token file is reread for every request") {
            val tokenFile = Files.createTempFile("knurl-broker-token-", ".txt")
            try {
                Files.writeString(tokenFile, "first-job-token\n")
                val source =
                    createBrokerBearerTokenSource(
                        CredentialBrokerSettings(bearerTokenFile = tokenFile.toString()),
                    )
                withBrokerServer { server ->
                    server.enqueue(validBrokerResponse())
                    server.enqueue(validBrokerResponse())
                    val provider = brokerProvider(server, bearerTokenSource = source)

                    provider.getAccessToken()
                    Files.writeString(tokenFile, "second-job-token\n")
                    provider.getAccessToken()

                    server.takeRequest().getHeader("Authorization") shouldBe "Bearer first-job-token"
                    server.takeRequest().getHeader("Authorization") shouldBe "Bearer second-job-token"
                }
            } finally {
                Files.deleteIfExists(tokenFile)
            }
        }

        test("broker provider rejects a response for another account") {
            withBrokerServer { server ->
                server.enqueue(validBrokerResponse(accountId = "account-2"))
                val exception =
                    shouldThrow<CredentialBrokerException> {
                        brokerProvider(server).getAccessToken()
                    }

                exception.message shouldBe "Credential broker returned credentials for an unexpected Instagram account"
            }
        }

        test("broker provider rejects a token expiring within 24 hours") {
            withBrokerServer { server ->
                server.enqueue(validBrokerResponse(expiresAt = NOW.plusSeconds(24 * 60 * 60).toString()))

                shouldThrow<CredentialBrokerException> {
                    brokerProvider(server).getAccessToken()
                }.message shouldBe "Credential broker returned a token expiring within 24 hours"
            }
        }

        test("broker provider does not expose an error response body") {
            withBrokerServer { server ->
                server.enqueue(MockResponse().setResponseCode(401).setBody("secret-response-body"))

                val exception =
                    shouldThrow<CredentialBrokerException> {
                        brokerProvider(server).getAccessToken()
                    }
                exception.message shouldBe "Credential broker returned HTTP 401"
                exception.toString() shouldNotContain "secret-response-body"
            }
        }

        test("broker provider does not expose malformed JSON containing a token") {
            withBrokerServer { server ->
                server.enqueue(MockResponse().setBody("{\"accessToken\":\"secret-instagram-token\""))

                val exception =
                    shouldThrow<CredentialBrokerException> {
                        brokerProvider(server).getAccessToken()
                    }
                exception.message shouldBe "Credential broker returned malformed JSON"
                exception.toString() shouldNotContain "secret-instagram-token"
            }
        }

        test("broker provider rejects a response larger than 16 KiB") {
            withBrokerServer { server ->
                server.enqueue(MockResponse().setBody("x".repeat(16 * 1024 + 1)))

                shouldThrow<CredentialBrokerException> {
                    brokerProvider(server).getAccessToken()
                }.message shouldBe "Credential broker response exceeded 16 KiB"
            }
        }

        test("broker URL rejects public plaintext HTTP unless explicitly enabled") {
            shouldThrow<IllegalArgumentException> {
                HttpBrokerInstagramAccessTokenProvider(
                    OkHttpClient(),
                    "http://broker.internal/internal/v1/instagram/access-token",
                    "account-1",
                    { "job-token" },
                    allowPlaintextHttp = false,
                    clock = FIXED_CLOCK,
                )
            }

            runCatching {
                HttpBrokerInstagramAccessTokenProvider(
                    OkHttpClient(),
                    "http://broker.internal/internal/v1/instagram/access-token",
                    "account-1",
                    { "job-token" },
                    allowPlaintextHttp = true,
                    clock = FIXED_CLOCK,
                )
            }.isSuccess shouldBe true

            runCatching {
                HttpBrokerInstagramAccessTokenProvider(
                    OkHttpClient(),
                    "http://localhost:8080/internal/v1/instagram/access-token",
                    "account-1",
                    { "job-token" },
                    allowPlaintextHttp = false,
                    clock = FIXED_CLOCK,
                )
            }.isSuccess shouldBe true
        }

        test("broker auth configuration requires exactly one token source") {
            shouldThrow<IllegalArgumentException> {
                createBrokerBearerTokenSource(CredentialBrokerSettings())
            }
            shouldThrow<IllegalArgumentException> {
                createBrokerBearerTokenSource(
                    CredentialBrokerSettings(bearerToken = "inline", bearerTokenFile = "token.txt"),
                )
            }
        }

        test("provider factory keeps database mode as the default and conditionally requires its seed token") {
            val store = FakeAuthConfigStore()
            val settings =
                InstagramSettings(
                    accessToken = "initial-token",
                    businessAccountId = "account-1",
                    adminToken = "admin-token",
                )

            createInstagramAccessTokenProvider(
                settings,
                store,
                { TokenRefreshResult("refreshed", NOW.plusSeconds(100_000)) },
                OkHttpClient(),
                FIXED_CLOCK,
            )::class shouldBe DatabaseInstagramAccessTokenProvider::class

            shouldThrow<IllegalArgumentException> {
                createInstagramAccessTokenProvider(
                    settings.copy(accessToken = null),
                    store,
                    { TokenRefreshResult("refreshed", NOW.plusSeconds(100_000)) },
                    OkHttpClient(),
                    FIXED_CLOCK,
                )
            }
        }

        test("provider factory selects broker mode without a configured Instagram token") {
            val provider =
                createInstagramAccessTokenProvider(
                    InstagramSettings(
                        businessAccountId = "account-1",
                        adminToken = "admin-token",
                        credentialProvider = InstagramCredentialProviderType.HTTP_BROKER,
                        credentialBroker =
                            CredentialBrokerSettings(
                                url = "https://broker.example/internal/v1/instagram/access-token",
                                bearerToken = "job-token",
                            ),
                    ),
                    FakeAuthConfigStore(),
                    { error("broker mode must not refresh through MetaGraphClient") },
                    OkHttpClient(),
                    FIXED_CLOCK,
                )

            provider::class shouldBe HttpBrokerInstagramAccessTokenProvider::class
        }

        test("credential settings validation accepts private-network HTTP only with explicit opt-in") {
            val brokerSettings =
                InstagramSettings(
                    businessAccountId = "account-1",
                    adminToken = "admin-token",
                    credentialProvider = InstagramCredentialProviderType.HTTP_BROKER,
                    credentialBroker =
                        CredentialBrokerSettings(
                            url = "http://broker.internal/internal/v1/instagram/access-token",
                            bearerToken = "job-token",
                        ),
                )

            shouldThrow<IllegalArgumentException> {
                validateInstagramCredentialSettings(brokerSettings)
            }
            runCatching {
                validateInstagramCredentialSettings(
                    brokerSettings.copy(
                        credentialBroker = brokerSettings.credentialBroker.copy(allowPlaintextHttp = true),
                    ),
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
        endpointUrl = server.url("/internal/v1/instagram/access-token").toString(),
        expectedAccountId = "account-1",
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
