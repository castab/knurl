package knurl.domain.repositories

import knurl.domain.models.AuthConfig
import knurl.domain.models.CatalogEntry
import knurl.domain.models.CatalogFilter
import knurl.domain.models.CatalogUpsert
import knurl.domain.models.CatalogUpsertResult
import knurl.domain.models.EvictionCandidate
import knurl.domain.models.InstagramPostUpsert
import knurl.domain.models.Page
import knurl.domain.models.PostMediaItem
import knurl.domain.models.PurgeRequestResult
import knurl.domain.models.becameNonDigestible
import knurl.domain.security.CredentialCipher
import knurl.domain.security.TokenHasher
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.bindKotlin
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.statement.Query
import java.time.Instant
import kotlin.uuid.Uuid

/** The outcome of checking a candidate bearer token against one account's stored token hash. */
sealed interface AccountTokenVerification {
    /** No account is registered under this id - safe to reveal, since account ids are public. */
    data object UnknownAccount : AccountTokenVerification

    /** The account exists and the candidate token's hash matches its stored hash. */
    data object Match : AccountTokenVerification

    /** The account exists but the candidate token (or its absence) does not match. */
    data object Mismatch : AccountTokenVerification
}

/**
 * The comparison at the heart of every per-account token check - whether admin or read - kept as a
 * pure function, separate from whatever supplies [storedHash], specifically so it has direct unit
 * test coverage. This function *is* the mechanism that keeps one account's content from being
 * reachable with another account's token.
 *
 * [storedHash] used to come from `instagram_accounts`; it now comes from `presentation-service`'s
 * in-memory credential store, populated either from its own environment or from the control plane.
 * The comparison is unchanged by that move, which is the point of it living here.
 */
fun verifyAccountToken(
    storedHash: String?,
    candidateToken: String?,
): AccountTokenVerification {
    if (storedHash == null) return AccountTokenVerification.UnknownAccount
    if (candidateToken == null) return AccountTokenVerification.Mismatch
    return if (TokenHasher.matches(TokenHasher.sha256(candidateToken), storedHash)) {
        AccountTokenVerification.Match
    } else {
        AccountTokenVerification.Mismatch
    }
}

/**
 * Registry of which Instagram accounts this deployment knows about. Rows carry no credentials:
 * `instagram_accounts` is the foreign-key anchor that `instagram_posts` and `galleries` reference,
 * and the work queue `ingestion-service`'s feed-sync workers claim from. Nothing more.
 *
 * Presentation tokens deliberately do not live here. They used to - `ingestion-service` rewrote
 * both hashes from its own environment on every startup, which silently clobbered whatever a
 * control plane had provisioned each time ingestion restarted. Both services now resolve those
 * tokens from `presentation-service`'s in-memory credential store instead, so [ensureAccount] is
 * the whole of this repository's write surface and it is idempotent by construction.
 */
class AccountRepository(
    private val jdbi: Jdbi,
) {
    /**
     * Anchors [accountId] so rows referencing it can exist, without disturbing an existing row's
     * sync-claim state. Called by `ingestion-service` for its own configured account, and by
     * `presentation-service` for every account a credential fetch returns - which is how an account
     * the control plane knows about becomes one ingestion's workers will claim and sync.
     */
    fun ensureAccount(accountId: String) {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO instagram_accounts (instagram_account_id)
                    VALUES (:accountId)
                    ON CONFLICT (instagram_account_id) DO NOTHING
                    """.trimIndent(),
                ).bind("accountId", accountId)
                .execute()
        }
    }
}

/**
 * Persistence boundary for standalone ingestion's refreshable Instagram access token.
 *
 * `auth_config` has no foreign key to `instagram_accounts` (it's slated to move into its own,
 * separate database owned by a future credentials broker), so nothing at the database level
 * cleans up a row when its account is removed. [delete] is that cleanup primitive - callers
 * implementing account removal are expected to invoke it explicitly, even though no such feature
 * exists yet.
 */
interface AuthConfigStore {
    fun get(accountId: String): AuthConfig?

    fun upsert(config: AuthConfig)

    fun delete(accountId: String)
}

/**
 * [AuthConfig.accessToken] is encrypted with [cipher] going into `access_token_encrypted` and
 * decrypted coming back out, transparently to every caller - [AuthConfig] itself always carries
 * the plaintext token in memory. See [CredentialCipher]'s doc
 * comment for why this token is encrypted rather than hashed like the admin token.
 */
class AuthConfigRepository(
    private val jdbi: Jdbi,
    private val cipher: CredentialCipher,
) : AuthConfigStore {
    override fun get(accountId: String): AuthConfig? =
        jdbi.withHandle<AuthConfig?, Exception> { handle ->
            handle
                .createQuery(
                    """
                    SELECT instagram_account_id, access_token_encrypted AS access_token, expires_at, updated_at
                    FROM auth_config WHERE instagram_account_id = :accountId
                    """.trimIndent(),
                ).bind("accountId", accountId)
                .mapTo<AuthConfig>()
                .findFirst()
                .orElse(null)
                ?.let { it.copy(accessToken = cipher.decrypt(it.accessToken)) }
        }

    override fun upsert(config: AuthConfig) {
        val toStore = config.copy(accessToken = cipher.encrypt(config.accessToken))
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO auth_config (instagram_account_id, access_token_encrypted, expires_at, updated_at)
                    VALUES (:instagramAccountId, :accessToken, :expiresAt, :updatedAt)
                    ON CONFLICT (instagram_account_id) DO UPDATE SET
                        access_token_encrypted = EXCLUDED.access_token_encrypted,
                        expires_at = EXCLUDED.expires_at,
                        updated_at = EXCLUDED.updated_at
                    """.trimIndent(),
                ).bindKotlin(toStore)
                .execute()
        }
    }

    override fun delete(accountId: String) {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate("DELETE FROM auth_config WHERE instagram_account_id = :accountId")
                .bind("accountId", accountId)
                .execute()
        }
    }
}

/**
 * One account claimed by [AccountClaimRepository.claimDueAccount]. [previousClaimedAt] is the
 * `sync_claimed_at` value the claimed row had *before* this claim overwrote it - non-null means
 * this claim retook an abandoned attempt (a previous worker's `sync_claimed_at` went stale without
 * ever clearing it via [AccountClaimRepository.markSynced]), which callers log at WARN for
 * operator visibility. Null means this was a fresh, never-claimed-or-cleanly-completed account.
 */
data class AccountClaim(
    val instagramAccountId: String,
    val previousClaimedAt: Instant?,
)

/**
 * Account-agnostic feed-sync scheduling: which account is due for a sync, claimed via Postgres row
 * locking rather than a coroutine semaphore, so any worker in any process can safely claim any due
 * account with no coordination beyond the database itself.
 *
 * Deliberately a fast claim-and-release, not a held transaction: [claimDueAccount] and
 * [markSynced] are each one short statement, and the actual feed-sync work in between runs with no
 * transaction open at all. A worker that crashes mid-sync simply leaves `sync_claimed_at` set and
 * stale - see [AccountClaim] - rather than holding a connection for as long as that sync takes.
 */
class AccountClaimRepository(
    private val jdbi: Jdbi,
) {
    /**
     * Claims exactly one account whose `last_synced_at` is null or at/before [dueBefore], and
     * whose `sync_claimed_at` is null or older than [leaseSeconds] (an abandoned claim), ordered
     * oldest-due-first. Returns null when nothing is currently due.
     *
     * [dueBefore] is a caller-computed cutoff rather than an interval, deliberately: the caller
     * decides whether it's relative to "now" (recomputed fresh on every poll, for a persistent
     * daemon's continuous staleness check) or a single fixed instant captured once before a
     * `RUN_ONCE` invocation's loop starts (see `feedSyncWorkerLoop` in `Main.kt`) - the latter is
     * what makes each account claimable *at most once* per one-shot run: once successfully synced,
     * its `last_synced_at` becomes newer than that fixed cutoff and it stops being due for the rest
     * of that invocation, rather than being claimable again the instant it's released (an interval
     * of "0 seconds relative to now" would never actually elapse, claiming the same account
     * forever in a tight loop that `RUN_ONCE` would never exit).
     *
     * `LIMIT 1`, not a batch: a claimed account means running its entire multi-page feed sync, so
     * concurrency should come from running more independent workers (each claiming one account and
     * looping) rather than from claiming several accounts into one round trip.
     */
    fun claimDueAccount(
        dueBefore: Instant,
        leaseSeconds: Long,
    ): AccountClaim? =
        jdbi.withHandle<AccountClaim?, Exception> { handle ->
            handle
                .createQuery(
                    """
                    WITH claimable AS (
                        SELECT instagram_account_id, sync_claimed_at
                        FROM instagram_accounts
                        WHERE (last_synced_at IS NULL OR last_synced_at <= :dueBefore)
                          AND (sync_claimed_at IS NULL OR sync_claimed_at <= now() - (:leaseSeconds || ' seconds')::interval)
                        ORDER BY last_synced_at ASC NULLS FIRST
                        FOR UPDATE SKIP LOCKED
                        LIMIT 1
                    )
                    UPDATE instagram_accounts
                    SET sync_claimed_at = CURRENT_TIMESTAMP
                    FROM claimable
                    WHERE instagram_accounts.instagram_account_id = claimable.instagram_account_id
                    RETURNING instagram_accounts.instagram_account_id, claimable.sync_claimed_at AS previous_claimed_at
                    """.trimIndent(),
                ).bind("dueBefore", dueBefore)
                .bind("leaseSeconds", leaseSeconds)
                .mapTo<AccountClaim>()
                .findFirst()
                .orElse(null)
        }

    /** Clears the claim and stamps `last_synced_at`, marking a sync cycle as cleanly completed. */
    fun markSynced(accountId: String) {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate(
                    """
                    UPDATE instagram_accounts
                    SET last_synced_at = CURRENT_TIMESTAMP, sync_claimed_at = NULL
                    WHERE instagram_account_id = :accountId
                    """.trimIndent(),
                ).bind("accountId", accountId)
                .execute()
        }
    }
}

/**
 * One item claimed by [DownloadQueueRepository.claimBatch]. [previousStatus] is the row's status
 * *before* this claim overwrote it - `"CLAIMED"` means this claim retook an abandoned attempt
 * (see [DownloadQueueRepository]'s doc comment), which callers log at WARN; `"UNCLAIMED"` means
 * this was fresh work.
 */
data class PendingDownload(
    val instagramAccountId: String,
    val instagramMediaId: String,
    val previousStatus: String,
)

/**
 * Account-agnostic download queue: which catalog items are selected into a gallery and not yet
 * downloaded, claimed via Postgres row locking so any worker in any process can safely pull work
 * regardless of which account it belongs to - the credential provider resolves the right token
 * per claimed item's own `instagramAccountId`.
 *
 * Fast claim-and-release, not a held transaction: [claimBatch] and [markComplete] are each one
 * short statement, and the actual download/optimize/store work in between runs with no
 * transaction open. A worker that crashes or fails mid-item simply leaves the row `CLAIMED` and
 * its `claimed_at` stale - the next claim treats that identically to a fresh crash, with no
 * distinction and no separate reaper process.
 */
class DownloadQueueRepository(
    private val jdbi: Jdbi,
) {
    /** Idempotent: called every time [knurl.ingestion.pipeline.SyncPipeline] decides an item should be downloaded. */
    fun enqueue(
        accountId: String,
        mediaId: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO pending_downloads (instagram_account_id, instagram_media_id)
                    VALUES (:accountId, :mediaId)
                    ON CONFLICT (instagram_account_id, instagram_media_id) DO NOTHING
                    """.trimIndent(),
                ).bind("accountId", accountId)
                .bind("mediaId", mediaId)
                .execute()
        }
    }

    /**
     * Claims up to [batchSize] items that are `UNCLAIMED`, or `CLAIMED` with a `claimed_at` older
     * than [leaseSeconds] (abandoned), oldest-enqueued-first. With no lock held across the actual
     * download, claimed items can be processed concurrently by the caller - unlike the account
     * claim above, there is no reason to keep this batch small purely for lock-duration reasons.
     */
    fun claimBatch(
        batchSize: Int,
        leaseSeconds: Long,
    ): List<PendingDownload> =
        jdbi.withHandle<List<PendingDownload>, Exception> { handle ->
            handle
                .createQuery(
                    """
                    WITH claimable AS (
                        SELECT instagram_account_id, instagram_media_id, status AS previous_status
                        FROM pending_downloads
                        WHERE status = 'UNCLAIMED'
                           OR (status = 'CLAIMED' AND claimed_at <= now() - (:leaseSeconds || ' seconds')::interval)
                        ORDER BY enqueued_at
                        FOR UPDATE SKIP LOCKED
                        LIMIT :batchSize
                    )
                    UPDATE pending_downloads
                    SET status = 'CLAIMED', claimed_at = CURRENT_TIMESTAMP
                    FROM claimable
                    WHERE pending_downloads.instagram_account_id = claimable.instagram_account_id
                      AND pending_downloads.instagram_media_id = claimable.instagram_media_id
                    RETURNING pending_downloads.instagram_account_id, pending_downloads.instagram_media_id, claimable.previous_status
                    """.trimIndent(),
                ).bind("batchSize", batchSize)
                .bind("leaseSeconds", leaseSeconds)
                .mapTo<PendingDownload>()
                .list()
        }

    fun markComplete(
        accountId: String,
        mediaId: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate(
                    """
                    UPDATE pending_downloads
                    SET status = 'COMPLETE', completed_at = CURRENT_TIMESTAMP
                    WHERE instagram_account_id = :accountId AND instagram_media_id = :mediaId
                    """.trimIndent(),
                ).bind("accountId", accountId)
                .bind("mediaId", mediaId)
                .execute()
        }
    }

    /**
     * Whether [mediaId] still belongs to at least one gallery - checked right before downloading,
     * since an item can sit in the queue for an unbounded time between enqueue and claim, and an
     * admin may have removed it from every gallery in the meantime.
     */
    fun isStillInAnyGallery(mediaId: String): Boolean =
        jdbi.withHandle<Boolean, Exception> { handle ->
            handle
                .createQuery("SELECT EXISTS (SELECT 1 FROM gallery_items WHERE instagram_media_id = :mediaId)")
                .bind("mediaId", mediaId)
                .mapTo<Boolean>()
                .one()
        }

    /** Purges `COMPLETE` rows older than [retentionHours], kept until then purely for operator visibility. */
    fun deleteOldCompleted(retentionHours: Long): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle
                .createUpdate(
                    """
                    DELETE FROM pending_downloads
                    WHERE status = 'COMPLETE' AND completed_at <= now() - (:retentionHours || ' hours')::interval
                    """.trimIndent(),
                ).bind("retentionHours", retentionHours)
                .execute()
        }
}

/** One row of `instagram_post_media`, scoped down to just the S3 paths needed for eviction's S3 delete. */
private data class MediaPathsRow(
    val postId: Uuid,
    val smallPath: String,
    val largePath: String,
    val videoPath: String?,
)

/**
 * Wraps a single `id` column. JDBI's Kotlin `mapTo<Uuid>()` doesn't route a bare scalar through
 * the registered `KotlinUuidColumnMapper` (it only resolves that mapper for a `Uuid`-typed *field*
 * inside a composite row, which is how every other `Uuid` column in this file is already mapped)
 * - wrapping it in a one-field data class sidesteps that and reuses the already-working path.
 */
internal data class UuidRow(
    val id: Uuid,
)

/**
 * Semi-join predicate: "this post belongs to at least one gallery". Expects the `instagram_posts`
 * row to be aliased `p`.
 *
 * This is the successor to the old `selected` flag, and it inherits every job that flag did: it
 * gates the download in `SyncPipeline`, it protects a post from the retention sweep, and its going
 * false is what starts the deletion clock.
 *
 * Note what it deliberately is *not*. With one implicit gallery, visibility and reapability were
 * the same predicate, and a shared constant was what guaranteed they could never disagree. That is
 * no longer true: visibility is now per-gallery (`gi.gallery_id = :galleryId`) while reapability is
 * any-gallery, so "invisible in gallery X but correctly not reaped" is an ordinary, intended state.
 * This constant is shared only between [InstagramPostRepository.findEvictionCandidates] and
 * [InstagramPostRepository.deleteIfNotInAnyGallery], which really are two halves of one rule.
 */
private const val IN_ANY_GALLERY =
    """EXISTS (
           SELECT 1 FROM gallery_items gi
           WHERE gi.instagram_media_id = p.instagram_media_id
       )"""

class InstagramPostRepository(
    private val jdbi: Jdbi,
) {
    /**
     * Used by the ingestion pipeline to skip re-downloading/re-processing media that's already
     * been synced, since [upsert] would otherwise re-fetch and re-upload unchanged posts on every
     * cycle. Scoped to one account so multiple ingestion instances sharing this database never
     * see each other's already-synced media ids.
     */
    fun existingMediaIds(accountId: String): Set<String> =
        jdbi.withHandle<Set<String>, Exception> { handle ->
            handle
                .createQuery("SELECT instagram_media_id FROM instagram_posts WHERE instagram_account_id = :accountId")
                .bind("accountId", accountId)
                .mapTo<String>()
                .toSet()
        }

    /**
     * `id` and `created_at` are deliberately absent from the insert column list, leaving them to
     * their column defaults (Postgres's native `uuidv7()` / `CURRENT_TIMESTAMP`) on first insert.
     * The conflict target is the natural `instagram_media_id` key, not `id`, so a post's surrogate
     * id - and any independently-tracked view/click counters - never change across re-syncs.
     * `instagram_account_id` is likewise absent from the `DO UPDATE SET` clause: a post's owning
     * account is fixed at first insert and never legitimately changes.
     *
     * Media items are replaced wholesale (delete then batch-insert) rather than diffed, since a
     * carousel's child count/order can legitimately change between sync cycles. Everything runs in
     * one transaction so a mid-failure never leaves a post with stale or missing media rows.
     */
    fun upsert(post: InstagramPostUpsert) {
        jdbi.inTransaction<Unit, Exception> { handle ->
            val postId =
                handle
                    .createQuery(
                        """
                        INSERT INTO instagram_posts
                            (instagram_account_id, instagram_media_id, media_type, caption, permalink, timestamp)
                        VALUES
                            (:instagramAccountId, :instagramMediaId, :mediaType, :caption, :permalink, :timestamp)
                        ON CONFLICT (instagram_media_id) DO UPDATE SET
                            media_type = EXCLUDED.media_type,
                            caption = EXCLUDED.caption,
                            permalink = EXCLUDED.permalink,
                            timestamp = EXCLUDED.timestamp
                        RETURNING id
                        """.trimIndent(),
                    ).bindKotlin(post)
                    .mapTo<UuidRow>()
                    .one()
                    .id

            handle
                .createUpdate("DELETE FROM instagram_post_media WHERE post_id = :postId")
                .bind("postId", postId)
                .execute()

            val batch =
                handle.prepareBatch(
                    """
                    INSERT INTO instagram_post_media (
                        post_id,
                        position,
                        media_type,
                        small_path,
                        small_file_size_bytes,
                        small_width,
                        small_height,
                        large_path,
                        large_file_size_bytes,
                        large_width,
                        large_height,
                        video_path,
                        video_file_size_bytes,
                        video_width,
                        video_height
                    )
                    VALUES (
                        :postId,
                        :position,
                        :mediaType,
                        :smallPath,
                        :smallFileSizeBytes,
                        :smallWidth,
                        :smallHeight,
                        :largePath,
                        :largeFileSizeBytes,
                        :largeWidth,
                        :largeHeight,
                        :videoPath,
                        :videoFileSizeBytes,
                        :videoWidth,
                        :videoHeight
                    )
                    """.trimIndent(),
                )
            post.mediaItems.forEach { item -> batch.bind("postId", postId).bindKotlin(item).add() }
            batch.execute()
        }
    }

    /**
     * Posts whose downloaded media is now due for deletion: out of every gallery for longer than
     * the retention grace period, or explicitly purge-requested by an admin.
     *
     * The grace period exists so removing an item from a gallery is reversible for free - within the
     * window the media is still in the bucket, so adding it back costs no re-download
     * (`existingMediaIds` still covers it and `SyncPipeline` skips it). Re-adding clears both
     * timestamps, which is what makes the item ineligible here and restarts the countdown from
     * scratch on a later removal rather than resuming the old one.
     *
     * Belonging to no gallery is required regardless of which clock fired: evicting a post that is
     * still in one would only have `SyncPipeline` re-download it next cycle, an unbounded
     * download/delete loop.
     *
     * **The `COALESCE(c.deselected_at, p.created_at)` is a deliberate backstop, not a convenience.**
     * `deselected_at` is a denormalisation maintained by four separate membership-mutating paths,
     * and the failure mode that matters is an item ending up in zero galleries with the stamp
     * missed: it would then be invisible in every gallery *and* permanently unreapable, holding its
     * bytes forever with nothing to detect it (`ObjectKeyRepository.allKnownKeys` still covers those
     * objects, so the orphan sweeper leaves them alone by design). Falling back to the post's own
     * creation time makes "in no gallery" *sufficient* for eventual reaping however the clock was
     * maintained. The cost is honest: in that case the item loses its grace period and goes on the
     * next cycle instead of in 30 days - far better than never.
     *
     * [is_pinned][knurl.domain.models.InstagramPost.isPinned] defeats the ordinary time-based sweep,
     * but **not an explicit purge request**. A pin is a passive "retention must not reap this"
     * escape hatch; an admin naming the item in a DELETE request is an active instruction, and
     * silently ignoring it would be the worse surprise of the two.
     *
     * Scoped to one account so retention is computed within that account's own pool, never blended
     * with another account's posts sharing this database. Media paths (for the S3 delete that
     * follows the row delete) are fetched in one batched follow-up query.
     *
     * Note the INNER JOIN: a post with no catalog row at all is neither shown nor reaped. That
     * should be unreachable - the catalog is upserted for every feed item on every cycle - but
     * [countOrphanedPosts] exists so the pipeline can log it rather than leak silently.
     */
    fun findEvictionCandidates(
        accountId: String,
        deselectedBefore: Instant,
    ): List<EvictionCandidate> =
        jdbi.withHandle<List<EvictionCandidate>, Exception> { handle ->
            val candidateIds =
                handle
                    .createQuery(
                        """
                        SELECT p.id
                        FROM instagram_posts p
                        JOIN instagram_media_catalog c ON c.instagram_media_id = p.instagram_media_id
                        WHERE p.instagram_account_id = :accountId
                          AND NOT $IN_ANY_GALLERY
                          AND (
                                c.purge_requested_at IS NOT NULL
                                OR COALESCE(c.deselected_at, p.created_at) < :deselectedBefore
                              )
                          AND (p.is_pinned = FALSE OR c.purge_requested_at IS NOT NULL)
                        """.trimIndent(),
                    ).bind("accountId", accountId)
                    .bind("deselectedBefore", deselectedBefore)
                    .mapTo<UuidRow>()
                    .list()
                    .map { it.id }

            if (candidateIds.isEmpty()) return@withHandle emptyList()

            val pathsByPost =
                handle
                    .createQuery(
                        "SELECT post_id, small_path, large_path, video_path FROM instagram_post_media WHERE post_id IN (<ids>)",
                    ).bindList("ids", candidateIds)
                    .mapTo<MediaPathsRow>()
                    .list()
                    .groupBy { it.postId }

            candidateIds.map { id ->
                val paths = pathsByPost[id].orEmpty().flatMap { listOfNotNull(it.smallPath, it.largePath, it.videoPath) }
                EvictionCandidate(id = id, mediaPaths = paths)
            }
        }

    /**
     * Deletes a post **unless it was added back to some gallery since it was chosen for eviction**,
     * returning the number of rows affected (1 = deleted, 0 = live again, leave its S3 objects
     * alone).
     *
     * The guard is not redundant with [findEvictionCandidates]'s own membership filter. That query
     * snapshots the candidate list at the top of the sweep, and candidates then drain through a
     * concurrency semaphore - an admin adding the item to a gallery in that gap would otherwise have
     * the media deleted out from under a post that is once again live, costing a full re-download
     * and leaving a gap in that gallery until the next cycle. Doing the re-check inside the DELETE
     * itself makes eviction atomic with respect to that add, which no amount of re-reading
     * beforehand can.
     *
     * Named for the guard rather than `deleteById` so a later caller cannot assume an unconditional
     * delete and quietly drop it. `instagram_post_media` rows go via ON DELETE CASCADE.
     */
    fun deleteIfNotInAnyGallery(id: Uuid): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle
                .createUpdate(
                    """
                    DELETE FROM instagram_posts p
                    WHERE p.id = :id AND NOT $IN_ANY_GALLERY
                    """.trimIndent(),
                ).bind("id", id)
                .execute()
        }

    /**
     * Deletes the catalog row and, if one was ever downloaded, the post (and its media) for every
     * media id in [vanishedMediaIds] - media [knurl.ingestion.pipeline.SyncPipeline] has determined
     * Instagram's own feed no longer returns for this account, having just finished a complete,
     * successful pagination sweep. Returns an [EvictionCandidate] per post that existed, so the
     * caller can delete its S3 objects the same way retention eviction does.
     *
     * Unconditional, unlike [deleteIfNotInAnyGallery]: there is no re-add race to protect against
     * here, because reselecting an item Instagram itself no longer serves cannot bring it back - the
     * next sync would simply fail to find it in the feed again. `selected` and `is_pinned` are both
     * overridden for the same reason: they express a preference for keeping content Instagram still
     * has, never a claim that Instagram is wrong about what it has.
     *
     * Both tables are deleted from inside one transaction on purpose, even though
     * `instagram_media_catalog` is normally [CatalogRepository]'s table to touch. Splitting this
     * across two separate calls would let a crash in between leave a postless catalog row cleared
     * but a catalog-less post row behind - exactly the state [countOrphanedPosts] exists to detect,
     * but that nothing would then be able to heal automatically.
     */
    fun deleteVanished(
        accountId: String,
        vanishedMediaIds: Set<String>,
    ): List<EvictionCandidate> {
        if (vanishedMediaIds.isEmpty()) return emptyList()

        return jdbi.inTransaction<List<EvictionCandidate>, Exception> { handle ->
            val postIds =
                handle
                    .createQuery(
                        """
                        SELECT id FROM instagram_posts
                        WHERE instagram_account_id = :accountId AND instagram_media_id = ANY(:mediaIds)
                        """.trimIndent(),
                    ).bind("accountId", accountId)
                    .bindArray("mediaIds", String::class.java, vanishedMediaIds)
                    .mapTo<UuidRow>()
                    .list()
                    .map { it.id }

            val pathsByPost =
                if (postIds.isEmpty()) {
                    emptyMap()
                } else {
                    handle
                        .createQuery(
                            "SELECT post_id, small_path, large_path, video_path FROM instagram_post_media WHERE post_id IN (<ids>)",
                        ).bindList("ids", postIds)
                        .mapTo<MediaPathsRow>()
                        .list()
                        .groupBy { it.postId }
                }

            handle
                .createUpdate(
                    """
                    DELETE FROM instagram_posts
                    WHERE instagram_account_id = :accountId AND instagram_media_id = ANY(:mediaIds)
                    """.trimIndent(),
                ).bind("accountId", accountId)
                .bindArray("mediaIds", String::class.java, vanishedMediaIds)
                .execute()

            handle
                .createUpdate(
                    """
                    DELETE FROM instagram_media_catalog
                    WHERE instagram_account_id = :accountId AND instagram_media_id = ANY(:mediaIds)
                    """.trimIndent(),
                ).bind("accountId", accountId)
                .bindArray("mediaIds", String::class.java, vanishedMediaIds)
                .execute()

            postIds.map { id ->
                EvictionCandidate(
                    id = id,
                    mediaPaths = pathsByPost[id].orEmpty().flatMap { listOfNotNull(it.smallPath, it.largePath, it.videoPath) },
                )
            }
        }
    }

    /**
     * Posts for this account with no `instagram_media_catalog` row at all. Such a post can never be
     * shown (a gallery holds catalog items) nor reaped (retention joins the catalog), so it
     * would occupy bucket space forever while being invisible. It should be unreachable; this
     * exists purely so `SyncPipeline` can warn instead of leaking in silence.
     */
    fun countOrphanedPosts(accountId: String): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle
                .createQuery(
                    """
                    SELECT COUNT(*) FROM instagram_posts p
                    WHERE p.instagram_account_id = :accountId
                      AND NOT EXISTS (
                          SELECT 1 FROM instagram_media_catalog c
                          WHERE c.instagram_media_id = p.instagram_media_id
                      )
                    """.trimIndent(),
                ).bind("accountId", accountId)
                .mapTo<Int>()
                .one()
        }
}

class SyncConfigurationRepository(
    private val jdbi: Jdbi,
) {
    fun get(key: String): String? =
        jdbi.withHandle<String?, Exception> { handle ->
            handle
                .createQuery("SELECT value FROM sync_configurations WHERE key = :key")
                .bind("key", key)
                .mapTo<String>()
                .findFirst()
                .orElse(null)
        }

    fun getInt(
        key: String,
        default: Int,
    ): Int = get(key)?.toIntOrNull() ?: default

    fun set(
        key: String,
        value: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO sync_configurations (key, value)
                    VALUES (:key, :value)
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                    """.trimIndent(),
                ).bind("key", key)
                .bind("value", value)
                .execute()
        }
    }

    fun getBoolean(
        key: String,
        default: Boolean,
    ): Boolean = get(key)?.lowercase()?.toBooleanStrictOrNull() ?: default

    /**
     * Atomically claims a periodic job slot: returns true (and stamps [now]) only if [key] was last
     * claimed more than [intervalSeconds] ago, false otherwise.
     *
     * This is what stops several `ingestion-service` instances sharing one database and bucket from
     * all running the same expensive sweep on the same schedule - the `WHERE` on the conflict target
     * makes the read-and-write one statement, so exactly one caller can win. The stamp is written
     * *before* the work runs, so a crash mid-job simply defers the next attempt by one interval
     * rather than letting every cycle retry it.
     *
     * The value is stored as **epoch seconds**, not an ISO-8601 string. `Instant.toString()` omits
     * a zero seconds field (`...T10:00Z` vs `...T10:00:05Z`), and `'Z' > '0'` in ASCII, so
     * lexicographic comparison of those strings silently orders them wrongly. Integers have no such
     * edge, and `::bigint` is safe here because this method is the only writer of these keys.
     */
    fun claimIfElapsed(
        key: String,
        now: Instant,
        intervalSeconds: Long,
    ): Boolean =
        jdbi.withHandle<Boolean, Exception> { handle ->
            handle
                .createQuery(
                    """
                    INSERT INTO sync_configurations (key, value)
                    VALUES (:key, :now)
                    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                    WHERE sync_configurations.value::bigint <= :cutoff
                    RETURNING key
                    """.trimIndent(),
                ).bind("key", key)
                .bind("now", now.epochSecond.toString())
                .bind("cutoff", now.epochSecond - intervalSeconds)
                .mapTo<String>()
                .findFirst()
                .isPresent
        }

    fun all(): Map<String, String> =
        jdbi.withHandle<Map<String, String>, Exception> { handle ->
            handle
                .createQuery("SELECT key, value FROM sync_configurations")
                .map { rs, _ -> rs.getString("key") to rs.getString("value") }
                .list()
                .toMap()
        }
}

/**
 * The `AND ...` fragment appending [filter] to a catalog query's account-scoped WHERE clause, or
 * `""` for an unrestricted filter. Paired with [bindCatalogFilter], which supplies the parameters
 * this fragment references; every query using one must use the other.
 *
 * Nothing here interpolates a client-supplied value. The membership and `not_digestible_reason`
 * clauses are fixed literals chosen by a type-safe `when`, and the gallery id and media types
 * appear only as bind parameters / a JDBI `<mediaTypes>` list-binding placeholder. An empty
 * [CatalogFilter.mediaTypes] omits that clause entirely - it means "no restriction", and JDBI
 * rejects an empty `bindList` regardless.
 *
 * The membership clauses fully qualify `instagram_media_catalog.instagram_media_id` rather than
 * relying on a bare column name: they are correlated subqueries over `gallery_items`, and the
 * queries these fragments are spliced into give the catalog table no alias.
 */
private fun catalogFilterClause(filter: CatalogFilter): String =
    buildString {
        if (filter.galleryId != null) {
            append(
                " AND EXISTS (SELECT 1 FROM gallery_items gi" +
                    " WHERE gi.instagram_media_id = instagram_media_catalog.instagram_media_id" +
                    " AND gi.gallery_id = :galleryId)",
            )
        }
        when (filter.inAnyGallery) {
            null -> Unit
            true -> append(ANY_GALLERY_MEMBERSHIP_CLAUSE)
            false -> append(" AND NOT$ANY_GALLERY_MEMBERSHIP_CLAUSE_BODY")
        }
        if (!filter.includeNotDigestible) append(" AND not_digestible_reason IS NULL")
        if (filter.mediaTypes.isNotEmpty()) append(" AND media_type IN (<mediaTypes>)")
    }

private const val ANY_GALLERY_MEMBERSHIP_CLAUSE_BODY =
    " EXISTS (SELECT 1 FROM gallery_items gi" +
        " WHERE gi.instagram_media_id = instagram_media_catalog.instagram_media_id)"

private const val ANY_GALLERY_MEMBERSHIP_CLAUSE = " AND$ANY_GALLERY_MEMBERSHIP_CLAUSE_BODY"

/** Binds the parameters referenced by [catalogFilterClause]; both clauses are optional, so both binds are conditional. */
private fun Query.bindCatalogFilter(filter: CatalogFilter): Query {
    val withGallery = if (filter.galleryId == null) this else bind("galleryId", filter.galleryId)
    return if (filter.mediaTypes.isEmpty()) withGallery else withGallery.bindList("mediaTypes", filter.mediaTypes.sorted())
}

/**
 * One `(media, gallery)` membership pair. A wrapper row rather than a bare `mapTo<Uuid>()` for the
 * same reason [UuidRow] exists - JDBI resolves the Kotlin `Uuid` column mapper only for a
 * `Uuid`-typed field inside a composite row.
 */
private data class MembershipRow(
    val instagramMediaId: String,
    val galleryId: Uuid,
    val sortOrder: Long?,
)

/** One `RETURNING` row from [CatalogRepository.requestPurge] - the shortcode plus whether it had anything downloaded. */
private data class PurgeRow(
    val instagramMediaId: String,
    val shortcode: String,
    val hadPost: Boolean,
)

class CatalogRepository(
    private val jdbi: Jdbi,
) {
    /**
     * `selected` is deliberately absent from both the insert column list and the `ON CONFLICT`
     * SET clause, so re-syncing the account's feed never resets an admin's selection back to
     * false. On first insert it takes its column default (`FALSE`). `not_digestible_reason` *is*
     * refreshed on every sync (unlike `selected`) since ingestion, not an admin, is the sole
     * source of truth for it - e.g. it should clear itself automatically if Instagram later starts
     * returning `media_url` for an item previously flagged.
     *
     * This is also the fifth path (alongside `GalleryRepository`'s add/remove/gallery-delete/purge)
     * that can move `instagram_media_catalog.deselected_at`: when [becameNonDigestible] says this
     * upsert just flipped the item from downloadable to non-digestible, it is reactively removed
     * from every gallery holding it and its retention clock is started, atomically with the upsert
     * itself - a non-digestible item sitting in a gallery would otherwise sit forever without ever
     * producing an [knurl.domain.models.InstagramPost]. All of this runs in one transaction: the
     * `INSERT ... ON CONFLICT DO UPDATE` above already takes and holds this row's lock for the rest
     * of the transaction (the same lock `GalleryRepository.lockCatalogRows`'s explicit `FOR UPDATE`
     * takes for its four paths), which is what serializes this against a concurrent
     * `GalleryRepository.updateItems`/`delete`/`requestPurge` on the same media id - no extra
     * explicit lock is needed. The reverse transition (an item clearing back to digestible) needs no
     * reactive code here: it was already removed from every gallery when it first became
     * non-digestible, and `GalleryRepository.addItems` refuses to re-add a still-non-digestible item,
     * so there is nothing to restore automatically either way.
     */
    fun upsert(entry: CatalogUpsert): CatalogUpsertResult =
        jdbi.inTransaction<CatalogUpsertResult, Exception> { handle ->
            val previousReason =
                handle
                    .createQuery("SELECT not_digestible_reason FROM instagram_media_catalog WHERE instagram_media_id = :id")
                    .bind("id", entry.instagramMediaId)
                    .mapTo<String>()
                    .findFirst()
                    .orElse(null)

            handle
                .createUpdate(
                    """
                    INSERT INTO instagram_media_catalog
                        (instagram_media_id, instagram_account_id, shortcode, media_type, caption, permalink,
                         timestamp, not_digestible_reason)
                    VALUES
                        (:instagramMediaId, :instagramAccountId, :shortcode, :mediaType, :caption, :permalink,
                         :timestamp, :notDigestibleReason)
                    ON CONFLICT (instagram_media_id) DO UPDATE SET
                        shortcode = EXCLUDED.shortcode,
                        media_type = EXCLUDED.media_type,
                        caption = EXCLUDED.caption,
                        permalink = EXCLUDED.permalink,
                        timestamp = EXCLUDED.timestamp,
                        not_digestible_reason = EXCLUDED.not_digestible_reason,
                        updated_at = CURRENT_TIMESTAMP
                    """.trimIndent(),
                ).bindKotlin(entry)
                .execute()

            if (!becameNonDigestible(previousReason, entry.notDigestibleReason)) {
                return@inTransaction CatalogUpsertResult(removedFromGalleries = emptyList())
            }

            val affectedGalleryNames =
                handle
                    .createQuery(
                        """
                        SELECT DISTINCT g.name FROM galleries g
                        JOIN gallery_items gi ON gi.gallery_id = g.id
                        WHERE gi.instagram_media_id = :id
                        ORDER BY g.name
                        """.trimIndent(),
                    ).bind("id", entry.instagramMediaId)
                    .mapTo<String>()
                    .list()

            if (affectedGalleryNames.isEmpty()) {
                return@inTransaction CatalogUpsertResult(removedFromGalleries = emptyList())
            }

            handle
                .createUpdate("DELETE FROM gallery_items WHERE instagram_media_id = :id")
                .bind("id", entry.instagramMediaId)
                .execute()

            handle
                .createUpdate(
                    """
                    UPDATE instagram_media_catalog
                    SET deselected_at = COALESCE(deselected_at, CURRENT_TIMESTAMP)
                    WHERE instagram_media_id = :id
                    """.trimIndent(),
                ).bind("id", entry.instagramMediaId)
                .execute()

            CatalogUpsertResult(removedFromGalleries = affectedGalleryNames)
        }

    /**
     * Every media id currently in this account's catalog, selected or not. Used by the ingestion
     * pipeline to detect which catalog entries Instagram's own feed no longer returned this cycle -
     * see [knurl.ingestion.pipeline.SyncPipeline]'s vanished-media removal - by diffing this against
     * what the cycle's complete pagination actually found.
     */
    fun allMediaIds(accountId: String): Set<String> =
        jdbi.withHandle<Set<String>, Exception> { handle ->
            handle
                .createQuery("SELECT instagram_media_id FROM instagram_media_catalog WHERE instagram_account_id = :accountId")
                .bind("accountId", accountId)
                .mapTo<String>()
                .toSet()
        }

    /**
     * Used by the ingestion pipeline to decide which catalog items to download/store as posts: an
     * item is worth downloading exactly when at least one of the account's galleries holds it.
     *
     * Scoped through `galleries`, deliberately, rather than through the catalog row's own
     * `instagram_account_id`. Those two agree only because every write path enforces it; going via
     * the gallery means a membership row that somehow crossed accounts feeds the *owning* account's
     * download gate rather than silently widening someone else's.
     */
    fun mediaIdsInAnyGallery(accountId: String): Set<String> =
        jdbi.withHandle<Set<String>, Exception> { handle ->
            handle
                .createQuery(
                    """
                    SELECT DISTINCT gi.instagram_media_id
                    FROM gallery_items gi
                    JOIN galleries g ON g.id = gi.gallery_id
                    WHERE g.instagram_account_id = :accountId
                    """.trimIndent(),
                ).bind("accountId", accountId)
                .mapTo<String>()
                .toSet()
        }

    /** Used by the ingestion pipeline to decide which catalog items still need a thumbnail fetched, independent of [mediaIdsInAnyGallery]. */
    fun mediaIdsNeedingThumbnail(accountId: String): Set<String> =
        jdbi.withHandle<Set<String>, Exception> { handle ->
            handle
                .createQuery(
                    "SELECT instagram_media_id FROM instagram_media_catalog WHERE instagram_account_id = :accountId AND thumbnail_path IS NULL",
                ).bind("accountId", accountId)
                .mapTo<String>()
                .toSet()
        }

    /**
     * Not account-scoped, unlike [applySelection]'s defensive `accountId` check - `instagramMediaId`
     * is already this table's primary key, and this is only ever called by the single-account-scoped
     * `SyncPipeline` for an item it just fetched a thumbnail for itself.
     */
    fun updateThumbnailPath(
        instagramMediaId: String,
        thumbnailPath: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate(
                    "UPDATE instagram_media_catalog SET thumbnail_path = :thumbnailPath WHERE instagram_media_id = :instagramMediaId",
                ).bind("thumbnailPath", thumbnailPath)
                .bind("instagramMediaId", instagramMediaId)
                .execute()
        }
    }

    /**
     * One page of an account's catalog, plus the total matching [filter], for offset pagination.
     *
     * The WHERE clause is assembled by [catalogFilterClause] and bound by [bindCatalogFilter],
     * which the `COUNT(*)` and the row query both go through - so the total can never describe a
     * different set of rows than the page it accompanies. Neither helper interpolates client input:
     * the membership and `not_digestible_reason` clauses are fixed literals chosen by a type-safe
     * `when`, and the gallery id and media types go through real bind parameters.
     *
     * `ORDER BY` carries `instagram_media_id` (the primary key) as a tiebreaker because `timestamp`
     * alone is not unique and offset paging needs a total order.
     *
     * Each entry's gallery memberships are gathered in one batched follow-up query rather than
     * per row, the same shape [GalleryRepository.findContentPage] uses for media items.
     */
    fun findPage(
        accountId: String,
        filter: CatalogFilter,
        pageSize: Int,
        page: Int,
    ): Page<CatalogEntry> {
        val filterClause = catalogFilterClause(filter)

        return jdbi.withHandle<Page<CatalogEntry>, Exception> { handle ->
            val totalRecords =
                handle
                    .createQuery(
                        """
                        SELECT COUNT(*) FROM instagram_media_catalog
                        WHERE instagram_account_id = :accountId $filterClause
                        """.trimIndent(),
                    ).bind("accountId", accountId)
                    .bindCatalogFilter(filter)
                    .mapTo<Int>()
                    .one()

            Page.of(totalRecords, pageSize, page) { offset ->
                val entries =
                    handle
                        .createQuery(
                            """
                            SELECT * FROM instagram_media_catalog
                            WHERE instagram_account_id = :accountId $filterClause
                            ORDER BY timestamp DESC, instagram_media_id
                            LIMIT :limit OFFSET :offset
                            """.trimIndent(),
                        ).bind("accountId", accountId)
                        .bind("limit", pageSize)
                        .bind("offset", offset)
                        .bindCatalogFilter(filter)
                        .mapTo<CatalogEntry>()
                        .list()

                if (entries.isEmpty()) {
                    emptyList()
                } else {
                    val membershipsByMedia =
                        handle
                            .createQuery(
                                "SELECT instagram_media_id, gallery_id, sort_order FROM gallery_items WHERE instagram_media_id IN (<ids>)",
                            ).bindList("ids", entries.map { it.instagramMediaId })
                            .mapTo<MembershipRow>()
                            .list()
                            .groupBy { it.instagramMediaId }

                    entries.map { entry ->
                        val memberships = membershipsByMedia[entry.instagramMediaId].orEmpty()
                        entry.copy(
                            galleryIds = memberships.map { it.galleryId },
                            gallerySortOrders =
                                memberships
                                    .mapNotNull { membership ->
                                        membership.sortOrder?.let { membership.galleryId to it }
                                    }.toMap(),
                        )
                    }
                }
            }
        }
    }

    /**
     * Arms an immediate purge of the given items' downloaded media: removes them from every gallery
     * holding them (so they leave those galleries at once and cannot be re-downloaded next cycle)
     * and stamps `purge_requested_at`, which makes the next ingestion cycle's retention sweep delete
     * their `instagram_posts` rows and S3 objects without waiting out the grace period.
     *
     * The catalog row itself deliberately survives, along with its browse thumbnail - so the item
     * stays visible in the admin catalog and can be added to a gallery again later, re-downloading
     * fresh. Deleting the catalog row would be futile anyway: it is an unconditional mirror of the
     * Instagram feed, so the next sync would simply re-insert it. Note the flip side, since it is a
     * real cost: a purge discards that item's view/click counters in *every* gallery at once.
     *
     * `deselected_at` is stamped alongside so the two lifecycle columns can never disagree about
     * whether the item is on a clock.
     *
     * **Transactional, and in this order.** This is three statements now, not one: lock, stamp, then
     * delete memberships. The lock is what stops a concurrent membership change from interleaving
     * (see [GalleryRepository.updateItems] for why that matters), and stamping before deleting means
     * an interleaved add can never leave the item membership-free with no clock running.
     *
     * `RETURNING` names the rows that existed (the rest were not found for this account), and the
     * `EXISTS` tells them apart into "media will be deleted" and "there was nothing downloaded to
     * delete". Account-scoped so a shortcode belonging to a *different* tracked account reports as
     * not found rather than silently purging across a tenant boundary.
     */
    fun requestPurge(
        accountId: String,
        shortcodes: Set<String>,
    ): PurgeRequestResult {
        if (shortcodes.isEmpty()) return PurgeRequestResult(accepted = emptySet(), notDownloaded = emptySet())

        val rows =
            jdbi.inTransaction<List<PurgeRow>, Exception> { handle ->
                handle
                    .createQuery(
                        """
                        SELECT 1 FROM instagram_media_catalog
                        WHERE instagram_account_id = :accountId AND shortcode = ANY(:shortcodes)
                        ORDER BY instagram_media_id
                        FOR UPDATE
                        """.trimIndent(),
                    ).bind("accountId", accountId)
                    .bindArray("shortcodes", String::class.java, shortcodes)
                    .mapTo<Int>()
                    .list()

                val purged =
                    handle
                        .createQuery(
                            """
                            UPDATE instagram_media_catalog c
                            SET deselected_at = COALESCE(c.deselected_at, CURRENT_TIMESTAMP),
                                purge_requested_at = COALESCE(c.purge_requested_at, CURRENT_TIMESTAMP)
                            WHERE c.instagram_account_id = :accountId AND c.shortcode = ANY(:shortcodes)
                            RETURNING c.instagram_media_id,
                                      c.shortcode,
                                      EXISTS (
                                          SELECT 1 FROM instagram_posts p
                                          WHERE p.instagram_media_id = c.instagram_media_id
                                      ) AS had_post
                            """.trimIndent(),
                        ).bind("accountId", accountId)
                        .bindArray("shortcodes", String::class.java, shortcodes)
                        .mapTo<PurgeRow>()
                        .list()

                if (purged.isNotEmpty()) {
                    handle
                        .createUpdate("DELETE FROM gallery_items WHERE instagram_media_id = ANY(:mediaIds)")
                        .bindArray("mediaIds", String::class.java, purged.map { it.instagramMediaId })
                        .execute()
                }

                purged
            }

        return PurgeRequestResult(
            accepted = rows.filter { it.hadPost }.map { it.shortcode }.toSet(),
            notDownloaded = rows.filterNot { it.hadPost }.map { it.shortcode }.toSet(),
        )
    }
}

/**
 * Every S3 object key the database knows about, across **all** accounts.
 *
 * Deliberately not account-scoped, and that is the whole point. Object keys are
 * `posts/{instagramMediaId}/...` and `catalog/{instagramMediaId}/...` with no account segment in
 * them, so a bucket listing cannot be attributed to one account. A sweeper holding only one
 * account's keys would classify every sibling account's media as an orphan and delete it.
 *
 * The corollary is a deployment constraint worth stating plainly: **one bucket per database**.
 * Pointing two ingestion deployments backed by *separate* databases at a shared bucket would have
 * each sweeper delete the other's objects, because neither database can vouch for the other's keys.
 */
class ObjectKeyRepository(
    private val jdbi: Jdbi,
) {
    /**
     * Peak memory is one string per object (~60 bytes each: ~600KB at 10k objects, ~6MB at 100k),
     * which is why the sweeper streams the *listing* past this set rather than materialising both.
     * Keep it that way - holding the listing too is what would break the heap budget.
     */
    fun allKnownKeys(): Set<String> =
        jdbi.withHandle<Set<String>, Exception> { handle ->
            handle
                .createQuery(
                    """
                    SELECT small_path AS key FROM instagram_post_media
                    UNION
                    SELECT large_path FROM instagram_post_media
                    UNION
                    SELECT video_path FROM instagram_post_media WHERE video_path IS NOT NULL
                    UNION
                    SELECT thumbnail_path FROM instagram_media_catalog WHERE thumbnail_path IS NOT NULL
                    """.trimIndent(),
                ).mapTo<String>()
                .toSet()
        }
}
