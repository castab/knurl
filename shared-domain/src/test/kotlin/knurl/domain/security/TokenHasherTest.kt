package knurl.domain.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class TokenHasherTest :
    FunSpec({
        test("hashing the same value twice produces the same digest") {
            TokenHasher.sha256("a-token") shouldBe TokenHasher.sha256("a-token")
        }

        test("hashing different values produces different digests") {
            TokenHasher.sha256("a-token") shouldNotBe TokenHasher.sha256("a-different-token")
        }

        test("the digest is not the plaintext value") {
            TokenHasher.sha256("a-token") shouldNotBe "a-token"
        }

        test("matches is true for a candidate that hashes to the stored value") {
            val stored = TokenHasher.sha256("correct-token")

            TokenHasher.matches(TokenHasher.sha256("correct-token"), stored) shouldBe true
        }

        test("matches is false for a candidate that hashes to a different value") {
            val stored = TokenHasher.sha256("correct-token")

            TokenHasher.matches(TokenHasher.sha256("wrong-token"), stored) shouldBe false
        }
    })
