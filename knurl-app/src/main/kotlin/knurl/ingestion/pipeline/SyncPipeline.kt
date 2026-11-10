package knurl.ingestion.pipeline

import knurl.domain.models.CatalogUpsert
import knurl.domain.models.accountObjectPrefix
import knurl.domain.repositories.CatalogRepository
import knurl.domain.repositories.DownloadQueueRepository
import knurl.domain.repositories.InstagramPostRepository
import knurl.ingestion.client.MediaChild
import knurl.ingestion.client.MediaItem
import knurl.ingestion.client.MetaGraphClient
import knurl.ingestion.credentials.InstagramAccessTokenProvider
import knurl.ingestion.processor.MediaProcessor
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Matches `instagram.com/p/{shortcode}/` and `instagram.com/reel/{shortcode}/`, with or without
 * `www.`/scheme/trailing slash/query string.
 */
private val SHORTCODE_PATTERN = Regex("""instagram\.com/(?:p|reel)/([A-Za-z0-9_-]+)""")

/**
 * The Graph API returns timestamps like `2020-04-18T10:48:36+0000` - a numeric offset with no
 * colon, which `OffsetDateTime.parse(CharSequence)`'s default ISO_OFFSET_DATE_TIME formatter
 * rejects (it requires `+00:00` or `Z`).
 */
internal val INSTAGRAM_TIMESTAMP_FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.ROOT)

fun extractShortcode(url: String): String? = SHORTCODE_PATTERN.find(url)?.groupValues?.get(1)

/**
 * The children to download for [item]: a `CAROUSEL_ALBUM`'s own `children` list when populated,
 * or a single synthetic child built from the item itself otherwise - an ordinary IMAGE/VIDEO
 * post, or (defensively) a carousel whose `children` came back empty/null, rather than storing
 * zero media for that post. Pure/no I/O so it's unit-testable on its own; [SyncPipeline] is
 * responsible for logging when the fallback path was used for a genuine carousel.
 */
fun resolveChildren(item: MediaItem): List<MediaChild> {
    val children = item.children?.data.orEmpty()
    if (children.isNotEmpty()) return children

    return listOf(
        MediaChild(id = item.id, mediaType = item.mediaType, mediaUrl = item.mediaUrl, thumbnailUrl = item.thumbnailUrl),
    )
}

/** Stored in `instagram_media_catalog.not_digestible_reason` - see [notDigestibleReason]. */
const val NOT_DIGESTIBLE_REASON_COPYRIGHT = "copyright"

/**
 * Instagram normally finishes processing a freshly-published video's `media_url` well within this
 * window. If it's still missing after this long, it's far more likely the permanent omission Meta
 * applies to media flagged for copyrighted audio (common on Reels) than ongoing processing.
 */
private const val MEDIA_URL_LIKELY_PERMANENT_THRESHOLD_HOURS = 6L

/**
 * Null if [item] can be downloaded normally. Non-null (currently only [NOT_DIGESTIBLE_REASON_COPYRIGHT])
 * once a child has been missing `media_url`/`thumbnail_url` for long enough that it's far more likely
 * Meta's permanent copyrighted-audio omission (see the `media_url` field docs on Meta's IG Media
 * reference) than the video still being processed - stored on the catalog row so the admin browse UI
 * can surface it even before the item is ever put in a gallery. Pure/no I/O so it's unit-testable on its own.
 */
fun notDigestibleReason(item: MediaItem): String? {
    val notReady =
        resolveChildren(item).any { child ->
            child.mediaUrl == null || (child.mediaType == "VIDEO" && child.thumbnailUrl == null)
        }
    if (!notReady) return null

    val hoursSincePosted =
        runCatching { OffsetDateTime.parse(item.timestamp, INSTAGRAM_TIMESTAMP_FORMATTER).toInstant() }
            .getOrNull()
            ?.let { Duration.between(it, Instant.now()).toHours() }

    return if (hoursSincePosted != null && hoursSincePosted >= MEDIA_URL_LIKELY_PERMANENT_THRESHOLD_HOURS) {
        NOT_DIGESTIBLE_REASON_COPYRIGHT
    } else {
        null
    }
}

/**
 * The instant before which a deselection is old enough for its media to be deleted.
 *
 * Computed here rather than as a SQL `INTERVAL` so the window is unit-testable without a database -
 * the same reason the other decision helpers in this file are pure top-level functions.
 */
fun retentionCutoff(
    now: Instant,
    retentionDays: Int,
): Instant = now.minus(retentionDays.toLong(), ChronoUnit.DAYS)

/**
 * Whether a catalog item's media should be downloaded this cycle: at least one of the account's
 * galleries must hold it, and it must not already have been downloaded.
 *
 * The second half is what makes re-adding an item to a gallery inside its retention grace period
 * free. The post row survives that whole window, so the item is still in [existingIds] and no
 * re-download happens - it simply reappears. Only once retention has actually deleted the row does
 * adding it back fetch the media again. Pure/no I/O so it's unit-testable on its own, and factored
 * out precisely because that behaviour is emergent from this condition rather than stated anywhere
 * explicitly - inlined, it would be easy to "simplify" away.
 */
fun shouldDownload(
    mediaId: String,
    galleryMemberIds: Set<String>,
    existingIds: Set<String>,
): Boolean = mediaId in galleryMemberIds && mediaId !in existingIds

/**
 * Below this many vanished items, [shouldSkipVanishedRemoval] never refuses to act, however large a
 * fraction of the catalog they are - an account with 3 posts losing all 3 is completely ordinary
 * and would otherwise trip a percentage guard on every genuine deletion of a small catalog.
 */
private const val VANISHED_MEDIA_MIN_COUNT_FLOOR = 5

private const val DEFAULT_VANISHED_MEDIA_MAX_PERCENT = 50

/**
 * Whether a batch of media Instagram's feed no longer returned this cycle is too large a fraction
 * of the account's existing catalog to trust as a genuine deletion, and should be left untouched
 * for investigation instead.
 *
 * Above [VANISHED_MEDIA_MIN_COUNT_FLOOR], removing more than [maxPercent] of the catalog in a
 * single cycle is treated as more likely an incomplete or anomalous API response - rate limiting,
 * a permissions hiccup, a bug that returns a page short - than an account that genuinely deleted
 * most of its own content in the fifteen minutes since the last sync. Pure/no I/O so it's
 * unit-testable on its own; [SyncPipeline] owns reading the configured percent and logging.
 */
fun shouldSkipVanishedRemoval(
    vanishedCount: Int,
    existingCatalogCount: Int,
    maxPercent: Int,
): Boolean {
    if (vanishedCount <= VANISHED_MEDIA_MIN_COUNT_FLOOR) return false
    if (existingCatalogCount <= 0) return false
    return vanishedCount * 100L > existingCatalogCount.toLong() * maxPercent
}

private const val DEFAULT_RETENTION_DAYS = 30

/**
 * Source URL for [item]'s cheap catalog-browse thumbnail, or null if none should be fetched (yet).
 * The decision is made on the *resolved first child*, never on the post's own `mediaType`:
 * [resolveChildren] returns a carousel's real first child, or a synthetic single child standing in for
 * an ordinary image/video post, so both shapes go through one code path. If that child is a video,
 * Meta's own `thumbnail_url` for it is used once the item isn't (or isn't yet known to be) permanently
 * copyright-blocked - reusing [notDigestibleReason] rather than reimplementing its "still processing vs.
 * permanently omitted" distinction. Otherwise the child's `media_url` is the thumbnail source.
 * Pure/no I/O so it's unit-testable on its own.
 */
fun thumbnailSourceUrl(item: MediaItem): String? {
    // Branch on the resolved CHILD's media type, not the post's. A CAROUSEL_ALBUM whose first child is a
    // video previously fell into the else-branch and returned that child's `media_url` - an .mp4 handed
    // to the image decoder, which fails every cycle and re-downloads the whole video each time because
    // `thumbnail_path` never gets set.
    val child = resolveChildren(item).firstOrNull() ?: return null
    return if (child.mediaType == "VIDEO") {
        if (notDigestibleReason(item) == NOT_DIGESTIBLE_REASON_COPYRIGHT) null else child.thumbnailUrl
    } else {
        child.mediaUrl
    }
}

/** Feed enumeration and thumbnail processing; durable orchestration lives in IngestionCoordinator. */
class SyncPipeline(
    private val accessTokenProvider: InstagramAccessTokenProvider,
    private val postRepository: InstagramPostRepository,
    private val catalogRepository: CatalogRepository,
    private val downloadQueueRepository: DownloadQueueRepository,
    private val metaGraphClient: MetaGraphClient,
    private val mediaProcessor: MediaProcessor,
    private val targetUserId: String,
) {
    private val log = LoggerFactory.getLogger(SyncPipeline::class.java)

    suspend fun syncFeed(
        onSeen: (String) -> Unit,
        checkActive: () -> Unit,
    ) {
        val token = accessTokenProvider.getAccessToken(targetUserId)
        val existing = postRepository.existingMediaIds(targetUserId)
        val members = catalogRepository.mediaIdsInAnyGallery(targetUserId)
        val visitedCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            checkActive()
            val page = metaGraphClient.fetchMediaList(targetUserId, token, cursor)
            page.data.forEach { item ->
                checkActive()
                onSeen(item.id)
                syncCatalogEntry(item, members, existing)
            }
            cursor =
                page.paging?.next?.let { next ->
                    next.toHttpUrl().queryParameter("after") ?: throw IllegalStateException("invalid pagination cursor")
                }
            if (cursor != null && !visitedCursors.add(cursor)) throw IllegalStateException("repeated pagination cursor")
        } while (cursor != null)
    }

    suspend fun thumbnail(mediaId: String): Boolean {
        val item = metaGraphClient.fetchMediaItem(mediaId, accessTokenProvider.getAccessToken(targetUserId))
        val source = thumbnailSourceUrl(item) ?: if (notDigestibleReason(item) != null) return false else throw MediaNotReadyException()
        val key = "${accountObjectPrefix(targetUserId)}catalog/${item.id}/thumbnail.webp"
        mediaProcessor.processCatalogThumbnail(source, key)
        catalogRepository.updateThumbnailPath(item.id, key)
        return true
    }

    private fun syncCatalogEntry(
        item: MediaItem,
        galleryMemberIds: Set<String>,
        existingIds: Set<String>,
    ) {
        val shortcode = extractShortcode(item.permalink)
        if (shortcode == null) {
            log.warn("Could not extract a shortcode from permalink for media ${item.id}; skipping catalog upsert: ${item.permalink}")
            return
        }

        // An unparseable timestamp previously propagated all the way out of runOnce and aborted the whole
        // sync cycle, skipping every remaining item in the feed. Skip just this item instead.
        val timestamp =
            runCatching { OffsetDateTime.parse(item.timestamp, INSTAGRAM_TIMESTAMP_FORMATTER).toInstant() }
                .getOrElse {
                    log.warn("Unparseable timestamp '${item.timestamp}' for media ${item.id}; skipping this item", it)
                    return
                }

        val upsertResult =
            catalogRepository.upsert(
                CatalogUpsert(
                    instagramMediaId = item.id,
                    instagramAccountId = targetUserId,
                    shortcode = shortcode,
                    mediaType = item.mediaType,
                    caption = item.caption,
                    permalink = item.permalink,
                    timestamp = timestamp,
                    notDigestibleReason = notDigestibleReason(item),
                ),
            )

        if (upsertResult.removedFromGalleries.isNotEmpty()) {
            log.warn(
                "Media ${item.id} for $targetUserId became non-digestible (likely copyright-flagged by " +
                    "Instagram) and was removed from ${upsertResult.removedFromGalleries.size} curated " +
                    "galler${if (upsertResult.removedFromGalleries.size == 1) "y" else "ies"}: " +
                    upsertResult.removedFromGalleries,
            )
        }

        if (shouldDownload(item.id, galleryMemberIds, existingIds)) {
            downloadQueueRepository.enqueue(targetUserId, item.id)
        }
    }
}
