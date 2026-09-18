package knurl.ingestion

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knurl.domain.config.CredentialsMode
import knurl.domain.config.CredentialsSettings
import knurl.domain.config.DatabaseSettings
import knurl.domain.config.S3Settings

// Any non-blank value does: the init block checks presence, and base64/32-byte validation belongs to
// CredentialCipher's own constructor, which nothing here builds.
private const val PRESENT_KEY = "test-encryption-key"

private fun testConfig(
    credentials: CredentialsSettings = CredentialsSettings(),
    credentialEncryptionKey: String? = PRESENT_KEY,
): IngestionConfig =
    IngestionConfig(
        database = DatabaseSettings(url = "postgres://user:pass@localhost:5432/db"),
        s3 =
            S3Settings(
                bucketName = "test-bucket",
                region = "us-east-1",
                accessKeyId = "test-access-key",
                secretAccessKey = "test-secret-key",
            ),
        instagram = InstagramSettings(accessToken = "initial-token", businessAccountId = "account-1"),
        credentials = credentials,
        credentialEncryptionKey = credentialEncryptionKey,
    )

private val HTTP_CREDENTIALS = CredentialsSettings(mode = CredentialsMode.HTTP)

class IngestionConfigTest :
    FunSpec({
        test("LOCAL mode requires CREDENTIAL_ENCRYPTION_KEY") {
            shouldThrow<IllegalArgumentException> { testConfig(credentialEncryptionKey = null) }
            // An unset ${?VAR} can arrive as an empty string rather than a dropped key.
            shouldThrow<IllegalArgumentException> { testConfig(credentialEncryptionKey = "") }
        }

        test("HTTP mode starts with no CREDENTIAL_ENCRYPTION_KEY") {
            val config = testConfig(credentials = HTTP_CREDENTIALS, credentialEncryptionKey = null)

            config.credentialEncryptionKey shouldBe null
        }

        test("HTTP mode ignores a present key rather than failing to start") {
            val config = testConfig(credentials = HTTP_CREDENTIALS, credentialEncryptionKey = PRESENT_KEY)

            config.credentialEncryptionKey shouldBe PRESENT_KEY
        }

        // The dev defaults in config/application-local.conf are the lowest-priority config layer, so
        // anything they supply silently backfills a value the environment was supposed to set. If they
        // ever return to src/main/resources they ship inside the jar and image, and every "required"
        // variable quietly stops being required in a deployment.
        test("dev defaults are not packaged on the classpath") {
            IngestionConfig::class.java.getResource("/application-local.conf") shouldBe null
        }
    })
