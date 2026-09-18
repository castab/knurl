package knurl.presentation.credentials

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knurl.domain.security.TokenHasher
import knurl.presentation.LocalCredentials

class LocalCredentialSourceTest :
    FunSpec({
        val local = LocalCredentials("17841457350963368", "admin-secret", "read-secret")

        test("yields exactly the one configured account") {
            LocalCredentialSource(local).fetch().keys shouldBe setOf("17841457350963368")
        }

        test("hashes both tokens rather than retaining the plaintext") {
            val credentials = LocalCredentialSource(local).fetch().getValue("17841457350963368")

            credentials.adminTokenHash shouldBe TokenHasher.sha256("admin-secret")
            credentials.readTokenHash shouldBe TokenHasher.sha256("read-secret")
        }

        test("a blank token fails validation rather than being silently hashed") {
            shouldThrow<IllegalArgumentException> { LocalCredentials("account", "", "read-secret").validate() }
            shouldThrow<IllegalArgumentException> { LocalCredentials("account", "admin-secret", "  ").validate() }
        }

        test("a blank account id fails validation - it would key the credential set on nothing") {
            shouldThrow<IllegalArgumentException> { LocalCredentials("", "admin-secret", "read-secret").validate() }
        }

        test("reusing one value for both tokens fails validation - the read token is browser-exposed") {
            shouldThrow<IllegalArgumentException> { LocalCredentials("account", "same", "same").validate() }
        }
    })
