package knurl.ingestion.processor

import com.sksamuel.scrimage.ImmutableImage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

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

        test("parseVideoDimensions reads ffprobe's width,height output") {
            parseVideoDimensions("1920,1080\n") shouldBe VideoDimensions(1920, 1080)
        }

        test("parseVideoDimensions rejects missing or invalid dimensions") {
            shouldThrow<IllegalArgumentException> { parseVideoDimensions("0,1080\n") }
            shouldThrow<IllegalArgumentException> { parseVideoDimensions("not dimensions\n") }
        }

        test("copyWithLimit copies a source at or under the limit in full") {
            val source = ByteArray(100) { it.toByte() }
            val output = ByteArrayOutputStream()

            copyWithLimit(ByteArrayInputStream(source), output, limit = 100, sourceUrl = "http://example/video")

            output.toByteArray() shouldBe source
        }

        test("copyWithLimit aborts once the source exceeds the limit") {
            val source = ByteArray(101) { it.toByte() }
            val output = ByteArrayOutputStream()

            shouldThrow<IOException> {
                copyWithLimit(ByteArrayInputStream(source), output, limit = 100, sourceUrl = "http://example/video")
            }
        }

        test("copyWithLimit's exception names the source URL, so a rejected download is traceable in logs") {
            val source = ByteArray(101)

            val exception =
                shouldThrow<IOException> {
                    copyWithLimit(
                        ByteArrayInputStream(source),
                        ByteArrayOutputStream(),
                        limit = 100,
                        sourceUrl = "http://example/oversized",
                    )
                }

            exception.message shouldBe "Source video at http://example/oversized exceeded the 100 byte download limit"
        }
    })
