package knurl.presentation.ratelimit

import org.http4k.core.Filter
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed-window rate limiter keyed by client IP, applied to every route by [Main][knurl.presentation.Main] -
 * this repository's own security audit flagged `POST .../track` and the gallery reads as
 * unthrottled; every other route gets the same protection for free by applying it once, globally,
 * rather than per-route.
 *
 * The key is the first hop of `X-Forwarded-For` when present (Railway, and any standard reverse
 * proxy, sets this), falling back to one shared "unknown" bucket otherwise - a real gap for a
 * direct, unproxied deployment (every unidentified client shares one limit), but a fixed-window
 * counter with no external store is the right tradeoff for what is, per AGENTS.md, always a
 * single-instance deployment; a client IP a proxy doesn't forward is the uncommon case, not the
 * norm this needs to optimize for.
 *
 * Windows are fixed, not sliding: a client can burst up to `2x` its limit across a window
 * boundary. That's an accepted imprecision for the same reason a sliding window isn't worth the
 * extra bookkeeping here - this is a coarse defensive backstop against casual abuse and
 * runaway/misbehaving clients, not a precise quota system.
 */
class RateLimiter(
    private val maxRequestsPerWindow: Int,
    private val window: Duration = Duration.ofMinutes(1),
    private val clock: Clock = Clock.systemUTC(),
) {
    private class WindowCounter(
        @Volatile var windowStart: Instant,
        val count: AtomicInteger = AtomicInteger(0),
    )

    private val counters = ConcurrentHashMap<String, WindowCounter>()

    val filter =
        Filter { next ->
            { request ->
                if (isAllowed(clientKey(request))) {
                    next(request)
                } else {
                    Response(Status.TOO_MANY_REQUESTS).body("""{"error":"rate limit exceeded"}""")
                }
            }
        }

    private fun isAllowed(key: String): Boolean {
        val now = clock.instant()
        val counter =
            counters.compute(key) { _, existing ->
                if (existing == null || Duration.between(existing.windowStart, now) >= window) {
                    WindowCounter(now)
                } else {
                    existing
                }
            }!!

        // Opportunistic sweep, run only past a size threshold rather than on every request: bounds
        // memory against a large number of distinct client IPs whose windows have long since
        // expired and which nothing will request again. Mirrors Presigner's own cache-sweep-on-miss
        // pattern for the same reason - cheap relative to the map growth it rides along with.
        if (counters.size > SWEEP_THRESHOLD) {
            counters.entries.removeIf { (_, c) -> Duration.between(c.windowStart, now) > window.multipliedBy(2) }
        }

        return counter.count.incrementAndGet() <= maxRequestsPerWindow
    }

    private fun clientKey(request: Request): String =
        request
            .header("X-Forwarded-For")
            ?.substringBefore(",")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: UNKNOWN_CLIENT_KEY

    companion object {
        private const val SWEEP_THRESHOLD = 10_000
        private const val UNKNOWN_CLIENT_KEY = "unknown"
    }
}
