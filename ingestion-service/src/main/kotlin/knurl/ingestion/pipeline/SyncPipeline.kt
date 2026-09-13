package knurl.ingestion.pipeline

import knurl.domain.models.AuthConfig
import knurl.domain.models.CatalogUpsert
import knurl.domain.models.EvictionCandidate
import knurl.domain.models.InstagramPostUpsert
import knurl.domain.models.PostMediaItemUpsert
import knurl.domain.repositories.AuthConfigRepository
import knurl.domain.repositories.CatalogRepository
import knurl.domain.repositories.InstagramPostRepository
import knurl.domain.repositories.SyncConfigurationRepository
import knurl.ingestion.client.MediaChild
import knurl.ingestion.client.MediaItem
import knurl.ingestion.client.MetaGraphClient
import knurl.ingestion.processor.MediaProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
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
private val INSTAGRAM_TIMESTAMP_FORMATTER =
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
 * can surface it even before the item is ever selected. Pure/no I/O so it's unit-testable on its own.
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
 * Whether a catalog item's media should be downloaded this cycle: it must be admin-selected, and
 * must not already have been downloaded.
 *
 * The second half is what makes reselecting an item inside its retention grace period free. The
 * post row survives that whole window, so the item is still in [existingIds] and no re-download
 * happens - it simply reappears in the gallery. Only once retention has actually deleted the row
 * does a reselect fetch the media again. Pure/no I/O so it's unit-testable on its own, and factored
 * out precisely because that behaviour is emergent from this condition rather than stated anywhere
 * explicitly - inlined, it would be easy to "simplify" away.
 */
fun shouldDownload(
    mediaId: String,
    selectedIds: Set<String>,
    existingIds: Set<String>,
): Boolean = mediaId in selectedIds && mediaId !in existingIds

private const val TOKEN_REFRESH_THRESHOLD_HOURS = 24L
private const val DEFAULT_RETENTION_DAYS = 30
private const val EVICTION_CONCURRENCY = 2
private const val THUMBNAIL_FETCH_CONCURRENCY = 4

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

/**
 * Orchestrates one ingestion cycle: refresh the Graph API token if it's close to expiring, sync
 * the account's entire media feed into the catalog (metadata only) while additionally downloading
 * and storing any catalog item that's both admin-`selected` and not yet in `instagram_posts`, then
 * run the retention sweep that deletes the media of items deselected longer ago than
 * `retention_days` (or explicitly purge-requested by an admin).
 *
 * Every paginated feed item is upserted into `instagram_media_catalog` unconditionally, regardless
 * of selection state - this is what powers the admin browse-and-select API in presentation-service.
 * Selection itself (`instagram_media_catalog.selected`) is never touched here; it's exclusively
 * set/cleared via `PATCH /api/v1/admin/accounts/{accountId}/catalog/selections`.
 */
class SyncPipeline(
    private val authConfigRepository: AuthConfigRepository,
    private val postRepository: InstagramPostRepository,
    private val catalogRepository: CatalogRepository,
    private val syncConfigurationRepository: SyncConfigurationRepository,
    private val metaGraphClient: MetaGraphClient,
    private val mediaProcessor: MediaProcessor,
    private val s3Client: S3Client,
    private val bucketName: String,
    private val targetUserId: String,
    private val initialAccessToken: String,
) {
    private val log = LoggerFactory.getLogger(SyncPipeline::class.java)

    suspend fun runOnce() {
        val accessToken = ensureFreshToken()
        syncCatalogAndSelectedMedia(accessToken)
        runEviction()
    }

    private fun ensureFreshToken(): String {
        val stored = authConfigRepository.get(targetUserId)
        val nearExpiry =
            stored == null ||
                Instant.now().plusSeconds(TOKEN_REFRESH_THRESHOLD_HOURS * 3600).isAfter(stored.expiresAt)

        if (!nearExpiry) return stored.accessToken

        val tokenToRefresh = stored?.accessToken ?: initialAccessToken
        val refreshed = metaGraphClient.refreshLongLivedToken(tokenToRefresh)
        authConfigRepository.upsert(
            AuthConfig(
                instagramAccountId = targetUserId,
                accessToken = refreshed.accessToken,
                expiresAt = refreshed.expiresAt,
                updatedAt = Instant.now(),
            ),
        )
        return refreshed.accessToken
    }

    private suspend fun syncCatalogAndSelectedMedia(accessToken: String) {
        val existingIds = postRepository.existingMediaIds(targetUserId)
        val selectedIds = catalogRepository.selectedMediaIds(targetUserId)
        val syncedItems = mutableListOf<MediaItem>()
        var cursor: String? = null

        do {
            val page = metaGraphClient.fetchMediaList(targetUserId, accessToken, cursor)
            page.data.forEach { item ->
                syncCatalogEntry(item, selectedIds, existingIds)
                syncedItems += item
            }
            cursor = page.paging?.next?.let { nextUrl -> runCatching { nextUrl.toHttpUrl().queryParameter("after") }.getOrNull() }
        } while (cursor != null)

        // Queried after the loop, not before: a catalog row inserted for the first time by this
        // very cycle's upsert above wouldn't be in a pre-loop snapshot of "needs a thumbnail" yet,
        // and would then sit unthumbnailed for a full cycle before being picked up.
        val needsThumbnailIds = catalogRepository.mediaIdsNeedingThumbnail(targetUserId)
        fetchThumbnailsConcurrently(syncedItems.filter { it.id in needsThumbnailIds })
    }

    /**
     * Fetches thumbnails for [items] with bounded concurrency, same shape as [runEviction]'s
     * `Semaphore` + `Dispatchers.IO` pattern - sequential would make first-sync cycle time scale
     * linearly with catalog size, but unbounded concurrency isn't safe under the heap budget.
     */
    private suspend fun fetchThumbnailsConcurrently(items: List<MediaItem>) {
        if (items.isEmpty()) return

        coroutineScope {
            val semaphore = Semaphore(THUMBNAIL_FETCH_CONCURRENCY)
            items
                .map { item ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            runCatching { fetchAndStoreThumbnail(item) }
                                .onFailure { log.warn("Failed to fetch thumbnail for media ${item.id}; will retry next cycle", it) }
                        }
                    }
                }.awaitAll()
        }
    }

    private fun fetchAndStoreThumbnail(item: MediaItem) {
        val sourceUrl = thumbnailSourceUrl(item) ?: return
        val key = "catalog/${item.id}/thumbnail.webp"
        mediaProcessor.processCatalogThumbnail(sourceUrl, key)
        catalogRepository.updateThumbnailPath(item.id, key)
    }

    /** Upserts catalog metadata unconditionally, then downloads/stores the media if it's selected and not already synced. */
    private fun syncCatalogEntry(
        item: MediaItem,
        selectedIds: Set<String>,
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

        if (shouldDownload(item.id, selectedIds, existingIds)) {
            runCatching { processAndStore(item) }
                .onFailure { log.error("Failed to process media ${item.id}", it) }
        }
    }

    private fun processAndStore(item: MediaItem) {
        val hasNoChildren =
            item.children
                ?.data
                .orEmpty()
                .isEmpty()
        if (item.mediaType == "CAROUSEL_ALBUM" && hasNoChildren) {
            log.warn("CAROUSEL_ALBUM media ${item.id} had no children; falling back to its own media_url")
        }

        val children = resolveChildren(item)
        val notReady =
            children.firstOrNull { child ->
                child.mediaUrl == null || (child.mediaType == "VIDEO" && child.thumbnailUrl == null)
            }
        if (notReady != null) {
            if (notDigestibleReason(item) == NOT_DIGESTIBLE_REASON_COPYRIGHT) {
                log.warn(
                    "Media ${item.id} (${item.mediaProductType ?: item.mediaType}) still missing " +
                        "media_url/thumbnail_url for child ${notReady.id} - Instagram permanently omits this field " +
                        "for media flagged with copyrighted audio (common on Reels); already marked not-digestible " +
                        "in the catalog, skipping",
                )
            } else {
                log.warn(
                    "Media ${item.id} not ready yet (child ${notReady.id} missing media_url/thumbnail_url) - " +
                        "Instagram may still be processing it; will retry on the next sync cycle",
                )
            }
            return
        }

        val mediaItems =
            children.mapIndexed { position, child ->
                val keyPrefix = "posts/${item.id}/$position"

                val (smallPath, largePath, videoPath) =
                    if (child.mediaType == "VIDEO") {
                        val videoKey = "$keyPrefix/original"
                        mediaProcessor.processVideoPassthrough(
                            requireNotNull(child.mediaUrl) { "VIDEO child ${child.id} of media ${item.id} missing media_url" },
                            videoKey,
                        )
                        val thumbnailKeys =
                            mediaProcessor.processThumbnail(
                                requireNotNull(child.thumbnailUrl) {
                                    "VIDEO child ${child.id} of media ${item.id} missing thumbnail_url"
                                },
                                keyPrefix,
                            )
                        Triple(thumbnailKeys.smallKey, thumbnailKeys.largeKey, videoKey as String?)
                    } else {
                        val imageKeys =
                            mediaProcessor.processImage(
                                requireNotNull(child.mediaUrl) { "IMAGE child ${child.id} of media ${item.id} missing media_url" },
                                keyPrefix,
                            )
                        Triple(imageKeys.smallKey, imageKeys.largeKey, null as String?)
                    }

                PostMediaItemUpsert(
                    position = position,
                    mediaType = child.mediaType,
                    smallPath = smallPath,
                    largePath = largePath,
                    videoPath = videoPath,
                )
            }

        postRepository.upsert(
            InstagramPostUpsert(
                instagramAccountId = targetUserId,
                instagramMediaId = item.id,
                mediaType = item.mediaType,
                caption = item.caption,
                permalink = item.permalink,
                timestamp = OffsetDateTime.parse(item.timestamp, INSTAGRAM_TIMESTAMP_FORMATTER).toInstant(),
                mediaItems = mediaItems,
            ),
        )
    }

    /**
     * Deletes the media of posts whose retention clock has run out - deselected longer ago than
     * `retention_days`, or explicitly purge-requested by an admin.
     *
     * Deletion order is DB row first, S3 objects second. A mid-failure then leaves an orphaned S3
     * object, which [knurl.ingestion.pipeline.OrphanSweeper] exists precisely to reap. (The previous
     * S3-first ordering had this exactly backwards: deleting objects first is what leaves a surviving
     * row pointing at media that is already gone.)
     */
    private suspend fun runEviction() {
        val retentionDays = syncConfigurationRepository.getInt("retention_days", DEFAULT_RETENTION_DAYS)
        val cutoff = retentionCutoff(Instant.now(), retentionDays)

        val orphanedPosts = postRepository.countOrphanedPosts(targetUserId)
        if (orphanedPosts > 0) {
            log.warn(
                "$orphanedPosts post(s) for $targetUserId have no catalog row: they can never be shown " +
                    "(the gallery filters on `selected`) nor reaped (retention joins the catalog), so their " +
                    "S3 objects are held indefinitely. This should be unreachable - investigate.",
            )
        }

        val candidates = postRepository.findEvictionCandidates(targetUserId, cutoff)
        if (candidates.isEmpty()) return

        coroutineScope {
            val semaphore = Semaphore(EVICTION_CONCURRENCY)
            candidates
                .map { candidate ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            runCatching { evictOne(candidate) }
                                .onFailure { log.warn("Eviction failed for ${candidate.id}, will retry next cycle", it) }
                        }
                    }
                }.awaitAll()
        }
    }

    private fun evictOne(candidate: EvictionCandidate) {
        // The row delete re-checks selection, so a reselect landing between findEvictionCandidates
        // and here wins: 0 rows means the item is live again and its objects must survive. Bailing
        // out before touching S3 is the whole reason this ordering is row-first.
        if (postRepository.deleteIfNotSelected(candidate.id) == 0) {
            log.info("Eviction candidate ${candidate.id} was reselected mid-cycle; keeping its media")
            return
        }

        // A post with no media rows has nothing to delete from S3, and DeleteObjects rejects an empty
        // object list outright (MalformedXML).
        val objectIds = candidate.mediaPaths.map { ObjectIdentifier.builder().key(it).build() }
        if (objectIds.isEmpty()) {
            log.warn("Eviction candidate ${candidate.id} had no media paths; deleted the row only")
            return
        }

        val deleteResponse =
            s3Client.deleteObjects(
                DeleteObjectsRequest
                    .builder()
                    .bucket(bucketName)
                    .delete(Delete.builder().objects(objectIds).build())
                    .build(),
            )

        check(deleteResponse.errors().isEmpty()) { "S3 delete errors for ${candidate.id}: ${deleteResponse.errors()}" }
    }
}
