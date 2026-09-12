package knurl.presentation.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import knurl.domain.models.Page
import org.http4k.core.Method
import org.http4k.core.Request

private const val GALLERY_PATH = "/api/v1/accounts/17841400000000000/gallery"

private fun request(query: String) = Request(Method.GET, "$GALLERY_PATH?$query")

private fun page(
    currentPage: Int,
    totalPages: Int,
    totalRecords: Int = totalPages * 50,
) = Page(items = listOf("row"), totalRecords = totalRecords, currentPage = currentPage, totalPages = totalPages)

class PaginationTest :
    FunSpec({

        test("copies the page counts straight off the Page") {
            val meta = paginationMeta(request("page=2"), page(currentPage = 2, totalPages = 10, totalRecords = 493))

            meta.totalRecords shouldBe 493
            meta.currentPage shouldBe 2
            meta.totalPages shouldBe 10
        }

        test("builds the full link set for a page in the middle of the range") {
            val links = paginationMeta(request("sort=views&limit=50&page=5"), page(currentPage = 5, totalPages = 10)).links

            links.first shouldBe "$GALLERY_PATH?sort=views&limit=50&page=1"
            links.prev shouldBe "$GALLERY_PATH?sort=views&limit=50&page=4"
            links.self shouldBe "$GALLERY_PATH?sort=views&limit=50&page=5"
            links.next shouldBe "$GALLERY_PATH?sort=views&limit=50&page=6"
            links.last shouldBe "$GALLERY_PATH?sort=views&limit=50&page=10"
        }

        test("omits prev on the first page and next on the last") {
            paginationMeta(request("page=1"), page(currentPage = 1, totalPages = 10)).links.prev shouldBe null
            paginationMeta(request("page=10"), page(currentPage = 10, totalPages = 10)).links.next shouldBe null
        }

        test("collapses to a single self-referential page when there is only one") {
            val links = paginationMeta(request("sort=recent"), page(currentPage = 1, totalPages = 1, totalRecords = 3)).links

            links.prev shouldBe null
            links.next shouldBe null
            links.first shouldBe links.self
            links.last shouldBe links.self
        }

        test("preserves every other query parameter in every link") {
            val links =
                paginationMeta(
                    request("selected=false&mediaType=IMAGE&mediaType=VIDEO&limit=25&page=2"),
                    page(currentPage = 2, totalPages = 3),
                ).links

            // Filters have to survive navigation, or following `next` silently widens the result set.
            for (link in listOf(links.first, links.prev, links.self, links.next, links.last)) {
                link!! shouldContain "selected=false"
                link shouldContain "mediaType=IMAGE"
                link shouldContain "mediaType=VIDEO"
                link shouldContain "limit=25"
            }
        }

        test("rewrites rather than appends page, so following links repeatedly cannot accumulate duplicates") {
            val links = paginationMeta(request("sort=recent&page=2"), page(currentPage = 2, totalPages = 4)).links

            links.next!!.split("page=").size shouldBe 2
            links.self.split("page=").size shouldBe 2
        }

        test("adds page to a request that did not send one") {
            val links = paginationMeta(Request(Method.GET, GALLERY_PATH), page(currentPage = 1, totalPages = 4)).links

            links.self shouldBe "$GALLERY_PATH?page=1"
            links.next shouldBe "$GALLERY_PATH?page=2"
        }

        test("emits relative links, never an absolute URL") {
            val links = paginationMeta(request("page=1"), page(currentPage = 1, totalPages = 2)).links

            links.self.startsWith("/") shouldBe true
            links.next!!.startsWith("/") shouldBe true
        }

        test("example pagination populates every link, as the OpenAPI schema generator requires") {
            // A null example value crashes http4k's schema reflection - see examplePagination's kdoc.
            val links = examplePagination(GALLERY_PATH).links

            listOf(links.first, links.prev, links.self, links.next, links.last).all { !it.isNullOrBlank() } shouldBe true
        }
    })
