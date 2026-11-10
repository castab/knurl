package knurl.ingestion.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import knurl.ingestion.client.MediaChild
import knurl.ingestion.client.MediaChildren
import knurl.ingestion.client.MediaItem
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private fun testItem(
    mediaType: String,
    children: MediaChildren? = null,
    mediaUrl: String? = "https://cdn.example/media-1.jpg",
    thumbnailUrl: String? = null,
    timestamp: String = "2024-01-01T00:00:00+0000",
): MediaItem =
    MediaItem(
        id = "media-1",
        mediaType = mediaType,
        caption = null,
        permalink = "https://www.instagram.com/p/abc/",
        timestamp = timestamp,
        mediaUrl = mediaUrl,
        thumbnailUrl = thumbnailUrl,
        children = children,
    )

private fun instagramTimestamp(instant: Instant): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ").format(instant.atOffset(java.time.ZoneOffset.UTC))

class SyncPipelineTest :
    FunSpec({
        test("extracts a shortcode from a standard post URL") {
            extractShortcode("https://www.instagram.com/p/Cabc123XYZ/") shouldBe "Cabc123XYZ"
        }

        test("extracts a shortcode from a reel URL without www or trailing slash") {
            extractShortcode("https://instagram.com/reel/Cabc123XYZ") shouldBe "Cabc123XYZ"
        }

        test("extracts a shortcode ignoring a trailing query string") {
            extractShortcode("https://www.instagram.com/p/Cabc123XYZ/?utm_source=ig_web_copy_link") shouldBe "Cabc123XYZ"
        }

        test("returns null for a non-matching URL") {
            extractShortcode("https://example.com/not-instagram") shouldBe null
        }

        test("resolveChildren: a non-carousel item resolves to a single synthetic child from itself") {
            val item = testItem(mediaType = "IMAGE")

            val children = resolveChildren(item)

            children shouldContainExactly
                listOf(MediaChild(id = "media-1", mediaType = "IMAGE", mediaUrl = item.mediaUrl, thumbnailUrl = null))
        }

        test("resolveChildren: a carousel with populated children resolves to those children, in order") {
            val childList =
                listOf(
                    MediaChild(id = "child-1", mediaType = "IMAGE", mediaUrl = "https://cdn.example/1.jpg"),
                    MediaChild(
                        id = "child-2",
                        mediaType = "VIDEO",
                        mediaUrl = "https://cdn.example/2.mp4",
                        thumbnailUrl = "https://cdn.example/2-thumb.jpg",
                    ),
                )
            val item = testItem(mediaType = "CAROUSEL_ALBUM", children = MediaChildren(data = childList))

            resolveChildren(item) shouldContainExactly childList
        }

        test("resolveChildren: a carousel with no children falls back to a single synthetic child") {
            val item = testItem(mediaType = "CAROUSEL_ALBUM", children = MediaChildren(data = emptyList()))

            val children = resolveChildren(item)

            children shouldContainExactly
                listOf(MediaChild(id = "media-1", mediaType = "CAROUSEL_ALBUM", mediaUrl = item.mediaUrl, thumbnailUrl = null))
        }

        test("resolveChildren: a carousel with a null children field falls back to a single synthetic child") {
            val item = testItem(mediaType = "CAROUSEL_ALBUM", children = null)

            val children = resolveChildren(item)

            children shouldContainExactly
                listOf(MediaChild(id = "media-1", mediaType = "CAROUSEL_ALBUM", mediaUrl = item.mediaUrl, thumbnailUrl = null))
        }

        test("notDigestibleReason: null when media_url is present") {
            val item = testItem(mediaType = "IMAGE", mediaUrl = "https://cdn.example/media-1.jpg")

            notDigestibleReason(item) shouldBe null
        }

        test("notDigestibleReason: null for a just-published video still missing media_url - probably still processing") {
            val item =
                testItem(
                    mediaType = "VIDEO",
                    mediaUrl = null,
                    thumbnailUrl = "https://cdn.example/media-1-thumb.jpg",
                    timestamp = instagramTimestamp(Instant.now()),
                )

            notDigestibleReason(item) shouldBe null
        }

        test("notDigestibleReason: copyright once a video has been missing media_url for a long time") {
            val item =
                testItem(
                    mediaType = "VIDEO",
                    mediaUrl = null,
                    thumbnailUrl = "https://cdn.example/media-1-thumb.jpg",
                    timestamp = instagramTimestamp(Instant.now().minusSeconds(60 * 60 * 24)),
                )

            notDigestibleReason(item) shouldBe NOT_DIGESTIBLE_REASON_COPYRIGHT
        }

        test("notDigestibleReason: copyright once an old video is missing thumbnail_url even with media_url present") {
            val item =
                testItem(
                    mediaType = "VIDEO",
                    mediaUrl = "https://cdn.example/media-1.mp4",
                    thumbnailUrl = null,
                    timestamp = instagramTimestamp(Instant.now().minusSeconds(60 * 60 * 24)),
                )

            notDigestibleReason(item) shouldBe NOT_DIGESTIBLE_REASON_COPYRIGHT
        }

        test("thumbnailSourceUrl: IMAGE uses the item's own media_url") {
            val item = testItem(mediaType = "IMAGE", mediaUrl = "https://cdn.example/media-1.jpg")

            thumbnailSourceUrl(item) shouldBe "https://cdn.example/media-1.jpg"
        }

        test("thumbnailSourceUrl: CAROUSEL_ALBUM with children uses the first child's media_url, not the item's own") {
            val childList =
                listOf(
                    MediaChild(id = "child-1", mediaType = "IMAGE", mediaUrl = "https://cdn.example/first-child.jpg"),
                    MediaChild(id = "child-2", mediaType = "IMAGE", mediaUrl = "https://cdn.example/second-child.jpg"),
                )
            val item =
                testItem(
                    mediaType = "CAROUSEL_ALBUM",
                    mediaUrl = "https://cdn.example/should-not-be-used.jpg",
                    children = MediaChildren(data = childList),
                )

            thumbnailSourceUrl(item) shouldBe "https://cdn.example/first-child.jpg"
        }

        test("thumbnailSourceUrl: CAROUSEL_ALBUM with empty children falls back to the item's own media_url") {
            val item =
                testItem(
                    mediaType = "CAROUSEL_ALBUM",
                    mediaUrl = "https://cdn.example/media-1.jpg",
                    children = MediaChildren(data = emptyList()),
                )

            thumbnailSourceUrl(item) shouldBe "https://cdn.example/media-1.jpg"
        }

        test("thumbnailSourceUrl: VIDEO with thumbnail_url set and not copyright-flagged returns it verbatim") {
            val item =
                testItem(
                    mediaType = "VIDEO",
                    mediaUrl = "https://cdn.example/media-1.mp4",
                    thumbnailUrl = "https://cdn.example/media-1-thumb.jpg",
                    timestamp = instagramTimestamp(Instant.now()),
                )

            thumbnailSourceUrl(item) shouldBe "https://cdn.example/media-1-thumb.jpg"
        }

        test("thumbnailSourceUrl: VIDEO flagged copyright returns null") {
            val item =
                testItem(
                    mediaType = "VIDEO",
                    mediaUrl = null,
                    thumbnailUrl = null,
                    timestamp = instagramTimestamp(Instant.now().minusSeconds(60 * 60 * 24)),
                )

            thumbnailSourceUrl(item) shouldBe null
        }

        test("thumbnailSourceUrl: VIDEO not yet ready (recent, missing thumbnail_url) returns null") {
            val item =
                testItem(
                    mediaType = "VIDEO",
                    mediaUrl = null,
                    thumbnailUrl = null,
                    timestamp = instagramTimestamp(Instant.now()),
                )

            thumbnailSourceUrl(item) shouldBe null
        }

        test("thumbnailSourceUrl: CAROUSEL_ALBUM whose first child is a VIDEO uses that child's thumbnail_url, not its mp4") {
            val childList =
                listOf(
                    MediaChild(
                        id = "child-1",
                        mediaType = "VIDEO",
                        mediaUrl = "https://cdn.example/first-child.mp4",
                        thumbnailUrl = "https://cdn.example/first-child-thumb.jpg",
                    ),
                    MediaChild(id = "child-2", mediaType = "IMAGE", mediaUrl = "https://cdn.example/second-child.jpg"),
                )
            val item =
                testItem(
                    mediaType = "CAROUSEL_ALBUM",
                    mediaUrl = "https://cdn.example/should-not-be-used.jpg",
                    children = MediaChildren(data = childList),
                    timestamp = instagramTimestamp(Instant.now()),
                )

            thumbnailSourceUrl(item) shouldBe "https://cdn.example/first-child-thumb.jpg"
        }
        test("retention: a deselection older than the retention window is past the cutoff") {
            val now = Instant.parse("2024-06-01T00:00:00Z")
            val cutoff = retentionCutoff(now, retentionDays = 30)

            now.minus(31, ChronoUnit.DAYS).isBefore(cutoff) shouldBe true
        }

        test("retention: a deselection inside the retention window is not yet past the cutoff") {
            val now = Instant.parse("2024-06-01T00:00:00Z")
            val cutoff = retentionCutoff(now, retentionDays = 30)

            now.minus(29, ChronoUnit.DAYS).isBefore(cutoff) shouldBe false
        }

        test("retention: a zero-day retention makes a deselection deletable immediately") {
            val now = Instant.parse("2024-06-01T00:00:00Z")

            retentionCutoff(now, retentionDays = 0) shouldBe now
        }

        // The mechanism behind "reselecting inside the grace period costs no re-download": the post
        // row survives the whole window, so the item stays in existingIds and this gate stays shut.
        test("download gate: a reselected item that still has its media is not re-downloaded") {
            shouldDownload("media-1", galleryMemberIds = setOf("media-1"), existingIds = setOf("media-1")) shouldBe false
        }

        test("download gate: a selected item whose media was already deleted is downloaded again") {
            shouldDownload("media-1", galleryMemberIds = setOf("media-1"), existingIds = emptySet()) shouldBe true
        }

        test("download gate: a deselected item is never downloaded, media present or not") {
            shouldDownload("media-1", galleryMemberIds = emptySet(), existingIds = emptySet()) shouldBe false
            shouldDownload("media-1", galleryMemberIds = emptySet(), existingIds = setOf("media-1")) shouldBe false
        }
        test("vanished-media guard: at or below the floor, any fraction proceeds") {
            shouldSkipVanishedRemoval(vanishedCount = 5, existingCatalogCount = 5, maxPercent = 50) shouldBe false
            shouldSkipVanishedRemoval(vanishedCount = 3, existingCatalogCount = 3, maxPercent = 50) shouldBe false
        }

        test("vanished-media guard: above the floor, a small fraction proceeds") {
            shouldSkipVanishedRemoval(vanishedCount = 6, existingCatalogCount = 200, maxPercent = 50) shouldBe false
        }

        test("vanished-media guard: above the floor, a fraction over the configured percent is refused") {
            shouldSkipVanishedRemoval(vanishedCount = 51, existingCatalogCount = 100, maxPercent = 50) shouldBe true
        }

        test("vanished-media guard: exactly at the configured percent still proceeds") {
            shouldSkipVanishedRemoval(vanishedCount = 50, existingCatalogCount = 100, maxPercent = 50) shouldBe false
        }

        test("vanished-media guard: an empty existing catalog never trips the guard") {
            shouldSkipVanishedRemoval(vanishedCount = 10, existingCatalogCount = 0, maxPercent = 50) shouldBe false
        }
    })
