package knurl.domain.repositories

import knurl.domain.models.Gallery
import knurl.domain.models.GalleryDeleteResult
import knurl.domain.models.GalleryItemsResult
import knurl.domain.models.GalleryPost
import knurl.domain.models.InstagramPost
import knurl.domain.models.Page
import knurl.domain.models.PostMediaItem
import knurl.domain.models.SortOrder
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import java.time.Instant
import kotlin.uuid.Uuid

/** Raw `galleries` row plus its two computed counts - mirrors what [GalleryRepository]'s queries select. */
private data class GalleryRow(
    val id: Uuid,
    val instagramAccountId: String,
    val name: String,
    val itemCount: Int,
    val publishedCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun toDomain(): Gallery =
        Gallery(
            id = id,
            instagramAccountId = instagramAccountId,
            name = name,
            itemCount = itemCount,
            publishedCount = publishedCount,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}

/**
 * Raw gallery-content row: an `instagram_posts` row widened with that gallery's own counters.
 *
 * A separate type from `InstagramPostRow` deliberately - that one mirrors `instagram_posts` exactly,
 * and the counters are not columns of that table.
 */
private data class GalleryPostRow(
    val id: Uuid,
    val instagramAccountId: String,
    val instagramMediaId: String,
    val mediaType: String,
    val caption: String?,
    val permalink: String,
    val timestamp: Instant,
    val isPinned: Boolean,
    val createdAt: Instant,
    val viewCount: Int,
    val clickCount: Int,
) {
    fun toDomain(
        galleryId: Uuid,
        mediaItems: List<PostMediaItem>,
    ): GalleryPost =
        GalleryPost(
            galleryId = galleryId,
            post =
                InstagramPost(
                    id = id,
                    instagramAccountId = instagramAccountId,
                    instagramMediaId = instagramMediaId,
                    mediaType = mediaType,
                    caption = caption,
                    permalink = permalink,
                    timestamp = timestamp,
                    mediaItems = mediaItems,
                    isPinned = isPinned,
                    createdAt = createdAt,
                ),
            viewCount = viewCount,
            clickCount = clickCount,
        )
}

/** One shortcode that [GalleryRepository.updateItems] resolved, with whether the insert actually created a row. */
private data class AddedRow(
    val shortcode: String,
    val instagramMediaId: String,
    val inserted: Boolean,
)

/** One shortcode that [GalleryRepository.updateItems] removed, with the media id whose clock may now need stamping. */
private data class RemovedRow(
    val shortcode: String,
    val instagramMediaId: String,
)

/**
 * The `galleries` and `gallery_items` tables: naming, membership, and the public content query.
 *
 * Membership here is what the old `instagram_media_catalog.selected` flag used to be, generalised
 * from one implicit gallery to many named ones. Belonging to *any* gallery is what makes ingestion
 * download an item and what protects it from the retention sweep; leaving the last one is what
 * starts its deletion clock.
 *
 * **Every method that changes membership takes the catalog row lock first**, and that is load-bearing
 * rather than defensive - see [updateItems] for the exact race it closes.
 */
class GalleryRepository(
    private val jdbi: Jdbi,
) {
    /**
     * `itemCount` is how many items the admin has curated into the gallery; `publishedCount` is how
     * many of those actually have downloaded media and therefore appear in it. The two differ while
     * ingestion catches up, which is exactly the state an admin needs to be able to see - otherwise
     * a freshly curated item just looks missing.
     */
    private fun selectGalleryColumns(where: String): String =
        """
        SELECT g.id, g.instagram_account_id, g.name, g.created_at, g.updated_at,
               COUNT(gi.instagram_media_id) AS item_count,
               COUNT(p.id) AS published_count
        FROM galleries g
        LEFT JOIN gallery_items gi ON gi.gallery_id = g.id
        LEFT JOIN instagram_posts p ON p.instagram_media_id = gi.instagram_media_id
        WHERE $where
        GROUP BY g.id
        """.trimIndent()

    /**
     * Creates a gallery, or returns null if the account already has one whose name differs only by
     * case or surrounding whitespace.
     *
     * The collision is detected by catching the unique-violation rather than by checking first,
     * because a check-then-insert is a race: two concurrent creates would both see no conflict.
     */
    fun create(
        accountId: String,
        name: String,
    ): Gallery? =
        try {
            jdbi.withHandle<Gallery?, Exception> { handle ->
                val id =
                    handle
                        .createQuery(
                            """
                            INSERT INTO galleries (instagram_account_id, name)
                            VALUES (:accountId, :name)
                            RETURNING id
                            """.trimIndent(),
                        ).bind("accountId", accountId)
                        .bind("name", name)
                        .mapTo<UuidRow>()
                        .one()
                        .id
                findWithHandle(handle, accountId, id)
            }
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) null else throw e
        }

    /**
     * Renames a gallery, returning null if it isn't this account's and throwing nothing special if
     * the new name collides - the caller distinguishes those two cases, so the collision comes back
     * as a [GalleryNameTaken] to keep them apart from "not found".
     *
     * `updated_at` is set explicitly here: this schema has no triggers, so a timestamp column that
     * nothing writes would quietly always equal `created_at`.
     */
    fun rename(
        accountId: String,
        id: Uuid,
        name: String,
    ): Gallery? =
        try {
            jdbi.withHandle<Gallery?, Exception> { handle ->
                val updated =
                    handle
                        .createUpdate(
                            """
                            UPDATE galleries
                            SET name = :name, updated_at = CURRENT_TIMESTAMP
                            WHERE id = :id AND instagram_account_id = :accountId
                            """.trimIndent(),
                        ).bind("name", name)
                        .bind("id", id)
                        .bind("accountId", accountId)
                        .execute()

                if (updated == 0) null else findWithHandle(handle, accountId, id)
            }
        } catch (e: UnableToExecuteStatementException) {
            if (e.isUniqueViolation()) throw GalleryNameTaken(name) else throw e
        }

    fun find(
        accountId: String,
        id: Uuid,
    ): Gallery? = jdbi.withHandle<Gallery?, Exception> { handle -> findWithHandle(handle, accountId, id) }

    private fun findWithHandle(
        handle: Handle,
        accountId: String,
        id: Uuid,
    ): Gallery? =
        handle
            .createQuery(selectGalleryColumns("g.id = :id AND g.instagram_account_id = :accountId"))
            .bind("id", id)
            .bind("accountId", accountId)
            .mapTo<GalleryRow>()
            .findFirst()
            .orElse(null)
            ?.toDomain()

    /**
     * One page of an account's galleries, ordered by name so a picker reads sensibly.
     *
     * `lower(btrim(name))` matches the unique index exactly, and `g.id` is the tiebreaker - two
     * galleries can't share a normalised name within an account, but the ordering expression must
     * still be a total order for offset paging to be correct.
     */
    fun findPage(
        accountId: String,
        pageSize: Int,
        page: Int,
    ): Page<Gallery> =
        jdbi.withHandle<Page<Gallery>, Exception> { handle ->
            val totalRecords =
                handle
                    .createQuery("SELECT COUNT(*) FROM galleries WHERE instagram_account_id = :accountId")
                    .bind("accountId", accountId)
                    .mapTo<Int>()
                    .one()

            Page.of(totalRecords, pageSize, page) { offset ->
                handle
                    .createQuery(
                        selectGalleryColumns("g.instagram_account_id = :accountId") +
                            "\nORDER BY lower(btrim(g.name)), g.id\nLIMIT :limit OFFSET :offset",
                    ).bind("accountId", accountId)
                    .bind("limit", pageSize)
                    .bind("offset", offset)
                    .mapTo<GalleryRow>()
                    .list()
                    .map { it.toDomain() }
            }
        }

    /**
     * One page of a gallery's content: the posts it holds, carrying that gallery's own counters.
     *
     * Only members with downloaded media appear - the join to `instagram_posts` is what enforces
     * that, so an item added moments ago is simply absent until ingestion fetches it. `publishedCount`
     * on the gallery itself is how an admin sees that gap rather than guessing at it.
     *
     * Both orderings tiebreak on `gi.instagram_media_id`, not `p.id`. Within a fixed `gallery_id`
     * the media id is unique (it is the other half of the primary key), so it is a valid total order
     * *and* it keeps `idx_gallery_items_views` able to serve the whole `sort=views` ordering. A `p.id`
     * tiebreaker would quietly make that index useless.
     *
     * Note that `sort=recent` orders by a column on `instagram_posts` while filtering on
     * `gallery_items`, so no single index can serve it: expect a join plus a sort of the gallery per
     * page. That is fine at the sizes galleries actually reach, but it is a real change from the
     * pure index scan the single-gallery query used to get.
     */
    fun findContentPage(
        galleryId: Uuid,
        sort: SortOrder,
        pageSize: Int,
        page: Int,
    ): Page<GalleryPost> {
        val orderByClause =
            when (sort) {
                SortOrder.RECENT -> "p.timestamp DESC, gi.instagram_media_id"
                SortOrder.VIEWS -> "gi.view_count DESC, gi.instagram_media_id"
            }

        return jdbi.withHandle<Page<GalleryPost>, Exception> { handle ->
            val totalRecords =
                handle
                    .createQuery(
                        """
                        SELECT COUNT(*)
                        FROM gallery_items gi
                        JOIN instagram_posts p ON p.instagram_media_id = gi.instagram_media_id
                        WHERE gi.gallery_id = :galleryId
                        """.trimIndent(),
                    ).bind("galleryId", galleryId)
                    .mapTo<Int>()
                    .one()

            Page.of(totalRecords, pageSize, page) { offset ->
                val posts =
                    handle
                        .createQuery(
                            """
                            SELECT p.id, p.instagram_account_id, p.instagram_media_id, p.media_type,
                                   p.caption, p.permalink, p.timestamp, p.is_pinned, p.created_at,
                                   gi.view_count, gi.click_count
                            FROM gallery_items gi
                            JOIN instagram_posts p ON p.instagram_media_id = gi.instagram_media_id
                            WHERE gi.gallery_id = :galleryId
                            ORDER BY $orderByClause
                            LIMIT :limit OFFSET :offset
                            """.trimIndent(),
                        ).bind("galleryId", galleryId)
                        .bind("limit", pageSize)
                        .bind("offset", offset)
                        .mapTo<GalleryPostRow>()
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

                    posts.map { it.toDomain(galleryId, mediaByPost[it.id].orEmpty()) }
                }
            }
        }
    }

    /**
     * Adds and removes gallery members by shortcode, maintaining the deletion clock as it goes.
     * Returns null if the gallery isn't this account's.
     *
     * **The catalog row lock at the top is not optional.** Without it, two admins removing the same
     * item from two *different* galleries at the same time both fail to stamp `deselected_at`: under
     * READ COMMITTED each transaction's "are there memberships left?" check takes a fresh statement
     * snapshot that still sees the *other's* uncommitted row, so both conclude a membership remains
     * and neither stamps. They never touch a common row, so there is no serialization failure to
     * retry either - the item simply ends up in zero galleries with no clock running, invisible
     * everywhere and never reaped. Locking the shared catalog row serialises them, and ordering the
     * lock by media id stops two multi-item batches from deadlocking each other.
     *
     * **The add is scoped on both sides.** Shortcodes are globally unique rather than per-account, so
     * joining only `galleries` to the account would let one account add another account's media to
     * its own public gallery. The join requires the catalog row and the gallery to agree on the
     * account, and a shortcode that fails that test comes back as `notFound` - the same way a
     * typo'd one does.
     *
     * Adds clear both lifecycle timestamps (the item is live again); removes stamp `deselected_at`
     * only for items that end the transaction with no memberships left, using `COALESCE` so an item
     * removed from its second-to-last gallery keeps any earlier deadline rather than having it
     * pushed out.
     */
    fun updateItems(
        accountId: String,
        galleryId: Uuid,
        add: Set<String>,
        remove: Set<String>,
    ): GalleryItemsResult? =
        jdbi.inTransaction<GalleryItemsResult?, Exception> { handle ->
            val ownsGallery =
                handle
                    .createQuery("SELECT 1 FROM galleries WHERE id = :id AND instagram_account_id = :accountId")
                    .bind("id", galleryId)
                    .bind("accountId", accountId)
                    .mapTo<Int>()
                    .findFirst()
                    .isPresent
            if (!ownsGallery) return@inTransaction null

            lockCatalogRows(handle, accountId, add + remove)

            val added = if (add.isEmpty()) emptyList() else addItems(handle, accountId, galleryId, add)
            val removed = if (remove.isEmpty()) emptyList() else removeItems(handle, accountId, galleryId, remove)

            if (added.isNotEmpty()) {
                handle
                    .createUpdate(
                        """
                        UPDATE instagram_media_catalog
                        SET deselected_at = NULL, purge_requested_at = NULL
                        WHERE instagram_media_id = ANY(:mediaIds)
                        """.trimIndent(),
                    ).bindArray("mediaIds", String::class.java, added.map { it.instagramMediaId })
                    .execute()
            }

            stampOrphanedItems(handle, removed.map { it.instagramMediaId })

            val resolved = added.map { it.shortcode }.toSet() + removed.map { it.shortcode }.toSet()
            GalleryItemsResult(
                added = added.filter { it.inserted }.map { it.shortcode }.toSet(),
                alreadyPresent = added.filterNot { it.inserted }.map { it.shortcode }.toSet(),
                removed = removed.map { it.shortcode }.toSet(),
                notFound = (add + remove) - resolved,
            )
        }

    private fun addItems(
        handle: Handle,
        accountId: String,
        galleryId: Uuid,
        shortcodes: Set<String>,
    ): List<AddedRow> =
        handle
            .createQuery(
                """
                WITH requested AS (
                    SELECT c.shortcode, c.instagram_media_id
                    FROM galleries g
                    JOIN instagram_media_catalog c ON c.instagram_account_id = g.instagram_account_id
                    WHERE g.id = :galleryId
                      AND g.instagram_account_id = :accountId
                      AND c.shortcode = ANY(:shortcodes)
                ), inserted AS (
                    INSERT INTO gallery_items (gallery_id, instagram_media_id)
                    SELECT :galleryId, instagram_media_id FROM requested
                    ON CONFLICT (gallery_id, instagram_media_id) DO NOTHING
                    RETURNING instagram_media_id
                )
                SELECT r.shortcode,
                       r.instagram_media_id,
                       (r.instagram_media_id IN (SELECT instagram_media_id FROM inserted)) AS inserted
                FROM requested r
                """.trimIndent(),
            ).bind("galleryId", galleryId)
            .bind("accountId", accountId)
            .bindArray("shortcodes", String::class.java, shortcodes)
            .mapTo<AddedRow>()
            .list()

    private fun removeItems(
        handle: Handle,
        accountId: String,
        galleryId: Uuid,
        shortcodes: Set<String>,
    ): List<RemovedRow> =
        handle
            .createQuery(
                """
                DELETE FROM gallery_items gi
                USING instagram_media_catalog c
                WHERE gi.gallery_id = :galleryId
                  AND gi.instagram_media_id = c.instagram_media_id
                  AND c.instagram_account_id = :accountId
                  AND c.shortcode = ANY(:shortcodes)
                RETURNING c.shortcode, c.instagram_media_id
                """.trimIndent(),
            ).bind("galleryId", galleryId)
            .bind("accountId", accountId)
            .bindArray("shortcodes", String::class.java, shortcodes)
            .mapTo<RemovedRow>()
            .list()

    /**
     * Deletes a gallery and reports what that cost, or null if it isn't this account's.
     *
     * Member media ids are captured *before* the delete, because `ON DELETE CASCADE` clears
     * `gallery_items` as the gallery goes and there would be nothing left to ask afterwards. The
     * items that end up in no gallery at all then get their clock stamped - which is the number
     * worth surfacing to the admin, since deleting a gallery is otherwise a very quiet way to
     * schedule a lot of media for deletion.
     */
    fun delete(
        accountId: String,
        id: Uuid,
    ): GalleryDeleteResult? =
        jdbi.inTransaction<GalleryDeleteResult?, Exception> { handle ->
            val gallery = findWithHandle(handle, accountId, id) ?: return@inTransaction null

            val memberIds =
                handle
                    .createQuery("SELECT instagram_media_id FROM gallery_items WHERE gallery_id = :id")
                    .bind("id", id)
                    .mapTo<String>()
                    .list()

            lockCatalogRows(handle, accountId, memberIds.toSet(), byShortcode = false)

            handle
                .createUpdate("DELETE FROM galleries WHERE id = :id AND instagram_account_id = :accountId")
                .bind("id", id)
                .bind("accountId", accountId)
                .execute()

            val released = stampOrphanedItems(handle, memberIds)

            GalleryDeleteResult(name = gallery.name, itemsRemoved = memberIds.size, itemsReleased = released)
        }

    /**
     * Names of this account's galleries that currently hold any of [mediaIds], for logging.
     *
     * Exists because media vanishing from Instagram silently empties a slot in a gallery an admin
     * curated by hand. The deletion itself is correct and unavoidable, but "3 items disappeared" is
     * a much worse operational message than naming the galleries it happened in.
     */
    fun galleryNamesHolding(
        accountId: String,
        mediaIds: Set<String>,
    ): List<String> {
        if (mediaIds.isEmpty()) return emptyList()
        return jdbi.withHandle<List<String>, Exception> { handle ->
            handle
                .createQuery(
                    """
                    SELECT DISTINCT g.name
                    FROM galleries g
                    JOIN gallery_items gi ON gi.gallery_id = g.id
                    WHERE g.instagram_account_id = :accountId AND gi.instagram_media_id = ANY(:mediaIds)
                    ORDER BY g.name
                    """.trimIndent(),
                ).bind("accountId", accountId)
                .bindArray("mediaIds", String::class.java, mediaIds)
                .mapTo<String>()
                .list()
        }
    }

    fun incrementViewCount(
        galleryId: Uuid,
        postId: Uuid,
        accountId: String,
    ): Int = incrementCounter(galleryId, postId, accountId, "view_count")

    fun incrementClickCount(
        galleryId: Uuid,
        postId: Uuid,
        accountId: String,
    ): Int = incrementCounter(galleryId, postId, accountId, "click_count")

    /**
     * [column] is never client input - it comes from a fixed literal at the two call sites above,
     * the same enum-to-literal discipline [findContentPage]'s `orderByClause` uses.
     *
     * `accountId` is a defensive tenant check rather than a correctness requirement (the gallery id
     * already implies the account), so a post or gallery belonging to another account falls through
     * to the same "0 rows affected" the caller already reads as not-found.
     */
    private fun incrementCounter(
        galleryId: Uuid,
        postId: Uuid,
        accountId: String,
        column: String,
    ): Int =
        jdbi.withHandle<Int, Exception> { handle ->
            handle
                .createUpdate(
                    """
                    UPDATE gallery_items gi
                    SET $column = gi.$column + 1
                    FROM galleries g, instagram_posts p
                    WHERE gi.gallery_id = :galleryId
                      AND g.id = gi.gallery_id
                      AND g.instagram_account_id = :accountId
                      AND p.id = :postId
                      AND p.instagram_media_id = gi.instagram_media_id
                    """.trimIndent(),
                ).bind("galleryId", galleryId)
                .bind("postId", postId)
                .bind("accountId", accountId)
                .execute()
        }

    /**
     * Takes a row lock on the catalog rows a membership change is about to affect, in media-id order.
     * See [updateItems] for the race this closes and why the ordering matters.
     */
    private fun lockCatalogRows(
        handle: Handle,
        accountId: String,
        keys: Set<String>,
        byShortcode: Boolean = true,
    ) {
        if (keys.isEmpty()) return
        val keyColumn = if (byShortcode) "shortcode" else "instagram_media_id"
        handle
            .createQuery(
                """
                SELECT 1 FROM instagram_media_catalog
                WHERE instagram_account_id = :accountId AND $keyColumn = ANY(:keys)
                ORDER BY instagram_media_id
                FOR UPDATE
                """.trimIndent(),
            ).bind("accountId", accountId)
            .bindArray("keys", String::class.java, keys)
            .mapTo<Int>()
            .list()
    }

    /**
     * Stamps `deselected_at` on any of [mediaIds] that now belongs to no gallery at all, returning
     * how many were stamped. `COALESCE` means an item that was already on a clock keeps its original
     * deadline rather than having it pushed out by an unrelated removal elsewhere.
     */
    private fun stampOrphanedItems(
        handle: Handle,
        mediaIds: List<String>,
    ): Int {
        if (mediaIds.isEmpty()) return 0
        return handle
            .createUpdate(
                """
                UPDATE instagram_media_catalog c
                SET deselected_at = COALESCE(c.deselected_at, CURRENT_TIMESTAMP)
                WHERE c.instagram_media_id = ANY(:mediaIds)
                  AND c.deselected_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM gallery_items gi WHERE gi.instagram_media_id = c.instagram_media_id
                  )
                """.trimIndent(),
            ).bindArray("mediaIds", String::class.java, mediaIds)
            .execute()
    }
}

/** Thrown by [GalleryRepository.rename] when the account already has a gallery with that normalised name. */
class GalleryNameTaken(
    val name: String,
) : RuntimeException("a gallery named '$name' already exists for this account")

/**
 * Whether this JDBI failure is Postgres's unique-violation (23505), i.e. the gallery name index
 * rejected a duplicate. Detected on the way out rather than by checking first, because
 * check-then-insert is a race two concurrent creates would both win.
 */
private fun UnableToExecuteStatementException.isUniqueViolation(): Boolean =
    (cause as? PSQLException)?.sqlState == PSQLState.UNIQUE_VIOLATION.state
