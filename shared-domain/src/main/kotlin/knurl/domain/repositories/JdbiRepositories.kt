package knurl.domain.repositories

import knurl.domain.models.AuthConfig
import knurl.domain.models.CatalogEntry
import knurl.domain.models.CatalogFilter
import knurl.domain.models.CatalogSelectionResult
import knurl.domain.models.CatalogUpsert
import knurl.domain.models.EvictionCandidate
import knurl.domain.models.InstagramPost
import knurl.domain.models.InstagramPostUpsert
import knurl.domain.models.Page
import knurl.domain.models.PostMediaItem
import knurl.domain.models.PurgeRequestResult
import knurl.domain.models.SortOrder
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.bindKotlin
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.statement.Query
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Registry of known Instagram accounts and their admin-API tokens. A row is (re)written by
 * whichever `ingestion-service` instance owns that account on every startup - `register` always
 * overwrites `admin_token` from current config, which is also how an operator rotates it.
 */
class AccountRepository(
    private val jdbi: Jdbi,
) {
    fun register(
        accountId: String,
        adminToken: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO instagram_accounts (instagram_account_id, admin_token)
                    VALUES (:accountId, :adminToken)
                    ON CONFLICT (instagram_account_id) DO UPDATE SET admin_token = EXCLUDED.admin_token
                    """.trimIndent(),
                ).bind("accountId", accountId)
                .bind("adminToken", adminToken)
                .execute()
        }
    }

    fun findAdminToken(accountId: String): String? =
        jdbi.withHandle<String?, Exception> { handle ->
            handle
                .createQuery("SELECT admin_token FROM instagram_accounts WHERE instagram_account_id = :accountId")
                .bind("accountId", accountId)
                .mapTo<String>()
                .findFirst()
                .orElse(null)
        }
}

class AuthConfigRepository(
    private val jdbi: Jdbi,
) {
    fun get(accountId: String): AuthConfig? =
        jdbi.withHandle<AuthConfig?, Exception> { handle ->
            handle
                .createQuery("SELECT * FROM auth_config WHERE instagram_account_id = :accountId")
                .bind("accountId", accountId)
                .mapTo<AuthConfig>()
                .findFirst()
                .orElse(null)
        }

    fun upsert(config: AuthConfig) {
        jdbi.useHandle<Exception> { handle ->
            handle
                .createUpdate(
                    """
                    INSERT INTO auth_config (instagram_account_id, access_token, expires_at, updated_at)
                    VALUES (:instagramAccountId, :accessToken, :expiresAt, :updatedAt)
                    ON CONFLICT (instagram_account_id) DO UPDATE SET
                        access_token = EXCLUDED.access_token,
                        expires_at = EXCLUDED.expires_at,
                        updated_at = EXCLUDED.updated_at
                    """.trimIndent(),
                ).bindKotlin(config)
                .execute()
        }
    }
}

/** Raw `instagram_posts` row shape - mirrors the table exactly, unlike [InstagramPost] which also carries [PostMediaItem]s from a separate table/query. */
private data class InstagramPostRow(
    val id: Uuid,
    val instagramAccountId: String,
    val instagramMediaId: String,
    val mediaType: String,
    val caption: String?,
    val permalink: String,
    val timestamp: Instant,
    val viewCount: Int,
    val clickCount: Int,
    val isPinned: Boolean,
    val createdAt: Instant,
) {
    fun toDomain(mediaItems: List<PostMediaItem>): InstagramPost =
        InstagramPost(
            id = id,
            instagramAccountId = instagramAccountId,
            instagramMediaId = instagramMediaId,
            mediaType = mediaType,
            caption = caption,
            permalink = permalink,
            timestamp = timestamp,
            mediaItems = mediaItems,
            viewCount = viewCount,
            clickCount = clickCount,
            isPinned = isPinned,
            createdAt = createdAt,
        )
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
private data class UuidRow(
    val id: Uuid,
)

/**
 * Semi-join predicate: "this post's catalog row is still admin-selected". Expects the
 * `instagram_posts` row to be aliased `p`.
 *
 * Shared verbatim between the gallery page query and the retention sweep because they are two
 * halves of one rule - a post is visible exactly while it is selected, and becomes eligible for
 * deletion exactly when it stops being. Writing the condition once means the two can never drift
 * into a state where something is invisible but also never reaped, or reaped while still on show.
 */
private const val SELECTED_IN_CATALOG =
    """EXISTS (
           SELECT 1 FROM instagram_media_catalog c
           WHERE c.instagram_media_id = p.instagram_media_id AND c.selected = TRUE
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
     * One page of an account's posts, plus the total number of posts it has, for offset pagination.
     *
     * Sort column is selected via a fixed enum->literal mapping, never interpolated from
     * unvalidated client input, to avoid SQL injection through the `sort` query parameter. Both
     * orderings carry `id` as a tiebreaker: `timestamp`/`view_count` alone are not unique, and
     * without a total order Postgres is free to return a tied row on two different pages (or on
     * neither) - the same reasoning that already applies to [findEvictionCandidates].
     *
     * Only posts whose catalog row is still `selected` are returned. A deselected post keeps its
     * row and its S3 objects for the retention grace period so the decision stays reversible for
     * free, but it must leave the gallery the moment it is deselected - so visibility is derived
     * from `selected` here rather than from the mere existence of a post row. The same semi-join
     * shape as [findEvictionCandidates]'s, and it is applied to the `COUNT(*)` as well as the row
     * query: a total that described a different set of rows than the page it accompanies would be
     * worse than no total at all.
     *
     * The `COUNT(*)` and the row query share one handle (one pooled connection, not two). They are
     * not wrapped in a transaction: a concurrent ingestion cycle could in principle land a post
     * between them, which at worst shifts one row across a page boundary. That is not worth
     * holding a transaction open for on a read-only endpoint.
     *
     * Media items are fetched in one batched follow-up query (not per-post) to avoid N+1.
     */
    fun findPage(
        accountId: String,
        sort: SortOrder,
        pageSize: Int,
        page: Int,
    ): Page<InstagramPost> {
        val orderByClause =
            when (sort) {
                SortOrder.RECENT -> "p.timestamp DESC, p.id"
                SortOrder.VIEWS -> "p.view_count DESC, p.id"
            }

        return jdbi.withHandle<Page<InstagramPost>, Exception> { handle ->
            val totalRecords =
                handle
                    .createQuery(
                        """
                        SELECT COUNT(*) FROM instagram_posts p
                        WHERE p.instagram_account_id = :accountId AND $SELECTED_IN_CATALOG
                        """.trimIndent(),
                    ).bind("accountId", accountId)
                    .mapTo<Int>()
                    .one()

            Page.of(totalRecords, pageSize, page) { offset ->
                val posts =
                    handle
                        .createQuery(
                            """
                            SELECT p.* FROM instagram_posts p
                            WHERE p.instagram_account_id = :accountId AND $SELECTED_IN_CATALOG
                            ORDER BY $orderByClause
                            LIMIT :limit OFFSET :offset
                            """.trimIndent(),
                        ).bind("accountId", accountId)
                        .bind("limit", pageSize)
                        .bind("offset", offset)
                        .mapTo<InstagramPostRow>()
                        .list()

                if (posts.isEmpty()) {
                    emptyList()
                } else {
                    val mediaByPost =
                        handle
                            .createQuery("SELECT * FROM instagram_post_media WHERE post_id IN (<ids>) ORDER BY position")
                            .bindList("ids", posts.map { it.id })
                            .mapTo<PostMediaItem>()
                            .list()
                            .groupBy { it.postId }

                    posts.map { it.toDomain(mediaByPost[it.id].orEmpty()) }
                }
            }
        }
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
     * `accountId` scoping here isn't required for correctness (the UUID alone already uniquely
     * identifies the row) - it's a defensive tenant check, so a track call carrying a UUID that
     * belongs to a *different* account returns the same "0 rows affected" the caller already
     * treats as "post not found", rather than silently succeeding across a tenant boundary.
     */
    fun incrementViewCount(
        id: Uuid,
        accountId: String,
    ): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle
                .createUpdate(
                    "UPDATE instagram_posts SET view_count = view_count + 1 WHERE id = :id AND instagram_account_id = :accountId",
                ).bind("id", id)
                .bind("accountId", accountId)
                .execute()
        }

    fun incrementClickCount(
        id: Uuid,
        accountId: String,
    ): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle
                .createUpdate(
                    "UPDATE instagram_posts SET click_count = click_count + 1 WHERE id = :id AND instagram_account_id = :accountId",
                ).bind("id", id)
                .bind("accountId", accountId)
                .execute()
        }

    /**
     * Posts whose downloaded media is now due for deletion: deselected long enough ago that the
     * retention grace period has elapsed, or explicitly purge-requested by an admin.
     *
     * The grace period exists so deselection is reversible for free - within the window the media
     * is still in the bucket, so a reselect costs no re-download (`existingMediaIds` still covers
     * the item and `SyncPipeline` skips it). Reselecting clears both timestamps, which is what makes
     * a reselected item ineligible here and restarts the countdown from scratch on a later
     * deselection rather than resuming the old one.
     *
     * `selected = FALSE` is required regardless of which clock fired: evicting a still-selected post
     * would only have `SyncPipeline` re-download it next cycle, an unbounded download/delete loop.
     *
     * [is_pinned][knurl.domain.models.InstagramPost.isPinned] defeats the ordinary time-based sweep,
     * but **not an explicit purge request**. A pin is a passive "retention must not reap this"
     * escape hatch; an admin naming the item in a DELETE request is an active instruction, and
     * silently ignoring it would be the worse surprise of the two.
     *
     * Scoped to one account so retention is computed within that account's own pool, never blended
     * with another account's posts sharing this database. Media paths (for the S3 delete that
     * follows the row delete) are fetched in one batched follow-up query, same pattern as [findPage].
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
                          AND c.selected = FALSE
                          AND (
                                c.purge_requested_at IS NOT NULL
                                OR (c.deselected_at IS NOT NULL AND c.deselected_at < :deselectedBefore)
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
     * Deletes a post **unless it has been reselected since it was chosen for eviction**, returning
     * the number of rows affected (1 = deleted, 0 = reselected, leave its S3 objects alone).
     *
     * The guard is not redundant with [findEvictionCandidates]'s own `selected = FALSE` filter.
     * That query snapshots the candidate list at the top of the sweep, and candidates then drain
     * through a concurrency semaphore - an admin reselecting in that gap would otherwise have the
     * media deleted out from under a post that is once again live, costing a full re-download and
     * leaving a gallery gap until the next cycle. Doing the re-check inside the DELETE itself makes
     * eviction atomic with respect to a reselect, which no amount of re-reading beforehand can.
     *
     * Named for the guard rather than `deleteById` so a later caller cannot assume an unconditional
     * delete and quietly drop it. `instagram_post_media` rows go via ON DELETE CASCADE.
     */
    fun deleteIfNotSelected(id: Uuid): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle
                .createUpdate(
                    """
                    DELETE FROM instagram_posts p
                    WHERE p.id = :id AND NOT $SELECTED_IN_CATALOG
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
     * Unconditional, unlike [deleteIfNotSelected]: there is no reselect race to protect against
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
     * shown (the gallery filters on `selected`) nor reaped (retention joins the catalog), so it
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
 * Nothing here interpolates a client-supplied value. The `selected` and `not_digestible_reason`
 * clauses are fixed literals chosen by a type-safe `when`, and media types appear only as a JDBI
 * `<mediaTypes>` list-binding placeholder expanded into real bind parameters. An empty
 * [CatalogFilter.mediaTypes] omits that clause entirely - it means "no restriction", and JDBI
 * rejects an empty `bindList` regardless.
 */
private fun catalogFilterClause(filter: CatalogFilter): String =
    buildString {
        when (filter.selected) {
            null -> Unit
            true -> append(" AND selected = TRUE")
            false -> append(" AND selected = FALSE")
        }
        if (!filter.includeNotDigestible) append(" AND not_digestible_reason IS NULL")
        if (filter.mediaTypes.isNotEmpty()) append(" AND media_type IN (<mediaTypes>)")
    }

/** Binds the parameters referenced by [catalogFilterClause]; see there for why only media types need binding. */
private fun Query.bindCatalogFilter(filter: CatalogFilter): Query =
    if (filter.mediaTypes.isEmpty()) this else bindList("mediaTypes", filter.mediaTypes.sorted())

/** One `RETURNING` row from [CatalogRepository.requestPurge] - the shortcode plus whether it had anything downloaded. */
private data class PurgeRow(
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
     */
    fun upsert(entry: CatalogUpsert) {
        jdbi.useHandle<Exception> { handle ->
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
        }
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

    /** Used by the ingestion pipeline to decide which catalog items to download/store as posts. */
    fun selectedMediaIds(accountId: String): Set<String> =
        jdbi.withHandle<Set<String>, Exception> { handle ->
            handle
                .createQuery(
                    "SELECT instagram_media_id FROM instagram_media_catalog WHERE instagram_account_id = :accountId AND selected = TRUE",
                ).bind("accountId", accountId)
                .mapTo<String>()
                .toSet()
        }

    /** Used by the ingestion pipeline to decide which catalog items still need a thumbnail fetched, independent of [selectedMediaIds]. */
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
     * the `selected`/`not_digestible_reason` clauses are fixed literals chosen by a type-safe
     * `when` (same pattern as [InstagramPostRepository.findPage]'s `orderByClause`) and media types
     * go through JDBI's `bindList`, i.e. real bind parameters.
     *
     * `ORDER BY` carries `instagram_media_id` (the primary key) as a tiebreaker because `timestamp`
     * alone is not unique and offset paging needs a total order - see [InstagramPostRepository.findPage].
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
            }
        }
    }

    /**
     * Bulk select/deselect keyed by `shortcode` (the human-facing identifier admins work with),
     * not `instagram_media_id`. Each requested set that's empty skips its round trip entirely.
     * Scoped to `accountId` even though shortcodes are already globally unique, so a copy-paste
     * mistake naming a shortcode from a *different* tracked account shows up as `notFound` rather
     * than silently cross-selecting another account's content.
     *
     * Uses `UPDATE ... RETURNING shortcode` executed via `createQuery` (not `createUpdate`) since
     * JDBI's `Query` delegates to `PreparedStatement.executeQuery()`, which pgjdbc supports for
     * any statement carrying a `RETURNING` clause - the returned rows tell the caller exactly
     * which of the requested shortcodes actually existed (for this account).
     */
    fun updateSelection(
        accountId: String,
        select: Set<String>,
        deselect: Set<String>,
    ): CatalogSelectionResult {
        val selected = if (select.isEmpty()) emptySet() else applySelection(accountId, select, newValue = true)
        val deselected = if (deselect.isEmpty()) emptySet() else applySelection(accountId, deselect, newValue = false)
        return CatalogSelectionResult(selectedShortcodes = selected, deselectedShortcodes = deselected)
    }

    /**
     * Also maintains the deletion clock, which is why this is one statement rather than a plain
     * flag flip:
     *
     * - **Deselect** stamps `deselected_at`, starting the retention grace period. `COALESCE` keeps
     *   any existing stamp, so deselecting an already-deselected item does not push its deletion
     *   date out - an admin UI that submits its whole baseline on every save would otherwise keep
     *   the item alive indefinitely.
     * - **Reselect** clears both `deselected_at` and `purge_requested_at`, which is what makes a
     *   reselected item ineligible for deletion. Clearing `deselected_at` is also why a *later*
     *   deselection starts a fresh countdown instead of resuming the old one: the `COALESCE` above
     *   then has nothing to coalesce onto.
     * - `purge_requested_at` must be cleared on reselect too. Left armed, it would make the next
     *   ordinary deselection delete the item instantly with no grace period at all - a surprising
     *   result to reach from two unrelated actions taken days apart.
     */
    private fun applySelection(
        accountId: String,
        shortcodes: Set<String>,
        newValue: Boolean,
    ): Set<String> =
        jdbi.withHandle<Set<String>, Exception> { handle ->
            handle
                .createQuery(
                    """
                    UPDATE instagram_media_catalog
                    SET selected = :newValue,
                        deselected_at = CASE
                            WHEN :newValue THEN NULL
                            ELSE COALESCE(deselected_at, CURRENT_TIMESTAMP)
                        END,
                        purge_requested_at = CASE WHEN :newValue THEN NULL ELSE purge_requested_at END
                    WHERE instagram_account_id = :accountId AND shortcode = ANY(:shortcodes)
                    RETURNING shortcode
                    """.trimIndent(),
                ).bind("newValue", newValue)
                .bind("accountId", accountId)
                .bindArray("shortcodes", String::class.java, shortcodes)
                .mapTo<String>()
                .toSet()
        }

    /**
     * Arms an immediate purge of the given items' downloaded media: deselects them (so they leave
     * the gallery at once and cannot be re-downloaded next cycle) and stamps `purge_requested_at`,
     * which makes the next ingestion cycle's retention sweep delete their `instagram_posts` rows and
     * S3 objects without waiting out the grace period.
     *
     * The catalog row itself deliberately survives, along with its browse thumbnail - so the item
     * stays visible in the admin catalog and can be selected again later, re-downloading fresh.
     * Deleting the catalog row would be futile anyway: it is an unconditional mirror of the
     * Instagram feed, so the next sync would simply re-insert it.
     *
     * `deselected_at` is stamped alongside so the two lifecycle columns can never disagree about
     * whether the item is on a clock.
     *
     * One round trip yields both outcomes: `RETURNING` names the rows that existed (the rest were
     * not found for this account), and the `EXISTS` tells them apart into "media will be deleted"
     * and "there was nothing downloaded to delete". Account-scoped for the same reason
     * [applySelection] is - a shortcode belonging to a *different* tracked account must report as
     * not found rather than silently purge across a tenant boundary.
     */
    fun requestPurge(
        accountId: String,
        shortcodes: Set<String>,
    ): PurgeRequestResult {
        if (shortcodes.isEmpty()) return PurgeRequestResult(accepted = emptySet(), notInGallery = emptySet())

        val rows =
            jdbi.withHandle<List<PurgeRow>, Exception> { handle ->
                handle
                    .createQuery(
                        """
                        UPDATE instagram_media_catalog c
                        SET selected = FALSE,
                            deselected_at = COALESCE(c.deselected_at, CURRENT_TIMESTAMP),
                            purge_requested_at = COALESCE(c.purge_requested_at, CURRENT_TIMESTAMP)
                        WHERE c.instagram_account_id = :accountId AND c.shortcode = ANY(:shortcodes)
                        RETURNING c.shortcode,
                                  EXISTS (
                                      SELECT 1 FROM instagram_posts p
                                      WHERE p.instagram_media_id = c.instagram_media_id
                                  ) AS had_post
                        """.trimIndent(),
                    ).bind("accountId", accountId)
                    .bindArray("shortcodes", String::class.java, shortcodes)
                    .mapTo<PurgeRow>()
                    .list()
            }

        return PurgeRequestResult(
            accepted = rows.filter { it.hadPost }.map { it.shortcode }.toSet(),
            notInGallery = rows.filterNot { it.hadPost }.map { it.shortcode }.toSet(),
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
