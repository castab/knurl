package knurl.presentation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import knurl.domain.config.DatabaseSettings
import knurl.domain.config.S3Settings

private fun testConfig(
    presignedGetTtlSeconds: Long = 21_600,
    rateLimitPerMinute: Int = 120,
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
        presentationProvisioningToken = "test-provisioning-token",
        presignedGetTtlSeconds = presignedGetTtlSeconds,
        rateLimitPerMinute = rateLimitPerMinute,
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
    })
