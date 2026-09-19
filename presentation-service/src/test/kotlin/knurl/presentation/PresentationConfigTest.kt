package knurl.presentation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knurl.domain.config.CredentialsHttpSettings
import knurl.domain.config.CredentialsMode
import knurl.domain.config.CredentialsSettings
import knurl.domain.config.DatabaseSettings
import knurl.domain.config.S3Settings

private val LOCAL = LocalCredentials("17841457350963368", "admin-token", "read-token")

private fun testConfig(
    presignedGetTtlSeconds: Long = 21_600,
    rateLimitPerMinute: Int = 120,
    credentials: CredentialsSettings = CredentialsSettings(),
    local: LocalCredentials? = LOCAL,
): PresentationConfig =
    PresentationConfig(
        database = DatabaseSettings(url = "postgres://user:pass@localhost:5432/db"),
        s3 =
            S3Settings(
                bucketName = "test-bucket",
                region = "us-east-1",
                accessKeyId = "test-access-key",
                secretAccessKey = "test-secret-key",
            ),
        credentials = credentials,
        local = local,
        presignedGetTtlSeconds = presignedGetTtlSeconds,
        rateLimitPerMinute = rateLimitPerMinute,
    )

private fun httpCredentials(
    url: String = "https://credentials.example/v1/presentation/credentials",
    token: String? = "shared-secret",
    tokenFile: String? = null,
    allowPlaintext: Boolean = false,
) = CredentialsSettings(
    mode = CredentialsMode.HTTP,
    http = CredentialsHttpSettings(url, token, tokenFile, allowPlaintext),
)

/**
 * S3's SigV4 presigned-URL scheme caps X-Amz-Expires at 7 days (604800s) regardless of the
 * requested duration; a value beyond that isn't rejected by the AWS SDK, only later by S3 itself
 * (AuthorizationQueryParametersError) the first time someone's browser requests the URL. These
 * tests confirm that misconfiguration fails fast at config load instead.
 */
class PresentationConfigTest :
    FunSpec({
        test("accepts a TTL within S3's 7-day SigV4 cap") {
            testConfig(presignedGetTtlSeconds = 604_800)
        }

        test("accepts the default TTL") {
            testConfig(presignedGetTtlSeconds = 21_600)
        }

        test("rejects a TTL beyond S3's 7-day SigV4 cap") {
            shouldThrow<IllegalArgumentException> {
                testConfig(presignedGetTtlSeconds = 604_801)
            }
        }

        test("rejects a zero TTL") {
            shouldThrow<IllegalArgumentException> {
                testConfig(presignedGetTtlSeconds = 0)
            }
        }

        test("rejects a negative TTL") {
            shouldThrow<IllegalArgumentException> {
                testConfig(presignedGetTtlSeconds = -1)
            }
        }

        test("accepts a positive rate limit") {
            testConfig(rateLimitPerMinute = 1)
        }

        test("rejects a zero rate limit") {
            shouldThrow<IllegalArgumentException> {
                testConfig(rateLimitPerMinute = 0)
            }
        }

        test("rejects a negative rate limit") {
            shouldThrow<IllegalArgumentException> {
                testConfig(rateLimitPerMinute = -1)
            }
        }

        test("LOCAL mode requires the account id and both tokens") {
            shouldThrow<IllegalArgumentException> {
                testConfig(credentials = CredentialsSettings(mode = CredentialsMode.LOCAL), local = null)
            }
        }

        test("LOCAL mode rejects a blank token") {
            shouldThrow<IllegalArgumentException> { testConfig(local = LOCAL.copy(adminToken = " ")) }
            shouldThrow<IllegalArgumentException> { testConfig(local = LOCAL.copy(readToken = "")) }
            shouldThrow<IllegalArgumentException> { testConfig(local = LOCAL.copy(instagramBusinessAccountId = "")) }
        }

        test("LOCAL mode rejects one value reused for both tokens - the read token is browser-exposed") {
            shouldThrow<IllegalArgumentException> {
                testConfig(local = LOCAL.copy(adminToken = "same", readToken = "same"))
            }
        }

        // Ignored rather than rejected: flipping this would stop any existing HTTP deployment still
        // setting ADMIN_TOKEN/READ_TOKEN from booting. See PresentationConfig's init block.
        test("HTTP mode ignores a populated local block rather than failing to start") {
            testConfig(credentials = httpCredentials(), local = LOCAL)
        }

        test("HTTP mode tolerates blank local values, which is how unset env vars arrive") {
            testConfig(credentials = httpCredentials(), local = LocalCredentials("", "", ""))
        }

        test("accepts HTTP mode with an endpoint and a secret") {
            testConfig(credentials = httpCredentials(), local = null)
        }

        // The dev defaults in config/application-local.conf are the lowest-priority config layer, so
        // anything they supply silently backfills a value the environment was supposed to set. If they
        // ever return to src/main/resources they ship inside the jar and image, and this service starts
        // accepting the checked-in dev ADMIN_TOKEN/READ_TOKEN in a real deployment.
        test("dev defaults are not packaged on the classpath") {
            PresentationConfig::class.java.getResource("/application-local.conf") shouldBe null
        }

        test("HTTP mode requires an endpoint URL") {
            shouldThrow<IllegalArgumentException> {
                testConfig(credentials = httpCredentials(url = ""), local = null)
            }
        }

        test("HTTP mode requires exactly one of the inline token and the token file") {
            shouldThrow<IllegalArgumentException> {
                testConfig(credentials = httpCredentials(token = null), local = null)
            }
            shouldThrow<IllegalArgumentException> {
                testConfig(credentials = httpCredentials(token = "inline", tokenFile = "token.txt"), local = null)
            }
        }

        test("HTTP mode rejects a plaintext endpoint without the explicit opt-in") {
            shouldThrow<IllegalArgumentException> {
                testConfig(credentials = httpCredentials(url = "http://credentials.internal/v1"), local = null)
            }
            testConfig(
                credentials = httpCredentials(url = "http://credentials.internal/v1", allowPlaintext = true),
                local = null,
            )
        }
    })
