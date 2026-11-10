package knurl.ingestion.pipeline

import knurl.domain.models.InstagramPostUpsert
import knurl.domain.models.PostMediaItemUpsert
import knurl.domain.repositories.DownloadQueueRepository
import knurl.domain.repositories.InstagramPostRepository
import knurl.ingestion.client.MediaItem
import knurl.ingestion.client.MetaGraphClient
import knurl.ingestion.credentials.InstagramAccessTokenProvider
import knurl.ingestion.processor.MediaProcessor
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

class MediaNotReadyException : java.io.IOException("media not ready")

/** Processes one durable job item. The coordinator owns retries and completion accounting. */
class DownloadWorker(
    private val accessTokenProvider: InstagramAccessTokenProvider,
    private val metaGraphClient: MetaGraphClient,
    private val mediaProcessor: MediaProcessor,
    private val postRepository: InstagramPostRepository,
    private val downloadQueueRepository: DownloadQueueRepository,
) {
    private val log = LoggerFactory.getLogger(DownloadWorker::class.java)

    suspend fun process(
        accountId: String,
        mediaId: String,
    ): Boolean {
        if (!downloadQueueRepository.isStillInAnyGallery(accountId, mediaId)) return false
        if (mediaId in postRepository.existingMediaIds(accountId)) return true
        val fresh = metaGraphClient.fetchMediaItem(mediaId, accessTokenProvider.getAccessToken(accountId))
        return processAndStore(accountId, fresh)
    }

    private fun processAndStore(
        accountId: String,
        item: MediaItem,
    ): Boolean {
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
            if (notDigestibleReason(item) != null) return false
            throw MediaNotReadyException()
        }

        val mediaItems =
            children.mapIndexed { position, child ->
                val keyPrefix = "${knurl.domain.models.accountObjectPrefix(accountId)}posts/${item.id}/$position"

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
        return true
    }
}
