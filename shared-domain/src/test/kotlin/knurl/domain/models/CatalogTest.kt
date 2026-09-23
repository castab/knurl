package knurl.domain.models

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CatalogTest :
    FunSpec({

        test("becameNonDigestible: stays false while an item stays digestible") {
            becameNonDigestible(previousReason = null, newReason = null) shouldBe false
        }

        test("becameNonDigestible: true the cycle a digestible (or brand new) item first gets flagged") {
            becameNonDigestible(previousReason = null, newReason = "copyright") shouldBe true
        }

        test("becameNonDigestible: false for an item that was already flagged, even if the reason value changes") {
            becameNonDigestible(previousReason = "copyright", newReason = "copyright") shouldBe false
        }

        // The inverse direction: Instagram starts serving the media again. Nothing reacts to this -
        // the item was already pulled from every gallery when it first went non-digestible, and
        // GalleryRepository.addItems refuses to re-add a still-non-digestible item, so there is no
        // gallery membership left to restore automatically either way.
        test("becameNonDigestible: false when an item clears back to digestible") {
            becameNonDigestible(previousReason = "copyright", newReason = null) shouldBe false
        }
    })
