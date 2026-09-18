package knurl.presentation.routes

import knurl.domain.repositories.AccountRepository
import kotlinx.serialization.Serializable
import org.http4k.contract.ContractRoute
import org.http4k.contract.bindContract
import org.http4k.contract.div
import org.http4k.contract.meta
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.autoBody
import org.http4k.lens.Path

@Serializable
data class AccountTokensRequest(
    val adminToken: String,
    val readToken: String,
)

/**
 * Provisioning surface for control-plane (or any other trusted caller holding the deployment-wide
 * provisioning secret) to create-or-replace an account's admin/read token pair.
 *
 * This is a pure upsert - there is no prior per-account identity to check, since this route is what
 * *creates* the identity that every other admin/gallery route's [authorizeAccount] later checks. Auth
 * is therefore [authorizeProvisioning], not [authorizeAccount]: a single shared secret, not a
 * per-account one, and no unknown-account case (there's nothing to hide - an unknown accountId here
 * just means a new row gets created).
 */
class AdminAccountRoutes(
    private val accountRepository: AccountRepository,
    private val provisioningSecret: String,
) {
    private val accountIdPath = Path.of("accountId")
    private val requestLens = autoBody<AccountTokensRequest>().toLens()
    private val errorResponseLens = autoBody<ErrorResponse>().toLens()

    private fun badRequest(message: String): Response = Response(Status.BAD_REQUEST).with(errorResponseLens of ErrorResponse(message))

    private fun putTokens(): ContractRoute =
        "/api/v1/admin/accounts" / accountIdPath / "tokens" meta {
            summary = "Create or replace an account's admin/read tokens"
            security = provisioningBearerSecurity
            receiving(requestLens to AccountTokensRequest(adminToken = "admin-secret", readToken = "read-secret"))
        } bindContract Method.PUT to { accountId, _ ->
            { request ->
                authorizeProvisioning(request, provisioningSecret) {
                    // A malformed body (unparseable JSON, missing/wrong-typed fields) never reaches
                    // here - http4k's contract route validates it against the `receiving` lens above
                    // and returns its own 400 first.
                    val body = requestLens(request)
                    if (body.adminToken.isBlank() || body.readToken.isBlank()) {
                        badRequest("adminToken and readToken must both be non-blank")
                    } else {
                        accountRepository.register(accountId, body.adminToken, body.readToken)
                        Response(Status.OK)
                    }
                }
            }
        }

    fun routes(): List<ContractRoute> = listOf(putTokens())
}
