package knurl.presentation.s3

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.Closeable
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** A [Clock] whose reported instant can be moved forward on demand, to simulate elapsed time without sleeping. */
private class MutableClock(
    private var instant: Instant,
) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId) = this

    override fun instant() = instant

    fun advanceBy(duration: Duration) {
        instant = instant.plus(duration)
    }
}

private fun fakePresignedMedia(
    key: String,
    callNumber: Int,
): PresignedMedia = PresignedMedia(url = "https://example-bucket.s3.amazonaws.com/$key?call=$callNumber", expiresAt = Instant.EPOCH)

/**
 * Exercises the URL-reuse cache added on top of the underlying (real, local-only) SigV4 signing -
 * see [Presigner]'s class doc for why it exists. Uses the `internal` constructor with a fake
 * `sign` function instead of a real [software.amazon.awssdk.services.s3.presigner.S3Presigner] so
 * "was signing actually skipped" is directly observable via a call counter, rather than inferred
 * from URL content - two real signs of the same key within the same wall-clock second produce an
 * identical URL regardless of caching, which would make that inference unreliable.
 */
class PresignerCachingTest :
    FunSpec({
        test("reuses the cached URL for repeat requests of the same key within cacheDuration") {
            val signCalls = AtomicInteger(0)
            val clock = MutableClock(Instant.EPOCH)
            val presigner =
                Presigner(
                    ttl = Duration.ofHours(6),
                    cacheDuration = Duration.ofMinutes(15),
                    clock = clock,
                    closeable = Closeable {},
                    sign = { key -> fakePresignedMedia(key, signCalls.incrementAndGet()) },
                )

            val first = presigner.presignGet("posts/abc/small.webp")
            clock.advanceBy(Duration.ofMinutes(10))
            val second = presigner.presignGet("posts/abc/small.webp")

            second shouldBe first
            signCalls.get() shouldBe 1
        }

        test("resigns once cacheDuration has elapsed") {
            val signCalls = AtomicInteger(0)
            val clock = MutableClock(Instant.EPOCH)
            val presigner =
                Presigner(
                    ttl = Duration.ofHours(6),
                    cacheDuration = Duration.ofMinutes(15),
                    clock = clock,
                    closeable = Closeable {},
                    sign = { key -> fakePresignedMedia(key, signCalls.incrementAndGet()) },
                )

            val first = presigner.presignGet("posts/abc/small.webp")
            clock.advanceBy(Duration.ofMinutes(16))
            val second = presigner.presignGet("posts/abc/small.webp")

            second shouldBe fakePresignedMedia("posts/abc/small.webp", 2)
            first shouldBe fakePresignedMedia("posts/abc/small.webp", 1)
            signCalls.get() shouldBe 2
        }

        test("caches each key independently") {
            val signCalls = AtomicInteger(0)
            val clock = MutableClock(Instant.EPOCH)
            val presigner =
                Presigner(
                    ttl = Duration.ofHours(6),
                    cacheDuration = Duration.ofMinutes(15),
                    clock = clock,
                    closeable = Closeable {},
                    sign = { key -> fakePresignedMedia(key, signCalls.incrementAndGet()) },
                )

            presigner.presignGet("posts/abc/small.webp")
            presigner.presignGet("posts/abc/large.webp")
            presigner.presignGet("posts/abc/small.webp")

            signCalls.get() shouldBe 2
        }

        test("never caches longer than a shorter ttl allows") {
            val signCalls = AtomicInteger(0)
            val clock = MutableClock(Instant.EPOCH)
            val presigner =
                Presigner(
                    ttl = Duration.ofMinutes(5),
                    cacheDuration = Duration.ofMinutes(15),
                    clock = clock,
                    closeable = Closeable {},
                    sign = { key -> fakePresignedMedia(key, signCalls.incrementAndGet()) },
                )

            presigner.presignGet("posts/abc/small.webp")
            clock.advanceBy(Duration.ofMinutes(6))
            presigner.presignGet("posts/abc/small.webp")

            signCalls.get() shouldBe 2
        }
    })
