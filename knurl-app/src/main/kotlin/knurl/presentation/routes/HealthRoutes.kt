package knurl.presentation.routes

import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.autoBody
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Serializable
data class HealthResponse(
    val status: String,
)

/**
 * Unauthenticated liveness/readiness probe. Reports only whether the database answers - no version,
 * account, or configuration detail. The result is cached briefly so probes and anonymous callers
 * cannot turn this into a steady drain on the two-connection pool.
 */
class HealthRoutes(
    private val databaseAnswers: () -> Unit,
    private val clock: Clock = Clock.systemUTC(),
    private val cacheFor: Duration = Duration.ofSeconds(5),
) {
    private val lens = autoBody<HealthResponse>().toLens()
    private val lock = Any()
    private var checkedAt: Instant? = null
    private var healthy = false

    private fun check(): Boolean =
        synchronized(lock) {
            val now = clock.instant()
            val last = checkedAt
            if (last == null || Duration.between(last, now) >= cacheFor) {
                healthy =
                    try {
                        databaseAnswers()
                        true
                    } catch (_: Exception) {
                        false
                    }
                checkedAt = now
            }
            healthy
        }

    private fun health(): ContractRoute =
        "/health" meta {
            summary = "Report whether the application can reach its database"
            returning(Status.OK, lens to HealthResponse("ok"))
            returning(Status.SERVICE_UNAVAILABLE, lens to HealthResponse("unavailable"))
        } bindContract Method.GET to { _ ->
            if (check()) {
                Response(Status.OK).with(lens of HealthResponse("ok"))
            } else {
                Response(Status.SERVICE_UNAVAILABLE).with(lens of HealthResponse("unavailable"))
            }
        }

    fun routes(): List<ContractRoute> = listOf(health())
}
