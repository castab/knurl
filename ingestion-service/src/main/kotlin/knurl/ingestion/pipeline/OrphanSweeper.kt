package knurl.ingestion.pipeline

import knurl.domain.repositories.ObjectKeyRepository
import knurl.domain.repositories.SyncConfigurationRepository
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import java.time.Duration
import java.time.Instant

/** Key under which the sweep's last run is recorded, so its cadence survives restarts and `RUN_ONCE` deployments. */
private const val LAST_SWEEP_KEY = "last_orphan_sweep_at"

private const val DEFAULT_SWEEP_INTERVAL_HOURS = 24
private const val DEFAULT_GRACE_MINUTES = 60
private const val DEFAULT_MAX_DELETES = 1000

/** S3's `DeleteObjects` accepts at most 1000 keys per request. */
private const val DELETE_BATCH_SIZE = 1000

/** One object as returned by a bucket listing - just the two fields orphan classification needs. */
data class ListedObject(
    val key: String,
    val lastModified: Instant,
)

/**
 * The keys in [listed] that the database has no record of and that are old enough to delete, capped
 * at [limit].
 *
 * The age check is the guard that makes an orphan sweep safe to run against a live system. An object
 * is uploaded *before* the row referencing it is written - `processAndStore` uploads every child of
 * a post and only then inserts - so between those two moments a perfectly healthy object looks
 * exactly like an orphan. Worse, keys carry no account segment, so this sweep is inherently
 * cross-account and may be looking at a *sibling* ingestion instance's in-flight upload, which it
 * cannot coordinate with at all. Requiring an object to have sat unreferenced for a while collapses
 * that race: a genuine orphan is simply reaped on the next sweep instead.
 *
 * Pure/no I/O so it's unit-testable on its own; [OrphanSweeper] owns the listing, the guards that
 * need database state, and the deletes.
 */
fun classifyOrphans(
    listed: List<ListedObject>,
    knownKeys: Set<String>,
    uploadedBefore: Instant,
    limit: Int,
): List<String> =
    listed
        .asSequence()
        .filter { it.key !in knownKeys }
        .filter { it.lastModified.isBefore(uploadedBefore) }
        .map { it.key }
        .take(limit)
        .toList()

/**
 * Deletes bucket objects the database has no record of, enforcing the invariant that the database
 * knows about every object in the bucket.
 *
 * Orphans arise in normal operation and cannot be prevented by careful ordering alone: an upload
 * that fails partway through a carousel leaves its earlier children referenced by nothing, a
 * carousel that shrinks upstream strands its higher positions, and eviction's row-first delete
 * leaves objects behind if the S3 call then fails. Every one of those is a permanent leak without
 * a sweep, because nothing else in the system can enumerate the bucket.
 *
 * Runs on its own cadence rather than every ingestion cycle - a full `ListObjectsV2` of the bucket
 * is the expensive operation here, and orphans are not urgent.
 */
class OrphanSweeper(
    private val objectKeyRepository: ObjectKeyRepository,
    private val syncConfigurationRepository: SyncConfigurationRepository,
    private val s3Client: S3Client,
    private val bucketName: String,
) {
    private val log = LoggerFactory.getLogger(OrphanSweeper::class.java)

    /**
     * Runs a sweep if one is due and this instance wins the claim, otherwise returns immediately.
     *
     * The claim is atomic and shared, so several ingestion instances against one database and bucket
     * produce one sweep between them rather than one each.
     */
    fun sweepIfDue() {
        val intervalHours =
            syncConfigurationRepository.getInt("orphan_sweep_interval_hours", DEFAULT_SWEEP_INTERVAL_HOURS)
        val now = Instant.now()
        val claimed =
            syncConfigurationRepository.claimIfElapsed(
                key = LAST_SWEEP_KEY,
                now = now,
                intervalSeconds = Duration.ofHours(intervalHours.toLong()).seconds,
            )
        if (!claimed) return

        val graceMinutes = syncConfigurationRepository.getInt("orphan_grace_minutes", DEFAULT_GRACE_MINUTES)
        val maxDeletes = syncConfigurationRepository.getInt("orphan_sweep_max_deletes", DEFAULT_MAX_DELETES)
        val dryRun = syncConfigurationRepository.getBoolean("orphan_sweep_dry_run", false)
        val uploadedBefore = now.minus(Duration.ofMinutes(graceMinutes.toLong()))

        val knownKeys = objectKeyRepository.allKnownKeys()
        val orphans = mutableListOf<String>()
        var listedCount = 0

        // Paged, with only the orphan keys accumulated - never the whole listing. The known-key set
        // is already linear in bucket size; holding the listing alongside it would double that for
        // no reason and put the heap budget at risk on a large bucket.
        s3Client
            .listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucketName).build())
            .forEach { page ->
                listedCount += page.contents().size
                if (orphans.size < maxDeletes) {
                    orphans +=
                        classifyOrphans(
                            listed = page.contents().map { ListedObject(it.key(), it.lastModified()) },
                            knownKeys = knownKeys,
                            uploadedBefore = uploadedBefore,
                            limit = maxDeletes - orphans.size,
                        )
                }
            }

        // An empty known-key set against a non-empty bucket is almost always a misconfiguration - a
        // freshly reset or simply wrong database - and acting on it would empty the bucket. Refuse
        // rather than trust it; a genuinely empty database has nothing worth sweeping anyway.
        if (knownKeys.isEmpty() && listedCount > 0) {
            log.error(
                "Refusing to sweep: the database knows of no S3 objects at all, but the bucket holds " +
                    "$listedCount. That is far more likely a misconfigured or reset database than " +
                    "$listedCount genuine orphans, and sweeping would empty the bucket.",
            )
            return
        }

        if (orphans.size >= maxDeletes) {
            log.warn(
                "Orphan sweep hit its cap of $maxDeletes objects; the remainder will be reaped by later " +
                    "sweeps. A cap this full is worth investigating - it usually means something is leaking, " +
                    "not that the cap is too low.",
            )
        }

        if (orphans.isEmpty()) {
            log.info("Orphan sweep: $listedCount object(s) listed, ${knownKeys.size} known, none orphaned")
            return
        }

        if (dryRun) {
            log.warn(
                "Orphan sweep (DRY RUN, orphan_sweep_dry_run is set): would delete ${orphans.size} " +
                    "orphaned object(s), e.g. ${orphans.take(20)}",
            )
            return
        }

        deleteAll(orphans)
        log.info(
            "Orphan sweep: $listedCount object(s) listed, ${knownKeys.size} known, " +
                "${orphans.size} orphaned and deleted",
        )
    }

    private fun deleteAll(keys: List<String>) {
        keys.chunked(DELETE_BATCH_SIZE).forEach { batch ->
            val response =
                s3Client.deleteObjects(
                    DeleteObjectsRequest
                        .builder()
                        .bucket(bucketName)
                        .delete(
                            Delete
                                .builder()
                                .objects(batch.map { ObjectIdentifier.builder().key(it).build() })
                                .build(),
                        ).build(),
                )
            check(response.errors().isEmpty()) { "S3 delete errors during orphan sweep: ${response.errors()}" }
        }
    }
}
