package knurl.presentation.routes

import knurl.domain.repositories.AccountRepository
import knurl.presentation.auth.BearerToken
import org.http4k.core.Filter
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.autoBody
import org.http4k.security.BearerAuthSecurity

private val errorResponseLens = autoBody<ErrorResponse>().toLens()

/**
 * The OpenAPI description of the per-account admin bearer scheme.
 *
 * The filter is deliberately a no-op: http4k's `ContractRouteMatcher` would happily enforce a single
 * shared token, but admin auth here is per-account and can only be resolved once the `{accountId}`
 * path segment is known. So the scheme exists to document the requirement and [authorizeAccount]
 * does the real check inline.
 */
internal val adminBearerSecurity = BearerAuthSecurity(Filter { next -> next }, "accountAdminBearer")

/**
 * Runs [onAuthorized] only if [request] carries the admin token belonging to [accountId].
 *
 * `404` for an unknown account is safe to reveal - account ids are public, non-secret Graph API
 * business account ids - and distinguishes "no such account" from "wrong token for a real account"
 * (`401`), which is the difference an operator debugging a deployment actually needs.
 *
 * Shared by every admin route across both admin route classes: a single deployment serves many
 * accounts, so one account's token must never be usable against another's content.
 */
internal fun authorizeAccount(
    accountRepository: AccountRepository,
    accountId: String,
    request: Request,
    onAuthorized: () -> Response,
): Response {
    val expectedToken =
        accountRepository.findAdminToken(accountId)
            ?: return Response(Status.NOT_FOUND).with(errorResponseLens of ErrorResponse("unknown account"))

    return if (BearerToken.matches(BearerToken.extract(request), expectedToken)) {
        onAuthorized()
    } else {
        Response(Status.UNAUTHORIZED).with(errorResponseLens of ErrorResponse("unauthorized"))
    }
}
