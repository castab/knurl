package knurl.domain.config

/**
 * Decoded from each service's `application.conf` via Hoplite. Pool bounds default to the
 * low-memory footprint required by the 128MB heap deployment target (see AGENTS.md) - only
 * override them in a `.conf` file if you've re-checked that budget.
 */
data class DatabaseSettings(
    val url: String,
    val maximumPoolSize: Int = 2,
    val minimumIdle: Int = 1,
    val idleTimeoutMs: Long = 30_000,
)
