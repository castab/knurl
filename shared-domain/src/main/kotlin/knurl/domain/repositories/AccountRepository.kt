package knurl.domain.repositories

import knurl.domain.config.S3Settings
import knurl.domain.models.AccountChange
import knurl.domain.models.AccountRegistration
import knurl.domain.models.AccountStorage
import knurl.domain.models.AccountSummary
import knurl.domain.models.InstagramCredential
import knurl.domain.models.Page
import knurl.domain.models.accountObjectPrefix
import knurl.domain.security.CredentialCipher
import knurl.domain.security.TokenHasher
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import java.net.URI
import java.time.Instant

class AccountConflict(
    message: String,
) : RuntimeException(message)

class AccountUnavailable : RuntimeException("unknown or disabled account")

/** All credential writes encrypt before binding SQL parameters. No boot-time credential writer exists. */
class AccountRepository(
    private val jdbi: Jdbi,
    private val cipher: CredentialCipher,
) {
    fun register(input: AccountRegistration): AccountSummary {
        validateAccount(input)
        return jdbi.inTransaction<AccountSummary, Exception> { h ->
            // Serializes account creation/rotation across both token roles, including simultaneous registrations.
            h.execute("SELECT pg_advisory_xact_lock(59837421)")
            if (find(h, input.accountId) != null) throw AccountConflict("account already exists")
            checkFingerprint(h, input.readToken)
            checkFingerprint(h, input.adminToken)
            if (input.readToken == input.adminToken) throw AccountConflict("tokens must be distinct")
            h
                .createUpdate(
                    """
                    INSERT INTO instagram_accounts
                      (instagram_account_id,read_token,admin_token_encrypted,instagram_access_token_encrypted,
                       instagram_token_expires_at,s3_bucket_name,s3_region,s3_endpoint,s3_access_key_id,
                       s3_secret_access_key_encrypted,s3_path_style_access)
                    VALUES (:id,:read,:admin,:instagram,:expiry,:bucket,:region,:endpoint,:access,:secret,:path)
                    """.trimIndent(),
                ).bind("id", input.accountId)
                .bind("read", input.readToken)
                .bind("admin", cipher.encrypt(input.adminToken))
                .bind("instagram", cipher.encrypt(input.instagramAccessToken))
                .bind("expiry", input.instagramTokenExpiresAt)
                .bind("bucket", input.storage.bucketName)
                .bind("region", input.storage.region)
                .bind("endpoint", input.storage.endpoint)
                .bind("access", input.storage.accessKeyId)
                .bind("secret", cipher.encrypt(input.storage.secretAccessKey))
                .bind("path", input.storage.pathStyleAccess)
                .execute()
            fingerprint(h, input.accountId, "READ", input.readToken)
            fingerprint(h, input.accountId, "ADMIN", input.adminToken)
            checkNotNull(find(h, input.accountId))
        }
    }

    fun update(
        id: String,
        change: AccountChange,
    ): AccountSummary =
        jdbi.inTransaction<AccountSummary, Exception> { h ->
            h.execute("SELECT pg_advisory_xact_lock(59837421)")
            h
                .createQuery("SELECT instagram_account_id FROM instagram_accounts WHERE instagram_account_id=:id FOR UPDATE")
                .bind("id", id)
                .mapTo<String>()
                .findOne()
                .orElseThrow { AccountUnavailable() }
            val old = checkNotNull(find(h, id))
            require((change.instagramAccessToken == null) == (change.instagramTokenExpiresAt == null)) {
                "Instagram token and expiry must be supplied together"
            }
            change.instagramAccessToken?.let {
                validToken(it)
                require(change.instagramTokenExpiresAt!!.isAfter(Instant.now())) { "expiry must be in the future" }
            }
            change.readToken?.let { validAccountToken(it) }
            change.adminToken?.let { validAccountToken(it) }
            // Drop only replaced roles inside the transaction; rollback restores them on any collision.
            if (change.readToken !=
                null
            ) {
                h
                    .createUpdate(
                        "DELETE FROM account_token_fingerprints WHERE instagram_account_id=:id AND role='READ'",
                    ).bind("id", id)
                    .execute()
            }
            if (change.adminToken !=
                null
            ) {
                h
                    .createUpdate(
                        "DELETE FROM account_token_fingerprints WHERE instagram_account_id=:id AND role='ADMIN'",
                    ).bind("id", id)
                    .execute()
            }
            change.readToken?.let {
                checkFingerprint(h, it)
                fingerprint(h, id, "READ", it)
            }
            change.adminToken?.let {
                checkFingerprint(h, it)
                fingerprint(h, id, "ADMIN", it)
            }
            change.storage?.let { s ->
                validateStorage(s)
                val destinationChanged =
                    old.bucketName != s.bucketName || old.region != s.region || old.endpoint != s.endpoint ||
                        old.pathStyleAccess != s.pathStyleAccess
                val busy =
                    h
                        .createQuery(
                            """
                                SELECT storage_used
                            OR EXISTS (SELECT 1 FROM instagram_posts WHERE instagram_account_id=:id)
                            OR EXISTS (SELECT 1 FROM instagram_media_catalog WHERE instagram_account_id=:id AND thumbnail_path IS NOT NULL)
                            OR EXISTS (SELECT 1 FROM ingestion_jobs WHERE instagram_account_id=:id AND status IN ('QUEUED','RUNNING'))
                                FROM instagram_accounts WHERE instagram_account_id=:id
                            """.trimIndent(),
                        ).bind("id", id)
                        .mapTo<Boolean>()
                        .one()
                if (destinationChanged && busy) throw AccountConflict("storage destination is in use; bucket moves are unsupported")
                h
                    .createUpdate(
                        """
                        UPDATE instagram_accounts SET s3_bucket_name=:bucket,s3_region=:region,s3_endpoint=:endpoint,
                        s3_access_key_id=:access,s3_secret_access_key_encrypted=:secret,s3_path_style_access=:path,storage_version=storage_version+1
                        WHERE instagram_account_id=:id
                        """.trimIndent(),
                    ).bind("id", id)
                    .bind("bucket", s.bucketName)
                    .bind("region", s.region)
                    .bind("endpoint", s.endpoint)
                    .bind("access", s.accessKeyId)
                    .bind("secret", cipher.encrypt(s.secretAccessKey))
                    .bind("path", s.pathStyleAccess)
                    .execute()
            }
            h
                .createUpdate(
                    """
                    UPDATE instagram_accounts SET enabled=COALESCE(:enabled,enabled),read_token=COALESCE(:read,read_token),
                    admin_token_encrypted=COALESCE(:admin,admin_token_encrypted),
                    instagram_access_token_encrypted=COALESCE(:instagram,instagram_access_token_encrypted),
                    instagram_token_expires_at=COALESCE(:expiry,instagram_token_expires_at),
                    credential_version=credential_version + :increment,
                    next_run_at=CASE WHEN :enabled=TRUE AND NOT enabled AND schedule_enabled THEN CURRENT_TIMESTAMP ELSE next_run_at END
                    WHERE instagram_account_id=:id
                    """.trimIndent(),
                ).bind("id", id)
                .bind("enabled", change.enabled)
                .bind("read", change.readToken)
                .bind("admin", change.adminToken?.let(cipher::encrypt))
                .bind("instagram", change.instagramAccessToken?.let(cipher::encrypt))
                .bind("expiry", change.instagramTokenExpiresAt)
                .bind("increment", if (change.instagramAccessToken == null) 0 else 1)
                .execute()
            if (change.enabled == false) {
                h
                    .createUpdate("UPDATE ingestion_jobs SET cancel_requested=TRUE WHERE instagram_account_id=:id AND status='RUNNING'")
                    .bind("id", id)
                    .execute()
                h
                    .createUpdate(
                        """
                        UPDATE ingestion_jobs SET status='CANCELLED',phase='CANCELLED',completed_at=CURRENT_TIMESTAMP,error_code='ACCOUNT_DISABLED'
                        WHERE instagram_account_id=:id AND status='QUEUED'
                        """.trimIndent(),
                    ).bind("id", id)
                    .execute()
            }
            checkNotNull(find(h, id))
        }

    fun find(id: String): AccountSummary? = jdbi.withHandle<AccountSummary?, Exception> { find(it, id) }

    fun list(
        size: Int,
        page: Int,
    ): Page<AccountSummary> =
        jdbi.withHandle<Page<AccountSummary>, Exception> { h ->
            Page.of(h.createQuery("SELECT COUNT(*) FROM instagram_accounts").mapTo<Int>().one(), size, page) { offset ->
                h
                    .createQuery("$SUMMARY ORDER BY instagram_account_id LIMIT :limit OFFSET :offset")
                    .bind("limit", size)
                    .bind("offset", offset)
                    .mapTo<AccountSummary>()
                    .list()
            }
        }

    fun verifyAdminToken(
        id: String,
        candidate: String?,
    ): AccountTokenVerification = verify(id, candidate, true)

    fun verifyReadToken(
        id: String,
        candidate: String?,
    ): AccountTokenVerification = verify(id, candidate, false)

    private fun verify(
        id: String,
        candidate: String?,
        admin: Boolean,
    ): AccountTokenVerification =
        jdbi.withHandle<AccountTokenVerification, Exception> { h ->
            val column = if (admin) "admin_token_encrypted" else "read_token"
            val stored =
                h
                    .createQuery("SELECT $column FROM instagram_accounts WHERE instagram_account_id=:id AND enabled")
                    .bind("id", id)
                    .mapTo<String>()
                    .findOne()
                    .orElse(null) ?: return@withHandle AccountTokenVerification.UnknownAccount
            val plaintext = if (admin) cipher.decrypt(stored) else stored
            verifyAccountToken(TokenHasher.sha256(plaintext), candidate)
        }

    fun credential(id: String): InstagramCredential =
        jdbi.withHandle<InstagramCredential, Exception> { h ->
            h
                .createQuery(
                    "SELECT instagram_access_token_encrypted,instagram_token_expires_at,credential_version FROM instagram_accounts WHERE instagram_account_id=:id AND enabled",
                ).bind("id", id)
                .map {
                    rs,
                    _,
                    ->
                    InstagramCredential(cipher.decrypt(rs.getString(1)), rs.getTimestamp(2).toInstant(), rs.getLong(3))
                }.findOne()
                .orElseThrow { AccountUnavailable() }
        }

    fun refreshCredential(
        id: String,
        expectedVersion: Long,
        token: String,
        expiry: Instant,
    ): Boolean =
        jdbi.withHandle<Boolean, Exception> { h ->
            h
                .createUpdate(
                    """
                    UPDATE instagram_accounts SET instagram_access_token_encrypted=:token,instagram_token_expires_at=:expiry,
                    credential_version=credential_version+1 WHERE instagram_account_id=:id AND credential_version=:version AND enabled
                    """.trimIndent(),
                ).bind("id", id)
                .bind("token", cipher.encrypt(token))
                .bind("expiry", expiry)
                .bind("version", expectedVersion)
                .execute() ==
                1
        }

    fun storage(id: String): AccountStorage =
        jdbi.withHandle<AccountStorage, Exception> { h ->
            h
                .createQuery("SELECT * FROM instagram_accounts WHERE instagram_account_id=:id AND enabled")
                .bind("id", id)
                .map { rs, _ ->
                    AccountStorage(
                        id,
                        rs.getLong("storage_version"),
                        S3Settings(
                            bucketName = rs.getString("s3_bucket_name"),
                            region = rs.getString("s3_region"),
                            endpoint = rs.getString("s3_endpoint"),
                            accessKeyId = rs.getString("s3_access_key_id"),
                            secretAccessKey = cipher.decrypt(rs.getString("s3_secret_access_key_encrypted")),
                            pathStyleAccess = rs.getBoolean("s3_path_style_access"),
                        ),
                    )
                }.findOne()
                .orElseThrow { AccountUnavailable() }
        }

    /** Mark before attempting an upload: even a failed request may have left an orphan. */
    fun markStorageUsed(id: String) {
        jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE instagram_accounts SET storage_used=TRUE WHERE instagram_account_id=:id").bind("id", id).execute()
        }
    }

    fun markStorageEmpty(id: String) {
        jdbi.useHandle<Exception> { h ->
            h
                .createUpdate(
                    "UPDATE instagram_accounts SET storage_used=FALSE WHERE instagram_account_id=:id",
                ).bind("id", id)
                .execute()
        }
    }

    fun enabledIds(): List<String> =
        jdbi.withHandle<List<String>, Exception> {
            it
                .createQuery(
                    "SELECT instagram_account_id FROM instagram_accounts WHERE enabled ORDER BY instagram_account_id",
                ).mapTo<String>()
                .list()
        }

    fun claimSweep(
        id: String,
        intervalSeconds: Long,
    ): Boolean =
        jdbi.withHandle<Boolean, Exception> { h ->
            h
                .createUpdate(
                    """UPDATE instagram_accounts SET last_orphan_sweep_at=CURRENT_TIMESTAMP WHERE instagram_account_id=:id AND enabled
            AND (last_orphan_sweep_at IS NULL OR last_orphan_sweep_at < CURRENT_TIMESTAMP - (:seconds * INTERVAL '1 second'))""",
                ).bind("id", id)
                .bind("seconds", intervalSeconds)
                .execute() ==
                1
        }

    private fun find(
        h: Handle,
        id: String,
    ): AccountSummary? =
        h
            .createQuery("$SUMMARY WHERE instagram_account_id=:id")
            .bind("id", id)
            .mapTo<AccountSummary>()
            .findOne()
            .orElse(null)

    private fun checkFingerprint(
        h: Handle,
        token: String,
    ) {
        if (h
                .createQuery(
                    "SELECT EXISTS (SELECT 1 FROM account_token_fingerprints WHERE fingerprint=:hash)",
                ).bind("hash", TokenHasher.sha256(token))
                .mapTo<Boolean>()
                .one()
        ) {
            throw AccountConflict("token already in use")
        }
    }

    private fun fingerprint(
        h: Handle,
        id: String,
        role: String,
        token: String,
    ) {
        h
            .createUpdate(
                "INSERT INTO account_token_fingerprints VALUES (:hash,:id,:role)",
            ).bind("hash", TokenHasher.sha256(token))
            .bind("id", id)
            .bind("role", role)
            .execute()
    }

    companion object {
        private const val SUMMARY = """SELECT instagram_account_id,enabled,read_token,instagram_token_expires_at,
            s3_bucket_name AS bucket_name,s3_region AS region,s3_endpoint AS endpoint,s3_access_key_id AS access_key_id,
            s3_path_style_access AS path_style_access,storage_version,schedule_enabled,interval_seconds,next_run_at,created_at FROM instagram_accounts"""

        fun validToken(value: String) {
            require(value.length in 1..4096 && value.none { it.isWhitespace() || it.isISOControl() }) { "invalid token" }
        }

        /** Caller-chosen read/admin bearer tokens share the management secret's 16-character floor. */
        fun validAccountToken(value: String) {
            validToken(value)
            require(value.length >= MIN_ACCOUNT_TOKEN_LENGTH) { "account token too short" }
        }

        const val MIN_ACCOUNT_TOKEN_LENGTH = 16

        fun validateStorage(s: S3Settings) {
            require(s.bucketName.length in 3..63 && Regex("[a-z0-9][a-z0-9.-]*[a-z0-9]").matches(s.bucketName)) { "invalid bucket name" }
            require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,99}").matches(s.region)) { "invalid region" }
            validToken(s.accessKeyId)
            validToken(s.secretAccessKey)
            s.endpoint?.let {
                val uri = URI.create(it)
                require(
                    uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                        (uri.path.isNullOrEmpty() || uri.path == "/"),
                ) { "invalid S3 endpoint" }
                require(
                    uri.scheme == "https" || (
                        uri.scheme == "http" && uri.host in
                            setOf(
                                "localhost",
                                "127.0.0.1",
                                "[::1]",
                                "::1",
                                "garage",
                            )
                    ),
                ) {
                    "S3 endpoint requires HTTPS except local development"
                }
            }
        }

        private fun validateAccount(i: AccountRegistration) {
            accountObjectPrefix(i.accountId)
            validAccountToken(i.readToken)
            validAccountToken(i.adminToken)
            validToken(i.instagramAccessToken)
            require(i.instagramTokenExpiresAt.isAfter(Instant.now())) { "expiry must be in the future" }
            validateStorage(i.storage)
        }
    }
}
