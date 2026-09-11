package knurl.domain.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DatabaseConfigTest :
    FunSpec({
        test("converts a Railway-style postgres:// URL into JDBC form") {
            val credentials = DatabaseConfig.parseJdbcUrl("postgres://user:secret@dbhost:5432/railway")

            credentials.jdbcUrl shouldBe "jdbc:postgresql://dbhost:5432/railway"
            credentials.username shouldBe "user"
            credentials.password shouldBe "secret"
        }

        test("preserves query parameters when converting") {
            val credentials = DatabaseConfig.parseJdbcUrl("postgres://user:secret@dbhost:5432/railway?sslmode=require")

            credentials.jdbcUrl shouldBe "jdbc:postgresql://dbhost:5432/railway?sslmode=require"
        }

        test("passes an already-JDBC URL through unchanged") {
            val credentials = DatabaseConfig.parseJdbcUrl("jdbc:postgresql://dbhost:5432/railway")

            credentials.jdbcUrl shouldBe "jdbc:postgresql://dbhost:5432/railway"
            credentials.username shouldBe null
            credentials.password shouldBe null
        }

        test("defaults to port 5432 when the URL omits a port") {
            val credentials = DatabaseConfig.parseJdbcUrl("postgres://user:secret@dbhost/railway")

            credentials.jdbcUrl shouldBe "jdbc:postgresql://dbhost:5432/railway"
            credentials.username shouldBe "user"
            credentials.password shouldBe "secret"
        }

        test("percent-encoded characters in the password are decoded, and a literal + is preserved") {
            val credentials = DatabaseConfig.parseJdbcUrl("postgres://user:p%40ss%2Bx@dbhost:5432/railway")

            credentials.password shouldBe "p@ss+x"
        }

        test("fails fast rather than silently producing a null host") {
            shouldThrow<IllegalArgumentException> {
                DatabaseConfig.parseJdbcUrl("postgres://user:p@ss@dbhost:5432/railway")
            }
        }
    })
