package knurl.presentation.auth

import org.http4k.core.Filter
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.security.BearerAuthSecurity
import java.security.MessageDigest

/**
 * Shared bearer-token primitives. Extracted so both the single global [BearerAuth] filter (used
 * for POST /api/v1/accounts/{accountId}/gallery/track) and the per-account admin catalog routes - which must look up a
 * *different* expected token per request, not one fixed at filter-construction time - can share
 * the same constant-time comparison without duplicating it.
 */
object BearerToken {
    private const val PREFIX = "Bearer "

    fun extract(request: Request): String? =
        request
            .header("Authorization")
            ?.takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.trim()

    /** Constant-time comparison via [MessageDigest.isEqual], not plain string equality. */
    fun matches(
        candidate: String?,
        expected: String,
    ): Boolean = candidate != null && MessageDigest.isEqual(candidate.toByteArray(), expected.toByteArray())
}

/**
 * Guards state-modifying routes with a single, fixed expected token (currently just
 * POST /api/v1/accounts/{accountId}/gallery/track's global anti-abuse secret).
 */
class BearerAuth(
    private val expectedToken: String,
) {
    val filter =
        Filter { next ->
            { request ->
                if (BearerToken.matches(BearerToken.extract(request), expectedToken)) {
                    next(request)
                } else {
                    Response(Status.UNAUTHORIZED).body("""{"error":"unauthorized"}""")
                }
            }
        }

    val security = BearerAuthSecurity(filter, "galleryTrackingBearer")
}
