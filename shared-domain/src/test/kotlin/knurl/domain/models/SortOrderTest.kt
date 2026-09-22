package knurl.domain.models

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SortOrderTest :
    FunSpec({
        test("defaults gallery content to curated ordering") {
            SortOrder.fromQueryParam(null) shouldBe SortOrder.CURATED
        }

        test("accepts every supported gallery content sort") {
            SortOrder.fromQueryParam("curated") shouldBe SortOrder.CURATED
            SortOrder.fromQueryParam("recent") shouldBe SortOrder.RECENT
            SortOrder.fromQueryParam("views") shouldBe SortOrder.VIEWS
        }
    })
