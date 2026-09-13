package knurl.ingestion

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.PropertySource
import knurl.domain.config.DatabaseConfig
import knurl.domain.config.S3Settings
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.AuthConfigRepository
import knurl.domain.repositories.CatalogRepository
import knurl.domain.repositories.GalleryRepository
import knurl.domain.repositories.InstagramPostRepository
import knurl.domain.repositories.ObjectKeyRepository
import knurl.domain.repositories.SyncConfigurationRepository
import knurl.ingestion.client.MetaGraphClient
import knurl.ingestion.pipeline.OrphanSweeper
import knurl.ingestion.pipeline.SyncPipeline
import knurl.ingestion.processor.MediaProcessor
import kotlinx.coroutines.delay
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
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("knurl.ingestion.Main")

/**
 * Plain `fun main()` entrypoint - no application framework. Everything below is manually
 * constructed and wired by hand.
 *
 * Supports both a persistent-daemon deployment (default: loop on `intervalSeconds`) and a
 * cron-triggered one (`RUN_ONCE=true`: run a single cycle and exit), per the spec's "persistent
 * or cron-triggered background daemon" framing.
 *
 * Architectural boundary: this service owns the database schema (only it calls
 * [DatabaseConfig.runMigrations]) and never opens an HTTP listener / accepts inbound traffic.
 */
fun main() =
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

        val dataSource = DatabaseConfig.createDataSource(config.database)
        DatabaseConfig.runMigrations(dataSource)
        val jdbi = DatabaseConfig.createJdbi(dataSource)

        val accountRepository = AccountRepository(jdbi)
        val authConfigRepository = AuthConfigRepository(jdbi)
        val postRepository = InstagramPostRepository(jdbi)
        val catalogRepository = CatalogRepository(jdbi)
        val galleryRepository = GalleryRepository(jdbi)
        val syncConfigurationRepository = SyncConfigurationRepository(jdbi)
        val objectKeyRepository = ObjectKeyRepository(jdbi)

        accountRepository.register(config.instagram.businessAccountId, config.instagram.adminToken)

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
        val mediaProcessor = MediaProcessor(okHttpClient, s3Client, config.s3.bucketName)
        val syncPipeline =
            SyncPipeline(
                authConfigRepository = authConfigRepository,
                postRepository = postRepository,
                catalogRepository = catalogRepository,
                galleryRepository = galleryRepository,
                syncConfigurationRepository = syncConfigurationRepository,
                metaGraphClient = metaGraphClient,
                mediaProcessor = mediaProcessor,
                s3Client = s3Client,
                bucketName = config.s3.bucketName,
                targetUserId = config.instagram.businessAccountId,
                initialAccessToken = config.instagram.accessToken,
            )
        val orphanSweeper =
            OrphanSweeper(
                objectKeyRepository = objectKeyRepository,
                syncConfigurationRepository = syncConfigurationRepository,
                s3Client = s3Client,
                bucketName = config.s3.bucketName,
            )

        Runtime.getRuntime().addShutdownHook(
            Thread {
                dataSource.close()
                okHttpClient.dispatcher.executorService.shutdown()
                s3Client.close()
            },
        )

        log.info("Knurl ingestion-service starting (runOnce=${config.runOnce})")
        val intervalMs = config.intervalSeconds * 1000
        while (true) {
            runCatching { syncPipeline.runOnce() }.onFailure { log.error("Sync cycle failed", it) }
            // Separate from the sync cycle, and after it: the sweep runs on its own (much longer)
            // cadence, and a failure to reap orphans must never cost us an ingestion cycle.
            runCatching { orphanSweeper.sweepIfDue() }.onFailure { log.error("Orphan sweep failed", it) }
            if (config.runOnce) break
            delay(intervalMs)
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
