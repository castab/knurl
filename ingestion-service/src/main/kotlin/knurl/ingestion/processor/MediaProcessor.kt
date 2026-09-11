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

/**
 * Hard ceiling on a source image download before it is decoded. Decoding produces an uncompressed
 * raster roughly `width * height * 4` bytes, so an unbounded source is the most direct route to an OOM
 * under the 128MB heap budget. Instagram's own CDN assets sit far below this.
 * See REMEDIATION-PLAN.md P10.
 */
private const val MAX_SOURCE_IMAGE_BYTES = 32L * 1024 * 1024

/** Content types this service will store verbatim on a video object; anything else falls back to `video/mp4`. */
private val ALLOWED_VIDEO_CONTENT_TYPES = setOf("video/mp4", "video/quicktime", "video/webm")

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
        val original = downloadImage(sourceUrl)

        val smallKey = "$keyPrefix/small.webp"
        val largeKey = "$keyPrefix/large.webp"
        uploadResizedWebp(original, SMALL_DIMENSION, smallKey)
        uploadResizedWebp(original, LARGE_DIMENSION, largeKey)

        return ImageVariantKeys(smallKey, largeKey)
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
        uploadResizedWebp(downloadImage(sourceUrl), SMALL_DIMENSION, key)
    }

    /**
     * Single download-and-decode path for both image entry points. Checks the HTTP status before
     * decoding (a 4xx error body previously reached the decoder and surfaced as a misleading
     * "unsupported format" error) and refuses a source larger than [MAX_SOURCE_IMAGE_BYTES], since the
     * decoded raster is unbounded relative to the compressed download. See REMEDIATION-PLAN.md P10.
     */
    private fun downloadImage(sourceUrl: String): ImmutableImage {
        val request = Request.Builder().url(sourceUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Image download failed (${response.code}) for $sourceUrl")
            }
            val body = response.body ?: throw IOException("Empty image body for $sourceUrl")

            val declaredLength = body.contentLength()
            if (declaredLength > MAX_SOURCE_IMAGE_BYTES) {
                throw IOException(
                    "Source image at $sourceUrl is $declaredLength bytes, over the " +
                        "$MAX_SOURCE_IMAGE_BYTES byte decode limit",
                )
            }

            return ImmutableImage.loader().fromStream(body.byteStream())
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
            // Allowlisted, not taken verbatim: this value is stored on the S3 object and replayed by every
            // presigned GET, so an unexpected upstream Content-Type would be served from the bucket origin
            // as-is. Images are already pinned to image/webp. See REMEDIATION-PLAN.md P20.
            val upstreamContentType = body.contentType()?.let { "${it.type}/${it.subtype}" }
            val contentType = if (upstreamContentType in ALLOWED_VIDEO_CONTENT_TYPES) upstreamContentType!! else "video/mp4"
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
