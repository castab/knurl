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
import java.time.Duration
import java.time.Instant

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
 */
class Presigner(
    private val s3Presigner: S3Presigner,
    private val bucketName: String,
    private val ttl: Duration,
) : Closeable {
    fun presignGet(key: String): PresignedMedia {
        val presigned =
            s3Presigner.presignGetObject(
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

    override fun close() = s3Presigner.close()

    companion object {
        fun create(
            settings: S3Settings,
            ttl: Duration,
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

            return Presigner(builder.build(), settings.bucketName, ttl)
        }
    }
}
