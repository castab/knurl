package knurl.domain.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DatabaseConfigTest :
    FunSpec({
        test("converts a Railway-style postgres:// URL into JDBC form") {
            val credentials = DatabaseConfig.parseJdbcUrl("postgres://user:secret@dbhost:5432/railway")

            credentials.jdbcUrl shouldBe "jdbc:postgresql://dbhost:5432/railway?sslmode=require"
            credentials.username shouldBe "user"
            credentials.password shouldBe "secret"
        }

        test("preserves an existing sslmode query parameter instead of overriding it") {
            val credentials = DatabaseConfig.parseJdbcUrl("postgres://user:secret@dbhost:5432/railway?sslmode=verify-full")

            credentials.jdbcUrl shouldBe "jdbc:postgresql://dbhost:5432/railway?sslmode=verify-full"
        }

        test("appends sslmode after other existing query parameters with &, not ?") {
            val credentials = DatabaseConfig.parseJdbcUrl("postgres://user:secret@dbhost:5432/railway?currentSchema=public")

            credentials.jdbcUrl shouldBe "jdbc:postgresql://dbhost:5432/railway?currentSchema=public&sslmode=require"
        }

        test("does not force sslmode for a loopback host - local Postgres has no TLS configured") {
            val credentials = DatabaseConfig.parseJdbcUrl("postgres://user:secret@localhost:5432/railway")

            credentials.jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/railway"
        }

        test("does not force sslmode for a 127.0.0.1 host") {
            val credentials = DatabaseConfig.parseJdbcUrl("postgres://user:secret@127.0.0.1:5432/railway")

            credentials.jdbcUrl shouldBe "jdbc:postgresql://127.0.0.1:5432/railway"
        }

        test("forces sslmode on an already-JDBC URL for a non-loopback host") {
            val credentials = DatabaseConfig.parseJdbcUrl("jdbc:postgresql://dbhost:5432/railway")

            credentials.jdbcUrl shouldBe "jdbc:postgresql://dbhost:5432/railway?sslmode=require"
            credentials.username shouldBe null
            credentials.password shouldBe null
        }

        test("passes an already-JDBC URL through unchanged for a loopback host") {
            val credentials = DatabaseConfig.parseJdbcUrl("jdbc:postgresql://localhost:5432/railway")

            credentials.jdbcUrl shouldBe "jdbc:postgresql://localhost:5432/railway"
        }

        test("defaults to port 5432 when the URL omits a port") {
            val credentials = DatabaseConfig.parseJdbcUrl("postgres://user:secret@dbhost/railway")

            credentials.jdbcUrl shouldBe "jdbc:postgresql://dbhost:5432/railway?sslmode=require"
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
