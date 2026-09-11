package knurl.presentation.s3

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import knurl.domain.config.S3Settings
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Demonstrates the storage-visibility requirement end-to-end against a real S3-compatible
 * bucket: an unsigned request to the plain object URL is denied, a presigned GET succeeds. Needs
 * live infrastructure, so it's gated behind an env var per AGENTS.md's testing convention rather
 * than running by default.
 *
 * To run: `docker-compose up -d minio minio-init`, then
 * `S3_INTEGRATION_TEST=true ./gradlew :presentation-service:test` (matches the local dev
 * credentials in application-local.conf / docker-compose.yml).
 */
class PresignerMinioIntegrationTest :
    FunSpec({
        if (System.getenv("S3_INTEGRATION_TEST") != "true") {
            xtest(
                "plain GET is denied, presigned GET succeeds " +
                    "(set S3_INTEGRATION_TEST=true against a running docker-compose MinIO to run)",
            ) {}
        } else {
            val settings =
                S3Settings(
                    bucketName = "knurl-media",
                    region = "us-east-1",
                    endpoint = "http://localhost:9000",
                    accessKeyId = "knurl",
                    secretAccessKey = "knurl-dev-secret",
                    pathStyleAccess = true,
                )
            val key = "presigner-integration-test/${System.currentTimeMillis()}.txt"
            val body = "hello from PresignerMinioIntegrationTest".toByteArray()

            val s3Client =
                S3Client
                    .builder()
                    .region(Region.of(settings.region))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(settings.accessKeyId, settings.secretAccessKey),
                        ),
                    ).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .endpointOverride(URI.create(requireNotNull(settings.endpoint)))
                    .build()

            s3Client.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(settings.bucketName)
                    .key(key)
                    .build(),
                RequestBody.fromBytes(body),
            )

            val httpClient = HttpClient.newHttpClient()

            test("plain unsigned GET to the object is denied") {
                val plainUrl = "${settings.endpoint}/${settings.bucketName}/$key"
                val response =
                    httpClient.send(
                        HttpRequest.newBuilder(URI.create(plainUrl)).GET().build(),
                        HttpResponse.BodyHandlers.discarding(),
                    )

                (response.statusCode() == 403 || response.statusCode() == 401) shouldBe true
            }

            test("presigned GET succeeds and returns the uploaded bytes") {
                val presigner = Presigner.create(settings, Duration.ofMinutes(5))
                val presigned = presigner.presignGet(key)

                val response =
                    httpClient.send(
                        HttpRequest.newBuilder(URI.create(presigned.url)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray(),
                    )

                response.statusCode() shouldBe 200
                response.body() shouldBe body
            }

            afterSpec { s3Client.close() }
        }
    })
