package knurl.presentation.ratelimit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.RequestSource
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

private fun requestFrom(ip: String) = Request(Method.GET, "/x").header("CF-Connecting-IP", ip)

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

        test("caller-supplied X-Forwarded-For entries cannot mint new buckets") {
            val limiter = RateLimiter(maxRequestsPerWindow = 1)
            val handler = limiter.filter.then { Response(Status.OK) }

            handler(requestFrom("3.3.3.3").header("X-Forwarded-For", "9.9.9.1")).status shouldBe Status.OK
            handler(requestFrom("3.3.3.3").header("X-Forwarded-For", "9.9.9.2")).status shouldBe Status.TOO_MANY_REQUESTS
        }

        test("a missing client IP header falls back to the socket peer address, ignoring X-Forwarded-For") {
            val limiter = RateLimiter(maxRequestsPerWindow = 1)
            val handler = limiter.filter.then { Response(Status.OK) }
            val direct = Request(Method.GET, "/x").source(RequestSource("192.0.2.1"))

            handler(direct).status shouldBe Status.OK
            handler(direct.header("X-Forwarded-For", "9.9.9.9")).status shouldBe Status.TOO_MANY_REQUESTS
            handler(Request(Method.GET, "/x").source(RequestSource("192.0.2.2"))).status shouldBe Status.OK
        }

        test("a different edge header can be configured") {
            val limiter = RateLimiter(maxRequestsPerWindow = 1, clientIpHeader = "Fly-Client-IP")
            val handler = limiter.filter.then { Response(Status.OK) }
            val direct = Request(Method.GET, "/x").source(RequestSource("192.0.2.1"))

            handler(direct.header("Fly-Client-IP", "1.1.1.1")).status shouldBe Status.OK
            handler(direct.header("Fly-Client-IP", "1.1.1.1").header("CF-Connecting-IP", "2.2.2.2")).status shouldBe
                Status.TOO_MANY_REQUESTS
            handler(direct.header("Fly-Client-IP", "2.2.2.2")).status shouldBe Status.OK
        }

        test("no configured header keys on the peer address and ignores every forwarding header") {
            val limiter = RateLimiter(maxRequestsPerWindow = 1, clientIpHeader = null)
            val handler = limiter.filter.then { Response(Status.OK) }
            val direct = Request(Method.GET, "/x").source(RequestSource("192.0.2.1"))

            handler(direct.header("CF-Connecting-IP", "1.1.1.1")).status shouldBe Status.OK
            handler(direct.header("CF-Connecting-IP", "2.2.2.2")).status shouldBe Status.TOO_MANY_REQUESTS
        }

        test("rejects a malformed header name") {
            shouldThrow<IllegalArgumentException> { RateLimiter(maxRequestsPerWindow = 1, clientIpHeader = "Bad Header:") }
        }

        test("clients with neither a client IP header nor a peer address share one bucket") {
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
