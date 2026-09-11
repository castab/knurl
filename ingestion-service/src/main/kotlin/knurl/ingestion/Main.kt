package knurl.ingestion

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.PropertySource
import knurl.domain.config.DatabaseConfig
import knurl.domain.config.S3Settings
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.AuthConfigRepository
import knurl.domain.repositories.CatalogRepository
import knurl.domain.repositories.InstagramPostRepository
import knurl.domain.repositories.SyncConfigurationRepository
import knurl.ingestion.client.MetaGraphClient
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
                .addPropertySource(PropertySource.resource("/application-instagram.conf", optional = true))
                .addPropertySource(PropertySource.resource("/application-local.conf", optional = true))
                .addPropertySource(PropertySource.resource("/application.conf"))
                .build()
                .loadConfigOrThrow<IngestionConfig>()

        val dataSource = DatabaseConfig.createDataSource(config.database)
        DatabaseConfig.runMigrations(dataSource)
        val jdbi = DatabaseConfig.createJdbi(dataSource)

        val accountRepository = AccountRepository(jdbi)
        val authConfigRepository = AuthConfigRepository(jdbi)
        val postRepository = InstagramPostRepository(jdbi)
        val catalogRepository = CatalogRepository(jdbi)
        val syncConfigurationRepository = SyncConfigurationRepository(jdbi)

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
                syncConfigurationRepository = syncConfigurationRepository,
                metaGraphClient = metaGraphClient,
                mediaProcessor = mediaProcessor,
                s3Client = s3Client,
                bucketName = config.s3.bucketName,
                targetUserId = config.instagram.businessAccountId,
                initialAccessToken = config.instagram.accessToken,
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
