package knurl.app.storage

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import knurl.domain.config.S3Settings
import knurl.domain.models.AccountStorage
import knurl.domain.models.accountObjectPrefix
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.ObjectKeyRepository
import knurl.domain.repositories.SyncConfigurationRepository
import knurl.ingestion.pipeline.OrphanSweeper
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.uuid.Uuid

class AccountStorageIntegrationTest :
    FunSpec({
        if (System.getenv("S3_INTEGRATION_TEST") != "true") {
            xtest("account storage isolation (set S3_INTEGRATION_TEST=true)") {}
        } else {
            val endpoint = System.getenv("TEST_S3_ENDPOINT") ?: "http://localhost:3900"
            val access = System.getenv("TEST_S3_ACCESS_KEY") ?: "GK00000000000000000000000000000000"
            val secret = System.getenv("TEST_S3_SECRET_KEY") ?: "1111111111111111111111111111111111111111111111111111111111111111"
            val region = System.getenv("TEST_S3_REGION") ?: "garage"
            val bucket = "knurl-test-" + Uuid.random().toString().take(20)
            val otherBucket = "$bucket-b"
            val client =
                S3Client
                    .builder()
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .region(Region.of(region))
                    .endpointOverride(
                        URI(endpoint),
                    ).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .build()
            client.createBucket { it.bucket(bucket) }
            client.createBucket { it.bucket(otherBucket) }
            val accounts = mockk<AccountRepository>(relaxed = true)
            val known = mockk<ObjectKeyRepository>()
            val config = mockk<SyncConfigurationRepository>()
            every { accounts.storage("a") } returns AccountStorage("a", 1, S3Settings(bucket, region, endpoint, access, secret, true))
            every { accounts.storage("b") } returns
                AccountStorage("b", 1, S3Settings(otherBucket, region, endpoint, access, secret, true))
            every { accounts.claimSweep(any(), any()) } returns true
            every { config.getInt("orphan_sweep_interval_hours", any()) } returns 24
            every { config.getInt("orphan_grace_minutes", any()) } returns 0
            every { config.getInt("orphan_sweep_max_deletes", any()) } returns 1000
            every { config.getBoolean("orphan_sweep_dry_run", any()) } returns false
            val manager = AccountStorageManager(accounts, Duration.ofMinutes(5))

            fun put(
                target: String,
                key: String,
            ) {
                client.putObject({ it.bucket(target).key(key) }, RequestBody.fromBytes("test media".toByteArray()))
            }

            fun keys(target: String) =
                client
                    .listObjectsV2 { it.bucket(target) }
                    .contents()
                    .map { it.key() }
                    .toSet()
            afterSpec {
                manager.close()
                for (target in listOf(bucket, otherBucket)) {
                    keys(target).forEach { key -> client.deleteObject { it.bucket(target).key(key) } }
                    client.deleteBucket { it.bucket(target) }
                }
                client.close()
            }
            test("account reads use their own buckets and signed reads work while plain reads fail") {
                val keyA = accountObjectPrefix("a") + "posts/1/0/small.webp"
                val keyB = accountObjectPrefix("b") + "posts/1/0/small.webp"
                put(bucket, keyA)
                put(otherBucket, keyB)
                val http = HttpClient.newHttpClient()
                manager.acquire("a").use { a ->
                    manager.acquire("b").use { b ->
                        a.bucket shouldBe bucket
                        b.bucket shouldBe otherBucket
                        val signedA = a.presigner.presignGet(keyA).url
                        val signedB = b.presigner.presignGet(keyB).url
                        signedA.contains("/$bucket/") shouldBe true
                        signedB.contains("/$otherBucket/") shouldBe true
                        http.send(HttpRequest.newBuilder(URI(signedA)).GET().build(), HttpResponse.BodyHandlers.ofString()).body() shouldBe
                            "test media"
                        http
                            .send(
                                HttpRequest.newBuilder(URI(signedB)).GET().build(),
                                HttpResponse.BodyHandlers.discarding(),
                            ).statusCode() shouldBe
                            200
                        http
                            .send(
                                HttpRequest.newBuilder(URI("$endpoint/$bucket/$keyA")).GET().build(),
                                HttpResponse.BodyHandlers.discarding(),
                            ).statusCode() shouldBe
                            403
                    }
                }
            }
            test("known-length input streams upload successfully without optional checksum trailers") {
                val key = accountObjectPrefix("a") + "posts/streamed/0/original"
                manager.acquire("a").use { lease ->
                    val input = java.io.ByteArrayInputStream(ByteArray(32768) { (it % 127).toByte() })
                    input.use { lease.client.putObject({ it.bucket(lease.bucket).key(key) }, RequestBody.fromInputStream(it, 32768)) }
                    lease.client.headObject { it.bucket(lease.bucket).key(key) }.contentLength() shouldBe 32768L
                }
                client.deleteObject { it.bucket(bucket).key(key) }
            }
            test("a shared bucket sweep cannot delete sibling or unrelated objects") {
                val owned = accountObjectPrefix("a") + "catalog/known/thumbnail.webp"
                val orphan = accountObjectPrefix("a") + "posts/orphan/0/small.webp"
                val sibling = accountObjectPrefix("b") + "posts/unrecognized/0/small.webp"
                val unrelated = "family-photo.jpg"
                put(bucket, owned)
                put(bucket, orphan)
                put(bucket, sibling)
                put(bucket, unrelated)
                every { known.allKnownKeys("a") } returns setOf(owned, accountObjectPrefix("a") + "posts/1/0/small.webp")
                OrphanSweeper(known, config, client, bucket, "a", accounts).sweepIfDue()
                val remaining = keys(bucket)
                remaining.contains(orphan) shouldBe false
                remaining.contains(owned) shouldBe true
                remaining.contains(sibling) shouldBe true
                remaining.contains(unrelated) shouldBe true
            }
            test("empty known keys retain the reset-database safety guard") {
                val orphan = accountObjectPrefix("a") + "posts/guarded/0/small.webp"
                put(bucket, orphan)
                every { known.allKnownKeys("a") } returns emptySet()
                OrphanSweeper(known, config, client, bucket, "a", accounts).sweepIfDue()
                keys(bucket).contains(orphan) shouldBe true
                verify(exactly = 0) { accounts.markStorageEmpty("a") }
            }
            test("rotation uses a fresh signer while an existing lease can finish") {
                manager.acquire("a").use { old ->
                    every { accounts.storage("a") } returns
                        AccountStorage("a", 2, S3Settings(bucket, region, endpoint, "rotated-access", secret, true))
                    manager.acquire("a").use { fresh ->
                        val key = accountObjectPrefix("a") + "posts/1/0/small.webp"
                        old.presigner
                            .presignGet(key)
                            .url
                            .contains("X-Amz-Credential=$access%2F") shouldBe true
                        fresh.presigner
                            .presignGet(key)
                            .url
                            .contains("X-Amz-Credential=rotated-access%2F") shouldBe true
                    }
                }
            }
        }
    })
