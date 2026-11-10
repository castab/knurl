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
 * The key is the single-address header the edge proxy sets for the connecting client -
 * Cloudflare's `CF-Connecting-IP` by default ([clientIpHeader]). Cloudflare replaces any value a
 * caller sends, so through the edge it cannot be forged; `X-Forwarded-For` is deliberately not
 * consulted because its leftmost entries are caller-supplied. The header is only as trustworthy as
 * the network path: the origin must accept traffic solely from the edge, otherwise a caller
 * reaching it directly can choose their own bucket. When the header is absent, or [clientIpHeader]
 * is null for a direct deployment, the key is the socket peer address; only without one do
 * requests share a single "unknown" bucket.
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
    private val clientIpHeader: String? = DEFAULT_CLIENT_IP_HEADER,
) {
    init {
        require(clientIpHeader == null || isValidHeaderName(clientIpHeader)) { "invalid client IP header name" }
    }

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

    private fun clientKey(request: Request): String {
        if (clientIpHeader != null) {
            // Bounded: a hostile oversized value must not become an arbitrarily large map key.
            request
                .header(clientIpHeader)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it.take(MAX_KEY_LENGTH) }
        }
        return request.source?.address ?: UNKNOWN_CLIENT_KEY
    }

    companion object {
        private const val SWEEP_THRESHOLD = 10_000
        private const val UNKNOWN_CLIENT_KEY = "unknown"
        private const val MAX_KEY_LENGTH = 64
        const val DEFAULT_CLIENT_IP_HEADER = "CF-Connecting-IP"

        fun isValidHeaderName(name: String): Boolean = Regex("[A-Za-z0-9-]{1,64}").matches(name)
    }
}
