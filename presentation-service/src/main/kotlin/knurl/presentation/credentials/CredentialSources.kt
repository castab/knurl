package knurl.presentation.credentials

import knurl.domain.http.CredentialHttpException
import knurl.domain.http.readBoundedBody
import knurl.domain.http.requireValidSecret
import knurl.domain.http.validCredentialValue
import knurl.domain.http.validateCredentialEndpoint
import knurl.domain.security.TokenHasher
import knurl.presentation.LocalCredentials
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Resolves the full set of accounts this service serves and the token hashes it accepts for each.
 *
 * Deliberately returns *every* account rather than one: only a complete set can express that an
 * account was removed, which a per-account lookup cannot.
 */
fun interface CredentialSource {
    fun fetch(): Map<String, AccountCredentials>
}

/** `LOCAL` mode: the single configured account, hashed once. */
class LocalCredentialSource(
    private val local: LocalCredentials,
) : CredentialSource {
    override fun fetch(): Map<String, AccountCredentials> =
        mapOf(
            local.instagramBusinessAccountId to
                AccountCredentials(
                    adminTokenHash = TokenHasher.sha256(local.adminToken),
                    readTokenHash = TokenHasher.sha256(local.readToken),
                ),
        )
}

/**
 * `HTTP` mode: every account's token pair, from the control plane that owns them.
 *
 * Tokens arrive as plaintext - the control plane must be able to hand the same values to a browser
 * - and are hashed here on arrival, so a heap dump of this process yields digests rather than
 * working credentials, exactly as the database column they replaced did.
 */
class HttpCredentialSource(
    private val okHttpClient: OkHttpClient,
    endpointUrl: String,
    private val bearerTokenSource: () -> String,
    allowPlaintext: Boolean,
) : CredentialSource {
    private val endpoint = validateCredentialEndpoint(endpointUrl, allowPlaintext, "CREDENTIALS_HTTP_URL")
    private val json = Json { ignoreUnknownKeys = true }

    override fun fetch(): Map<String, AccountCredentials> {
        val bearerToken = bearerTokenSource().requireValidSecret("bearer token")
        val request =
            Request
                .Builder()
                .url(endpoint)
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

        val payload =
            try {
                json.decodeFromString<AccountCredentialsResponse>(responseBody)
            } catch (_: SerializationException) {
                throw CredentialHttpException("returned malformed JSON")
            }

        return payload.accounts.associate { account ->
            val accountId = account.instagramAccountId.validCredentialValue("account id")
            accountId to
                AccountCredentials(
                    adminTokenHash = TokenHasher.sha256(account.adminToken.validCredentialValue("admin token")),
                    readTokenHash = TokenHasher.sha256(account.readToken.validCredentialValue("read token")),
                )
        }
    }
}

@Serializable
private class AccountCredentialsResponse(
    val accounts: List<AccountCredentialEntry> = emptyList(),
)

@Serializable
private class AccountCredentialEntry(
    val instagramAccountId: String,
    val adminToken: String,
    val readToken: String,
)
