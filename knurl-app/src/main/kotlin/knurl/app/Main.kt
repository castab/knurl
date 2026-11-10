package knurl.app

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.PropertySource
import knurl.app.ingestion.IngestionCoordinator
import knurl.app.storage.AccountStorageManager
import knurl.domain.config.DatabaseConfig
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.IngestionJobRepository
import knurl.domain.security.CredentialCipher
import knurl.ingestion.client.MetaGraphClient
import knurl.ingestion.credentials.DatabaseInstagramAccessTokenProvider
import knurl.presentation.ratelimit.RateLimiter
import knurl.presentation.security.SecurityHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.http4k.contract.ui.swaggerUiLite
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.http4k.routing.ResourceLoader
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.Undertow
import org.http4k.server.asServer
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

fun main() {
    val config =
        ConfigLoaderBuilder
            .default()
            // First source wins; only application.conf is packaged. Dev files never ship as resources.
            .addPropertySource(PropertySource.resource("/application.conf"))
            .addPropertySource(PropertySource.file(File("config/application-local.conf"), optional = true))
            .build()
            .loadConfigOrThrow<AppConfig>()
    config.managementSecret()
    val cipher = CredentialCipher(config.credentialEncryptionKey)
    val dataSource = DatabaseConfig.createDataSource(config.database)
    DatabaseConfig.runMigrations(dataSource)
    val jdbi = DatabaseConfig.createJdbi(dataSource)
    val accounts = AccountRepository(jdbi, cipher)
    val jobs = IngestionJobRepository(jdbi)
    jobs.recover()
    val storage = AccountStorageManager(accounts, Duration.ofSeconds(config.presignedGetTtlSeconds))
    val http =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    val graph = MetaGraphClient(http, config.instagramApiVersion)
    val tokens = DatabaseInstagramAccessTokenProvider(accounts, graph::refreshLongLivedToken)
    val coordinator = IngestionCoordinator(jdbi, accounts, jobs, storage, http, graph, tokens)
    val app = applicationContract(jdbi, accounts, jobs, storage, config::managementSecret)
    val docs = "/docs" bind swaggerUiLite { url = "/openapi.json" }
    val routed = if (config.uiEnabled) routes(app, docs, static(ResourceLoader.Classpath("public"))) else routes(app, docs)
    val handler: HttpHandler = { request ->
        if (request.uri.path ==
            "/docs"
        ) {
            Response(Status.FOUND).header("Location", "/docs/")
        } else {
            routed(request)
        }
    }
    val server =
        SecurityHeaders.filter
            .then(
                RateLimiter(config.rateLimitPerMinute, clientIpHeader = config.clientIpHeader.ifEmpty { null }).filter,
            ).then(applicationErrors)
            .then(handler)
            .asServer(Undertow(config.port))
            .start()
    val supervisor = SupervisorJob()
    val scope = CoroutineScope(supervisor + Dispatchers.IO)
    scope.launch { coordinator.workerLoop() }
    scope.launch { coordinator.maintenanceLoop() }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop()
            runBlocking { supervisor.cancelAndJoin() }
            storage.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
            dataSource.close()
        },
    )
    LoggerFactory.getLogger("knurl").info("Knurl listening on port {}; UI enabled={}", config.port, config.uiEnabled)
}
