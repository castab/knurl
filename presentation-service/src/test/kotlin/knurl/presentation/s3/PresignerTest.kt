package knurl.presentation.s3

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knurl.domain.config.S3Settings
import java.time.Duration
import java.time.Instant

private fun testSettings(): S3Settings =
    S3Settings(
        bucketName = "test-bucket",
        region = "us-east-1",
        endpoint = "http://localhost:9000",
        accessKeyId = "test-access-key",
        secretAccessKey = "test-secret-key",
        pathStyleAccess = true,
    )

private fun amzExpiresOf(url: String): Long =
    Regex("X-Amz-Expires=(\\d+)")
        .find(url)
        ?.groupValues
        ?.get(1)
        ?.toLong()
        ?: error("no X-Amz-Expires parameter in $url")

/**
 * S3Presigner performs signing entirely locally (it never opens a network connection), so these
 * are plain unit tests against dummy credentials/endpoint - no MinIO/S3 required.
 */
class PresignerTest :
    FunSpec({
        test("presigned URL references the requested bucket and key") {
            val presigner = Presigner.create(testSettings(), Duration.ofHours(1))

            val result = presigner.presignGet("posts/abc123/small.webp")

            result.url shouldContain "test-bucket"
            result.url shouldContain "posts/abc123/small.webp"
        }

        test("presigned URL's X-Amz-Expires matches the configured TTL") {
            val ttl = Duration.ofSeconds(21600)
            val presigner = Presigner.create(testSettings(), ttl)

            val result = presigner.presignGet("posts/abc123/small.webp")

            amzExpiresOf(result.url) shouldBe ttl.seconds
        }

        test("reported expiresAt falls within [now, now + ttl]") {
            val ttl = Duration.ofSeconds(3600)
            val presigner = Presigner.create(testSettings(), ttl)

            val before = Instant.now()
            val result = presigner.presignGet("posts/abc123/small.webp")
            val after = Instant.now()

            (result.expiresAt >= before.plus(ttl).minusSeconds(2)) shouldBe true
            (result.expiresAt <= after.plus(ttl).plusSeconds(2)) shouldBe true
        }

        test("different TTLs produce different X-Amz-Expires and expiresAt") {
            val shortLived = Presigner.create(testSettings(), Duration.ofMinutes(15))
            val longLived = Presigner.create(testSettings(), Duration.ofHours(6))

            val shortResult = shortLived.presignGet("posts/abc123/small.webp")
            val longResult = longLived.presignGet("posts/abc123/small.webp")

            amzExpiresOf(longResult.url) shouldBeGreaterThan amzExpiresOf(shortResult.url)
            (longResult.expiresAt.isAfter(shortResult.expiresAt)) shouldBe true
        }
    })
