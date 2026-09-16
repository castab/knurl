package knurl.presentation.security

import org.http4k.core.Filter

/**
 * Baseline defensive HTTP response headers, applied globally by [Main][knurl.presentation.Main] -
 * none of these were set anywhere before this filter.
 *
 * The CSP is skipped for `/docs*` (the bundled Swagger UI): that page's own assets/inline
 * behaviour are supplied by http4k's `swaggerUiLite`, which this service doesn't control the
 * internals of, so a strict policy risks silently breaking it. Everywhere else - the bundled
 * gallery/admin UI and every JSON route - gets it: that UI has no inline scripts or styles (see
 * `index.html`/`app.js`), so `'self'` is sufficient for scripts/styles/fetches, while `img-src`/
 * `media-src` stay open (`*`) because gallery media is genuinely cross-origin (presigned URLs
 * against whatever S3-compatible bucket/endpoint this deployment uses, over http or https
 * depending on environment).
 */
object SecurityHeaders {
    private const val CONTENT_SECURITY_POLICY =
        "default-src 'self'; " +
            "img-src 'self' data: *; " +
            "media-src 'self' *; " +
            "script-src 'self'; " +
            "style-src 'self'; " +
            "connect-src 'self'; " +
            "frame-ancestors 'none'; " +
            "base-uri 'self'"

    val filter =
        Filter { next ->
            { request ->
                val response =
                    next(request)
                        .header("X-Content-Type-Options", "nosniff")
                        .header("X-Frame-Options", "DENY")
                        // Ignored by browsers on a plain-HTTP response (e.g. local dev), so this is
                        // always safe to set rather than conditioned on the deployment's scheme.
                        .header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")

                if (request.uri.path.startsWith("/docs")) response else response.header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
            }
        }
}
