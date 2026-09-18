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
 * The OpenAPI description of the single deployment-wide provisioning bearer scheme - the same
 * no-op-filter convention as [adminBearerSecurity], for a different reason: there is no per-account
 * identity yet at this point in the flow, so there is nothing this filter alone could scope the check
 * to. [authorizeProvisioning] does the real check inline.
 */
internal val provisioningBearerSecurity = BearerAuthSecurity(Filter { next -> next }, "provisioningBearer")

/**
 * Runs [onAuthorized] only if [request] carries [expectedSecret]. Unlike [authorizeAccount], this has
 * no per-account identity to resolve - this route's whole purpose is to create that identity - so
 * there is no unknown-account case to distinguish and no 404 response.
 */
internal fun authorizeProvisioning(
    request: Request,
    expectedSecret: String,
    onAuthorized: () -> Response,
): Response =
    if (BearerToken.matches(BearerToken.extract(request), expectedSecret)) {
        onAuthorized()
    } else {
        Response(Status.UNAUTHORIZED).with(errorResponseLens of ErrorResponse("unauthorized"))
    }
