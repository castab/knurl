package knurl.presentation.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.http4k.contract.contract
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import java.time.Clock
import java.time.Instant

class HealthRoutesTest :
    FunSpec({
        test("reports ok while the database answers and unavailable without leaking why") {
            var failing = false
            val clock = mockk<Clock>()
            var now = Instant.parse("2026-01-01T00:00:00Z")
            every { clock.instant() } answers { now }
            val app = contract { routes += HealthRoutes({ if (failing) error("jdbc:postgresql://secret-host down") }, clock).routes() }

            app(Request(Method.GET, "/health")).let {
                it.status shouldBe Status.OK
                it.bodyString() shouldBe """{"status":"ok"}"""
            }
            failing = true
            now = now.plusSeconds(5)
            app(Request(Method.GET, "/health")).let {
                it.status shouldBe Status.SERVICE_UNAVAILABLE
                it.bodyString() shouldBe """{"status":"unavailable"}"""
            }
        }

        test("caches the probe so repeated calls cannot drain the connection pool") {
            var calls = 0
            val clock = mockk<Clock>()
            var now = Instant.parse("2026-01-01T00:00:00Z")
            every { clock.instant() } answers { now }
            val app = contract { routes += HealthRoutes({ calls++ }, clock).routes() }

            repeat(10) { app(Request(Method.GET, "/health")).status shouldBe Status.OK }
            calls shouldBe 1
            now = now.plusSeconds(5)
            app(Request(Method.GET, "/health")).status shouldBe Status.OK
            calls shouldBe 2
        }
    })
