package knurl.presentation.routes

import knurl.domain.repositories.AccountTokenVerification
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
 * Resolves whether [candidateToken] is the token belonging to the account identified by
 * `accountId`, e.g. `AccountRepository::verifyAdminToken` or `AccountRepository::verifyReadToken`.
 * A route class depends on this function type rather than the concrete `AccountRepository` it's
 * normally backed by, so a test can substitute an in-memory fake with no database at all - see
 * `GalleryRoutePathBindingTest` for why that distinction matters here specifically.
 */
typealias VerifyAccountToken = (accountId: String, candidateToken: String?) -> AccountTokenVerification

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
 * The OpenAPI description of the per-account gallery-read bearer scheme - the same no-op-filter
 * pattern as [adminBearerSecurity], for the same reason: the token this documents is resolved
 * per-account, not enforced by a single fixed-token filter.
 */
internal val readBearerSecurity = BearerAuthSecurity(Filter { next -> next }, "accountReadBearer")

/**
 * Runs [onAuthorized] only if [request] carries the token belonging to [accountId], as resolved
 * by [verify] - [AccountRepository.verifyAdminToken][knurl.domain.repositories.AccountRepository.verifyAdminToken]
 * for admin routes, [AccountRepository.verifyReadToken][knurl.domain.repositories.AccountRepository.verifyReadToken]
 * for public gallery routes. Both share this one function because both need the identical
 * `404`-vs-`401` distinction and the identical per-account isolation guarantee - only which
 * column gets checked differs, and [verify] is where that's decided by the caller.
 *
 * `404` for an unknown account is safe to reveal - account ids are public, non-secret Graph API
 * business account ids - and distinguishes "no such account" from "wrong token for a real account"
 * (`401`), which is the difference an operator debugging a deployment actually needs.
 *
 * Shared by every admin route and every public gallery route: a single deployment serves many
 * accounts, so one account's token - of either kind - must never be usable against another's content.
 */
internal fun authorizeAccount(
    accountId: String,
    request: Request,
    verify: VerifyAccountToken,
    onAuthorized: () -> Response,
): Response =
    when (verify(accountId, BearerToken.extract(request))) {
        AccountTokenVerification.UnknownAccount -> {
            Response(Status.NOT_FOUND).with(errorResponseLens of ErrorResponse("unknown account"))
        }

        AccountTokenVerification.Match -> {
            onAuthorized()
        }

        AccountTokenVerification.Mismatch -> {
            Response(Status.UNAUTHORIZED).with(errorResponseLens of ErrorResponse("unauthorized"))
        }
    }
