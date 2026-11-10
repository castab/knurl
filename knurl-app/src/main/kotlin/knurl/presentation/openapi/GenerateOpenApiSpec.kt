package knurl.presentation.openapi

import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.format.Jackson
import org.jdbi.v3.core.Jdbi
import java.io.File
import java.sql.Connection
import java.time.Duration
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Entry point for the `:knurl-app:generateOpenApiSpec` Gradle task. Builds the same
 * `contract` route wiring as [knurl.app.main] and dumps its `/openapi.json` response to a
 * file, without opening a database connection, an S3 client, or an HTTP listener - the OpenAPI
 * renderer only needs the route *definitions*, never a working [Jdbi]/[Presigner], since no
 * handler is ever invoked. This lets a caller (human or agent) get the spec offline, without
 * standing up Postgres/Garage or running the full server.
 */
fun main(args: Array<String>) {
    val jdbi = Jdbi.create(NeverConnectDataSource)
    val accounts =
        knurl.domain.repositories.AccountRepository(
            jdbi,
            knurl.domain.security.CredentialCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
        )
    val jobs = knurl.domain.repositories.IngestionJobRepository(jdbi)
    val storage = knurl.app.storage.AccountStorageManager(accounts, Duration.ofSeconds(21600))
    val app = knurl.app.applicationContract(jdbi, accounts, jobs, storage) { "placeholder-management-secret" }

    val outputPath = args.firstOrNull() ?: "build/openapi/openapi.json"
    val outputFile = File(outputPath).also { it.parentFile?.mkdirs() }
    val rawJson = app(Request(Method.GET, "/openapi.json")).bodyString()
    // Pretty-printed so the checked-in file diffs cleanly; the renderer itself emits it minified.
    outputFile.writeText(Jackson.mapper.readTree(rawJson).toPrettyString())
    storage.close()

    println("Wrote OpenAPI spec to ${outputFile.absolutePath}")
}

/** Constructing [Jdbi] only stores this - none of its methods run unless a handler executes a query. */
private object NeverConnectDataSource : DataSource {
    private fun unsupported(): Nothing = throw UnsupportedOperationException("NeverConnectDataSource is for OpenAPI spec generation only")

    override fun getConnection(): Connection = unsupported()

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = unsupported()

    override fun getLogWriter() = unsupported()

    override fun setLogWriter(out: java.io.PrintWriter?) = unsupported()

    override fun setLoginTimeout(seconds: Int) = unsupported()

    override fun getLoginTimeout() = unsupported()

    override fun getParentLogger(): Logger = unsupported()

    override fun <T : Any?> unwrap(iface: Class<T>?): T = unsupported()

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
