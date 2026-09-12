package knurl.domain.models

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PageTest :
    FunSpec({

        test("splits a total across pages, rounding the last partial page up") {
            Page.of(totalRecords = 95, pageSize = 50, requestedPage = 1) { emptyList<String>() }.totalPages shouldBe 2
            Page.of(totalRecords = 100, pageSize = 50, requestedPage = 1) { emptyList<String>() }.totalPages shouldBe 2
            Page.of(totalRecords = 101, pageSize = 50, requestedPage = 1) { emptyList<String>() }.totalPages shouldBe 3
        }

        test("passes the offset of the requested page to fetch") {
            var seenOffset: Int? = null
            Page.of(totalRecords = 500, pageSize = 50, requestedPage = 4) { offset ->
                seenOffset = offset
                listOf("row")
            }
            seenOffset shouldBe 150
        }

        test("reports one empty page rather than zero pages when nothing matched") {
            var fetched = false
            val page =
                Page.of(totalRecords = 0, pageSize = 50, requestedPage = 3) {
                    fetched = true
                    listOf("row")
                }

            // Skipping the query matters: a row fetch against an empty set cannot return anything,
            // and callers still need a coherent page 1 of 1 to build links from.
            fetched shouldBe false
            page.items shouldBe emptyList()
            page.totalRecords shouldBe 0
            page.currentPage shouldBe 1
            page.totalPages shouldBe 1
        }

        test("clamps a page below the range up to the first page") {
            var seenOffset: Int? = null
            val page =
                Page.of(totalRecords = 500, pageSize = 50, requestedPage = 0) {
                    seenOffset = it
                    listOf("row")
                }
            page.currentPage shouldBe 1
            seenOffset shouldBe 0

            Page.of(totalRecords = 500, pageSize = 50, requestedPage = -7) { emptyList<String>() }.currentPage shouldBe 1
        }

        test("clamps a page past the end down to the last page") {
            var seenOffset: Int? = null
            val page =
                Page.of(totalRecords = 120, pageSize = 50, requestedPage = 99) {
                    seenOffset = it
                    listOf("row")
                }
            page.currentPage shouldBe 3
            page.totalPages shouldBe 3
            seenOffset shouldBe 100
        }

        test("carries the fetched rows and the unmodified total through") {
            val page = Page.of(totalRecords = 3, pageSize = 2, requestedPage = 2) { listOf("c") }
            page.items shouldBe listOf("c")
            page.totalRecords shouldBe 3
            page.currentPage shouldBe 2
            page.totalPages shouldBe 2
        }

        test("rejects a non-positive page size rather than dividing by zero") {
            runCatching { Page.of(totalRecords = 10, pageSize = 0, requestedPage = 1) { emptyList<String>() } }
                .isFailure shouldBe true
        }
    })
