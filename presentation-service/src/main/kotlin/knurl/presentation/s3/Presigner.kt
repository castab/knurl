package knurl.presentation.s3

import knurl.domain.config.S3Settings
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.io.Closeable
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A presigned GET URL together with the instant it stops working, as reported by the AWS SDK's
 * own presigned request - not separately recomputed by callers.
 */
data class PresignedMedia(
    val url: String,
    val expiresAt: Instant,
)

/**
 * Wraps [S3Presigner] to produce short-lived, direct-to-bucket GET URLs so clients never proxy
 * asset bytes through this service. Presigning is a pure local signing operation - it needs
 * credentials but never opens a network connection itself.
 *
 * Reuses each key's most recently signed URL for [cacheDuration] instead of resigning on every
 * call. SigV4 embeds the signing timestamp in the query string, so two presigns of the same key
 * moments apart produce two different URLs for the same underlying bytes - a browser's HTTP cache
 * (which keys on the full URL) would then take a guaranteed miss on every gallery reload/navigation
 * even though nothing changed. [cacheDuration] is deliberately much shorter than [ttl] itself: it
 * only controls how often the *URL string* churns, not how long a given URL keeps working.
 */
class Presigner internal constructor(
    private val ttl: Duration,
    private val cacheDuration: Duration,
    private val clock: Clock,
    private val closeable: Closeable,
    private val sign: (String) -> PresignedMedia,
) : Closeable {
    private data class CacheEntry(
        val media: PresignedMedia,
        val cachedAt: Instant,
    )

    // Never cache longer than the URL itself stays valid for - relevant only when a caller passes
    // a [cacheDuration] longer than [ttl] (e.g. a short ttl in a test), which would otherwise let
    // this return an already-expired URL straight out of the cache.
    private val effectiveCacheDuration = minOf(cacheDuration, ttl)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun presignGet(key: String): PresignedMedia {
        val now = clock.instant()

        cache[key]?.let { entry ->
            if (Duration.between(entry.cachedAt, now) < effectiveCacheDuration) return entry.media
        }

        val fresh = sign(key)
        cache[key] = CacheEntry(fresh, now)
        // Opportunistic sweep, run only on a cache miss: bounds memory against keys for media that
        // SyncPipeline's retention eviction has since deleted and which nothing will request again,
        // which would otherwise sit in this map forever. Cheap relative to the miss it rides along
        // with - each pass is O(current cache size), and the cache only ever holds
        // currently-referenced media paths in steady state.
        cache.entries.removeIf { (_, e) -> Duration.between(e.cachedAt, now) > effectiveCacheDuration.multipliedBy(2) }
        return fresh
    }

    override fun close() = closeable.close()

    companion object {
        fun create(
            settings: S3Settings,
            ttl: Duration,
            cacheDuration: Duration = Duration.ofMinutes(15),
            clock: Clock = Clock.systemUTC(),
        ): Presigner {
            val builder =
                S3Presigner
                    .builder()
                    .region(Region.of(settings.region))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(settings.accessKeyId, settings.secretAccessKey),
                        ),
                    ).serviceConfiguration(
                        S3Configuration
                            .builder()
                            .pathStyleAccessEnabled(settings.pathStyleAccess)
                            .build(),
                    )

            settings.endpoint?.let { builder.endpointOverride(URI.create(it)) }

            val s3Presigner = builder.build()

            return Presigner(
                ttl = ttl,
                cacheDuration = cacheDuration,
                clock = clock,
                closeable = Closeable { s3Presigner.close() },
                sign = { key -> s3Presigner.presign(settings.bucketName, key, ttl) },
            )
        }

        private fun S3Presigner.presign(
            bucketName: String,
            key: String,
            ttl: Duration,
        ): PresignedMedia {
            val presigned =
                presignGetObject(
                    GetObjectPresignRequest
                        .builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(
                            GetObjectRequest
                                .builder()
                                .bucket(bucketName)
                                .key(key)
                                .build(),
                        ).build(),
                )
            return PresignedMedia(url = presigned.url().toString(), expiresAt = presigned.expiration())
        }
    }
}
