package knurl.domain.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.KotlinPlugin
import org.jdbi.v3.postgres.PostgresPlugin
import java.net.URI
import javax.sql.DataSource

/**
 * Connection pool, migration, and JDBI bootstrap shared by both services.
 *
 * Pool is intentionally capped at 2 connections (see AGENTS.md) to preserve the low-memory
 * deployment footprint - do not raise these without revisiting the relevant memory budget.
 */
object DatabaseConfig {
    private const val DEFAULT_POSTGRES_PORT = 5432
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

    /**
     * Railway (and Heroku-style) DATABASE_URL values arrive as `postgres://user:pass@host:port/db`,
     * which HikariCP/the JDBC driver cannot consume directly - it requires a `jdbc:postgresql://` URL
     * with credentials passed separately. This converts one form to the other; a URL that is already
     * in `jdbc:` form only gets [withEnforcedSsl] applied, nothing else changed.
     */
    fun parseJdbcUrl(raw: String): JdbcCredentials {
        if (raw.startsWith("jdbc:")) {
            val jdbcUrl = withEnforcedSsl(raw, hostFromJdbcUrl(raw))
            return JdbcCredentials(jdbcUrl = jdbcUrl, username = null, password = null)
        }

        val uri = URI(raw)

        // A URL whose password contains an unencoded reserved character (most often `@`) leaves
        // URI.getHost() null rather than throwing. Without this guard that produced a silently broken
        // "jdbc:postgresql://null:-1/db" with null credentials, surfacing much later as an opaque
        // connection failure. Fail fast at config load instead.
        val host =
            uri.host
                ?: throw IllegalArgumentException(
                    "Could not parse a host out of the database URL. If the password contains a reserved " +
                        "character such as '@', ':' or '/', it must be percent-encoded (e.g. '@' as '%40').",
                )

        // getUserInfo() already percent-decodes, and correctly preserves a literal '+'. Do not add
        // URLDecoder here - it would turn '+' into a space and corrupt valid passwords.
        val userInfo = uri.userInfo?.split(":", limit = 2)
        val username = userInfo?.getOrNull(0)
        val password = userInfo?.getOrNull(1)
        val port = if (uri.port == -1) DEFAULT_POSTGRES_PORT else uri.port
        // rawQuery, not query: getQuery() is percent-decoded, which would corrupt an encoded '&' or '='.
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        val jdbcUrl = withEnforcedSsl("jdbc:postgresql://$host:$port${uri.rawPath}$query", host)

        return JdbcCredentials(jdbcUrl = jdbcUrl, username = username, password = password)
    }

    /**
     * Appends `sslmode=require` when [jdbcUrl] doesn't already specify a mode and [host] isn't a
     * loopback address. The PostgreSQL JDBC driver's own default (`prefer`) silently falls back to
     * an unencrypted connection if the server doesn't offer TLS - the wrong failure mode for
     * anything that isn't local development, where it should be loud instead.
     *
     * Deliberately `require`, not `verify-full`: several managed Postgres providers terminate TLS
     * with a certificate the JVM's default trust store can't validate, which would turn a stricter
     * default into a startup outage this class can't anticipate for every provider. `require`
     * already closes the actual gap here - a connection that silently isn't encrypted at all -
     * without assuming anything about a provider's certificate chain. A deployment that wants
     * hostname/CA verification can still opt in explicitly via `?sslmode=verify-full` in
     * `DATABASE_URL`, which this function leaves untouched since a mode is already present.
     */
    private fun withEnforcedSsl(
        jdbcUrl: String,
        host: String?,
    ): String {
        if (host != null && isLoopbackHost(host)) return jdbcUrl
        if (jdbcUrl.contains("sslmode=")) return jdbcUrl
        val separator = if (jdbcUrl.contains("?")) "&" else "?"
        return "$jdbcUrl${separator}sslmode=require"
    }

    private fun isLoopbackHost(host: String): Boolean = host in LOOPBACK_HOSTS || host.endsWith(".localhost") || host.startsWith("127.")

    /** Best-effort host extraction from an already-`jdbc:`-prefixed URL, for [withEnforcedSsl]'s loopback check. */
    private fun hostFromJdbcUrl(jdbcUrl: String): String? = runCatching { URI(jdbcUrl.removePrefix("jdbc:")).host }.getOrNull()

    fun createDataSource(settings: DatabaseSettings): HikariDataSource {
        val credentials = parseJdbcUrl(settings.url)

        val config =
            HikariConfig().apply {
                jdbcUrl = credentials.jdbcUrl
                credentials.username?.let { username = it }
                credentials.password?.let { password = it }
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = settings.maximumPoolSize
                minimumIdle = settings.minimumIdle
                idleTimeout = settings.idleTimeoutMs
                poolName = "knurl-pool"
            }

        return HikariDataSource(config)
    }

    fun runMigrations(dataSource: DataSource) {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun createJdbi(dataSource: DataSource): Jdbi =
        Jdbi
            .create(dataSource)
            .installPlugin(KotlinPlugin())
            .installPlugin(PostgresPlugin())
            .registerArgument(KotlinUuidArgumentFactory())
            .registerColumnMapper(KotlinUuidColumnMapper())
}

data class JdbcCredentials(
    val jdbcUrl: String,
    val username: String?,
    val password: String?,
)
