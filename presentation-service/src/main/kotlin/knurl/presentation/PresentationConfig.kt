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
)
