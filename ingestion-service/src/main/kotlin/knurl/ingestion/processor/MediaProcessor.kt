package knurl.ingestion.processor

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.webp.WebpWriter
import okhttp3.OkHttpClient
import okhttp3.Request
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.IOException
import java.nio.file.Files

private const val SMALL_DIMENSION = 400
private const val LARGE_DIMENSION = 800
private const val WEBP_QUALITY = 80

data class ImageVariantKeys(
    val smallKey: String,
    val largeKey: String,
)

/**
 * Memory-safe download/upload pipeline. Images are decoded into memory only long enough to
 * produce two compressed WebP variants (unavoidable for any resize - see AGENTS.md); videos are
 * streamed straight through to S3 without ever landing in a byte array.
 */
class MediaProcessor(
    private val okHttpClient: OkHttpClient,
    private val s3Client: S3Client,
    private val bucketName: String,
) {
    fun processImage(
        sourceUrl: String,
        keyPrefix: String,
    ): ImageVariantKeys {
        val request = Request.Builder().url(sourceUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("Empty image body for $sourceUrl")
            val original = ImmutableImage.loader().fromStream(body.byteStream())

            val smallKey = "$keyPrefix/small.webp"
            val largeKey = "$keyPrefix/large.webp"
            uploadResizedWebp(original, SMALL_DIMENSION, smallKey)
            uploadResizedWebp(original, LARGE_DIMENSION, largeKey)

            return ImageVariantKeys(smallKey, largeKey)
        }
    }

    fun processThumbnail(
        thumbnailUrl: String,
        keyPrefix: String,
    ): ImageVariantKeys = processImage(thumbnailUrl, keyPrefix)

    /**
     * Cheap catalog-browse thumbnail: same decode/resize/WebP pipeline as [processImage], but
     * produces a single small variant instead of two - used for every catalog item regardless of
     * [selected][knurl.domain.models.CatalogEntry.selected] state, so an extra 800px variant per
     * item would waste the "cheap" premise of fetching this for the whole feed every cycle.
     */
    fun processCatalogThumbnail(
        sourceUrl: String,
        key: String,
    ) {
        val request = Request.Builder().url(sourceUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("Empty image body for $sourceUrl")
            val original = ImmutableImage.loader().fromStream(body.byteStream())
            uploadResizedWebp(original, SMALL_DIMENSION, key)
        }
    }

    /**
     * True streaming passthrough when the CDN reports `Content-Length`. Falls back to a local
     * temp file (disk, not heap) for chunked/unknown-length responses, since the AWS SDK's
     * streaming `RequestBody` requires a known length up front - this is a deliberate, documented
     * exception to "never buffer full files," not an in-memory buffer.
     */
    fun processVideoPassthrough(
        sourceUrl: String,
        key: String,
    ) {
        val request = Request.Builder().url(sourceUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body ?: throw IOException("Empty video body for $sourceUrl")
            val contentType = body.contentType()?.toString() ?: "video/mp4"
            val contentLength = body.contentLength()

            val putObjectRequest =
                PutObjectRequest
                    .builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .build()

            if (contentLength >= 0) {
                s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(body.byteStream(), contentLength))
            } else {
                val tempFile = Files.createTempFile("knurl-video-", ".tmp")
                try {
                    body.byteStream().use { input ->
                        Files.newOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }
                    s3Client.putObject(putObjectRequest, RequestBody.fromFile(tempFile))
                } finally {
                    Files.deleteIfExists(tempFile)
                }
            }
        }
    }

    private fun uploadResizedWebp(
        image: ImmutableImage,
        dimension: Int,
        key: String,
    ) {
        val bytes = image.cover(dimension, dimension).bytes(WebpWriter.DEFAULT.withQ(WEBP_QUALITY))
        s3Client.putObject(
            PutObjectRequest
                .builder()
                .bucket(bucketName)
                .key(key)
                .contentType("image/webp")
                .build(),
            RequestBody.fromBytes(bytes),
        )
    }
}
