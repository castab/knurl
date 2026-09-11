package knurl.domain.config

/**
 * Decoded from each service's `application.conf` via Hoplite. Shared by both services since
 * ingestion (writes/deletes objects) and presentation (presigns GET URLs) target the same
 * bucket. `endpoint`/`pathStyleAccess` are only needed for S3-compatible providers (e.g. Railway
 * Buckets) that require an endpoint override and/or path-style addressing.
 */
data class S3Settings(
    val bucketName: String,
    val region: String,
    val endpoint: String? = null,
    val accessKeyId: String,
    val secretAccessKey: String,
    val pathStyleAccess: Boolean = false,
)
