package knurl.domain.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CredentialCipherTest :
    FunSpec({
        val cipher = CredentialCipher(CredentialCipher.generateKey())

        test("decrypting an encrypted value returns the original plaintext") {
            val ciphertext = cipher.encrypt("a-real-access-token")

            cipher.decrypt(ciphertext) shouldBe "a-real-access-token"
        }

        test("encrypting the same plaintext twice produces different ciphertext") {
            // Each call draws a fresh random IV, so two encryptions of the same plaintext must
            // never look the same on disk - otherwise a database observer could tell two rows
            // share a token without ever decrypting either.
            cipher.encrypt("a-real-access-token") shouldNotBe cipher.encrypt("a-real-access-token")
        }

        test("ciphertext never contains the plaintext") {
            val ciphertext = cipher.encrypt("a-real-access-token")

            ciphertext shouldNotBe "a-real-access-token"
        }

        test("decrypting with a different key fails rather than returning garbage") {
            val ciphertext = cipher.encrypt("a-real-access-token")
            val otherCipher = CredentialCipher(CredentialCipher.generateKey())

            shouldThrow<Exception> { otherCipher.decrypt(ciphertext) }
        }

        test("rejects a key that is not valid base64") {
            shouldThrow<IllegalArgumentException> { CredentialCipher("not-valid-base64!!!") }
        }

        test("rejects a key that does not decode to 32 bytes") {
            shouldThrow<IllegalArgumentException> { CredentialCipher("dG9vLXNob3J0") }
        }

        test("generateKey produces a key CredentialCipher accepts") {
            CredentialCipher(CredentialCipher.generateKey())
        }
    })
