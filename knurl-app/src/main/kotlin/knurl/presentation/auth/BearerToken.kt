package knurl.presentation.auth

import org.http4k.core.Request
import java.security.MessageDigest

/**
 * Shared bearer-token primitives, used by every route that checks a bearer token against a
 * per-account expected value - admin routes via `AccountRepository.verifyAdminToken`, public
 * gallery routes via `AccountRepository.verifyReadToken` - so both share the same
 * header-extraction and constant-time comparison without duplicating either.
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
