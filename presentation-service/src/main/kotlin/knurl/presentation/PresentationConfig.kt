package knurl.presentation

import knurl.domain.config.DatabaseSettings
import knurl.domain.config.S3Settings

data class PresentationConfig(
    val database: DatabaseSettings,
    val s3: S3Settings,
    val port: Int = 8080,
    val presignedGetTtlSeconds: Long = 21600,
    /**
     * Defaults closed, not open: the bundled gallery/admin UI has no login of its own (see
     * README's "Token exposure" note), so a deployment that never explicitly opts in should not
     * silently expose an admin surface. Set `UI_ENABLED=true` to serve it.
     */
    val uiEnabled: Boolean = false,
    /**
     * Requests allowed per client per rolling minute, applied to every route (the public gallery
     * API most of all - `POST .../track` and the gallery reads were unthrottled before this).
     * Keyed by the nearest client IP this service can see; see [knurl.presentation.ratelimit.RateLimiter].
     */
    val rateLimitPerMinute: Int = 120,
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
        require(rateLimitPerMinute > 0) { "rateLimitPerMinute must be positive, was $rateLimitPerMinute" }
    }

    companion object {
        private const val MAX_PRESIGNED_GET_TTL_SECONDS = 604_800L
    }
}
