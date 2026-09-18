package knurl.presentation.routes

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
 * The OpenAPI description of the single deployment-wide credentials bearer scheme - the same
 * no-op-filter convention as [adminBearerSecurity], for a different reason: this route carries no
 * per-account identity to scope a check to. [authorizeCredentialsSignal] does the real check inline.
 */
internal val credentialsBearerSecurity = BearerAuthSecurity(Filter { next -> next }, "credentialsBearer")

/**
 * Runs [onAuthorized] only if [request] carries [expectedSecret] - the same
 * `CREDENTIALS_AUTHORIZATION_TOKEN` this service presents when fetching credentials, since both
 * directions authenticate the one trust relationship with the control plane.
 *
 * Unlike [authorizeAccount] there is no per-account identity here and so no unknown-account case to
 * distinguish, hence no 404.
 */
internal fun authorizeCredentialsSignal(
    request: Request,
    expectedSecret: String,
    onAuthorized: () -> Response,
): Response =
    if (BearerToken.matches(BearerToken.extract(request), expectedSecret)) {
        onAuthorized()
    } else {
        Response(Status.UNAUTHORIZED).with(errorResponseLens of ErrorResponse("unauthorized"))
    }
