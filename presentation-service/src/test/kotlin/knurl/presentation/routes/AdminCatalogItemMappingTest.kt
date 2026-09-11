package knurl.presentation.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import knurl.domain.config.S3Settings
import knurl.domain.models.CatalogEntry
import knurl.presentation.s3.Presigner
import java.time.Duration
import java.time.Instant

private fun testPresigner(ttl: Duration = Duration.ofHours(6)): Presigner =
    Presigner.create(
        S3Settings(
            bucketName = "test-bucket",
            region = "us-east-1",
            endpoint = "http://localhost:9000",
            accessKeyId = "test-access-key",
            secretAccessKey = "test-secret-key",
            pathStyleAccess = true,
        ),
        ttl,
    )

private fun testCatalogEntry(thumbnailPath: String?): CatalogEntry =
    CatalogEntry(
        instagramMediaId = "media-1",
        instagramAccountId = "account-1",
        shortcode = "Cabc123XYZ",
        mediaType = "IMAGE",
        caption = "a caption",
        permalink = "https://www.instagram.com/p/Cabc123XYZ/",
        timestamp = Instant.parse("2024-01-01T00:00:00Z"),
        selected = false,
        notDigestibleReason = null,
        thumbnailPath = thumbnailPath,
        updatedAt = Instant.parse("2024-01-01T00:00:00Z"),
    )

class AdminCatalogItemMappingTest :
    FunSpec({
        test("signs the thumbnail path when present") {
            val entry = testCatalogEntry(thumbnailPath = "catalog/media-1/thumbnail.webp")

            val response = entry.toResponse(testPresigner())

            response.thumbnailUrl.shouldNotBeNull()
            response.thumbnailUrl shouldContain "catalog/media-1/thumbnail.webp"
        }

        test("leaves thumbnailUrl null when no thumbnail has been fetched yet") {
            val entry = testCatalogEntry(thumbnailPath = null)

            val response = entry.toResponse(testPresigner())

            response.thumbnailUrl.shouldBeNull()
        }
    })
