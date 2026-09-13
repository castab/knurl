package knurl.domain.config

/**
 * Decoded from each service's `application.conf` via Hoplite. Pool bounds default to the
 * low-memory footprint required by both services (see AGENTS.md) - only override them in a
 * `.conf` file if you've re-checked the relevant service's heap budget.
 */
data class DatabaseSettings(
    val url: String,
    val maximumPoolSize: Int = 2,
    val minimumIdle: Int = 1,
    val idleTimeoutMs: Long = 30_000,
)
