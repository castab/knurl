package knurl.presentation.routes

import knurl.presentation.credentials.CredentialRefresher
import org.http4k.contract.ContractRoute
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.autoBody
import org.slf4j.LoggerFactory

/**
 * The control plane's way of telling this service its credentials changed.
 *
 * Registered only in `CREDENTIALS_MODE=HTTP` - in `LOCAL` mode there is nothing to re-fetch and no
 * external party entitled to ask. The signal carries no body: this service pulls the authoritative
 * set itself rather than trusting what a caller pushes, and rebuilds the *whole* snapshot because a
 * per-account update could never express that an account was removed.
 */
class AdminAccountRoutes(
    private val refresher: CredentialRefresher,
    /**
     * Resolved per request rather than captured once, so a `CREDENTIALS_AUTHORIZATION_TOKEN_FILE`
     * deployment rotating its mounted secret is honored on the inbound signal as well as outbound.
     */
    private val credentialsSecret: () -> String,
) {
    private val log = LoggerFactory.getLogger(AdminAccountRoutes::class.java)
    private val errorResponseLens = autoBody<ErrorResponse>().toLens()

    private fun refreshCredentials(): ContractRoute =
        "/api/v1/admin/credentials/refresh" meta {
            summary = "Signal that account credentials changed, prompting a re-fetch"
            description =
                "Takes no body. This service re-fetches every account's tokens from the configured " +
                "credential endpoint and replaces its in-memory set with the result."
            security = credentialsBearerSecurity
        } bindContract Method.PUT to { request ->
            authorizeCredentialsSignal(request, credentialsSecret()) {
                val failure = runCatching { refresher.refresh() }.exceptionOrNull()
                if (failure == null) {
                    Response(Status.OK)
                } else {
                    // The previous credentials stay live, so this is a stale-data warning rather
                    // than an outage: every already-issued token keeps working until a fetch lands.
                    log.error("Credential re-fetch failed; serving the previous set", failure)
                    Response(Status.BAD_GATEWAY)
                        .with(errorResponseLens of ErrorResponse("credential endpoint unavailable"))
                }
            }
        }

    fun routes(): List<ContractRoute> = listOf(refreshCredentials())
}
