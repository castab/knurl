package knurl.ingestion

import knurl.domain.config.DatabaseSettings
import knurl.domain.config.S3Settings

data class IngestionConfig(
    val database: DatabaseSettings,
    val s3: S3Settings,
    val instagram: InstagramSettings,
    val runOnce: Boolean = false,
    val intervalSeconds: Long = 900,
)

data class InstagramSettings(
    val accessToken: String,
    val businessAccountId: String,
    /**
     * Operator-chosen secret (not an Instagram API credential) gating admin access to this
     * account's catalog on presentation-service. Registered into `instagram_accounts` on every
     * startup - rotate by changing this value and restarting.
     */
    val adminToken: String,
    val apiVersion: String = "v21.0",
)
