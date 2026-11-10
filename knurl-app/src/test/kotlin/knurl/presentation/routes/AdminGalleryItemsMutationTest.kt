package knurl.presentation.routes

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class AdminGalleryItemsMutationTest :
    FunSpec({
        test("allows ranking an item added in the same request") {
            val mutation =
                validatedGalleryItemsMutation(
                    GalleryItemsRequest(add = listOf("Cabc123XYZ"), sortOrders = mapOf("Cabc123XYZ" to 0)),
                )

            mutation.add shouldBe setOf("Cabc123XYZ")
            mutation.sortOrders shouldBe mapOf("Cabc123XYZ" to 0)
        }

        test("allows clearing an explicit rank") {
            val mutation = validatedGalleryItemsMutation(GalleryItemsRequest(clearSortOrders = listOf("Cabc123XYZ")))

            mutation.sortOrders shouldBe mapOf("Cabc123XYZ" to null)
        }

        test("rejects a shortcode both removed and ranked") {
            val failure =
                shouldThrow<IllegalArgumentException> {
                    validatedGalleryItemsMutation(
                        GalleryItemsRequest(remove = listOf("Cabc123XYZ"), sortOrders = mapOf("Cabc123XYZ" to 1)),
                    )
                }

            failure.message shouldContain "both removed and ranked"
        }

        test("rejects a negative rank") {
            val failure =
                shouldThrow<IllegalArgumentException> {
                    validatedGalleryItemsMutation(GalleryItemsRequest(sortOrders = mapOf("Cabc123XYZ" to -1)))
                }

            failure.message shouldContain "zero or greater"
        }
    })
