package knurl.presentation.credentials

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.AccountTokenVerification
import knurl.domain.security.TokenHasher
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private fun credentials(vararg accountIds: String) =
    accountIds.associateWith { AccountCredentials(TokenHasher.sha256("admin-$it"), TokenHasher.sha256("read-$it")) }

class CredentialRefresherTest :
    FunSpec({
        test("a successful refresh becomes the live credential set") {
            val store = AccountCredentialStore()
            val source = mockk<CredentialSource>()
            every { source.fetch() } returns credentials("account-a")
            val accounts = mockk<AccountRepository>(relaxed = true)

            CredentialRefresher(source, store, accounts).refresh() shouldBe true

            store.verifyAdminToken("account-a", "admin-account-a") shouldBe AccountTokenVerification.Match
        }

        test("every fetched account is anchored in the database so galleries can reference it") {
            val source = mockk<CredentialSource>()
            every { source.fetch() } returns credentials("account-a", "account-b")
            val accounts = mockk<AccountRepository>(relaxed = true)

            CredentialRefresher(source, AccountCredentialStore(), accounts).refresh()

            verify(exactly = 1) { accounts.ensureAccount("account-a") }
            verify(exactly = 1) { accounts.ensureAccount("account-b") }
        }

        test("a failing fetch propagates and leaves the previous credentials live") {
            val store = AccountCredentialStore()
            store.replaceAll(credentials("account-a"))
            val source = mockk<CredentialSource>()
            every { source.fetch() } throws IOException("credential endpoint down")
            val accounts = mockk<AccountRepository>(relaxed = true)

            shouldThrow<IOException> { CredentialRefresher(source, store, accounts).refresh() }

            store.verifyAdminToken("account-a", "admin-account-a") shouldBe AccountTokenVerification.Match
            verify(exactly = 0) { accounts.ensureAccount(any()) }
        }

        test("a slow fetch returning after a newer one is discarded rather than reinstated") {
            val store = AccountCredentialStore()
            val accounts = mockk<AccountRepository>(relaxed = true)
            val slowStarted = CountDownLatch(1)
            val fastFinished = CountDownLatch(1)

            // The first (slow) call blocks until the second has fully applied, so its stale result
            // arrives last - the exact ordering that would silently roll back a rotation.
            val source =
                object : CredentialSource {
                    private var first = true

                    override fun fetch(): Map<String, AccountCredentials> =
                        if (first) {
                            first = false
                            slowStarted.countDown()
                            fastFinished.await(10, TimeUnit.SECONDS)
                            credentials("stale-account")
                        } else {
                            credentials("fresh-account")
                        }
                }
            val refresher = CredentialRefresher(source, store, accounts)

            val slow = thread { refresher.refresh() }
            slowStarted.await(10, TimeUnit.SECONDS)
            refresher.refresh() shouldBe true
            fastFinished.countDown()
            slow.join(TimeUnit.SECONDS.toMillis(10))

            store.accountIds() shouldBe setOf("fresh-account")
        }

        test("startup retries a transient failure rather than crash-looping on a restart race") {
            val source = mockk<CredentialSource>()
            every { source.fetch() } throws IOException("not up yet") andThen credentials("account-a")
            val store = AccountCredentialStore()
            val slept = mutableListOf<Long>()

            CredentialRefresher(source, store, mockk(relaxed = true), sleep = { slept += it }).loadAtStartup()

            store.accountIds() shouldBe setOf("account-a")
            slept.size shouldBe 1
            verify(exactly = 2) { source.fetch() }
        }

        test("startup backs off progressively between attempts") {
            val source = mockk<CredentialSource>()
            every { source.fetch() } throws IOException("down")
            val slept = mutableListOf<Long>()

            shouldThrow<IOException> {
                CredentialRefresher(
                    source,
                    AccountCredentialStore(),
                    mockk(relaxed = true),
                    bootAttempts = 3,
                    bootBackoffMillis = 100,
                    sleep = { slept += it },
                ).loadAtStartup()
            }

            slept shouldBe listOf(100L, 200L)
        }

        test("startup gives up once attempts are spent - serving an empty set would 404 every account") {
            val source = mockk<CredentialSource>()
            every { source.fetch() } throws IOException("still down")

            shouldThrow<IOException> {
                CredentialRefresher(
                    source,
                    AccountCredentialStore(),
                    mockk(relaxed = true),
                    bootAttempts = 2,
                    sleep = { },
                ).loadAtStartup()
            }

            verify(exactly = 2) { source.fetch() }
        }
    })
