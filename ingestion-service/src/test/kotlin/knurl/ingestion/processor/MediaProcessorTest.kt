package knurl.ingestion.processor

import com.sksamuel.scrimage.ImmutableImage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val SMALL = 400
private const val LARGE = 800

/**
 * Geometry only - these assertions deliberately stop short of [com.sksamuel.scrimage.webp.WebpWriter],
 * whose encoder shells out to a bundled native `cwebp` binary and would make the suite platform-dependent.
 * The regression being guarded here is which pixels survive the resize, not how they are compressed.
 */
class MediaProcessorTest :
    FunSpec({
        test("boundedVariant: a portrait source keeps its full height instead of being center-cropped square") {
            val bounded = boundedVariant(ImmutableImage.create(1080, 1350), LARGE)

            bounded.width shouldBe 640
            bounded.height shouldBe 800
        }

        test("boundedVariant: a landscape source keeps its full width") {
            val bounded = boundedVariant(ImmutableImage.create(1350, 1080), LARGE)

            bounded.width shouldBe 800
            bounded.height shouldBe 640
        }

        test("boundedVariant: a square source still fills the box exactly") {
            val bounded = boundedVariant(ImmutableImage.create(1000, 1000), LARGE)

            bounded.width shouldBe 800
            bounded.height shouldBe 800
        }

        test("boundedVariant: a source smaller than the box is left at its original size, never upscaled") {
            val bounded = boundedVariant(ImmutableImage.create(400, 500), LARGE)

            bounded.width shouldBe 400
            bounded.height shouldBe 500
        }

        test("squareVariant: every source shape still produces an exact square for the 1:1 grid cell") {
            listOf(
                ImmutableImage.create(1080, 1350),
                ImmutableImage.create(1350, 1080),
                ImmutableImage.create(1000, 1000),
                ImmutableImage.create(200, 250),
            ).forEach { source ->
                val square = squareVariant(source, SMALL)

                square.width shouldBe SMALL
                square.height shouldBe SMALL
            }
        }
    })
