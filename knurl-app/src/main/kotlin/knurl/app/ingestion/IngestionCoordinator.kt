package knurl.app.ingestion

import knurl.app.storage.AccountStorageManager
import knurl.domain.models.accountObjectPrefix
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.CatalogRepository
import knurl.domain.repositories.DownloadQueueRepository
import knurl.domain.repositories.GalleryRepository
import knurl.domain.repositories.IngestionJob
import knurl.domain.repositories.IngestionJobRepository
import knurl.domain.repositories.IngestionWork
import knurl.domain.repositories.InstagramPostRepository
import knurl.domain.repositories.ObjectKeyRepository
import knurl.domain.repositories.SyncConfigurationRepository
import knurl.ingestion.client.MetaGraphApiException
import knurl.ingestion.client.MetaGraphClient
import knurl.ingestion.credentials.InstagramAccessTokenProvider
import knurl.ingestion.pipeline.DownloadWorker
import knurl.ingestion.pipeline.OrphanSweeper
import knurl.ingestion.pipeline.SyncPipeline
import knurl.ingestion.pipeline.retentionCutoff
import knurl.ingestion.pipeline.shouldSkipVanishedRemoval
import knurl.ingestion.processor.MediaProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.IOException
import java.time.Clock
import java.time.Duration
import kotlin.uuid.Uuid

class AccountDisabledException : CancellationException("account disabled")

/** One account worker; bounded per-item batches share two media permits with maintenance. */
class IngestionCoordinator(
    private val jdbi: Jdbi,
    private val accounts: AccountRepository,
    private val jobs: IngestionJobRepository,
    private val storage: AccountStorageManager,
    private val http: OkHttpClient,
    private val graph: MetaGraphClient,
    private val tokens: InstagramAccessTokenProvider,
    private val clock: Clock = Clock.systemUTC(),
    private val pollMs: Long = 1000,
    private val waitFor: suspend (Long) -> Unit = { delay(it) },
) {
    private val log = LoggerFactory.getLogger(IngestionCoordinator::class.java)
    private val mediaPermits = Semaphore(2)
    private val posts = InstagramPostRepository(jdbi)
    private val catalog = CatalogRepository(jdbi)
    private val galleries = GalleryRepository(jdbi)
    private val config = SyncConfigurationRepository(jdbi)

    suspend fun workerLoop() =
        withContext(Dispatchers.IO) {
            while (isActive) {
                try {
                    jobs.enqueueDue()
                    val job = jobs.claim()
                    if (job == null) delay(pollMs) else execute(job)
                } catch (
                    e: CancellationException,
                ) {
                    throw e
                } catch (
                    _: Exception,
                ) {
                    log.error("Ingestion worker iteration failed; recovering durable work")
                    delay(pollMs)
                    try {
                        jobs.recover()
                    } catch (_: Exception) {
                        log.warn("Database recovery deferred")
                    }
                }
            }
        }

    internal suspend fun execute(job: IngestionJob) {
        try {
            val queue = DownloadQueueRepository(jdbi, job.id)
            val feedOk =
                step(job, "FEED", job.instagramAccountId) {
                    jobs.resetFeed(job.id)
                    storage.acquire(job.instagramAccountId).use { lease ->
                        val processor = MediaProcessor(http, lease.client, lease.bucket, beforeUpload = lease::beforeUpload)
                        SyncPipeline(tokens, posts, catalog, queue, graph, processor, job.instagramAccountId)
                            .syncFeed({ jobs.recordFeed(job.id, it) }, { checkAccount(job) })
                    }
                    true
                }
            if (!feedOk) {
                jobs.finish(job, "FAILED", "FEED_FAILED")
                return
            }

            val present = jobs.feedIds(job.id)
            step(job, "VANISHED", job.instagramAccountId) {
                val previous = catalog.allMediaIds(job.instagramAccountId)
                val vanished = previous - present
                if (shouldSkipVanishedRemoval(
                        vanished.size,
                        previous.size,
                        config.getInt("vanished_media_max_percent", 50),
                    )
                ) {
                    throw SuspiciousFeedException()
                }
                if (vanished.isNotEmpty()) {
                    val names = galleries.galleryNamesHolding(job.instagramAccountId, vanished)
                    if (names.isNotEmpty()) {
                        log.warn(
                            "Vanished media removed from galleries for account {}: {}",
                            job.instagramAccountId,
                            names,
                        )
                    }
                    // Durable keys precede row-first deletion, so crash/retry cannot lose cleanup intent.
                    posts.vanishedKeys(job.instagramAccountId, vanished).forEach { (id, keys) ->
                        jobs.ensureWork(job.id, "DELETE", id, keys)
                    }
                    posts.deleteVanished(job.instagramAccountId, vanished)
                }
                true
            }
            drain(job, "DELETE") { work ->
                check(jobs.vanishedDeletionReady(job.instagramAccountId, work.itemId)) { "database deletion has not committed" }
                storage.acquire(job.instagramAccountId).use { deleteObjects(job, it, work.objectKeys) }
                true
            }

            catalog
                .mediaIdsNeedingThumbnail(
                    job.instagramAccountId,
                ).filter { it in present }
                .forEach { jobs.ensureWork(job.id, "THUMBNAIL", it) }
            drain(job, "THUMBNAIL") { work ->
                storage.acquire(job.instagramAccountId).use { lease ->
                    val processor = MediaProcessor(http, lease.client, lease.bucket, beforeUpload = lease::beforeUpload)
                    SyncPipeline(tokens, posts, catalog, queue, graph, processor, job.instagramAccountId).thumbnail(work.itemId)
                }
            }
            drain(job, "DOWNLOAD") { work ->
                storage.acquire(job.instagramAccountId).use { lease ->
                    val processor = MediaProcessor(http, lease.client, lease.bucket, beforeUpload = lease::beforeUpload)
                    DownloadWorker(tokens, graph, processor, posts, queue).process(job.instagramAccountId, work.itemId)
                }
            }

            posts
                .findEvictionCandidates(job.instagramAccountId, retentionCutoff(clock.instant(), config.getInt("retention_days", 30)))
                .forEach { jobs.ensureWork(job.id, "RETENTION", it.id.toString(), it.mediaPaths) }
            drain(job, "RETENTION") { work ->
                val id = Uuid.parse(work.itemId)
                if (posts.deleteIfNotInAnyGallery(id) == 0 && posts.exists(id)) {
                    false
                } else {
                    storage.acquire(job.instagramAccountId).use { deleteObjects(job, it, work.objectKeys) }
                    true
                }
            }
            checkAccount(job)
            val summary = checkNotNull(jobs.find(job.instagramAccountId, job.id))
            jobs.finish(job, if (summary.failed > 0) "PARTIAL" else "SUCCEEDED", if (summary.failed > 0) "ITEM_FAILURES" else null)
        } catch (
            _: AccountDisabledException,
        ) {
            jobs.finish(job, "CANCELLED", "ACCOUNT_DISABLED")
        } catch (e: CancellationException) {
            // Startup recovery resumes this same job, including spent attempts.
            throw e
        } catch (_: knurl.domain.repositories.AccountUnavailable) {
            if (jobs.isEnabled(job.instagramAccountId)) {
                jobs.finish(job, "FAILED", "INGESTION_FAILED")
            } else {
                jobs.finish(job, "CANCELLED", "ACCOUNT_DISABLED")
            }
        } catch (_: Exception) {
            jobs.finish(job, "FAILED", "INGESTION_FAILED")
        }
    }

    private fun checkAccount(job: IngestionJob) {
        if (!jobs.isEnabled(job.instagramAccountId) || jobs.cancelRequested(job.id)) throw AccountDisabledException()
    }

    private suspend fun checkpoint(job: IngestionJob) {
        currentCoroutineContext().ensureActive()
        checkAccount(job)
    }

    private suspend fun pause(
        job: IngestionJob,
        millis: Long,
    ) {
        var left = millis
        while (left > 0) {
            checkpoint(job)
            val chunk = minOf(1000, left)
            waitFor(chunk)
            left -= chunk
        }
    }

    private suspend fun step(
        job: IngestionJob,
        phase: String,
        item: String,
        operation: suspend () -> Boolean,
    ): Boolean {
        jobs.ensureWork(job.id, phase, item)
        jobs.phase(job.id, phase)
        while (true) {
            checkpoint(job)
            val work = jobs.work(job.id, phase, item)
            if (work.status == "SUCCEEDED" || work.status == "SKIPPED") return true
            if (work.status == "FAILED") return false
            if (work.attempts >= 3) {
                jobs.outcome(work, "FAILED", "ATTEMPTS_EXHAUSTED")
                return false
            }
            work.retryAt?.let { pause(job, Duration.between(clock.instant(), it).toMillis().coerceAtLeast(0)) }
            jobs.begin(work)
            try {
                val result = operation()
                jobs.outcome(work, if (result) "SUCCEEDED" else "SKIPPED")
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = failureCode(e)
                val retry = retryable(e) && work.attempts + 1 < 3
                val seconds = retryDelaySeconds(work.attempts + 1, (e as? MetaGraphApiException)?.retryAfterSeconds)
                jobs.outcome(work, if (retry) "PENDING" else "FAILED", code, if (retry) clock.instant().plusSeconds(seconds) else null)
                log.warn("Ingestion operation failed: account={}, job={}, phase={}, code={}", job.instagramAccountId, job.id, phase, code)
                if (!retry) return false
            }
        }
    }

    private suspend fun drain(
        job: IngestionJob,
        phase: String,
        action: suspend (IngestionWork) -> Boolean,
    ) {
        while (true) {
            checkpoint(job)
            val batch = jobs.pending(job.id, phase)
            if (batch.isEmpty()) return
            coroutineScope {
                batch
                    .map { work ->
                        async(Dispatchers.IO) { mediaPermits.withPermit { step(job, phase, work.itemId) { action(work) } } }
                    }.awaitAll()
            }
        }
    }

    private fun deleteObjects(
        job: IngestionJob,
        lease: AccountStorageManager.Lease,
        keys: List<String>,
    ) {
        require(keys.all { it.startsWith(accountObjectPrefix(job.instagramAccountId)) }) { "object outside account prefix" }
        keys.chunked(1000).forEach { batch ->
            checkAccount(job)
            val response =
                lease.client.deleteObjects(
                    DeleteObjectsRequest
                        .builder()
                        .bucket(lease.bucket)
                        .delete(Delete.builder().objects(batch.map { ObjectIdentifier.builder().key(it).build() }).build())
                        .build(),
                )
            if (response.errors().isNotEmpty()) throw IOException("S3 deletion failed")
        }
    }

    suspend fun maintenanceLoop() =
        withContext(Dispatchers.IO) {
            while (isActive) {
                try {
                    // Sequential account iteration; no per-account coroutine fan-out.
                    for (id in accounts.enabledIds()) {
                        ensureActive()
                        mediaPermits.withPermit {
                            try {
                                storage.acquire(id).use { lease ->
                                    OrphanSweeper(ObjectKeyRepository(jdbi), config, lease.client, lease.bucket, id, accounts).sweepIfDue()
                                }
                            } catch (
                                e: CancellationException,
                            ) {
                                throw e
                            } catch (_: Exception) {
                                log.warn("Account maintenance failed: account={}", id)
                            }
                        }
                    }
                    jobs.prune()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    log.warn("Maintenance iteration failed")
                }
                delay(60_000)
            }
        }
}

class SuspiciousFeedException : RuntimeException("suspicious feed reduction")

internal fun retryDelaySeconds(
    attempt: Int,
    retryAfter: Long?,
): Long = maxOf(if (attempt == 1) 5 else 30, retryAfter?.coerceIn(0, 300) ?: 0)

internal fun retryable(error: Exception): Boolean =
    when (error) {
        is knurl.ingestion.processor.SourceMediaLimitException -> false
        is knurl.ingestion.processor.MediaHttpException -> error.statusCode == 429 || error.statusCode >= 500
        is MetaGraphApiException -> error.statusCode == 429 || error.statusCode >= 500
        is S3Exception -> error.statusCode() == 429 || error.statusCode() >= 500
        is IOException, is SdkClientException -> true
        else -> false
    }

internal fun failureCode(error: Exception): String =
    when (error) {
        is knurl.ingestion.processor.SourceMediaLimitException -> {
            "SOURCE_MEDIA_LIMIT"
        }

        is knurl.ingestion.processor.MediaHttpException -> {
            "SOURCE_HTTP_${error.statusCode}"
        }

        is MetaGraphApiException -> {
            if (error.statusCode == 401 ||
                error.statusCode == 403
            ) {
                "INSTAGRAM_CREDENTIAL_REJECTED"
            } else {
                "GRAPH_HTTP_${error.statusCode}"
            }
        }

        is S3Exception -> {
            if (error.statusCode() == 401 ||
                error.statusCode() == 403
            ) {
                "STORAGE_CREDENTIAL_REJECTED"
            } else {
                "STORAGE_HTTP_${error.statusCode()}"
            }
        }

        is SuspiciousFeedException -> {
            "SUSPICIOUS_FEED_REDUCTION"
        }

        is knurl.ingestion.pipeline.MediaNotReadyException -> {
            "MEDIA_NOT_READY"
        }

        is IOException, is SdkClientException -> {
            "REMOTE_IO_FAILURE"
        }

        else -> {
            "PROCESSING_FAILED"
        }
    }
