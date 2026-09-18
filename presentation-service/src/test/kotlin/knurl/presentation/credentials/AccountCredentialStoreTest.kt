package knurl.presentation.credentials

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knurl.domain.repositories.AccountTokenVerification
import knurl.domain.security.TokenHasher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private fun credentialsFor(
    adminToken: String,
    readToken: String,
) = AccountCredentials(TokenHasher.sha256(adminToken), TokenHasher.sha256(readToken))

class AccountCredentialStoreTest :
    FunSpec({
        val accountA = "account-a"
        val accountB = "account-b"

        fun populated() =
            AccountCredentialStore().apply {
                replaceAll(
                    mapOf(
                        accountA to credentialsFor("admin-a", "read-a"),
                        accountB to credentialsFor("admin-b", "read-b"),
                    ),
                )
            }

        test("an account's own admin token matches") {
            populated().verifyAdminToken(accountA, "admin-a") shouldBe AccountTokenVerification.Match
        }

        test("an account's own read token matches") {
            populated().verifyReadToken(accountA, "read-a") shouldBe AccountTokenVerification.Match
        }

        test("an unknown account is UnknownAccount, not Mismatch") {
            populated().verifyAdminToken("no-such-account", "admin-a") shouldBe AccountTokenVerification.UnknownAccount
        }

        test("a missing token on a known account is a Mismatch") {
            populated().verifyAdminToken(accountA, null) shouldBe AccountTokenVerification.Mismatch
        }

        test("one account's admin token does not authorize another account") {
            populated().verifyAdminToken(accountB, "admin-a") shouldBe AccountTokenVerification.Mismatch
        }

        test("a read token does not authorize admin access") {
            populated().verifyAdminToken(accountA, "read-a") shouldBe AccountTokenVerification.Mismatch
        }

        test("an admin token does not authorize read routes - each kind is checked against its own hash") {
            populated().verifyReadToken(accountA, "admin-a") shouldBe AccountTokenVerification.Mismatch
        }

        test("replaceAll drops accounts absent from the new set, which is how a deletion propagates") {
            val store = populated()

            store.replaceAll(mapOf(accountA to credentialsFor("admin-a", "read-a")))

            store.verifyAdminToken(accountB, "admin-b") shouldBe AccountTokenVerification.UnknownAccount
            store.accountIds() shouldBe setOf(accountA)
        }

        test("replaceAll copies its input, so mutating the caller's map cannot alter the live set") {
            val store = AccountCredentialStore()
            val mutable = mutableMapOf(accountA to credentialsFor("admin-a", "read-a"))

            store.replaceAll(mutable)
            mutable.clear()

            store.verifyAdminToken(accountA, "admin-a") shouldBe AccountTokenVerification.Match
        }

        test("an account present in every published set is never momentarily unknown during a swap") {
            // The failure this guards against is a store that clears and repopulates a live map:
            // a request landing mid-replacement would see the account as absent and 404, even though
            // every snapshot on either side of the swap contains it. A whole-map swap cannot do that.
            val store = populated()
            val before = mapOf(accountA to credentialsFor("admin-a", "read-a"))
            val after = mapOf(accountA to credentialsFor("admin-rotated", "read-rotated"))
            val start = CountDownLatch(1)
            val unknowns = mutableListOf<AccountTokenVerification>()

            val writer =
                thread {
                    start.await()
                    repeat(2_000) { index -> store.replaceAll(if (index % 2 == 0) before else after) }
                }
            val reader =
                thread {
                    start.await()
                    repeat(2_000) {
                        val result = store.verifyAdminToken(accountA, "admin-a")
                        if (result == AccountTokenVerification.UnknownAccount) {
                            synchronized(unknowns) { unknowns += result }
                        }
                    }
                }

            start.countDown()
            writer.join(TimeUnit.SECONDS.toMillis(10))
            reader.join(TimeUnit.SECONDS.toMillis(10))

            unknowns shouldBe emptyList()
        }
    })
