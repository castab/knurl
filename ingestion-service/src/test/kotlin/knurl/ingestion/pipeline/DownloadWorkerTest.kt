package knurl.ingestion.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job

private fun completedJob(): Job = Job().apply { complete() }

private fun incompleteJob(): CompletableJob = Job()

class DownloadWorkerTest :
    FunSpec({
        test("never stops when not running once, regardless of feed-sync job state") {
            shouldStopPolling(runOnce = false, claimedNothing = true, feedSyncJobs = emptyList()) shouldBe false
            shouldStopPolling(runOnce = false, claimedNothing = true, feedSyncJobs = listOf(completedJob())) shouldBe false
        }

        test("does not stop while anything was claimed, even once running once with every feed-sync job done") {
            shouldStopPolling(runOnce = true, claimedNothing = false, feedSyncJobs = listOf(completedJob())) shouldBe false
        }

        // The race this whole function exists to prevent: an early, empty poll must not be mistaken
        // for "nothing left to do" while a feed-sync worker could still enqueue something later.
        test("does not stop when running once, claimed nothing, but a feed-sync job is still running") {
            val stillRunning = incompleteJob()
            shouldStopPolling(runOnce = true, claimedNothing = true, feedSyncJobs = listOf(stillRunning)) shouldBe false
            shouldStopPolling(
                runOnce = true,
                claimedNothing = true,
                feedSyncJobs = listOf(completedJob(), stillRunning),
            ) shouldBe false
        }

        test("stops when running once, claimed nothing, and every feed-sync job has completed") {
            shouldStopPolling(runOnce = true, claimedNothing = true, feedSyncJobs = emptyList()) shouldBe true
            shouldStopPolling(
                runOnce = true,
                claimedNothing = true,
                feedSyncJobs = listOf(completedJob(), completedJob()),
            ) shouldBe true
        }
    })
