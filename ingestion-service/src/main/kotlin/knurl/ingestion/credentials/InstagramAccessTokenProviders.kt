package knurl.ingestion.credentials

import knurl.domain.models.AuthConfig
import knurl.domain.repositories.AuthConfigStore
import knurl.ingestion.CredentialBrokerSettings
import knurl.ingestion.InstagramCredentialProviderType
import knurl.ingestion.InstagramSettings
import knurl.ingestion.client.TokenRefreshResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val TOKEN_REFRESH_THRESHOLD: Duration = Duration.ofHours(24)
private const val MAX_CREDENTIAL_BYTES = 16 * 1024

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
 * Retrieves a token whose refresh and persistence are owned by an external credential broker,
 * for whichever account [getAccessToken] is asked about.
 *
 * The broker request now carries an explicit account id per call - a change from standalone
 * Knurl's original one-account-per-process shape, where the authenticated job identity alone was
 * enough to imply the account. A broker serving an account-agnostic ingestion deployment must
 * therefore accept one bearer token authorizing lookups for multiple accounts and dispatch its
 * response by the requested account id; this is a breaking change to any existing broker
 * implementation built against the single-account contract.
 */
class HttpBrokerInstagramAccessTokenProvider(
    private val okHttpClient: OkHttpClient,
    endpointUrl: String,
    private val bearerTokenSource: () -> String,
    allowPlaintextHttp: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) : InstagramAccessTokenProvider {
    private val endpoint = validateBrokerEndpoint(endpointUrl, allowPlaintextHttp)
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
                        throw CredentialBrokerException("returned HTTP ${response.code}")
                    }
                    val body = response.body ?: throw CredentialBrokerException("returned an empty response")
                    readBoundedBody(body)
                }
            } catch (exception: CredentialBrokerException) {
                throw exception
            } catch (_: IOException) {
                throw CredentialBrokerException("request failed")
            }

        val credential =
            try {
                json.decodeFromString<BrokerCredentialResponse>(responseBody)
            } catch (_: SerializationException) {
                throw CredentialBrokerException("returned malformed JSON")
            }

        if (credential.instagramAccountId != accountId) {
            throw CredentialBrokerException("returned credentials for an unexpected Instagram account")
        }
        val accessToken = credential.accessToken.validBrokerAccessToken()
        val expiresAt =
            try {
                Instant.parse(credential.expiresAt)
            } catch (_: RuntimeException) {
                throw CredentialBrokerException("returned an invalid token expiry")
            }
        if (!expiresAt.isAfter(Instant.now(clock).plus(TOKEN_REFRESH_THRESHOLD))) {
            throw CredentialBrokerException("returned a token expiring within 24 hours")
        }

        return accessToken
    }
}

class CredentialBrokerException internal constructor(
    reason: String,
) : IOException("Credential broker $reason")

@Serializable
private class BrokerCredentialResponse(
    val instagramAccountId: String,
    val accessToken: String,
    val expiresAt: String,
)

fun createInstagramAccessTokenProvider(
    settings: InstagramSettings,
    authConfigStore: AuthConfigStore,
    refreshToken: (String) -> TokenRefreshResult,
    okHttpClient: OkHttpClient,
    clock: Clock = Clock.systemUTC(),
): InstagramAccessTokenProvider {
    validateInstagramCredentialSettings(settings)
    return when (settings.credentialProvider) {
        InstagramCredentialProviderType.DATABASE -> {
            DatabaseInstagramAccessTokenProvider(
                authConfigStore = authConfigStore,
                seedAccountId = settings.businessAccountId,
                seedAccessToken = checkNotNull(settings.accessToken).trim(),
                refreshToken = refreshToken,
                clock = clock,
            )
        }

        InstagramCredentialProviderType.HTTP_BROKER -> {
            val broker = settings.credentialBroker
            HttpBrokerInstagramAccessTokenProvider(
                okHttpClient = okHttpClient,
                endpointUrl = broker.url.requireNonBlank("Instagram credential broker URL"),
                bearerTokenSource = createBrokerBearerTokenSource(broker),
                allowPlaintextHttp = broker.allowPlaintextHttp,
                clock = clock,
            )
        }
    }
}

fun validateInstagramCredentialSettings(settings: InstagramSettings) {
    when (settings.credentialProvider) {
        InstagramCredentialProviderType.DATABASE -> {
            settings.accessToken.requireValidSecret("Instagram access token")
        }

        InstagramCredentialProviderType.HTTP_BROKER -> {
            val broker = settings.credentialBroker
            validateBrokerEndpoint(
                broker.url.requireNonBlank("Instagram credential broker URL"),
                broker.allowPlaintextHttp,
            )
            createBrokerBearerTokenSource(broker)
        }
    }
}

internal fun createBrokerBearerTokenSource(settings: CredentialBrokerSettings): () -> String {
    val inlineToken = settings.bearerToken?.takeIf { it.isNotBlank() }
    val tokenFile = settings.bearerTokenFile?.takeIf { it.isNotBlank() }
    require((inlineToken == null) != (tokenFile == null)) {
        "HTTP_BROKER requires exactly one of INSTAGRAM_CREDENTIAL_BROKER_TOKEN or " +
            "INSTAGRAM_CREDENTIAL_BROKER_TOKEN_FILE"
    }

    return if (inlineToken != null) {
        val validatedToken = inlineToken.requireValidSecret("bearer token")
        val source = { validatedToken }
        source
    } else {
        val path = Path.of(checkNotNull(tokenFile))
        val source = { readBoundedTokenFile(path) }
        source
    }
}

private fun validateBrokerEndpoint(
    configuredUrl: String,
    allowPlaintextHttp: Boolean,
): HttpUrl {
    val endpoint = configuredUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid Instagram credential broker URL")
    require(endpoint.username.isEmpty() && endpoint.password.isEmpty()) {
        "Instagram credential broker URL must not contain user information"
    }
    require(endpoint.query == null && endpoint.fragment == null) {
        "Instagram credential broker URL must not contain a query string or fragment"
    }

    val isLoopback =
        endpoint.host == "localhost" ||
            endpoint.host.endsWith(".localhost") ||
            endpoint.host.startsWith("127.") ||
            endpoint.host == "::1" ||
            endpoint.host == "0:0:0:0:0:0:0:1"
    require(endpoint.isHttps || (endpoint.scheme == "http" && (isLoopback || allowPlaintextHttp))) {
        "Instagram credential broker URL must use HTTPS; set " +
            "INSTAGRAM_CREDENTIAL_BROKER_ALLOW_PLAINTEXT_HTTP=true only on a trusted private network"
    }
    return endpoint
}

private fun readBoundedBody(body: okhttp3.ResponseBody): String {
    if (body.contentLength() > MAX_CREDENTIAL_BYTES) {
        throw CredentialBrokerException("response exceeded 16 KiB")
    }
    val source = body.source()
    if (source.request(MAX_CREDENTIAL_BYTES.toLong() + 1)) {
        throw CredentialBrokerException("response exceeded 16 KiB")
    }
    return source.readString(StandardCharsets.UTF_8)
}

private fun readBoundedTokenFile(path: Path): String {
    val bytes =
        try {
            Files.newInputStream(path).use { input -> input.readNBytes(MAX_CREDENTIAL_BYTES + 1) }
        } catch (_: IOException) {
            throw CredentialBrokerException("could not read its bearer-token file")
        }
    if (bytes.size > MAX_CREDENTIAL_BYTES) {
        throw CredentialBrokerException("bearer-token file exceeded 16 KiB")
    }
    val token = bytes.toString(StandardCharsets.UTF_8).trim()
    if (token.isEmpty()) {
        throw CredentialBrokerException("bearer-token file was blank")
    }
    return token
}

private fun String.validBrokerAccessToken(): String {
    val value = trim()
    if (value.isEmpty()) {
        throw CredentialBrokerException("returned a blank Instagram access token")
    }
    if (value.toByteArray(StandardCharsets.UTF_8).size > MAX_CREDENTIAL_BYTES) {
        throw CredentialBrokerException("returned an Instagram access token exceeding 16 KiB")
    }
    return value
}

private fun String?.requireValidSecret(description: String): String {
    val value = this?.trim().orEmpty()
    require(value.isNotEmpty()) { "$description must not be blank" }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_CREDENTIAL_BYTES) {
        "$description must not exceed 16 KiB"
    }
    return value
}

private fun String?.requireNonBlank(description: String): String {
    val value = this?.trim().orEmpty()
    require(value.isNotEmpty()) { "$description must not be blank" }
    return value
}
