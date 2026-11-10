package knurl.ingestion.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import java.time.Instant

private val NOW: Instant = Instant.parse("2024-06-01T12:00:00Z")

/** An hour before [NOW] - the cutoff a default 60-minute grace period produces. */
private val CUTOFF: Instant = NOW.minusSeconds(3600)

private fun listed(
    key: String,
    minutesOld: Long,
): ListedObject = ListedObject(key = key, lastModified = NOW.minusSeconds(minutesOld * 60))

/**
 * `classifyOrphans` decides what gets deleted from the bucket, so the cases that matter most here
 * are the ones where it must *refuse* to: anything the database still references, and anything
 * recent enough to be a healthy upload whose row has not been written yet.
 */
class OrphanSweeperTest :
    FunSpec({
        test("an object the database references is never an orphan, however old") {
            val result =
                classifyOrphans(
                    listed = listOf(listed("posts/media-1/0/small.webp", minutesOld = 10_000)),
                    knownKeys = setOf("posts/media-1/0/small.webp"),
                    uploadedBefore = CUTOFF,
                    limit = 100,
                )

            result.shouldBeEmpty()
        }

        test("an unreferenced object older than the grace period is an orphan") {
            val result =
                classifyOrphans(
                    listed = listOf(listed("posts/media-9/0/small.webp", minutesOld = 120)),
                    knownKeys = setOf("posts/media-1/0/small.webp"),
                    uploadedBefore = CUTOFF,
                    limit = 100,
                )

            result shouldContainExactly listOf("posts/media-9/0/small.webp")
        }

        // The case that makes the sweep safe to run concurrently with ingestion: a sibling instance
        // uploads a post's children and only then writes the row referencing them. In that gap a
        // healthy object is indistinguishable from an orphan by key alone.
        test("an unreferenced object newer than the grace period is left alone as a possible in-flight upload") {
            val result =
                classifyOrphans(
                    listed = listOf(listed("posts/media-9/0/small.webp", minutesOld = 5)),
                    knownKeys = emptySet(),
                    uploadedBefore = CUTOFF,
                    limit = 100,
                )

            result.shouldBeEmpty()
        }

        test("an object exactly at the cutoff is left alone, since the boundary is exclusive") {
            val result =
                classifyOrphans(
                    listed = listOf(ListedObject("posts/media-9/0/small.webp", CUTOFF)),
                    knownKeys = emptySet(),
                    uploadedBefore = CUTOFF,
                    limit = 100,
                )

            result.shouldBeEmpty()
        }

        test("the limit caps how many orphans one sweep collects") {
            val result =
                classifyOrphans(
                    listed = (1..10).map { listed("posts/media-$it/0/small.webp", minutesOld = 120) },
                    knownKeys = emptySet(),
                    uploadedBefore = CUTOFF,
                    limit = 3,
                )

            result shouldContainExactly
                listOf(
                    "posts/media-1/0/small.webp",
                    "posts/media-2/0/small.webp",
                    "posts/media-3/0/small.webp",
                )
        }

        test("mixes referenced, recent and orphaned objects in one listing") {
            val result =
                classifyOrphans(
                    listed =
                        listOf(
                            listed("posts/media-1/0/small.webp", minutesOld = 500),
                            listed("posts/media-1/0/large.webp", minutesOld = 500),
                            listed("catalog/media-1/thumbnail.webp", minutesOld = 500),
                            listed("posts/media-2/0/small.webp", minutesOld = 500),
                            listed("posts/media-3/0/small.webp", minutesOld = 2),
                        ),
                    knownKeys =
                        setOf(
                            "posts/media-1/0/small.webp",
                            "posts/media-1/0/large.webp",
                            "catalog/media-1/thumbnail.webp",
                        ),
                    uploadedBefore = CUTOFF,
                    limit = 100,
                )

            result shouldContainExactly listOf("posts/media-2/0/small.webp")
        }

        test("an empty listing yields nothing") {
            classifyOrphans(
                listed = emptyList(),
                knownKeys = setOf("posts/media-1/0/small.webp"),
                uploadedBefore = CUTOFF,
                limit = 100,
            ).shouldBeEmpty()
        }
    })
