package knurl.domain.config

/** Account-owned S3 configuration. Secret material must never be logged. */
class S3Settings(
    val bucketName: String,
    val region: String,
    val endpoint: String? = null,
    val accessKeyId: String,
    val secretAccessKey: String,
    val pathStyleAccess: Boolean = false,
)
