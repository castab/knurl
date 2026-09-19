package knurl.ingestion.credentials

import knurl.domain.config.CredentialsMode
import knurl.domain.config.CredentialsSettings
import knurl.domain.http.CredentialHttpException
import knurl.domain.http.createBearerTokenSource
import knurl.domain.http.readBoundedBody
import knurl.domain.http.requireNonBlank
import knurl.domain.http.requireValidSecret
import knurl.domain.http.validCredentialValue
import knurl.domain.http.validateCredentialEndpoint
import knurl.domain.models.AuthConfig
import knurl.domain.repositories.AuthConfigStore
import knurl.ingestion.InstagramSettings
import knurl.ingestion.client.TokenRefreshResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val TOKEN_REFRESH_THRESHOLD: Duration = Duration.ofHours(24)

/**
 * Supplies the Instagram access token for a given account, shared across every account a
 * process's workers claim - not scoped to one account itself. Implementations own the token's
 * storage and refresh lifecycle; callers only consume the currently valid value for whichever
 * [accountId] they're currently working on.
 */
interface InstagramAccessTokenProvider {
    suspend fun getAccessToken(accountId: String): String
}

/**
 * Standalone Knurl's existing config-seeded, database-persisted token lifecycle, generalized to
 * serve any account rather than one fixed at construction.
 *
 * [seedAccessToken] only ever seeds [seedAccountId] - the one account this process's own
 * `INSTAGRAM_ACCESS_TOKEN`/`INSTAGRAM_BUSINESS_ACCOUNT_ID` env vars configure (see
 * [knurl.ingestion.IngestionConfig]; that pair deliberately stays single-account). Any other
 * account this provider is asked for must already have an `auth_config` row - provisioned
 * out-of-band, the same way its `instagram_accounts` row was - or [getAccessToken] fails loudly
 * rather than silently doing nothing, which the caller's per-item error handling then logs and
 * leaves for a later retry rather than crashing the whole process.
 */
class DatabaseInstagramAccessTokenProvider(
    private val authConfigStore: AuthConfigStore,
    private val seedAccountId: String?,
    private val seedAccessToken: String?,
    private val refreshToken: (String) -> TokenRefreshResult,
    private val clock: Clock = Clock.systemUTC(),
) : InstagramAccessTokenProvider {
    override suspend fun getAccessToken(accountId: String): String {
        val stored = authConfigStore.get(accountId)
        val now = Instant.now(clock)
        val nearExpiry = stored == null || now.plus(TOKEN_REFRESH_THRESHOLD).isAfter(stored.expiresAt)

        if (!nearExpiry) return stored.accessToken

        val seed =
            stored?.accessToken
                ?: seedAccessToken.takeIf { accountId == seedAccountId }
                ?: error(
                    "No auth_config row and no seed access token for account $accountId - accounts other " +
                        "than this process's configured INSTAGRAM_BUSINESS_ACCOUNT_ID must have their " +
                        "auth_config row provisioned out-of-band before ingestion can sync them",
                )
        val refreshed = refreshToken(seed)
        authConfigStore.upsert(
            AuthConfig(
                instagramAccountId = accountId,
                accessToken = refreshed.accessToken,
                expiresAt = refreshed.expiresAt,
                updatedAt = now,
            ),
        )
        return refreshed.accessToken
    }
}

/**
 * Retrieves a token whose refresh and persistence are owned by an external credential service, for
 * whichever account [getAccessToken] is asked about. Never reads or writes `auth_config`: in this
 * mode knurl holds no Instagram credential of its own and renews nothing.
 *
 * The request carries an explicit account id per call - a change from standalone Knurl's original
 * one-account-per-process shape, where the authenticated job identity alone was enough to imply the
 * account. A credential service serving an account-agnostic ingestion deployment must therefore
 * accept one bearer token authorizing lookups for multiple accounts and dispatch its response by
 * the requested account id.
 */
class HttpBrokerInstagramAccessTokenProvider(
    private val okHttpClient: OkHttpClient,
    endpointUrl: String,
    private val bearerTokenSource: () -> String,
    allowPlaintextHttp: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : InstagramAccessTokenProvider {
    private val endpoint = validateCredentialEndpoint(endpointUrl, allowPlaintextHttp, ENDPOINT_DESCRIPTION)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getAccessToken(accountId: String): String {
        val bearerToken = bearerTokenSource().requireValidSecret("bearer token")
        val request =
            Request
                .Builder()
                .url(endpoint.newBuilder().addQueryParameter("accountId", accountId).build())
                .header("Authorization", "Bearer $bearerToken")
                .get()
                .build()

        val responseBody =
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw CredentialHttpException("returned HTTP ${response.code}")
                    }
                    val body = response.body ?: throw CredentialHttpException("returned an empty response")
                    readBoundedBody(body)
                }
            } catch (exception: CredentialHttpException) {
                throw exception
            } catch (_: IOException) {
                throw CredentialHttpException("request failed")
            }

        val credential =
            try {
                json.decodeFromString<BrokerCredentialResponse>(responseBody)
            } catch (_: SerializationException) {
                throw CredentialHttpException("returned malformed JSON")
            }

        if (credential.instagramAccountId != accountId) {
            throw CredentialHttpException("returned credentials for an unexpected Instagram account")
        }
        val accessToken = credential.accessToken.validCredentialValue("Instagram access token")
        val expiresAt =
            try {
                Instant.parse(credential.expiresAt)
            } catch (_: RuntimeException) {
                throw CredentialHttpException("returned an invalid token expiry")
            }
        if (!expiresAt.isAfter(Instant.now(clock).plus(TOKEN_REFRESH_THRESHOLD))) {
            throw CredentialHttpException("returned a token expiring within 24 hours")
        }

        return accessToken
    }
}

private const val ENDPOINT_DESCRIPTION = "CREDENTIALS_HTTP_URL"

@Serializable
private class BrokerCredentialResponse(
    val instagramAccountId: String,
    val accessToken: String,
    val expiresAt: String,
)

fun createInstagramAccessTokenProvider(
    instagram: InstagramSettings,
    credentials: CredentialsSettings,
    /**
     * Nullable because `HTTP` mode has no store to give: no cipher, no encryption key, no
     * `auth_config` access at all (see [HttpBrokerInstagramAccessTokenProvider]). Kept as a parameter
     * rather than pushed into the `LOCAL` branch's own factory so a caller can still hand this
     * function a store in `HTTP` mode and assert it is never touched.
     */
    authConfigStore: AuthConfigStore?,
    refreshToken: (String) -> TokenRefreshResult,
    okHttpClient: OkHttpClient,
    clock: Clock = Clock.systemUTC(),
): InstagramAccessTokenProvider {
    validateCredentialsSettings(instagram, credentials)
    return when (credentials.mode) {
        CredentialsMode.LOCAL -> {
            DatabaseInstagramAccessTokenProvider(
                authConfigStore = checkNotNull(authConfigStore) { "CREDENTIALS_MODE=LOCAL requires an AuthConfigStore" },
                seedAccountId = instagram.businessAccountId,
                seedAccessToken = checkNotNull(instagram.accessToken).trim(),
                refreshToken = refreshToken,
                clock = clock,
            )
        }

        CredentialsMode.HTTP -> {
            HttpBrokerInstagramAccessTokenProvider(
                okHttpClient = okHttpClient,
                endpointUrl = credentials.http.url.requireNonBlank(ENDPOINT_DESCRIPTION),
                bearerTokenSource =
                    createBearerTokenSource(
                        credentials.http.authorizationToken,
                        credentials.http.authorizationTokenFile,
                    ),
                allowPlaintextHttp = credentials.http.allowPlaintext,
                clock = clock,
            )
        }
    }
}

/**
 * Fails a misconfigured deployment at startup, before a datasource is opened - a credential URL
 * typo should not first surface minutes into a sync cycle as a failed download.
 */
fun validateCredentialsSettings(
    instagram: InstagramSettings,
    credentials: CredentialsSettings,
) {
    when (credentials.mode) {
        CredentialsMode.LOCAL -> instagram.accessToken.requireValidSecret("INSTAGRAM_ACCESS_TOKEN")
        CredentialsMode.HTTP -> credentials.validateHttpSettings()
    }
}
