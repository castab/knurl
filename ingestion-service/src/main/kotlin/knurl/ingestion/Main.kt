package knurl.ingestion

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.PropertySource
import knurl.domain.config.DatabaseConfig
import knurl.domain.config.S3Settings
import knurl.domain.repositories.AccountClaimRepository
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.AuthConfigRepository
import knurl.domain.repositories.CatalogRepository
import knurl.domain.repositories.DownloadQueueRepository
import knurl.domain.repositories.GalleryRepository
import knurl.domain.repositories.InstagramPostRepository
import knurl.domain.repositories.ObjectKeyRepository
import knurl.domain.repositories.SyncConfigurationRepository
import knurl.domain.security.CredentialCipher
import knurl.ingestion.client.MetaGraphClient
import knurl.ingestion.credentials.InstagramAccessTokenProvider
import knurl.ingestion.credentials.createInstagramAccessTokenProvider
import knurl.ingestion.credentials.validateInstagramCredentialSettings
import knurl.ingestion.pipeline.DownloadWorker
import knurl.ingestion.pipeline.OrphanSweeper
import knurl.ingestion.pipeline.SyncPipeline
import knurl.ingestion.processor.MediaProcessor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("knurl.ingestion.Main")

/** Gates how often [completedDownloadCleanupLoop] actually purges, independent of how often it polls. */
private const val COMPLETED_DOWNLOAD_CLEANUP_INTERVAL_SECONDS = 3600L

/**
 * Plain `fun main()` entrypoint - no application framework. Everything below is manually
 * constructed and wired by hand.
 *
 * Supports both a persistent-daemon deployment (default: each worker loop keeps polling for work)
 * and a cron-triggered one (`RUN_ONCE=true`: every worker loop drains to "nothing currently
 * claimable" and exits, then the process exits once all of them have).
 *
 * Architectural boundary: this service owns the database schema (only it calls
 * [DatabaseConfig.runMigrations]) and never opens an HTTP listener / accepts inbound traffic.
 *
 * Account-agnostic by design: rather than being pinned to one Instagram account, this process
 * discovers and processes *any* account registered in `instagram_accounts`, claimed via Postgres
 * row locking (see [AccountClaimRepository], [DownloadQueueRepository]) so any number of processes
 * can safely share one database with no double-processing. `INSTAGRAM_BUSINESS_ACCOUNT_ID` and its
 * companions remain a single-account convenience/bootstrap path - see [InstagramSettings] - not a
 * restriction on which accounts this process can serve.
 */
fun main() {
    runBlocking {
        val config =
            ConfigLoaderBuilder
                .default()
                // Order is priority order and the FIRST source added wins. `application.conf` must come
                // first so that a deployed environment's real env vars (resolved into it by typesafe-config's
                // ${?VAR} substitution) outrank the dev defaults below. When a ${?VAR} is unset the key is
                // dropped from this source entirely, so local development still falls through to the two
                // local files. Do not reorder these lines.
                .addPropertySource(PropertySource.resource("/application.conf"))
                // Loaded from disk, NOT the classpath: this file holds real Instagram credentials and must
                // never be packaged into the jar/image. Resolved relative to the Gradle `run` task's working
                // directory, which is `ingestion-service/`.
                .addPropertySource(PropertySource.file(File("config/application-instagram.conf"), optional = true))
                .addPropertySource(PropertySource.resource("/application-local.conf", optional = true))
                .build()
                .loadConfigOrThrow<IngestionConfig>()

        validateInstagramCredentialSettings(config.instagram)

        val dataSource = DatabaseConfig.createDataSource(config.database)
        DatabaseConfig.runMigrations(dataSource)
        val jdbi = DatabaseConfig.createJdbi(dataSource)

        val accountRepository = AccountRepository(jdbi)
        val authConfigRepository = AuthConfigRepository(jdbi, CredentialCipher(config.credentialEncryptionKey))
        val postRepository = InstagramPostRepository(jdbi)
        val catalogRepository = CatalogRepository(jdbi)
        val galleryRepository = GalleryRepository(jdbi)
        val syncConfigurationRepository = SyncConfigurationRepository(jdbi)
        val objectKeyRepository = ObjectKeyRepository(jdbi)
        val accountClaimRepository = AccountClaimRepository(jdbi)
        val downloadQueueRepository = DownloadQueueRepository(jdbi)

        // Registers/seeds exactly the one account from INSTAGRAM_BUSINESS_ACCOUNT_ID et al., unchanged
        // from standalone Knurl's original single-account shape. This is the single-account convenience/
        // bootstrap path, not how a multi-account deployment provisions additional accounts - those rows
        // are inserted directly (see README), and accountClaimRepository/downloadQueueRepository below
        // pick up any account or item in the database regardless of which process registered it.
        accountRepository.register(config.instagram.businessAccountId, config.instagram.adminToken, config.instagram.readToken)

        val okHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
                .apply { dispatcher.maxRequestsPerHost = 4 }

        val s3Client = buildS3Client(config.s3)

        val metaGraphClient = MetaGraphClient(okHttpClient, config.instagram.apiVersion)
        val accessTokenProvider =
            createInstagramAccessTokenProvider(
                settings = config.instagram,
                authConfigStore = authConfigRepository,
                refreshToken = metaGraphClient::refreshLongLivedToken,
                okHttpClient = okHttpClient,
            )
        val mediaProcessor = MediaProcessor(okHttpClient, s3Client, config.s3.bucketName)
        val orphanSweeper =
            OrphanSweeper(
                objectKeyRepository = objectKeyRepository,
                syncConfigurationRepository = syncConfigurationRepository,
                s3Client = s3Client,
                bucketName = config.s3.bucketName,
            )
        val downloadWorker =
            DownloadWorker(
                accessTokenProvider = accessTokenProvider,
                metaGraphClient = metaGraphClient,
                mediaProcessor = mediaProcessor,
                postRepository = postRepository,
                downloadQueueRepository = downloadQueueRepository,
            )

        Runtime.getRuntime().addShutdownHook(
            Thread {
                dataSource.close()
                okHttpClient.dispatcher.executorService.shutdown()
                s3Client.close()
            },
        )

        log.info(
            "Knurl ingestion-service starting (runOnce=${config.runOnce}, " +
                "feedSyncConcurrency=${config.feedSyncConcurrency}, downloadWorkerConcurrency=${config.downloadWorkerConcurrency})",
        )

        coroutineScope {
            repeat(config.feedSyncConcurrency) {
                launch {
                    feedSyncWorkerLoop(
                        accountClaimRepository = accountClaimRepository,
                        postRepository = postRepository,
                        catalogRepository = catalogRepository,
                        galleryRepository = galleryRepository,
                        syncConfigurationRepository = syncConfigurationRepository,
                        downloadQueueRepository = downloadQueueRepository,
                        metaGraphClient = metaGraphClient,
                        mediaProcessor = mediaProcessor,
                        accessTokenProvider = accessTokenProvider,
                        s3Client = s3Client,
                        bucketName = config.s3.bucketName,
                        intervalSeconds = config.intervalSeconds,
                        leaseSeconds = config.accountSyncLeaseSeconds,
                        pollIntervalMs = config.claimPollIntervalMs,
                        runOnce = config.runOnce,
                    )
                }
            }
            repeat(config.downloadWorkerConcurrency) {
                launch {
                    downloadWorker.loop(
                        batchSize = config.downloadClaimBatchSize,
                        leaseSeconds = config.downloadClaimLeaseSeconds,
                        pollIntervalMs = config.claimPollIntervalMs,
                        runOnce = config.runOnce,
                    )
                }
            }
            launch {
                orphanSweepLoop(orphanSweeper, config.claimPollIntervalMs, config.runOnce)
            }
            launch {
                completedDownloadCleanupLoop(
                    downloadQueueRepository = downloadQueueRepository,
                    syncConfigurationRepository = syncConfigurationRepository,
                    retentionHours = config.completedDownloadRetentionHours,
                    pollIntervalMs = config.claimPollIntervalMs,
                    runOnce = config.runOnce,
                )
            }
        }
    }
}

/**
 * Claims whichever account is due for a feed sync - any account in `instagram_accounts`, not just
 * the one this process bootstrapped - syncs it with no transaction held across the work, then
 * marks it synced on success. On failure the claim is simply left stale (see
 * [AccountClaimRepository]), reclaimable by any worker once its lease elapses; safe to run as
 * several concurrent coroutines, in this process or several others sharing the same database.
 */
private suspend fun feedSyncWorkerLoop(
    accountClaimRepository: AccountClaimRepository,
    postRepository: InstagramPostRepository,
    catalogRepository: CatalogRepository,
    galleryRepository: GalleryRepository,
    syncConfigurationRepository: SyncConfigurationRepository,
    downloadQueueRepository: DownloadQueueRepository,
    metaGraphClient: MetaGraphClient,
    mediaProcessor: MediaProcessor,
    accessTokenProvider: InstagramAccessTokenProvider,
    s3Client: S3Client,
    bucketName: String,
    intervalSeconds: Long,
    leaseSeconds: Long,
    pollIntervalMs: Long,
    runOnce: Boolean,
) {
    while (true) {
        val claim = accountClaimRepository.claimDueAccount(intervalSeconds, leaseSeconds)
        if (claim == null) {
            if (runOnce) return
            delay(pollIntervalMs)
            continue
        }
        if (claim.previousClaimedAt != null) {
            log.warn(
                "Reclaiming abandoned feed sync for account ${claim.accountId} " +
                    "(previously claimed at ${claim.previousClaimedAt}) - a previous worker never completed it",
            )
        }

        val syncPipeline =
            SyncPipeline(
                accessTokenProvider = accessTokenProvider,
                postRepository = postRepository,
                catalogRepository = catalogRepository,
                galleryRepository = galleryRepository,
                syncConfigurationRepository = syncConfigurationRepository,
                downloadQueueRepository = downloadQueueRepository,
                metaGraphClient = metaGraphClient,
                mediaProcessor = mediaProcessor,
                s3Client = s3Client,
                bucketName = bucketName,
                targetUserId = claim.accountId,
            )

        runCatching { syncPipeline.runOnce() }
            .onSuccess { accountClaimRepository.markSynced(claim.accountId) }
            .onFailure { log.error("Feed sync failed for account ${claim.accountId}", it) }
        // On failure, sync_claimed_at is deliberately left as-is (stale): the account becomes
        // reclaimable once its lease elapses, with no separate bookkeeping to distinguish a clean
        // failure from a crash.
    }
}

private suspend fun orphanSweepLoop(
    orphanSweeper: OrphanSweeper,
    pollIntervalMs: Long,
    runOnce: Boolean,
) {
    while (true) {
        runCatching { orphanSweeper.sweepIfDue() }.onFailure { log.error("Orphan sweep failed", it) }
        if (runOnce) return
        delay(pollIntervalMs)
    }
}

/** Periodically purges old `COMPLETE` rows from `pending_downloads`, kept until then purely for operator visibility. */
private suspend fun completedDownloadCleanupLoop(
    downloadQueueRepository: DownloadQueueRepository,
    syncConfigurationRepository: SyncConfigurationRepository,
    retentionHours: Long,
    pollIntervalMs: Long,
    runOnce: Boolean,
) {
    while (true) {
        val claimed =
            syncConfigurationRepository.claimIfElapsed(
                key = "pending_downloads_cleanup_at",
                now = Instant.now(),
                intervalSeconds = COMPLETED_DOWNLOAD_CLEANUP_INTERVAL_SECONDS,
            )
        if (claimed) {
            runCatching { downloadQueueRepository.deleteOldCompleted(retentionHours) }
                .onFailure { log.error("Completed-download cleanup failed", it) }
        }
        if (runOnce) return
        delay(pollIntervalMs)
    }
}

private fun buildS3Client(settings: S3Settings): S3Client {
    val builder =
        S3Client
            .builder()
            .region(Region.of(settings.region))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(settings.accessKeyId, settings.secretAccessKey),
                ),
            ).serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(settings.pathStyleAccess).build(),
            )

    settings.endpoint?.let { builder.endpointOverride(URI.create(it)) }

    return builder.build()
}
