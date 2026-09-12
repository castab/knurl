package knurl.presentation

import knurl.domain.config.DatabaseSettings
import knurl.domain.config.S3Settings

data class PresentationConfig(
    val database: DatabaseSettings,
    val s3: S3Settings,
    val apiBearerToken: String,
    val port: Int = 8080,
    val presignedGetTtlSeconds: Long = 21600,
    val uiEnabled: Boolean = true,
) {
    init {
        // S3's SigV4 presigned-URL scheme hard-caps X-Amz-Expires at 7 days regardless of
        // credential type; a longer value isn't rejected here by the AWS SDK, only much later by
        // S3 itself (AuthorizationQueryParametersError) the first time a gallery visitor's browser
        // actually requests the URL - by which point this misconfiguration is a confusing runtime
        // 403 instead of a startup failure. Fail fast at config load instead.
        require(presignedGetTtlSeconds in 1..MAX_PRESIGNED_GET_TTL_SECONDS) {
            "presignedGetTtlSeconds must be between 1 and $MAX_PRESIGNED_GET_TTL_SECONDS " +
                "(S3's SigV4 7-day cap on presigned URL expiration), was $presignedGetTtlSeconds"
        }
    }

    companion object {
        private const val MAX_PRESIGNED_GET_TTL_SECONDS = 604_800L
    }
}
