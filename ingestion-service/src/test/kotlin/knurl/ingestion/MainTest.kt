package knurl.ingestion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

private val RUN_ONCE_CUTOFF: Instant = Instant.parse("2026-01-01T00:00:00Z")
private val NOW: Instant = Instant.parse("2026-01-01T00:10:00Z")

class MainTest :
    FunSpec({
        test("under RUN_ONCE, the cutoff is always the fixed run-start instant, regardless of intervalSeconds or now") {
            accountDueBefore(runOnce = true, runOnceCutoff = RUN_ONCE_CUTOFF, intervalSeconds = 900, now = NOW) shouldBe RUN_ONCE_CUTOFF
            accountDueBefore(runOnce = true, runOnceCutoff = RUN_ONCE_CUTOFF, intervalSeconds = 0, now = NOW) shouldBe RUN_ONCE_CUTOFF
        }

        // This is the exact case a naive "treat every account as always due" fix would get wrong:
        // an account just synced at NOW (i.e. last_synced_at == NOW) must NOT be claimable again
        // within the same RUN_ONCE invocation, or a worker would re-claim and re-sync it forever.
        // last_synced_at <= accountDueBefore(...) must be false for that to hold.
        test("under RUN_ONCE, an account synced after the run started is not due before the fixed cutoff") {
            val dueBefore = accountDueBefore(runOnce = true, runOnceCutoff = RUN_ONCE_CUTOFF, intervalSeconds = 900, now = NOW)
            (NOW.isAfter(dueBefore)) shouldBe true
        }

        test("under the persistent-daemon case, the cutoff is intervalSeconds before now, ignoring runOnceCutoff") {
            accountDueBefore(runOnce = false, runOnceCutoff = RUN_ONCE_CUTOFF, intervalSeconds = 600, now = NOW) shouldBe
                NOW.minusSeconds(600)
        }
    })
