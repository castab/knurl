package knurl.domain.models

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knurl.domain.config.S3Settings
import knurl.domain.repositories.AccountRepository

class AccountStorageTest :
    FunSpec({
        test("fixed account prefixes cannot escape into sibling namespaces") {
            accountObjectPrefix("17841400000000000") shouldBe "knurl/accounts/17841400000000000/"
            listOf("../other", "a/b", "", "a%2fb", "a\\b").forEach { value ->
                shouldThrow<IllegalArgumentException> { accountObjectPrefix(value) }
            }
        }
        test("bucket endpoints require HTTPS apart from local development and reject embedded credentials") {
            fun settings(endpoint: String) = S3Settings("my-bucket", "us-east-1", endpoint, "access-key", "secret-key", true)
            AccountRepository.validateStorage(settings("https://s3.example.com"))
            AccountRepository.validateStorage(settings("http://localhost:9000"))
            shouldThrow<IllegalArgumentException> { AccountRepository.validateStorage(settings("http://public.example.com")) }
            shouldThrow<IllegalArgumentException> { AccountRepository.validateStorage(settings("https://user:secret@s3.example.com")) }
            shouldThrow<IllegalArgumentException> { AccountRepository.validateStorage(settings("https://s3.example.com?token=secret")) }
        }
    })
