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
 * Pool is intentionally capped at 2 connections (see AGENTS.md) to fit the 128MB
 * heap / low-memory deployment target - do not raise these without revisiting the
 * memory budget.
 */
object DatabaseConfig {
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
        val userInfo = uri.userInfo?.split(":", limit = 2)
        val username = userInfo?.getOrNull(0)
        val password = userInfo?.getOrNull(1)
        val query = uri.query?.let { "?$it" } ?: ""
        val jdbcUrl = "jdbc:postgresql://${uri.host}:${uri.port}${uri.path}$query"

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
