package knurl.ingestion.credentials

import knurl.domain.repositories.AccountRepository
import knurl.ingestion.client.TokenRefreshResult
import java.time.Clock
import java.time.Duration

interface InstagramAccessTokenProvider {
    suspend fun getAccessToken(accountId: String): String
}

/** Compare-and-set ensures an in-flight refresh cannot overwrite management rotation. */
class DatabaseInstagramAccessTokenProvider(
    private val accounts: AccountRepository,
    private val refreshToken: (String) -> TokenRefreshResult,
    private val clock: Clock = Clock.systemUTC(),
) : InstagramAccessTokenProvider {
    override suspend fun getAccessToken(accountId: String): String {
        val stored = accounts.credential(accountId)
        if (clock.instant().plus(Duration.ofHours(24)).isBefore(stored.expiresAt)) return stored.accessToken
        val fresh = refreshToken(stored.accessToken)
        require(fresh.expiresAt.isAfter(clock.instant())) { "invalid refreshed credential expiry" }
        return if (accounts.refreshCredential(
                accountId,
                stored.version,
                fresh.accessToken,
                fresh.expiresAt,
            )
        ) {
            fresh.accessToken
        } else {
            accounts.credential(accountId).accessToken
        }
    }
}
