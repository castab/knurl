package knurl.ingestion.processor

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.webp.WebpWriter
import okhttp3.OkHttpClient
import okhttp3.Request
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit

private const val SMALL_DIMENSION = 400
private const val LARGE_DIMENSION = 800
private const val WEBP_QUALITY = 80

/**
 * Hard ceiling on a source image download before it is decoded. Decoding produces an uncompressed
 * raster roughly `width * height * 4` bytes, so an unbounded source is the most direct route to an OOM
 * under the 128MB heap budget. Instagram's own CDN assets sit far below this.
 */
private const val MAX_SOURCE_IMAGE_BYTES = 32L * 1024 * 1024

/**
 * Hard ceiling on a source video download. Unlike an image, a video is never decoded into memory
 * here - it streams straight to S3 (or, for a chunked/unknown-length response, to a local temp
 * file - see [processVideoPassthrough]) - so this isn't a heap-OOM guard the way
 * [MAX_SOURCE_IMAGE_BYTES] is. It exists for the temp-file branch specifically: without a cap, a
 * misbehaving or malicious upstream response can fill the container's disk indefinitely. 750MiB
 * comfortably covers real Instagram feed/Reels content while still being a bounded ceiling.
 */
private const val MAX_SOURCE_VIDEO_BYTES = 750L * 1024 * 1024

/** Content types this service will store verbatim on a video object; anything else falls back to `video/mp4`. */
private val ALLOWED_VIDEO_CONTENT_TYPES = setOf("video/mp4", "video/quicktime", "video/webm")

data class StoredMediaAsset(
    val key: String,
    val fileSizeBytes: Long,
    val width: Int,
    val height: Int,
)

data class ImageVariants(
    val small: StoredMediaAsset,
    val large: StoredMediaAsset,
)

data class StoredVideoAsset(
    val key: String,
    val fileSizeBytes: Long,
    val width: Int,
    val height: Int,
)

internal data class VideoDimensions(
    val width: Int,
    val height: Int,
)

/** Parses ffprobe's deliberately minimal `width,height` CSV output. */
internal fun parseVideoDimensions(output: String): VideoDimensions {
    val values =
        output
            .trim()
            .lineSequence()
            .lastOrNull()
            ?.split(',') ?: emptyList()
    val width = values.getOrNull(0)?.trim()?.toIntOrNull()
    val height = values.getOrNull(1)?.trim()?.toIntOrNull()
    require(width != null && width > 0 && height != null && height > 0) {
        "ffprobe did not return a valid video width and height: ${output.take(512)}"
    }
    return VideoDimensions(width, height)
}

/**
 * Copies [input] to [output] like [InputStream.copyTo], but aborts once more than [limit] bytes
 * have been read - unlike the known-length streaming branch in [MediaProcessor.processVideoPassthrough],
 * this path has no declared `Content-Length` to check upfront, so the only way to bound it is to
 * count while copying. The caller's own `finally` deletes the partial temp file this leaves behind.
 */
internal fun copyWithLimit(
    input: InputStream,
    output: OutputStream,
    limit: Long,
    sourceUrl: String,
) {
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) {
            throw IOException("Source video at $sourceUrl exceeded the $limit byte download limit")
        }
        output.write(buffer, 0, read)
    }
}

/**
 * Grid geometry: scale until the shorter side fills the box, then center-crop the longer side away.
 * Every grid that consumes a small variant is a fixed 1:1 cell (`aspect-ratio: 1 / 1` in the gallery
 * and admin stylesheets), so a uniform square is what that view actually wants.
 */
internal fun squareVariant(
    image: ImmutableImage,
    dimension: Int,
): ImmutableImage = image.cover(dimension, dimension)

/**
 * Detail-view geometry: scale down until the whole frame fits inside a `dimension` box, preserving
 * the source aspect ratio. Deliberately *not* [squareVariant] - the large variant backs the lightbox's
 * full-detail view, and a center-crop there permanently discarded the top and bottom of every portrait
 * photo (cropping through heads in event shots) before the image was ever stored.
 *
 * `bound` never upscales, so a source smaller than the box is stored at its original size, and it pads
 * nothing - the lightbox already letterboxes via `object-fit: contain`, so baked-in bars would be waste.
 */
internal fun boundedVariant(
    image: ImmutableImage,
    dimension: Int,
): ImmutableImage = image.bound(dimension, dimension)

/**
 * Memory-safe download/upload pipeline. Images are decoded into memory only long enough to produce
 * two compressed WebP variants - a center-cropped square thumbnail and an aspect-preserving detail
 * image (unavoidable for any resize - see AGENTS.md); videos are streamed straight through to S3
 * without ever landing in a byte array.
 */
class MediaProcessor(
    private val okHttpClient: OkHttpClient,
    private val s3Client: S3Client,
    private val bucketName: String,
    /** Defaulted so production always gets the real cap; tests override it to something small. */
    private val maxSourceVideoBytes: Long = MAX_SOURCE_VIDEO_BYTES,
) {
    fun processImage(
        sourceUrl: String,
        keyPrefix: String,
    ): ImageVariants {
        val original = downloadImage(sourceUrl)

        val small = uploadWebp(squareVariant(original, SMALL_DIMENSION), "$keyPrefix/small.webp")
        val large = uploadWebp(boundedVariant(original, LARGE_DIMENSION), "$keyPrefix/large.webp")

        return ImageVariants(small, large)
    }

    fun processThumbnail(
        thumbnailUrl: String,
        keyPrefix: String,
    ): ImageVariants = processImage(thumbnailUrl, keyPrefix)

    /**
     * Cheap catalog-browse thumbnail: same decode/resize/WebP pipeline as [processImage], but
     * produces a single small variant instead of two - used for every catalog item regardless of
     * gallery membership, so an extra 800px variant per
     * item would waste the "cheap" premise of fetching this for the whole feed every cycle.
     */
    fun processCatalogThumbnail(
        sourceUrl: String,
        key: String,
    ) {
        uploadWebp(squareVariant(downloadImage(sourceUrl), SMALL_DIMENSION), key)
    }

    /**
     * Single download-and-decode path for both image entry points. Checks the HTTP status before
     * decoding (a 4xx error body previously reached the decoder and surfaced as a misleading
     * "unsupported format" error) and refuses a source larger than [MAX_SOURCE_IMAGE_BYTES], since the
     * decoded raster is unbounded relative to the compressed download.
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
    ): StoredVideoAsset {
        val request = Request.Builder().url(sourceUrl).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Video download failed (${response.code}) for $sourceUrl")
            }
            val body = response.body ?: throw IOException("Empty video body for $sourceUrl")
            // Allowlisted, not taken verbatim: this value is stored on the S3 object and replayed by every
            // presigned GET, so an unexpected upstream Content-Type would be served from the bucket origin
            // as-is. Images are already pinned to image/webp.
            val upstreamContentType = body.contentType()?.let { "${it.type}/${it.subtype}" }
            val contentType = if (upstreamContentType in ALLOWED_VIDEO_CONTENT_TYPES) upstreamContentType!! else "video/mp4"
            val contentLength = body.contentLength()

            // The known-length branch below already bounds its read to exactly `contentLength`
            // bytes (RequestBody.fromInputStream(input, contentLength) stops there), so this
            // upfront check alone is enough to cover it. Only the unknown-length branch - which
            // streams to a local temp file with no length to stop at - needs its own guard further
            // down, since a declared length can't bound a read that has no declared length.
            if (contentLength > maxSourceVideoBytes) {
                throw IOException(
                    "Source video at $sourceUrl is $contentLength bytes, over the " +
                        "$maxSourceVideoBytes byte download limit",
                )
            }

            val putObjectRequest =
                PutObjectRequest
                    .builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .build()

            val probe = VideoProbe.start()
            try {
                val fileSizeBytes =
                    if (contentLength >= 0) {
                        val input = ProbingInputStream(body.byteStream(), probe)
                        try {
                            s3Client.putObject(putObjectRequest, RequestBody.fromInputStream(input, contentLength))
                            input.byteCount
                        } finally {
                            input.close()
                        }
                    } else {
                        val tempFile = Files.createTempFile("knurl-video-", ".tmp")
                        try {
                            ProbingInputStream(body.byteStream(), probe).use { input ->
                                Files.newOutputStream(tempFile).use { output ->
                                    copyWithLimit(input, output, maxSourceVideoBytes, sourceUrl)
                                }
                            }
                            val size = Files.size(tempFile)
                            s3Client.putObject(putObjectRequest, RequestBody.fromFile(tempFile))
                            size
                        } finally {
                            // Runs even when copyWithLimit throws past the cap, so a rejected
                            // download never leaves a partial file behind.
                            Files.deleteIfExists(tempFile)
                        }
                    }

                val dimensions = probe.finish()
                return StoredVideoAsset(key, fileSizeBytes, dimensions.width, dimensions.height)
            } catch (e: Exception) {
                probe.cancel()
                throw e
            }
        }
    }

    /** Encodes an already-resized raster and stores it; the geometry choice belongs to the caller. */
    private fun uploadWebp(
        image: ImmutableImage,
        key: String,
    ): StoredMediaAsset {
        val bytes = image.bytes(WebpWriter.DEFAULT.withQ(WEBP_QUALITY))
        s3Client.putObject(
            PutObjectRequest
                .builder()
                .bucket(bucketName)
                .key(key)
                .contentType("image/webp")
                .build(),
            RequestBody.fromBytes(bytes),
        )
        return StoredMediaAsset(key, bytes.size.toLong(), image.width, image.height)
    }
}

/**
 * Mirrors bytes already being streamed to S3 into ffprobe's standard input. ffprobe reads the
 * container incrementally, so the JVM retains neither the source video nor a second full copy.
 */
private class ProbingInputStream(
    input: java.io.InputStream,
    private val probe: VideoProbe,
) : FilterInputStream(input) {
    var byteCount: Long = 0
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            probe.writeByte(value)
            byteCount++
        }
        return value
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) {
            probe.write(buffer, offset, count)
            byteCount += count
        }
        return count
    }

    override fun close() {
        try {
            super.close()
        } finally {
            probe.closeInput()
        }
    }
}

private class VideoProbe private constructor(
    private val process: Process,
) {
    private var inputOpen = true

    fun writeByte(value: Int) {
        if (!inputOpen) return
        try {
            process.outputStream.write(value)
        } catch (_: IOException) {
            closeInput()
        }
    }

    fun write(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (!inputOpen) return
        try {
            process.outputStream.write(buffer, offset, length)
        } catch (_: IOException) {
            closeInput()
        }
    }

    fun closeInput() {
        if (!inputOpen) return
        inputOpen = false
        runCatching { process.outputStream.close() }
    }

    fun finish(): VideoDimensions {
        closeInput()
        if (!process.waitFor(PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("ffprobe did not finish within $PROBE_TIMEOUT")
        }

        // This command emits exactly one short CSV line; the bounded read keeps a broken binary
        // from turning probe diagnostics into another unbounded payload path.
        val output = process.inputStream.readNBytes(MAX_PROBE_OUTPUT_BYTES).toString(Charsets.UTF_8)
        if (process.exitValue() != 0) {
            throw IOException("ffprobe failed with exit code ${process.exitValue()}: ${output.take(512)}")
        }
        return parseVideoDimensions(output)
    }

    fun cancel() {
        closeInput()
        process.destroyForcibly()
    }

    companion object {
        private val PROBE_TIMEOUT: Duration = Duration.ofSeconds(30)
        private const val MAX_PROBE_OUTPUT_BYTES = 4_096

        fun start(): VideoProbe =
            VideoProbe(
                ProcessBuilder(
                    "ffprobe",
                    "-v",
                    "error",
                    "-select_streams",
                    "v:0",
                    "-show_entries",
                    "stream=width,height",
                    "-of",
                    "csv=p=0",
                    "pipe:0",
                ).redirectErrorStream(true).start(),
            )
    }
}
