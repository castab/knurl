package knurl.presentation.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import knurl.domain.config.S3Settings
import knurl.domain.models.InstagramPost
import knurl.domain.models.PostMediaItem
import knurl.presentation.s3.Presigner
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid

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

private fun testMediaItem(
    postId: Uuid,
    position: Int,
    mediaType: String = "IMAGE",
    videoPath: String? = null,
): PostMediaItem =
    PostMediaItem(
        id = Uuid.random(),
        postId = postId,
        position = position,
        mediaType = mediaType,
        smallPath = "posts/media-1/$position/small.webp",
        smallFileSizeBytes = 18_432,
        smallWidth = 400,
        smallHeight = 400,
        largePath = "posts/media-1/$position/large.webp",
        largeFileSizeBytes = 84_736,
        largeWidth = 800,
        largeHeight = 600,
        videoPath = videoPath,
        videoFileSizeBytes = videoPath?.let { 1_048_576 },
        videoWidth = videoPath?.let { 1_920 },
        videoHeight = videoPath?.let { 1_080 },
    )

/**
 * The post's own mediaType is CAROUSEL_ALBUM for more than one item, else it mirrors the single
 * item's own [PostMediaItem.mediaType] - deliberately the only place this post-level aggregate is
 * derived from an item, so every test below can rely on it never leaking back into
 * [GalleryMediaItemResponse.mediaType], which must always carry that item's own value.
 */
private fun testPost(mediaItems: List<PostMediaItem>): InstagramPost {
    val postId = mediaItems.firstOrNull()?.postId ?: Uuid.random()
    return InstagramPost(
        id = postId,
        instagramAccountId = "account-1",
        instagramMediaId = "media-1",
        mediaType = if (mediaItems.size > 1) "CAROUSEL_ALBUM" else mediaItems.single().mediaType,
        caption = "a caption",
        permalink = "https://www.instagram.com/p/abc/",
        timestamp = Instant.parse("2024-01-01T00:00:00Z"),
        mediaItems = mediaItems,
    )
}

/**
 * Confirms signing is always derived from the post's own persisted media items - never a
 * request-supplied value - and that expiry metadata reflects what the Presigner itself reports.
 * The gallery route has no caller-identity check by design (see the plan's Context section), so
 * this is a key-scoping test, not an authorization test.
 */
class GalleryItemMappingTest :
    FunSpec({
        test("signs exactly a single item's own small/large paths, nothing else") {
            val postId = Uuid.random()
            val item = testMediaItem(postId, position = 0)
            val post = testPost(listOf(item))

            val response = post.toResponse(testPresigner())

            response.mediaItems shouldHaveSize 1
            val mediaResponse = response.mediaItems.single()
            mediaResponse.smallUrl shouldContain item.smallPath
            mediaResponse.largeUrl shouldContain item.largePath
            mediaResponse.smallUrl shouldNotContain item.largePath
            mediaResponse.largeUrl shouldNotContain item.smallPath
        }

        test("includes the stored size and dimensions for every rendition") {
            val postId = Uuid.random()
            val item = testMediaItem(postId, position = 0, mediaType = "VIDEO", videoPath = "posts/media-1/0/original")

            val mediaResponse = testPost(listOf(item)).toResponse(testPresigner()).mediaItems.single()

            mediaResponse.smallFileSizeBytes shouldBe item.smallFileSizeBytes
            mediaResponse.smallWidth shouldBe item.smallWidth
            mediaResponse.smallHeight shouldBe item.smallHeight
            mediaResponse.largeFileSizeBytes shouldBe item.largeFileSizeBytes
            mediaResponse.largeWidth shouldBe item.largeWidth
            mediaResponse.largeHeight shouldBe item.largeHeight
            mediaResponse.videoFileSizeBytes shouldBe item.videoFileSizeBytes
            mediaResponse.videoWidth shouldBe item.videoWidth
            mediaResponse.videoHeight shouldBe item.videoHeight
        }

        test("signs the video path when present") {
            val postId = Uuid.random()
            val item = testMediaItem(postId, position = 0, mediaType = "VIDEO", videoPath = "posts/media-1/0/original")
            val post = testPost(listOf(item))

            val response = post.toResponse(testPresigner())

            val mediaResponse = response.mediaItems.single()
            mediaResponse.mediaType shouldBe "VIDEO"
            mediaResponse.videoUrl.shouldNotBeNull()
            mediaResponse.videoUrl shouldContain "posts/media-1/0/original"
        }

        test("leaves videoUrl null when an item has no video path") {
            val postId = Uuid.random()
            val item = testMediaItem(postId, position = 0, videoPath = null)
            val post = testPost(listOf(item))

            val response = post.toResponse(testPresigner())

            val mediaResponse = response.mediaItems.single()
            mediaResponse.mediaType shouldBe "IMAGE"
            mediaResponse.videoUrl.shouldBeNull()
        }

        test("mediaType is copied straight from the stored item, not derived from videoPath") {
            // A video whose download hasn't finished yet can have mediaType VIDEO with a null
            // videoPath - the two fields answer different questions, see PostMediaItem's kdoc.
            val postId = Uuid.random()
            val item = testMediaItem(postId, position = 0, mediaType = "VIDEO", videoPath = null)
            val post = testPost(listOf(item))

            val response = post.toResponse(testPresigner())

            val mediaResponse = response.mediaItems.single()
            mediaResponse.mediaType shouldBe "VIDEO"
            mediaResponse.videoUrl.shouldBeNull()
        }

        test("carousel: signs every media item in position order, independently") {
            val postId = Uuid.random()
            val items =
                listOf(
                    testMediaItem(postId, position = 0),
                    testMediaItem(postId, position = 1, mediaType = "VIDEO", videoPath = "posts/media-1/1/original"),
                    testMediaItem(postId, position = 2),
                )
            val post = testPost(items)
            post.mediaType shouldBe "CAROUSEL_ALBUM"

            val response = post.toResponse(testPresigner())

            response.mediaItems shouldHaveSize 3
            response.mediaItems.forEachIndexed { index, mediaResponse ->
                val item = items[index]
                mediaResponse.smallUrl shouldContain item.smallPath
                mediaResponse.largeUrl shouldContain item.largePath
                // Each item's own mediaType must survive the mapping untouched - never overwritten
                // by the post's aggregate CAROUSEL_ALBUM value.
                mediaResponse.mediaType shouldBe item.mediaType
                if (item.videoPath != null) {
                    mediaResponse.videoUrl.shouldNotBeNull()
                    mediaResponse.videoUrl shouldContain item.videoPath!!
                } else {
                    mediaResponse.videoUrl.shouldBeNull()
                }
            }
        }

        test("carousel: media items are ordered by position regardless of input order") {
            val postId = Uuid.random()
            val inOrder =
                listOf(
                    testMediaItem(postId, position = 0),
                    testMediaItem(postId, position = 1),
                    testMediaItem(postId, position = 2),
                )
            val post = testPost(inOrder.reversed())

            val response = post.toResponse(testPresigner())

            response.mediaItems shouldHaveSize 3
            response.mediaItems.forEachIndexed { index, mediaResponse ->
                mediaResponse.smallUrl shouldContain inOrder[index].smallPath
            }
        }

        test("mediaUrlExpiresAt reflects the configured TTL") {
            val ttl = Duration.ofHours(6)
            val presigner = testPresigner(ttl = ttl)
            val postId = Uuid.random()
            val post = testPost(listOf(testMediaItem(postId, position = 0)))

            val before = Instant.now()
            val response = post.toResponse(presigner)
            val after = Instant.now()

            val expiresAt = Instant.parse(response.mediaItems.single().mediaUrlExpiresAt)
            (expiresAt >= before.plus(ttl).minusSeconds(2)) shouldBe true
            (expiresAt <= after.plus(ttl).plusSeconds(2)) shouldBe true
        }
    })
