package knurl.presentation.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import knurl.domain.models.PurgeRequestResult

/**
 * The purge endpoint reports a status per requested shortcode, so what matters here is that every
 * shortcode the client asked about comes back exactly once, in the order it was asked about, with
 * the status the repository's two result sets imply - including the "not found" case, which the
 * repository reports only by omission.
 */
class MediaPurgeMappingTest :
    FunSpec({
        test("splits requested shortcodes into accepted, not-in-gallery and not-found") {
            val results =
                purgeResults(
                    requested = listOf("AAA", "BBB", "CCC"),
                    result = PurgeRequestResult(accepted = setOf("AAA"), notDownloaded = setOf("BBB")),
                )

            results shouldContainExactly
                listOf(
                    MediaPurgeItemResult("AAA", "ACCEPTED"),
                    MediaPurgeItemResult("BBB", "NOT_DOWNLOADED"),
                    MediaPurgeItemResult("CCC", "NOT_FOUND"),
                )
        }

        test("preserves request order so a client can zip the two lists positionally") {
            val results =
                purgeResults(
                    requested = listOf("CCC", "AAA", "BBB"),
                    result = PurgeRequestResult(accepted = setOf("AAA", "BBB"), notDownloaded = emptySet()),
                )

            results.map { it.shortcode } shouldContainExactly listOf("CCC", "AAA", "BBB")
        }

        // The repository is passed a Set, so a duplicated shortcode is one row there; emitting it
        // twice here would make results.size disagree with what actually happened.
        test("reports a duplicated shortcode once") {
            val results =
                purgeResults(
                    requested = listOf("AAA", "AAA", "BBB"),
                    result = PurgeRequestResult(accepted = setOf("AAA", "BBB"), notDownloaded = emptySet()),
                )

            results shouldContainExactly
                listOf(
                    MediaPurgeItemResult("AAA", "ACCEPTED"),
                    MediaPurgeItemResult("BBB", "ACCEPTED"),
                )
        }

        test("reports every shortcode as not found when the account has none of them") {
            val results =
                purgeResults(
                    requested = listOf("AAA", "BBB"),
                    result = PurgeRequestResult(accepted = emptySet(), notDownloaded = emptySet()),
                )

            results.map { it.status } shouldContainExactly listOf("NOT_FOUND", "NOT_FOUND")
        }

        test("the batch cap is the documented one") {
            MAX_PURGE_BATCH shouldBe 100
        }
    })
