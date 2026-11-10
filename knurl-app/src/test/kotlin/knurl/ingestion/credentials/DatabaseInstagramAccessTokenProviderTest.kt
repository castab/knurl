package knurl.ingestion.credentials

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import knurl.domain.models.InstagramCredential
import knurl.domain.repositories.AccountRepository
import knurl.ingestion.client.TokenRefreshResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DatabaseInstagramAccessTokenProviderTest :
    FunSpec({
        val now = Instant.parse("2026-11-09T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        test("each account resolves its own valid database token") {
            val accounts = mockk<AccountRepository>()
            every { accounts.credential("a") } returns InstagramCredential("account-a-token", now.plusSeconds(200000), 1)
            every { accounts.credential("b") } returns InstagramCredential("account-b-token", now.plusSeconds(200000), 1)
            val refresh = mockk<(String) -> TokenRefreshResult>()
            val provider = DatabaseInstagramAccessTokenProvider(accounts, refresh, clock)
            provider.getAccessToken("a") shouldBe "account-a-token"
            provider.getAccessToken("b") shouldBe "account-b-token"
            verify(exactly = 0) { refresh(any()) }
        }
        test("management rotation wins a concurrent refresh") {
            val accounts = mockk<AccountRepository>()
            every { accounts.credential("a") } returnsMany
                listOf(
                    InstagramCredential("old-token", now.plusSeconds(60), 4),
                    InstagramCredential("management-token", now.plusSeconds(200000), 5),
                )
            val refresh = mockk<(String) -> TokenRefreshResult>()
            every { refresh("old-token") } returns TokenRefreshResult("refreshed-token", now.plusSeconds(200000))
            every { accounts.refreshCredential("a", 4, "refreshed-token", now.plusSeconds(200000)) } returns false
            DatabaseInstagramAccessTokenProvider(accounts, refresh, clock).getAccessToken("a") shouldBe "management-token"
        }
    })
