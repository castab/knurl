package knurl.ingestion

import knurl.domain.config.CredentialsMode
import knurl.domain.config.CredentialsSettings
import knurl.domain.config.DatabaseSettings
import knurl.domain.config.S3Settings

data class IngestionConfig(
    val database: DatabaseSettings,
    val s3: S3Settings,
    val instagram: InstagramSettings,
    /**
     * Where the Instagram access token comes from. `LOCAL` seeds from `INSTAGRAM_ACCESS_TOKEN` and
     * refreshes against the Graph API, persisting to `auth_config`; `HTTP` fetches an
     * already-valid token from a control plane that owns its refresh entirely. Shared verbatim with
     * `presentation-service`, which reads the same variables to decide where *its* tokens come from.
     */
    val credentials: CredentialsSettings = CredentialsSettings(),
    /**
     * Base64-encoded AES-256 key (32 raw bytes) used by [knurl.domain.security.CredentialCipher]
     * to encrypt the Instagram access token before it is persisted to `auth_config`. Generate one
     * with [knurl.domain.security.CredentialCipher.generateKey].
     *
     * Rotating this key makes any already-stored `access_token_encrypted` value undecryptable
     * (AES-GCM has no partial-key-match fallback) - the next sync cycle fails with a decryption
     * error rather than silently recovering. Delete the account's `auth_config` row (or set
     * `INSTAGRAM_ACCESS_TOKEN` fresh, which `DatabaseInstagramAccessTokenProvider` falls back to
     * only when no row exists) after rotating this key.
     *
     * Null is legitimate under [CredentialsMode.HTTP]: the control plane owns the token, ingestion
     * never writes `auth_config`, and no cipher is ever constructed - so requiring a key there would
     * demand a secret that provably cannot be used. Required under `LOCAL`, enforced below.
     */
    val credentialEncryptionKey: String? = null,
    val runOnce: Boolean = false,
    /** How stale an account's `last_synced_at` must be before it's due for another feed sync. */
    val intervalSeconds: Long = 900,
    /** Concurrent feed-sync worker coroutines, each claiming and syncing one due account at a time. */
    val feedSyncConcurrency: Int = 1,
    /** Concurrent download-worker coroutines, each claiming and processing one batch at a time. */
    val downloadWorkerConcurrency: Int = 1,
    /** Max items one download worker claims per claim statement. */
    val downloadClaimBatchSize: Int = 5,
    /** How long a claimed download is honored before another worker treats it as abandoned. */
    val downloadClaimLeaseSeconds: Long = 300,
    /** How long a claimed account's feed sync is honored before another worker treats it as abandoned. */
    val accountSyncLeaseSeconds: Long = 600,
    /** How long a completed download's queue row is kept for observability before cleanup purges it. */
    val completedDownloadRetentionHours: Long = 24,
    /** Sleep between claim attempts for any worker loop that found nothing to claim. */
    val claimPollIntervalMs: Long = 5000,
) {
    init {
        // Mirrors INSTAGRAM_ACCESS_TOKEN's LOCAL-only requirement (validateCredentialsSettings).
        // Checked here rather than in that free function because this is storage-at-rest config, not
        // a credential *source*: it fires at loadConfigOrThrow, before the datasource opens. Blank
        // matters as much as null - an unset ${?VAR} can arrive as "" through an override layer.
        if (credentials.mode == CredentialsMode.LOCAL) {
            require(!credentialEncryptionKey.isNullOrBlank()) {
                "CREDENTIALS_MODE=LOCAL requires CREDENTIAL_ENCRYPTION_KEY (base64 AES-256 key; " +
                    "generate one with CredentialCipher.generateKey())"
            }
        }
    }
}

data class InstagramSettings(
    /** Seeds `LOCAL` credential mode for [businessAccountId] only; unused and rejected in `HTTP`. */
    val accessToken: String? = null,
    val businessAccountId: String,
    val apiVersion: String = "v21.0",
)
