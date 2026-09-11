package knurl.ingestion.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant

class MetaGraphApiException(
    val statusCode: Int,
    body: String,
) : IOException("Graph API request failed ($statusCode): $body")

data class TokenRefreshResult(
    val accessToken: String,
    val expiresAt: Instant,
)

@Serializable
data class TokenRefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresInSeconds: Long,
)

@Serializable
data class MediaChild(
    val id: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("media_url") val mediaUrl: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
)

@Serializable
data class MediaChildren(
    val data: List<MediaChild> = emptyList(),
)

@Serializable
data class MediaItem(
    val id: String,
    @SerialName("media_type") val mediaType: String,
    val caption: String? = null,
    val permalink: String,
    val timestamp: String,
    @SerialName("media_url") val mediaUrl: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    /** e.g. `REELS` - used only to make the "media_url missing" log message more precise. */
    @SerialName("media_product_type") val mediaProductType: String? = null,
    /** Only populated for `CAROUSEL_ALBUM` items - the individual images/videos it contains. */
    val children: MediaChildren? = null,
)

@Serializable
data class MediaListPaging(
    val next: String? = null,
)

@Serializable
data class MediaListResponse(
    val data: List<MediaItem> = emptyList(),
    val paging: MediaListPaging? = null,
)

/**
 * Thin OkHttp wrapper around Meta's Graph API. Every response body is read via `.use { }` so the
 * underlying connection is always released, even on a non-2xx status.
 */
class MetaGraphClient(
    private val okHttpClient: OkHttpClient,
    private val apiVersion: String = "v21.0",
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun refreshLongLivedToken(currentAccessToken: String): TokenRefreshResult {
        val url =
            "https://graph.instagram.com/refresh_access_token"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("grant_type", "ig_refresh_token")
                .addQueryParameter("access_token", currentAccessToken)
                .build()

        val response = json.decodeFromString<TokenRefreshResponse>(execute(url))
        return TokenRefreshResult(
            accessToken = response.accessToken,
            expiresAt = Instant.now().plusSeconds(response.expiresInSeconds),
        )
    }

    fun fetchMediaList(
        userId: String,
        accessToken: String,
        after: String? = null,
    ): MediaListResponse {
        val urlBuilder =
            "https://graph.instagram.com/$apiVersion/$userId/media"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter(
                    "fields",
                    "id,media_type,caption,permalink,timestamp,media_url,thumbnail_url,media_product_type," +
                        "children{media_type,media_url,thumbnail_url}",
                ).addQueryParameter("access_token", accessToken)
        after?.let { urlBuilder.addQueryParameter("after", it) }

        return json.decodeFromString(execute(urlBuilder.build()))
    }

    private fun execute(url: okhttp3.HttpUrl): String {
        val request =
            Request
                .Builder()
                .url(url)
                .get()
                .build()
        okHttpClient.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw MetaGraphApiException(response.code, bodyString)
            return bodyString
        }
    }
}
