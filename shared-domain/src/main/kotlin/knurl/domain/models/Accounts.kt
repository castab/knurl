package knurl.domain.models

import knurl.domain.config.S3Settings
import java.time.Instant

/** Deliberately contains no recoverable secret; safe for management responses. */
data class AccountSummary(
    val instagramAccountId: String,
    val enabled: Boolean,
    val readToken: String,
    val instagramTokenExpiresAt: Instant,
    val bucketName: String,
    val region: String,
    val endpoint: String?,
    val accessKeyId: String,
    val pathStyleAccess: Boolean,
    val storageVersion: Long,
    val scheduleEnabled: Boolean,
    val intervalSeconds: Long,
    val nextRunAt: Instant?,
    val createdAt: Instant,
)

/** Secret-bearing inputs are ordinary classes so generated toString/copy cannot expose them. */
class AccountRegistration(
    val accountId: String,
    val readToken: String,
    val adminToken: String,
    val instagramAccessToken: String,
    val instagramTokenExpiresAt: Instant,
    val storage: S3Settings,
)

class AccountChange(
    val enabled: Boolean? = null,
    val readToken: String? = null,
    val adminToken: String? = null,
    val instagramAccessToken: String? = null,
    val instagramTokenExpiresAt: Instant? = null,
    val storage: S3Settings? = null,
)

class InstagramCredential(
    val accessToken: String,
    val expiresAt: Instant,
    val version: Long,
)

class AccountStorage(
    val accountId: String,
    val version: Long,
    val settings: S3Settings,
)

fun accountObjectPrefix(accountId: String): String {
    require(Regex("[A-Za-z0-9_-]{1,100}").matches(accountId)) { "invalid account id" }
    return "knurl/accounts/$accountId/"
}
