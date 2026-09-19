package knurl.presentation

import knurl.domain.config.CredentialsMode
import knurl.domain.config.CredentialsSettings
import knurl.domain.config.DatabaseSettings
import knurl.domain.config.S3Settings

data class PresentationConfig(
    val database: DatabaseSettings,
    val s3: S3Settings,
    /**
     * Where the per-account admin/read tokens this service accepts come from. `LOCAL` reads
     * [local]; `HTTP` fetches every account's pair from a control plane that owns them, and this
     * service then mints, rotates and decides nothing.
     */
    val credentials: CredentialsSettings = CredentialsSettings(),
    /** The single account served in [CredentialsMode.LOCAL]. Required in that mode, ignored in `HTTP`. */
    val local: LocalCredentials? = null,
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

        // HTTP mode ignores [local] rather than rejecting it. This used to be forced: application-local.conf
        // shipped inside the jar and always supplied dev values for this block, so "the operator set
        // ADMIN_TOKEN" was indistinguishable here from "the dev defaults loaded". That file is now
        // read from disk and never packaged, so a populated block genuinely means the operator set it
        // and rejecting would be possible. It stays a warning deliberately: rejecting is a behaviour
        // change that would stop any existing HTTP deployment still setting these from booting.
        // [knurl.presentation.main] warns when a value is present and ignored.
        if (credentials.mode == CredentialsMode.LOCAL) {
            requireNotNull(local) {
                "CREDENTIALS_MODE=LOCAL requires INSTAGRAM_BUSINESS_ACCOUNT_ID, ADMIN_TOKEN and READ_TOKEN"
            }.validate()
        }
        credentials.validateHttpSettings()
    }

    companion object {
        private const val MAX_PRESIGNED_GET_TTL_SECONDS = 604_800L
    }
}

/**
 * The one account a standalone deployment serves, and the two tokens it accepts for that account.
 *
 * A single pair cannot serve a multi-tenant process, so `LOCAL` mode is deliberately single-account
 * - that is what `HTTP` mode and a control plane are for. [adminToken] grants catalog browse,
 * selection and gallery CRUD; [readToken] grants only gallery reads and is shipped to browsers, so
 * compromising it must never confer admin access.
 */
data class LocalCredentials(
    val instagramBusinessAccountId: String,
    val adminToken: String,
    val readToken: String,
) {
    /**
     * Checked by [PresentationConfig] only under [CredentialsMode.LOCAL], not in an `init` block -
     * config loading constructs this in either mode, and blank values are perfectly legitimate in
     * `HTTP` mode, where nothing ever reads them.
     */
    fun validate() {
        require(instagramBusinessAccountId.isNotBlank()) { "INSTAGRAM_BUSINESS_ACCOUNT_ID must not be blank" }
        require(adminToken.isNotBlank()) { "ADMIN_TOKEN must not be blank" }
        require(readToken.isNotBlank()) { "READ_TOKEN must not be blank" }
        require(adminToken != readToken) { "ADMIN_TOKEN and READ_TOKEN must differ" }
    }

    /** True when any field carries a value, i.e. this block was configured rather than left empty. */
    internal fun isConfigured(): Boolean = instagramBusinessAccountId.isNotBlank() || adminToken.isNotBlank() || readToken.isNotBlank()
}
