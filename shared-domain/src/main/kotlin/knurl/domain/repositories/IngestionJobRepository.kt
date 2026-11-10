package knurl.domain.repositories

import knurl.domain.models.Page
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import java.time.Clock
import java.time.Instant
import kotlin.uuid.Uuid

data class IngestionJob(
    val id: Uuid,
    val instagramAccountId: String,
    val source: String,
    val status: String,
    val phase: String,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val errorCode: String?,
    val attempts: Int,
    val succeeded: Int,
    val skipped: Int,
    val failed: Int,
    val pending: Int,
)

data class IngestionWork(
    val jobId: Uuid,
    val phase: String,
    val itemId: String,
    val status: String,
    val attempts: Int,
    val retryAt: Instant?,
    val errorCode: String?,
    val objectKeys: List<String>,
)

data class IngestionSchedule(
    val enabled: Boolean,
    val intervalSeconds: Long,
    val nextRunAt: Instant?,
)

/** One supported process, durable work. Short account-row locks serialize scheduling, enqueue, and destination edits. */
class IngestionJobRepository(
    private val jdbi: Jdbi,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun enqueue(
        accountId: String,
        source: String = "MANUAL",
    ): IngestionJob = jdbi.inTransaction<IngestionJob, Exception> { h -> enqueue(h, accountId, source) }

    private fun enqueue(
        h: Handle,
        id: String,
        source: String,
    ): IngestionJob {
        val enabled =
            h
                .createQuery(
                    "SELECT enabled FROM instagram_accounts WHERE instagram_account_id=:id FOR UPDATE",
                ).bind("id", id)
                .mapTo<Boolean>()
                .findOne()
                .orElse(false)
        if (!enabled) throw AccountUnavailable()
        val active =
            h
                .createQuery(
                    "SELECT id FROM ingestion_jobs WHERE instagram_account_id=:id AND status IN ('QUEUED','RUNNING')",
                ).bind("id", id)
                .map {
                    rs,
                    _,
                    ->
                    Uuid.parse(rs.getString(1))
                }.findOne()
                .orElse(null)
        val jobId =
            active
                ?: h
                    .createQuery(
                        "INSERT INTO ingestion_jobs (instagram_account_id,source) VALUES (:id,:source) RETURNING id",
                    ).bind("id", id)
                    .bind("source", source)
                    .map {
                        rs,
                        _,
                        ->
                        Uuid.parse(rs.getString(1))
                    }.one()
        h.createUpdate("UPDATE instagram_accounts SET next_run_at=NULL WHERE instagram_account_id=:id").bind("id", id).execute()
        return checkNotNull(find(h, id, jobId))
    }

    fun schedule(id: String): IngestionSchedule =
        jdbi.withHandle<IngestionSchedule, Exception> { h ->
            h
                .createQuery(
                    "SELECT schedule_enabled AS enabled,interval_seconds,next_run_at FROM instagram_accounts WHERE instagram_account_id=:id AND enabled",
                ).bind("id", id)
                .mapTo<IngestionSchedule>()
                .findOne()
                .orElseThrow { AccountUnavailable() }
        }

    fun updateSchedule(
        id: String,
        enabled: Boolean,
        intervalSeconds: Long,
    ): IngestionSchedule {
        require(intervalSeconds >= 300 && intervalSeconds <= 31536000) { "intervalSeconds must be between 300 and 31536000" }
        return jdbi.inTransaction<IngestionSchedule, Exception> { h ->
            val old =
                h
                    .createQuery(
                        "SELECT schedule_enabled AS enabled,interval_seconds,next_run_at FROM instagram_accounts WHERE instagram_account_id=:id AND enabled FOR UPDATE",
                    ).bind("id", id)
                    .mapTo<IngestionSchedule>()
                    .findOne()
                    .orElseThrow { AccountUnavailable() }
            val active =
                h
                    .createQuery(
                        "SELECT EXISTS (SELECT 1 FROM ingestion_jobs WHERE instagram_account_id=:id AND status IN ('QUEUED','RUNNING'))",
                    ).bind("id", id)
                    .mapTo<Boolean>()
                    .one()
            h
                .createUpdate(
                    """
                    UPDATE instagram_accounts SET schedule_enabled=:enabled,interval_seconds=:seconds,
                    next_run_at=CASE WHEN NOT :enabled OR :active THEN NULL ELSE CAST(:next AS TIMESTAMPTZ) END
                    WHERE instagram_account_id=:id
                    """.trimIndent(),
                ).bind("id", id)
                .bind("enabled", enabled)
                .bind("active", active)
                .bind("seconds", intervalSeconds)
                .bind("next", clock.instant().plusSeconds(intervalSeconds))
                .execute()
            if (enabled && !old.enabled) enqueue(h, id, "SCHEDULE")
            h
                .createQuery(
                    "SELECT schedule_enabled AS enabled,interval_seconds,next_run_at FROM instagram_accounts WHERE instagram_account_id=:id",
                ).bind("id", id)
                .mapTo<IngestionSchedule>()
                .one()
        }
    }

    /** At most one due account per tick: no coroutine or unbounded account list is created. */
    fun enqueueDue() {
        jdbi.inTransaction<Unit, Exception> { h ->
            val id =
                h
                    .createQuery(
                        """
                        SELECT instagram_account_id FROM instagram_accounts WHERE enabled AND schedule_enabled AND next_run_at <= CAST(:now AS TIMESTAMPTZ)
                        ORDER BY next_run_at,instagram_account_id FOR UPDATE SKIP LOCKED LIMIT 1
                        """.trimIndent(),
                    ).bind("now", clock.instant())
                    .mapTo<String>()
                    .findOne()
                    .orElse(null)
            if (id != null) enqueue(h, id, "SCHEDULE")
        }
    }

    /** Only called at startup, before workers start; multiple active processes are unsupported. */
    fun recover() {
        jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE ingestion_jobs SET status='QUEUED' WHERE status='RUNNING'")
            h.execute("UPDATE ingestion_work SET status='PENDING' WHERE status='RUNNING'")
            h.execute(
                """UPDATE ingestion_jobs j SET status='CANCELLED',phase='CANCELLED',completed_at=CURRENT_TIMESTAMP,error_code='ACCOUNT_DISABLED'
            FROM instagram_accounts a WHERE j.instagram_account_id=a.instagram_account_id AND (NOT a.enabled OR j.cancel_requested) AND j.status='QUEUED'""",
            )
        }
    }

    fun claim(): IngestionJob? =
        jdbi.inTransaction<IngestionJob?, Exception> { h ->
            val row =
                h
                    .createQuery(
                        """
                        SELECT j.id,j.instagram_account_id FROM ingestion_jobs j JOIN instagram_accounts a USING(instagram_account_id)
                        WHERE j.status='QUEUED' AND a.enabled ORDER BY j.created_at,j.id FOR UPDATE OF j SKIP LOCKED LIMIT 1
                        """.trimIndent(),
                    ).map { rs, _ -> rs.getString(1) to rs.getString(2) }
                    .findOne()
                    .orElse(null) ?: return@inTransaction null
            val id = Uuid.parse(row.first)
            h
                .createUpdate(
                    "UPDATE ingestion_jobs SET status='RUNNING',started_at=COALESCE(started_at,CURRENT_TIMESTAMP) WHERE id=:id",
                ).bind("id", id)
                .execute()
            find(h, row.second, id)
        }

    fun cancelRequested(id: Uuid): Boolean =
        jdbi.withHandle<Boolean, Exception> { h ->
            h
                .createQuery("SELECT cancel_requested FROM ingestion_jobs WHERE id=:id")
                .bind("id", id)
                .mapTo<Boolean>()
                .one()
        }

    fun isEnabled(id: String): Boolean =
        jdbi.withHandle<Boolean, Exception> { h ->
            h
                .createQuery(
                    "SELECT enabled FROM instagram_accounts WHERE instagram_account_id=:id",
                ).bind("id", id)
                .mapTo<Boolean>()
                .findOne()
                .orElse(false)
        }

    fun phase(
        jobId: Uuid,
        phase: String,
    ) {
        jdbi.useHandle<Exception> { h ->
            h
                .createUpdate(
                    "UPDATE ingestion_jobs SET phase=:phase WHERE id=:id",
                ).bind("id", jobId)
                .bind("phase", phase)
                .execute()
        }
    }

    fun finish(
        job: IngestionJob,
        status: String,
        error: String? = null,
    ) {
        jdbi.inTransaction<Unit, Exception> { h ->
            // Same lock order as enqueue and account changes.
            h
                .createQuery(
                    "SELECT instagram_account_id FROM instagram_accounts WHERE instagram_account_id=:id FOR UPDATE",
                ).bind("id", job.instagramAccountId)
                .mapTo<String>()
                .one()
            h
                .createUpdate(
                    "UPDATE ingestion_jobs SET status=:status,phase=:status,error_code=:error,completed_at=CURRENT_TIMESTAMP WHERE id=:id",
                ).bind("id", job.id)
                .bind("status", status)
                .bind("error", error)
                .execute()
            h
                .createUpdate(
                    """UPDATE instagram_accounts SET next_run_at=CASE WHEN enabled AND schedule_enabled THEN CAST(:now AS TIMESTAMPTZ) + ((CASE WHEN :cancelled THEN 0 ELSE interval_seconds END) * INTERVAL '1 second') ELSE NULL END
            WHERE instagram_account_id=:id""",
                ).bind("now", clock.instant())
                .bind("cancelled", status == "CANCELLED")
                .bind("id", job.instagramAccountId)
                .execute()
        }
    }

    fun find(
        accountId: String,
        id: Uuid,
    ): IngestionJob? = jdbi.withHandle<IngestionJob?, Exception> { find(it, accountId, id) }

    private fun find(
        h: Handle,
        accountId: String,
        id: Uuid,
    ): IngestionJob? =
        h
            .createQuery(
                "$JOBS WHERE j.instagram_account_id=:account AND j.id=:id",
            ).bind("account", accountId)
            .bind("id", id)
            .mapTo<IngestionJob>()
            .findOne()
            .orElse(null)

    fun list(
        accountId: String,
        size: Int,
        page: Int,
    ): Page<IngestionJob> =
        jdbi.withHandle<Page<IngestionJob>, Exception> { h ->
            Page.of(
                h
                    .createQuery("SELECT COUNT(*) FROM ingestion_jobs WHERE instagram_account_id=:id")
                    .bind("id", accountId)
                    .mapTo<Int>()
                    .one(),
                size,
                page,
            ) { offset ->
                h
                    .createQuery("$JOBS WHERE j.instagram_account_id=:id ORDER BY j.created_at DESC,j.id LIMIT :size OFFSET :offset")
                    .bind("id", accountId)
                    .bind("size", size)
                    .bind("offset", offset)
                    .mapTo<IngestionJob>()
                    .list()
            }
        }

    fun ensureWork(
        job: Uuid,
        phase: String,
        item: String,
        keys: List<String> = emptyList(),
    ) {
        jdbi.useHandle<Exception> { h ->
            h
                .createUpdate(
                    "INSERT INTO ingestion_work (job_id,phase,item_id,object_keys) VALUES (:job,:phase,:item,:keys) ON CONFLICT DO NOTHING",
                ).bind("job", job)
                .bind("phase", phase)
                .bind("item", item)
                .bindArray("keys", String::class.java, keys)
                .execute()
        }
    }

    fun work(
        job: Uuid,
        phase: String,
        item: String,
    ): IngestionWork =
        jdbi.withHandle<IngestionWork, Exception> { h ->
            h
                .createQuery("SELECT * FROM ingestion_work WHERE job_id=:job AND phase=:phase AND item_id=:item")
                .bind("job", job)
                .bind("phase", phase)
                .bind("item", item)
                .map { rs, _ -> mapWork(rs) }
                .one()
        }

    fun workPage(
        job: Uuid,
        size: Int,
        page: Int,
    ): Page<IngestionWork> =
        jdbi.withHandle<Page<IngestionWork>, Exception> { h ->
            Page.of(
                h
                    .createQuery("SELECT COUNT(*) FROM ingestion_work WHERE job_id=:job")
                    .bind("job", job)
                    .mapTo<Int>()
                    .one(),
                size,
                page,
            ) { offset ->
                h
                    .createQuery("SELECT * FROM ingestion_work WHERE job_id=:job ORDER BY phase,item_id LIMIT :size OFFSET :offset")
                    .bind("job", job)
                    .bind("size", size)
                    .bind("offset", offset)
                    .map { rs, _ -> mapWork(rs) }
                    .list()
            }
        }

    fun pending(
        job: Uuid,
        phase: String,
        limit: Int = 2,
    ): List<IngestionWork> =
        jdbi.withHandle<List<IngestionWork>, Exception> { h ->
            h
                .createQuery(
                    "SELECT * FROM ingestion_work WHERE job_id=:job AND phase=:phase AND status IN ('PENDING','RUNNING') ORDER BY item_id LIMIT :limit",
                ).bind("job", job)
                .bind("phase", phase)
                .bind("limit", limit)
                .map { rs, _ -> mapWork(rs) }
                .list()
        }

    fun begin(work: IngestionWork) {
        jdbi.useHandle<Exception> { h ->
            h
                .createUpdate(
                    "UPDATE ingestion_work SET status='RUNNING',attempts=attempts+1 WHERE job_id=:job AND phase=:phase AND item_id=:item AND attempts<3",
                ).bind("job", work.jobId)
                .bind("phase", work.phase)
                .bind("item", work.itemId)
                .execute()
        }
    }

    fun outcome(
        work: IngestionWork,
        status: String,
        error: String? = null,
        retryAt: Instant? = null,
    ) {
        jdbi.useHandle<Exception> { h ->
            h
                .createUpdate(
                    "UPDATE ingestion_work SET status=:status,error_code=:error,retry_at=:retry WHERE job_id=:job AND phase=:phase AND item_id=:item",
                ).bind(
                    "job",
                    work.jobId,
                ).bind(
                    "phase",
                    work.phase,
                ).bind("item", work.itemId)
                .bind("status", status)
                .bind("error", error)
                .bind("retry", retryAt)
                .execute()
        }
    }

    /** A prepared manifest alone is not proof that its row-first deletion committed. */
    fun vanishedDeletionReady(
        accountId: String,
        mediaId: String,
    ): Boolean =
        jdbi.withHandle<Boolean, Exception> { h ->
            h
                .createQuery(
                    """
                    SELECT NOT EXISTS (SELECT 1 FROM instagram_media_catalog WHERE instagram_account_id=:account AND instagram_media_id=:media)
                       AND NOT EXISTS (SELECT 1 FROM instagram_posts WHERE instagram_account_id=:account AND instagram_media_id=:media)
                    """.trimIndent(),
                ).bind("account", accountId)
                .bind("media", mediaId)
                .mapTo<Boolean>()
                .one()
        }

    fun resetFeed(job: Uuid) {
        jdbi.useHandle<Exception> { h -> h.createUpdate("DELETE FROM ingestion_feed_items WHERE job_id=:job").bind("job", job).execute() }
    }

    fun recordFeed(
        job: Uuid,
        mediaId: String,
    ) {
        jdbi.useHandle<Exception> { h ->
            h
                .createUpdate(
                    "INSERT INTO ingestion_feed_items VALUES (:job,:media) ON CONFLICT DO NOTHING",
                ).bind("job", job)
                .bind("media", mediaId)
                .execute()
        }
    }

    fun feedIds(job: Uuid): Set<String> =
        jdbi.withHandle<Set<String>, Exception> { h ->
            h
                .createQuery(
                    "SELECT instagram_media_id FROM ingestion_feed_items WHERE job_id=:job",
                ).bind("job", job)
                .mapTo<String>()
                .toSet()
        }

    fun prune() {
        jdbi.useHandle<Exception> {
            it.execute(
                "DELETE FROM ingestion_jobs WHERE completed_at < CURRENT_TIMESTAMP - INTERVAL '30 days' AND status NOT IN ('QUEUED','RUNNING')",
            )
        }
    }

    companion object {
        private const val JOBS = """SELECT j.*,COALESCE(w.attempts,0)::int AS attempts,COALESCE(w.succeeded,0)::int AS succeeded,
            COALESCE(w.skipped,0)::int AS skipped,COALESCE(w.failed,0)::int AS failed,COALESCE(w.pending,0)::int AS pending
            FROM ingestion_jobs j LEFT JOIN LATERAL (SELECT SUM(attempts) AS attempts,
            COUNT(*) FILTER(WHERE status='SUCCEEDED') AS succeeded,COUNT(*) FILTER(WHERE status='SKIPPED') AS skipped,
            COUNT(*) FILTER(WHERE status='FAILED') AS failed,COUNT(*) FILTER(WHERE status IN ('PENDING','RUNNING')) AS pending
            FROM ingestion_work WHERE job_id=j.id) w ON TRUE"""

        private fun mapWork(rs: java.sql.ResultSet) =
            IngestionWork(
                Uuid.parse(
                    rs.getString("job_id"),
                ),
                rs.getString(
                    "phase",
                ),
                rs.getString(
                    "item_id",
                ),
                rs.getString(
                    "status",
                ),
                rs.getInt(
                    "attempts",
                ),
                rs.getTimestamp("retry_at")?.toInstant(),
                rs.getString("error_code"),
                (rs.getArray("object_keys").array as Array<*>).map {
                    it.toString()
                },
            )
    }
}

/** Compatibility name for the feed pipeline's enqueue boundary; work is scoped to a durable job. */
class DownloadQueueRepository(
    private val jdbi: Jdbi,
    private val jobId: Uuid,
) {
    fun enqueue(
        accountId: String,
        mediaId: String,
    ) {
        jdbi.useHandle<Exception> { h ->
            h
                .createUpdate(
                    """INSERT INTO ingestion_work (job_id,phase,item_id) SELECT :job,'DOWNLOAD',:media
                FROM ingestion_jobs WHERE id=:job AND instagram_account_id=:account ON CONFLICT DO NOTHING""",
                ).bind("job", jobId)
                .bind("media", mediaId)
                .bind("account", accountId)
                .execute()
        }
    }

    fun isStillInAnyGallery(
        accountId: String,
        mediaId: String,
    ): Boolean =
        jdbi.withHandle<Boolean, Exception> { h ->
            h
                .createQuery(
                    """SELECT EXISTS (SELECT 1 FROM gallery_items gi JOIN galleries g ON g.id=gi.gallery_id
            JOIN instagram_media_catalog c ON c.instagram_media_id=gi.instagram_media_id
            WHERE gi.instagram_media_id=:media AND g.instagram_account_id=:account AND c.instagram_account_id=:account)""",
                ).bind("media", mediaId)
                .bind("account", accountId)
                .mapTo<Boolean>()
                .one()
        }
}
