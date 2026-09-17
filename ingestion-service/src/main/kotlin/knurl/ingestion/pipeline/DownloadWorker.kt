package knurl.ingestion.pipeline

import knurl.domain.models.InstagramPostUpsert
import knurl.domain.models.PostMediaItemUpsert
import knurl.domain.repositories.DownloadQueueRepository
import knurl.domain.repositories.InstagramPostRepository
import knurl.domain.repositories.PendingDownload
import knurl.ingestion.client.MediaItem
import knurl.ingestion.client.MetaGraphClient
import knurl.ingestion.credentials.InstagramAccessTokenProvider
import knurl.ingestion.processor.MediaProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

/**
 * Whether [DownloadWorker.loop] should stop polling and return rather than sleep and retry.
 *
 * Only true once *both* halves hold: this poll found nothing to claim, and every feed-sync job
 * has completed. The second half is the one easy to get wrong by inlining - see [DownloadWorker.loop]'s
 * doc comment for the exact race an `if (runOnce) return` on an empty claim alone reintroduces:
 * an empty queue on an early poll does not mean there is nothing left to do while a feed-sync
 * worker is still mid-pagination and hasn't enqueued anything yet. Pure/no I/O so it's
 * unit-testable on its own, the same reason [shouldDownload] and [shouldSkipVanishedRemoval] are.
 */
fun shouldStopPolling(
    runOnce: Boolean,
    claimedNothing: Boolean,
    feedSyncJobs: List<Job>,
): Boolean = runOnce && claimedNothing && feedSyncJobs.all { it.isCompleted }

/**
 * Claims and processes items from the account-agnostic `pending_downloads` queue - any account,
 * any process. See [DownloadQueueRepository.claimBatch] for the claim mechanics: a fast
 * claim-and-release statement, not a held transaction, so claimed items in one batch can be
 * processed concurrently and a crashed or failed attempt simply stays `CLAIMED` until its lease
 * expires, indistinguishable by design from a genuine crash - the next claim just retakes it.
 *
 * `processAndStore` lives here rather than in [SyncPipeline] because it now runs on a
 * freshly-fetched [MediaItem] (see [MetaGraphClient.fetchMediaItem]), not the one discovered
 * during feed pagination: Instagram's `media_url`/`thumbnail_url` are short-lived CDN links never
 * persisted anywhere, so a queued item's URLs would likely be stale by the time it's claimed,
 * possibly much later, if this used the original feed-page item instead.
 */
class DownloadWorker(
    private val accessTokenProvider: InstagramAccessTokenProvider,
    private val metaGraphClient: MetaGraphClient,
    private val mediaProcessor: MediaProcessor,
    private val postRepository: InstagramPostRepository,
    private val downloadQueueRepository: DownloadQueueRepository,
) {
    private val log = LoggerFactory.getLogger(DownloadWorker::class.java)

    /**
     * Claims and processes batches until nothing is left to claim; with [runOnce] false, sleeps
     * [pollIntervalMs] and retries instead of returning. Safe to run as several concurrent
     * coroutines, in this process or several others sharing the same database.
     *
     * [feedSyncJobs] matters only when [runOnce] is true: this loop and the feed-sync worker
     * loops all start concurrently, so an empty queue on this loop's very first check does *not*
     * mean there is nothing left to do - a feed-sync worker may simply not have paginated far
     * enough yet to have enqueued anything. Returning immediately in that case would exit this
     * coroutine for good while feed-sync workers are still enqueueing items minutes later, with no
     * download worker left alive to claim them (a real bug this loop used to have: a `RUN_ONCE`
     * run would exit cleanly having stored nothing at all). So while any feed-sync job is still
     * running, an empty claim just means "poll again shortly", exactly like the non-`runOnce`
     * case; only once every feed-sync job has completed - guaranteeing no more items can be
     * enqueued - does an empty claim mean this loop is actually done.
     */
    suspend fun loop(
        batchSize: Int,
        leaseSeconds: Long,
        pollIntervalMs: Long,
        runOnce: Boolean,
        feedSyncJobs: List<Job> = emptyList(),
    ) {
        while (true) {
            val claimed = downloadQueueRepository.claimBatch(batchSize, leaseSeconds)
            if (claimed.isEmpty()) {
                if (shouldStopPolling(runOnce, claimedNothing = true, feedSyncJobs)) return
                delay(pollIntervalMs)
                continue
            }

            coroutineScope {
                claimed
                    .map { item -> async(Dispatchers.IO) { processClaimed(item) } }
                    .awaitAll()
            }
        }
    }

    private suspend fun processClaimed(item: PendingDownload) {
        if (item.previousStatus == "CLAIMED") {
            log.warn(
                "Reclaiming abandoned download for media ${item.instagramMediaId} " +
                    "(account ${item.instagramAccountId}) - a previous worker never completed it",
            )
        }

        if (!downloadQueueRepository.isStillInAnyGallery(item.instagramMediaId)) {
            log.info("Media ${item.instagramMediaId} is no longer in any gallery; skipping download")
            downloadQueueRepository.markComplete(item.instagramAccountId, item.instagramMediaId)
            return
        }

        runCatching {
            val accessToken = accessTokenProvider.getAccessToken(item.instagramAccountId)
            val freshItem = metaGraphClient.fetchMediaItem(item.instagramMediaId, accessToken)
            processAndStore(item.instagramAccountId, freshItem)
            downloadQueueRepository.markComplete(item.instagramAccountId, item.instagramMediaId)
        }.onFailure {
            log.error(
                "Failed to process media ${item.instagramMediaId}; left claimed for another worker to retry " +
                    "once its lease elapses",
                it,
            )
        }
    }

    private fun processAndStore(
        accountId: String,
        item: MediaItem,
    ) {
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
                        "Instagram may still be processing it; will retry once reclaimed after its lease elapses",
                )
            }
            return
        }

        val mediaItems =
            children.mapIndexed { position, child ->
                val keyPrefix = "posts/${item.id}/$position"

                if (child.mediaType == "VIDEO") {
                    val videoKey = "$keyPrefix/original"
                    val video =
                        mediaProcessor.processVideoPassthrough(
                            requireNotNull(child.mediaUrl) {
                                "VIDEO child ${child.id} of media ${item.id} missing media_url"
                            },
                            videoKey,
                        )
                    val thumbnails =
                        mediaProcessor.processThumbnail(
                            requireNotNull(child.thumbnailUrl) {
                                "VIDEO child ${child.id} of media ${item.id} missing thumbnail_url"
                            },
                            keyPrefix,
                        )

                    PostMediaItemUpsert(
                        position = position,
                        mediaType = child.mediaType,
                        smallPath = thumbnails.small.key,
                        smallFileSizeBytes = thumbnails.small.fileSizeBytes,
                        smallWidth = thumbnails.small.width,
                        smallHeight = thumbnails.small.height,
                        largePath = thumbnails.large.key,
                        largeFileSizeBytes = thumbnails.large.fileSizeBytes,
                        largeWidth = thumbnails.large.width,
                        largeHeight = thumbnails.large.height,
                        videoPath = video.key,
                        videoFileSizeBytes = video.fileSizeBytes,
                        videoWidth = video.width,
                        videoHeight = video.height,
                    )
                } else {
                    val images =
                        mediaProcessor.processImage(
                            requireNotNull(child.mediaUrl) { "IMAGE child ${child.id} of media ${item.id} missing media_url" },
                            keyPrefix,
                        )

                    PostMediaItemUpsert(
                        position = position,
                        mediaType = child.mediaType,
                        smallPath = images.small.key,
                        smallFileSizeBytes = images.small.fileSizeBytes,
                        smallWidth = images.small.width,
                        smallHeight = images.small.height,
                        largePath = images.large.key,
                        largeFileSizeBytes = images.large.fileSizeBytes,
                        largeWidth = images.large.width,
                        largeHeight = images.large.height,
                        videoPath = null,
                        videoFileSizeBytes = null,
                        videoWidth = null,
                        videoHeight = null,
                    )
                }
            }

        postRepository.upsert(
            InstagramPostUpsert(
                instagramAccountId = accountId,
                instagramMediaId = item.id,
                mediaType = item.mediaType,
                caption = item.caption,
                permalink = item.permalink,
                timestamp = OffsetDateTime.parse(item.timestamp, INSTAGRAM_TIMESTAMP_FORMATTER).toInstant(),
                mediaItems = mediaItems,
            ),
        )
    }
}
