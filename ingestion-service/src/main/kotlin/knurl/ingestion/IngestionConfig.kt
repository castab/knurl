package knurl.ingestion

import knurl.domain.config.DatabaseSettings
import knurl.domain.config.S3Settings

data class IngestionConfig(
    val database: DatabaseSettings,
    val s3: S3Settings,
    val instagram: InstagramSettings,
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
     */
    val credentialEncryptionKey: String,
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
)

data class InstagramSettings(
    val accessToken: String? = null,
    val businessAccountId: String,
    /**
     * Operator-chosen secret (not an Instagram API credential) gating admin access to this
     * account's catalog on presentation-service. Registered into `instagram_accounts` on every
     * startup - rotate by changing this value and restarting.
     */
    val adminToken: String,
    /**
     * Operator-chosen secret (not an Instagram API credential) gating this account's own,
     * lower-privilege public gallery-read API on presentation-service. Distinct from [adminToken]
     * (and, unlike the shared `API_BEARER_TOKEN` this replaced, distinct per account) so a
     * browser-exposed read token for one account can never be replayed to read another's, and
     * compromising it never grants admin access. Registered into `instagram_accounts` on every
     * startup - rotate by changing this value and restarting.
     */
    val readToken: String,
    val apiVersion: String = "v21.0",
    val credentialProvider: InstagramCredentialProviderType = InstagramCredentialProviderType.DATABASE,
    val credentialBroker: CredentialBrokerSettings = CredentialBrokerSettings(),
)

enum class InstagramCredentialProviderType {
    DATABASE,
    HTTP_BROKER,
}

data class CredentialBrokerSettings(
    val url: String? = null,
    val bearerToken: String? = null,
    val bearerTokenFile: String? = null,
    val allowPlaintextHttp: Boolean = false,
)
