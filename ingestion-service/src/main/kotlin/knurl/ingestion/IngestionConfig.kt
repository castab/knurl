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
    val intervalSeconds: Long = 900,
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
