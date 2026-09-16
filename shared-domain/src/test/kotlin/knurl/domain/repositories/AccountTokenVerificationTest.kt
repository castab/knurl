package knurl.domain.repositories

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knurl.domain.security.TokenHasher

/**
 * Direct coverage for [verifyAccountToken] - the actual tenant-isolation mechanism behind both
 * [AccountRepository.verifyAdminToken] and [AccountRepository.verifyReadToken], and the gap the
 * audit flagged: "the single control enforcing tenant isolation has no dedicated test." Exercised
 * here as a pure function, without a database.
 */
class AccountTokenVerificationTest :
    FunSpec({
        val storedHash = TokenHasher.sha256("correct-token")

        test("unknown account when there is no stored hash") {
            verifyAccountToken(storedHash = null, candidateToken = "anything") shouldBe AccountTokenVerification.UnknownAccount
        }

        test("unknown account takes priority even with no candidate token") {
            verifyAccountToken(storedHash = null, candidateToken = null) shouldBe AccountTokenVerification.UnknownAccount
        }

        test("mismatch when no candidate token is presented") {
            verifyAccountToken(storedHash, candidateToken = null) shouldBe AccountTokenVerification.Mismatch
        }

        test("mismatch when the candidate token is wrong") {
            verifyAccountToken(storedHash, candidateToken = "wrong-token") shouldBe AccountTokenVerification.Mismatch
        }

        test("mismatch when the candidate token is a different account's genuinely valid token") {
            // The exact scenario tenant isolation exists to prevent: account A's real token
            // presented against account B's stored hash must not verify.
            verifyAccountToken(storedHash, candidateToken = "another-accounts-real-token") shouldBe AccountTokenVerification.Mismatch
        }

        test("match when the candidate token hashes to the stored value") {
            verifyAccountToken(storedHash, candidateToken = "correct-token") shouldBe AccountTokenVerification.Match
        }
    })
