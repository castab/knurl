package knurl.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knurl.domain.config.DatabaseSettings
import java.nio.file.Files

class AppConfigTest :
    FunSpec({
        val database = DatabaseSettings("jdbc:postgresql://localhost/test")
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        test("exactly one management credential source is required") {
            shouldThrow<IllegalArgumentException> { AppConfig(database, key) }
            shouldThrow<IllegalArgumentException> { AppConfig(database, key, "management-secret", "secret.txt") }
        }
        test("mounted management credentials are re-read and bounded") {
            val file = Files.createTempFile("knurl-management-", ".txt")
            try {
                Files.writeString(file, "first-management-secret\n")
                val config = AppConfig(database, key, managementTokenFile = file.toString())
                config.managementSecret() shouldBe "first-management-secret"
                Files.writeString(file, "second-management-secret\n")
                config.managementSecret() shouldBe "second-management-secret"
                Files.writeString(file, "x".repeat(4097))
                shouldThrow<IllegalArgumentException> { config.managementSecret() }
            } finally {
                Files.delete(file)
            }
        }
        test("configuration string representations never reveal secrets") {
            AppConfig(database, key, "management-secret").toString() shouldBe "AppConfig(redacted)"
        }
    })
