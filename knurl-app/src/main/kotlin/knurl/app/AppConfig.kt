package knurl.app

import knurl.domain.config.DatabaseSettings
import knurl.presentation.ratelimit.RateLimiter
import java.nio.file.Files
import java.nio.file.Path

data class AppConfig(
    val database: DatabaseSettings,
    val credentialEncryptionKey: String,
    val managementToken: String? = null,
    val managementTokenFile: String? = null,
    val port: Int = 8080,
    val uiEnabled: Boolean = false,
    val rateLimitPerMinute: Int = 120,
    val clientIpHeader: String = RateLimiter.DEFAULT_CLIENT_IP_HEADER,
    val presignedGetTtlSeconds: Long = 21600,
    val instagramApiVersion: String = "v21.0",
) {
    init {
        require(clientIpHeader.isEmpty() || RateLimiter.isValidHeaderName(clientIpHeader)) { "invalid CLIENT_IP_HEADER" }
        require(port in 1..65535 && rateLimitPerMinute > 0 && presignedGetTtlSeconds in 1..604800) { "invalid HTTP configuration" }
        require(managementToken.isNullOrBlank() != managementTokenFile.isNullOrBlank()) {
            "configure exactly one of MANAGEMENT_TOKEN or MANAGEMENT_TOKEN_FILE"
        }
    }

    /** Re-read mounted credentials on every use; never capture an obsolete management secret. */
    fun managementSecret(): String {
        val secret =
            if (managementTokenFile != null) {
                Files.newInputStream(Path.of(managementTokenFile)).use { stream ->
                    val bytes = stream.readNBytes(4097)
                    require(bytes.size <= 4096) { "management token file too large" }
                    bytes.toString(Charsets.UTF_8).trim()
                }
            } else {
                checkNotNull(managementToken)
            }
        require(secret.length in 16..4096 && secret.none { it.isWhitespace() || it.isISOControl() }) { "invalid management credential" }
        return secret
    }

    override fun toString(): String = "AppConfig(redacted)"
}
