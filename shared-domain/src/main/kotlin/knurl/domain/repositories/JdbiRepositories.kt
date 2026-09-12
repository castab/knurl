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
                SortOrder.RECENT -> "timestamp DESC, id"
                SortOrder.VIEWS -> "view_count DESC, id"
            }

        return jdbi.withHandle<Page<InstagramPost>, Exception> { handle ->
            val totalRecords =
                handle
                    .createQuery("SELECT COUNT(*) FROM instagram_posts WHERE instagram_account_id = :accountId")
                    .bind("accountId", accountId)
                    .mapTo<Int>()
                    .one()

            Page.of(totalRecords, pageSize, page) { offset ->
                val posts =
                    handle
                        .createQuery(
                            """
                            SELECT * FROM instagram_posts
                            WHERE instagram_account_id = :accountId
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
                    INSERT INTO instagram_post_media (post_id, position, media_type, small_path, large_path, video_path)
                    VALUES (:postId, :position, :mediaType, :smallPath, :largePath, :videoPath)
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
     * Eviction candidates are posts that are not pinned, **not currently admin-selected**, and outside
     * BOTH the top [maxRecentCount] by recency AND the top [maxViewCount] by views - i.e. a post
     * survives if it is pinned, selected, or in either top-N set. The `selected` exclusion is what
     * stops eviction from deleting media that `SyncPipeline` would immediately re-download on the next
     * cycle (an unbounded download/delete loop). Ranking uses `id` as a
     * tiebreaker so the window is stable across cycles when many posts share a view count.
     * Scoped to one account so retention is computed within that account's own pool, never
     * blended with another account's posts sharing this database. Media paths (for the S3 delete
     * that has to happen before the DB row itself is deleted) are fetched in one batched
     * follow-up query, same pattern as [findAll].
     */
    fun findEvictionCandidates(
        accountId: String,
        maxRecentCount: Int,
        maxViewCount: Int,
    ): List<EvictionCandidate> =
        jdbi.withHandle<List<EvictionCandidate>, Exception> { handle ->
            val candidateIds =
                handle
                    .createQuery(
                        """
                        WITH ranked AS (
                            SELECT p.id,
                                   ROW_NUMBER() OVER (ORDER BY p.timestamp DESC, p.id)  AS recent_rank,
                                   ROW_NUMBER() OVER (ORDER BY p.view_count DESC, p.id) AS view_rank
                            FROM instagram_posts p
                            WHERE p.is_pinned = FALSE
                              AND p.instagram_account_id = :accountId
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM instagram_media_catalog c
                                  WHERE c.instagram_media_id = p.instagram_media_id
                                    AND c.selected = TRUE
                              )
                        )
                        SELECT id
                        FROM ranked
                        WHERE recent_rank > :maxRecentCount AND view_rank > :maxViewCount
                        """.trimIndent(),
                    ).bind("accountId", accountId)
                    .bind("maxRecentCount", maxRecentCount)
                    .bind("maxViewCount", maxViewCount)
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

    fun deleteById(id: Uuid): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle
                .createUpdate("DELETE FROM instagram_posts WHERE id = :id")
                .bind("id", id)
                .execute()
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
                    SET selected = :newValue
                    WHERE instagram_account_id = :accountId AND shortcode = ANY(:shortcodes)
                    RETURNING shortcode
                    """.trimIndent(),
                ).bind("newValue", newValue)
                .bind("accountId", accountId)
                .bindArray("shortcodes", String::class.java, shortcodes)
                .mapTo<String>()
                .toSet()
        }
}
