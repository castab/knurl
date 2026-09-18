package knurl.domain.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

// Shared hardening for every outbound credential fetch, in `shared-domain` because both services now
// make one and neither may depend on the other: `ingestion-service` fetches its Instagram access
// token, `presentation-service` fetches the account tokens it should accept.
//
// The rules enforced here - a bounded response, a transport that can't be passively read, and an
// exception that never carries what the response said - are the reason this is one implementation
// rather than two.

/** Caps both a response body and any single secret read from one. */
const val MAX_CREDENTIAL_BYTES: Int = 16 * 1024

/**
 * Every failure of a credential fetch, carrying a fixed reason and *never* the response body, the
 * requested URL's credentials, or the token itself. A credential endpoint's error responses tend to
 * echo back what was sent; letting one reach a log line or a crash report is how a secret escapes.
 */
class CredentialHttpException(
    reason: String,
) : IOException("Credential endpoint $reason")

/**
 * Rejects a credential URL that could leak the secret it carries: plaintext on an untrusted
 * network, embedded userinfo, or a query string this code would later append to.
 *
 * Loopback is always allowed so local development needs no opt-in; anything else non-HTTPS demands
 * [allowPlaintextHttp], which exists for a trusted private service network and nothing else.
 */
fun validateCredentialEndpoint(
    configuredUrl: String,
    allowPlaintextHttp: Boolean,
    description: String = "Credential endpoint URL",
): HttpUrl {
    val endpoint = configuredUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid $description")
    require(endpoint.username.isEmpty() && endpoint.password.isEmpty()) {
        "$description must not contain user information"
    }
    require(endpoint.query == null && endpoint.fragment == null) {
        "$description must not contain a query string or fragment"
    }

    val isLoopback =
        endpoint.host == "localhost" ||
            endpoint.host.endsWith(".localhost") ||
            endpoint.host.startsWith("127.") ||
            endpoint.host == "::1" ||
            endpoint.host == "0:0:0:0:0:0:0:1"
    require(endpoint.isHttps || (endpoint.scheme == "http" && (isLoopback || allowPlaintextHttp))) {
        "$description must use HTTPS; set CREDENTIALS_HTTP_ALLOW_PLAINTEXT=true only on a trusted " +
            "private network"
    }
    return endpoint
}

/**
 * Resolves the pre-shared bearer token from exactly one of an inline value or a file.
 *
 * The file variant is re-read on every call rather than captured once, so a rotating mounted
 * workload credential takes effect without restarting the process.
 */
fun createBearerTokenSource(
    inlineToken: String?,
    tokenFile: String?,
): () -> String {
    val inline = inlineToken?.takeIf { it.isNotBlank() }
    val file = tokenFile?.takeIf { it.isNotBlank() }
    require((inline == null) != (file == null)) {
        "HTTP credentials mode requires exactly one of CREDENTIALS_AUTHORIZATION_TOKEN or " +
            "CREDENTIALS_AUTHORIZATION_TOKEN_FILE"
    }

    return if (inline != null) {
        val validated = inline.requireValidSecret("bearer token")
        val source = { validated }
        source
    } else {
        val path = Path.of(checkNotNull(file))
        val source = { readBoundedTokenFile(path) }
        source
    }
}

/**
 * Reads at most [MAX_CREDENTIAL_BYTES], failing rather than buffering an unbounded response from an
 * endpoint that is, by definition, trusted with secrets and therefore worth distrusting with memory.
 */
fun readBoundedBody(body: ResponseBody): String {
    if (body.contentLength() > MAX_CREDENTIAL_BYTES) {
        throw CredentialHttpException("response exceeded 16 KiB")
    }
    val source = body.source()
    if (source.request(MAX_CREDENTIAL_BYTES.toLong() + 1)) {
        throw CredentialHttpException("response exceeded 16 KiB")
    }
    return source.readString(StandardCharsets.UTF_8)
}

private fun readBoundedTokenFile(path: Path): String {
    val bytes =
        try {
            Files.newInputStream(path).use { input -> input.readNBytes(MAX_CREDENTIAL_BYTES + 1) }
        } catch (_: IOException) {
            throw CredentialHttpException("could not read its bearer-token file")
        }
    if (bytes.size > MAX_CREDENTIAL_BYTES) {
        throw CredentialHttpException("bearer-token file exceeded 16 KiB")
    }
    val token = bytes.toString(StandardCharsets.UTF_8).trim()
    if (token.isEmpty()) {
        throw CredentialHttpException("bearer-token file was blank")
    }
    return token
}

/** Trims, then rejects an empty or oversized secret. */
fun String?.requireValidSecret(description: String): String {
    val value = this?.trim().orEmpty()
    require(value.isNotEmpty()) { "$description must not be blank" }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_CREDENTIAL_BYTES) {
        "$description must not exceed 16 KiB"
    }
    return value
}

/** Trims, then rejects a blank value. For non-secret configuration such as a URL. */
fun String?.requireNonBlank(description: String): String {
    val value = this?.trim().orEmpty()
    require(value.isNotEmpty()) { "$description must not be blank" }
    return value
}

/** The same bounds as [requireValidSecret], as a [CredentialHttpException] for response payloads. */
fun String.validCredentialValue(description: String): String {
    val value = trim()
    if (value.isEmpty()) {
        throw CredentialHttpException("returned a blank $description")
    }
    if (value.toByteArray(StandardCharsets.UTF_8).size > MAX_CREDENTIAL_BYTES) {
        throw CredentialHttpException("returned an oversized $description")
    }
    return value
}
