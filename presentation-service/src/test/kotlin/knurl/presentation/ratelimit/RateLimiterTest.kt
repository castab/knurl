package knurl.presentation.ratelimit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private class MutableClock(
    private var instant: Instant,
) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?) = this

    override fun instant() = instant

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }
}

private fun requestFrom(ip: String) = Request(Method.GET, "/x").header("X-Forwarded-For", ip)

class RateLimiterTest :
    FunSpec({
        test("allows requests up to the limit within one window") {
            val limiter = RateLimiter(maxRequestsPerWindow = 3)
            val handler = limiter.filter.then { Response(Status.OK) }

            repeat(3) {
                handler(requestFrom("1.2.3.4")).status shouldBe Status.OK
            }
        }

        test("rejects a request beyond the limit within one window") {
            val limiter = RateLimiter(maxRequestsPerWindow = 3)
            val handler = limiter.filter.then { Response(Status.OK) }

            repeat(3) { handler(requestFrom("1.2.3.4")) }

            handler(requestFrom("1.2.3.4")).status shouldBe Status.TOO_MANY_REQUESTS
        }

        test("tracks each client IP independently") {
            val limiter = RateLimiter(maxRequestsPerWindow = 1)
            val handler = limiter.filter.then { Response(Status.OK) }

            handler(requestFrom("1.1.1.1")).status shouldBe Status.OK
            handler(requestFrom("1.1.1.1")).status shouldBe Status.TOO_MANY_REQUESTS
            handler(requestFrom("2.2.2.2")).status shouldBe Status.OK
        }

        test("uses only the first hop of a multi-hop X-Forwarded-For header") {
            val limiter = RateLimiter(maxRequestsPerWindow = 1)
            val handler = limiter.filter.then { Response(Status.OK) }

            handler(Request(Method.GET, "/x").header("X-Forwarded-For", "3.3.3.3, 10.0.0.1, 10.0.0.2")).status shouldBe Status.OK
            handler(Request(Method.GET, "/x").header("X-Forwarded-For", "3.3.3.3, 10.0.0.9")).status shouldBe Status.TOO_MANY_REQUESTS
        }

        test("clients with no X-Forwarded-For header share one bucket") {
            val limiter = RateLimiter(maxRequestsPerWindow = 1)
            val handler = limiter.filter.then { Response(Status.OK) }

            handler(Request(Method.GET, "/x")).status shouldBe Status.OK
            handler(Request(Method.GET, "/x")).status shouldBe Status.TOO_MANY_REQUESTS
        }

        test("resets the count once the window elapses") {
            val clock = MutableClock(Instant.parse("2024-01-01T00:00:00Z"))
            val limiter = RateLimiter(maxRequestsPerWindow = 1, window = Duration.ofMinutes(1), clock = clock)
            val handler = limiter.filter.then { Response(Status.OK) }

            handler(requestFrom("1.2.3.4")).status shouldBe Status.OK
            handler(requestFrom("1.2.3.4")).status shouldBe Status.TOO_MANY_REQUESTS

            clock.advance(Duration.ofMinutes(1))

            handler(requestFrom("1.2.3.4")).status shouldBe Status.OK
        }
    })
