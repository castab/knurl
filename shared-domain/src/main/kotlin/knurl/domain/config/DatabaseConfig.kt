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

    /**
     * Railway (and Heroku-style) DATABASE_URL values arrive as `postgres://user:pass@host:port/db`,
     * which HikariCP/the JDBC driver cannot consume directly - it requires a `jdbc:postgresql://` URL
     * with credentials passed separately. This converts one form to the other; a URL that is already
     * in `jdbc:` form is passed through unchanged.
     */
    fun parseJdbcUrl(raw: String): JdbcCredentials {
        if (raw.startsWith("jdbc:")) {
            return JdbcCredentials(jdbcUrl = raw, username = null, password = null)
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
        val jdbcUrl = "jdbc:postgresql://$host:$port${uri.rawPath}$query"

        return JdbcCredentials(jdbcUrl = jdbcUrl, username = username, password = password)
    }

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
