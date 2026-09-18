package knurl.domain.config

import knurl.domain.http.createBearerTokenSource
import knurl.domain.http.requireNonBlank
import knurl.domain.http.validateCredentialEndpoint

/**
 * Where a service gets the credentials it uses or accepts. One flag, one meaning, both services.
 *
 * Knurl wrapped by a control plane must not mint, rotate, or decide auth for itself; [HTTP] is how
 * that authority is handed over. [LOCAL] keeps a standalone deployment self-sufficient with no
 * external dependency, and is the default precisely so the simple deployment needs no extra config.
 */
enum class CredentialsMode {
    /** Credentials come from this service's own environment and database. No external dependency. */
    LOCAL,

    /** Credentials are fetched from an external credential service over HTTP, which owns them. */
    HTTP,
}

/**
 * The endpoint this service calls in [CredentialsMode.HTTP], and the pre-shared secret authorizing
 * the call. Each service points [url] at the endpoint serving *its* credentials, so the same
 * variable names carry different values in each process.
 */
data class CredentialsHttpSettings(
    val url: String? = null,
    val authorizationToken: String? = null,
    val authorizationTokenFile: String? = null,
    val allowPlaintext: Boolean = false,
)

data class CredentialsSettings(
    val mode: CredentialsMode = CredentialsMode.LOCAL,
    val http: CredentialsHttpSettings = CredentialsHttpSettings(),
) {
    /**
     * Validates the [CredentialsMode.HTTP] wiring without opening a connection, so a service can
     * reject a misconfigured endpoint at startup before touching a database or binding a port
     * rather than at the first credential fetch, which for ingestion is minutes into a sync cycle.
     */
    fun validateHttpSettings() {
        if (mode != CredentialsMode.HTTP) return
        validateCredentialEndpoint(
            http.url.requireNonBlank("CREDENTIALS_HTTP_URL"),
            http.allowPlaintext,
            "CREDENTIALS_HTTP_URL",
        )
        createBearerTokenSource(http.authorizationToken, http.authorizationTokenFile)
    }
}
