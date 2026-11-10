package knurl.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import knurl.app.ingestion.IngestionCoordinator
import knurl.domain.config.DatabaseConfig
import knurl.domain.config.DatabaseSettings
import knurl.domain.config.S3Settings
import knurl.domain.models.AccountChange
import knurl.domain.models.AccountRegistration
import knurl.domain.models.CatalogUpsert
import knurl.domain.repositories.AccountConflict
import knurl.domain.repositories.AccountRepository
import knurl.domain.repositories.AccountTokenVerification
import knurl.domain.repositories.CatalogRepository
import knurl.domain.repositories.IngestionJobRepository
import knurl.domain.security.CredentialCipher
import knurl.ingestion.client.MediaItem
import knurl.ingestion.client.MediaListPaging
import knurl.ingestion.client.MediaListResponse
import knurl.ingestion.client.MetaGraphApiException
import knurl.ingestion.client.MetaGraphClient
import knurl.ingestion.credentials.InstagramAccessTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Status
import org.http4k.core.then
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import kotlin.uuid.Uuid

/** Creates and drops only its own isolated schema; never resets the operator's database. */
class DatabaseIntegrationTest :
    FunSpec({
        if (System.getenv("DATABASE_INTEGRATION_TEST") != "true") {
            xtest("PostgreSQL 18 integration (set DATABASE_INTEGRATION_TEST=true)") {}
        } else {
            val base = System.getenv("TEST_DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/knurl?user=knurl&password=knurl"
            val schema = "knurl_test_" + Uuid.random().toString().replace("-", "")
            DriverManager.getConnection(base).use { it.createStatement().use { s -> s.execute("CREATE SCHEMA $schema") } }
            val url = base + (if ('?' in base) "&" else "?") + "currentSchema=$schema"
            val ds = DatabaseConfig.createDataSource(DatabaseSettings(url))
            DatabaseConfig.runMigrations(ds)
            val jdbi = DatabaseConfig.createJdbi(ds)
            val cipher = CredentialCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            val accounts = AccountRepository(jdbi, cipher)
            val jobs = IngestionJobRepository(jdbi)
            val storage = knurl.app.storage.AccountStorageManager(accounts, Duration.ofMinutes(5))
            val app = applicationErrors.then(applicationContract(jdbi, accounts, jobs, storage) { "test-management-secret" })

            fun register(id: String = "a") =
                accounts.register(
                    AccountRegistration(
                        id,
                        "read-token-for-$id",
                        "admin-token-for-$id",
                        "instagram-$id",
                        Instant.now().plusSeconds(200000),
                        S3Settings("knurl-media", "us-east-1", "http://localhost:9000", "access-$id", "secret-$id", true),
                    ),
                )
            beforeEach { jdbi.useHandle<Exception> { it.execute("TRUNCATE instagram_accounts CASCADE") } }
            afterSpec {
                storage.close()
                ds.close()
                DriverManager.getConnection(base).use { it.createStatement().use { s -> s.execute("DROP SCHEMA $schema CASCADE") } }
            }
            test("all recoverable secrets are encrypted, read tokens remain plaintext, and tenant auth is isolated") {
                register("a")
                register("b")
                jdbi.useHandle<Exception> { h ->
                    val row = h.createQuery("SELECT * FROM instagram_accounts WHERE instagram_account_id='a'").mapToMap().one()
                    row["read_token"] shouldBe "read-token-for-a"
                    cipher.decrypt(row["admin_token_encrypted"].toString()) shouldBe "admin-token-for-a"
                    cipher.decrypt(row["instagram_access_token_encrypted"].toString()) shouldBe "instagram-a"
                    cipher.decrypt(row["s3_secret_access_key_encrypted"].toString()) shouldBe "secret-a"
                    row.values.any { it == "admin-token-for-a" || it == "instagram-a" || it == "secret-a" } shouldBe false
                }
                accounts.verifyAdminToken("a", "admin-token-for-a") shouldBe AccountTokenVerification.Match
                accounts.verifyAdminToken("b", "admin-token-for-a") shouldBe AccountTokenVerification.Mismatch
                accounts.verifyReadToken("a", "read-token-for-a") shouldBe AccountTokenVerification.Match
                accounts.verifyAdminToken("missing", "admin-token-for-a") shouldBe AccountTokenVerification.UnknownAccount
            }
            test("token uniqueness spans roles and a rejected update rolls back every field") {
                register("a")
                register("b")
                shouldThrow<AccountConflict> { accounts.update("b", AccountChange(enabled = false, adminToken = "read-token-for-a")) }
                accounts.find("b")!!.enabled shouldBe true
                accounts.verifyAdminToken("b", "admin-token-for-b") shouldBe AccountTokenVerification.Match
                accounts.update("a", AccountChange(adminToken = "rotated-admin-token"))
                accounts.verifyAdminToken("a", "admin-token-for-a") shouldBe AccountTokenVerification.Mismatch
                accounts.verifyAdminToken("a", "rotated-admin-token") shouldBe AccountTokenVerification.Match
                shouldThrow<IllegalArgumentException> { accounts.update("a", AccountChange(adminToken = "short-admin")) }
                shouldThrow<IllegalArgumentException> { accounts.update("a", AccountChange(readToken = "short-read")) }
                accounts.verifyAdminToken("a", "rotated-admin-token") shouldBe AccountTokenVerification.Match
            }
            test("refresh compare-and-set cannot overwrite a credential replacement") {
                register()
                val previous = accounts.credential("a")
                accounts.update(
                    "a",
                    AccountChange(
                        instagramAccessToken = "management-rotation",
                        instagramTokenExpiresAt = Instant.now().plusSeconds(300000),
                    ),
                )
                accounts.refreshCredential("a", previous.version, "stale-refresh", Instant.now().plusSeconds(400000)) shouldBe false
                accounts.credential("a").accessToken shouldBe "management-rotation"
            }
            test("concurrent triggers coalesce, recovery preserves IDs and attempt budgets") {
                register()
                val ids = coroutineScope { (1..12).map { async(Dispatchers.IO) { jobs.enqueue("a").id } }.awaitAll() }
                ids.toSet().size shouldBe 1
                val claimed = checkNotNull(jobs.claim())
                jobs.ensureWork(claimed.id, "DOWNLOAD", "media")
                jobs.begin(jobs.work(claimed.id, "DOWNLOAD", "media"))
                jobs.recover()
                jobs.claim()!!.id shouldBe claimed.id
                jobs.work(claimed.id, "DOWNLOAD", "media").attempts shouldBe 1
                jobs.work(claimed.id, "DOWNLOAD", "media").status shouldBe "PENDING"
            }
            test("schedules enqueue immediately once, completion advances cadence, and missed runs do not accumulate") {
                register()
                accounts.find("a")!!.scheduleEnabled shouldBe false
                jobs.list("a", 50, 1).totalRecords shouldBe 0
                shouldThrow<IllegalArgumentException> { jobs.updateSchedule("a", true, 299) }
                jobs.updateSchedule("a", true, 300)
                val first = jobs.claim()!!
                jobs.enqueueDue()
                jobs.list("a", 50, 1).totalRecords shouldBe 1
                jobs.finish(first, "SUCCEEDED")
                jobs.schedule("a").nextRunAt!!.isAfter(Instant.now().plusSeconds(295)) shouldBe true
                jdbi.useHandle<Exception> { it.execute("UPDATE instagram_accounts SET next_run_at=CURRENT_TIMESTAMP - INTERVAL '1 day'") }
                jobs.enqueueDue()
                jobs.enqueueDue()
                jobs.list("a", 50, 1).totalRecords shouldBe 2
                jobs.updateSchedule("a", false, 300)
                jobs.schedule("a").nextRunAt shouldBe null
            }
            test("scheduler honors an injected clock without accumulating missed intervals") {
                register()
                val instant = Instant.parse("2030-01-01T00:00:00Z")
                val fixed = IngestionJobRepository(jdbi, java.time.Clock.fixed(instant, java.time.ZoneOffset.UTC))
                fixed.updateSchedule("a", true, 300)
                fixed.finish(fixed.claim()!!, "SUCCEEDED")
                fixed.schedule("a").nextRunAt shouldBe instant.plusSeconds(300)
                fixed.enqueueDue()
                fixed.list("a", 50, 1).totalRecords shouldBe 1
                val later = IngestionJobRepository(jdbi, java.time.Clock.fixed(instant.plusSeconds(86400), java.time.ZoneOffset.UTC))
                later.enqueueDue()
                later.enqueueDue()
                later.list("a", 50, 1).totalRecords shouldBe 2
            }
            test("destination changes serialize against queued work and possible remote objects; key rotation stays possible") {
                register()
                jobs.enqueue("a")
                shouldThrow<AccountConflict> {
                    accounts.update(
                        "a",
                        AccountChange(storage = S3Settings("other-bucket", "us-east-1", "http://localhost:9000", "access", "secret", true)),
                    )
                }
                accounts.update(
                    "a",
                    AccountChange(
                        storage = S3Settings("knurl-media", "us-east-1", "http://localhost:9000", "rotated-access", "rotated-secret", true),
                    ),
                )
                accounts.storage("a").version shouldBe 2
                accounts.storage("a").settings.secretAccessKey shouldBe "rotated-secret"
                jobs.finish(jobs.claim()!!, "SUCCEEDED")
                accounts.markStorageUsed("a")
                shouldThrow<AccountConflict> {
                    accounts.update(
                        "a",
                        AccountChange(storage = S3Settings("other-bucket", "us-east-1", "http://localhost:9000", "access", "secret", true)),
                    )
                }
            }
            test("disabling cancels queued jobs, revokes access, and preserves data") {
                register()
                val job = jobs.enqueue("a")
                accounts.update("a", AccountChange(enabled = false))
                jobs.find("a", job.id)!!.status shouldBe "CANCELLED"
                accounts.verifyReadToken("a", "read-token-for-a") shouldBe AccountTokenVerification.UnknownAccount
                accounts.find("a")!!.readToken shouldBe "read-token-for-a"
            }
            test("HTTP contracts bind account/job paths and never expose secret fields") {
                register()
                val management =
                    app(Request(Method.GET, "/api/v1/management/accounts/a").header("Authorization", "Bearer test-management-secret"))
                management.status shouldBe Status.OK
                listOf("admin-token-for-a", "instagram-a", "secret-a", "adminTokenEncrypted", "secretAccessKey\"").forEach { secret ->
                    management.bodyString().contains(secret) shouldBe
                        false
                }
                app(Request(Method.GET, "/api/v1/management/accounts")).status shouldBe Status.UNAUTHORIZED
                app(Request(Method.GET, "/health")).status shouldBe Status.OK
                app(
                    Request(Method.POST, "/api/v1/accounts/a/galleries/019a0000-0000-7000-8000-000000000001/track")
                        .header("Authorization", "Bearer read-token-for-a")
                        .body("x".repeat(65537)),
                ).status shouldBe Status.REQUEST_ENTITY_TOO_LARGE
                app(
                    Request(Method.POST, "/api/v1/admin/accounts/a/ingestion/jobs").header("Authorization", "Bearer admin-token-for-a"),
                ).status shouldBe
                    Status.ACCEPTED
                val id =
                    jobs
                        .list("a", 1, 1)
                        .items
                        .single()
                        .id
                app(
                    Request(Method.GET, "/api/v1/admin/accounts/a/ingestion/jobs/$id").header("Authorization", "Bearer admin-token-for-a"),
                ).status shouldBe
                    Status.OK
                app(
                    Request(Method.GET, "/api/v1/admin/accounts/a/ingestion/schedule").header("Authorization", "Bearer admin-token-for-a"),
                ).status shouldBe
                    Status.OK
                app(
                    Request(Method.GET, "/api/v1/admin/accounts/a/ingestion/jobs/$id").header("Authorization", "Bearer read-token-for-a"),
                ).status shouldBe
                    Status.UNAUTHORIZED
            }
            test("malformed credential expiry returns 400 without reflecting secret input") {
                register()
                val response =
                    app(
                        Request(
                            Method.PATCH,
                            "/api/v1/management/accounts/a",
                        ).header(
                            "Authorization",
                            "Bearer test-management-secret",
                        ).header(
                            "Content-Type",
                            "application/json",
                        ).body("""{"instagramAccessToken":"never-reflect-this-secret","instagramTokenExpiresAt":"not-an-instant"}"""),
                    )
                response.status shouldBe Status.BAD_REQUEST
                response.bodyString().contains("never-reflect-this-secret") shouldBe false
                accounts.credential("a").accessToken shouldBe "instagram-a"
            }
            test("a successful empty feed reaches a durable successful job") {
                register("empty")
                val graph = mockk<MetaGraphClient>()
                val tokens = mockk<InstagramAccessTokenProvider>()
                coEvery { tokens.getAccessToken("empty") } returns "token"
                every { graph.fetchMediaList("empty", "token", null) } returns MediaListResponse()
                jobs.enqueue("empty")
                val claimed = jobs.claim()!!
                IngestionCoordinator(jdbi, accounts, jobs, storage, OkHttpClient(), graph, tokens).execute(claimed)
                jobs.find("empty", claimed.id)!!.status shouldBe "SUCCEEDED"
                jobs.work(claimed.id, "FEED", "empty").attempts shouldBe 1
            }
            test("a partial pagination failure never removes existing catalog data") {
                register("failed")
                val catalog = CatalogRepository(jdbi)
                catalog.upsert(
                    CatalogUpsert(
                        "old-media",
                        "failed",
                        "oldshort",
                        "IMAGE",
                        null,
                        "https://instagram.com/p/oldshort/",
                        Instant.now(),
                        null,
                    ),
                )
                val graph = mockk<MetaGraphClient>()
                val tokens = mockk<InstagramAccessTokenProvider>()
                coEvery { tokens.getAccessToken("failed") } returns "token"
                every { graph.fetchMediaList("failed", "token", null) } returns
                    MediaListResponse(paging = MediaListPaging("https://graph.instagram.com/page?after=second"))
                every { graph.fetchMediaList("failed", "token", "second") } throws MetaGraphApiException(401, "secret must not appear")
                jobs.enqueue("failed")
                val claimed = jobs.claim()!!
                IngestionCoordinator(jdbi, accounts, jobs, storage, OkHttpClient(), graph, tokens).execute(claimed)
                jobs.find("failed", claimed.id)!!.status shouldBe "FAILED"
                jobs.work(claimed.id, "FEED", "failed").attempts shouldBe 1
                catalog.allMediaIds("failed") shouldBe setOf("old-media")
            }
            test("missing media URLs exhaust bounded retries and report partial instead of downloaded") {
                register("partial")
                val now =
                    java.time.OffsetDateTime
                        .now(
                            java.time.ZoneOffset.UTC,
                        ).format(knurl.ingestion.pipeline.INSTAGRAM_TIMESTAMP_FORMATTER)
                val media = MediaItem("pending-media", "IMAGE", permalink = "https://instagram.com/p/pending/", timestamp = now)
                CatalogRepository(
                    jdbi,
                ).upsert(CatalogUpsert(media.id, "partial", "pending", "IMAGE", null, media.permalink, Instant.now(), null))
                jdbi.useHandle<Exception> { h ->
                    h.execute("INSERT INTO galleries (instagram_account_id,name) VALUES ('partial','selected')")
                    h.execute(
                        "INSERT INTO gallery_items (gallery_id,instagram_media_id) SELECT id,'pending-media' FROM galleries WHERE instagram_account_id='partial'",
                    )
                }
                val graph = mockk<MetaGraphClient>()
                val tokens = mockk<InstagramAccessTokenProvider>()
                val waiter = mockk<suspend (Long) -> Unit>()
                coEvery { waiter(any()) } returns Unit
                coEvery { tokens.getAccessToken("partial") } returns "token"
                every { graph.fetchMediaList("partial", "token", null) } returns MediaListResponse(listOf(media))
                every { graph.fetchMediaItem(media.id, "token") } returns media
                jobs.enqueue("partial")
                val claimed = jobs.claim()!!
                IngestionCoordinator(jdbi, accounts, jobs, storage, OkHttpClient(), graph, tokens, waitFor = waiter).execute(claimed)
                jobs.find("partial", claimed.id)!!.status shouldBe "PARTIAL"
                jobs.work(claimed.id, "DOWNLOAD", media.id).status shouldBe "FAILED"
                jobs.work(claimed.id, "DOWNLOAD", media.id).attempts shouldBe 3
                jobs.work(claimed.id, "DOWNLOAD", media.id).errorCode shouldBe "MEDIA_NOT_READY"
                coVerify(atLeast = 1) { waiter(any()) }
                jdbi.withHandle<Int, Exception> {
                    it
                        .createQuery(
                            "SELECT COUNT(*) FROM instagram_posts",
                        ).mapTo(Int::class.java)
                        .one()
                } shouldBe
                    0
            }
            if (System.getenv("S3_INTEGRATION_TEST") == "true") {
                test("a selected image is ingested into Garage and served through account-signed gallery URLs") {
                    register("downloaded")
                    val endpoint = System.getenv("TEST_S3_ENDPOINT") ?: "http://localhost:3900"
                    accounts.update(
                        "downloaded",
                        AccountChange(
                            storage =
                                S3Settings(
                                    "knurl-media",
                                    System.getenv("TEST_S3_REGION") ?: "garage",
                                    endpoint,
                                    System.getenv("TEST_S3_ACCESS_KEY") ?: "GK00000000000000000000000000000000",
                                    System.getenv("TEST_S3_SECRET_KEY")
                                        ?: "1111111111111111111111111111111111111111111111111111111111111111",
                                    true,
                                ),
                        ),
                    )
                    val server = okhttp3.mockwebserver.MockWebServer()
                    server.start()
                    try {
                        val png =
                            java.io.ByteArrayOutputStream().use { output ->
                                javax.imageio.ImageIO.write(
                                    java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_RGB),
                                    "png",
                                    output,
                                )
                                output.toByteArray()
                            }
                        repeat(
                            2,
                        ) {
                            server.enqueue(
                                okhttp3.mockwebserver
                                    .MockResponse()
                                    .setHeader("Content-Type", "image/png")
                                    .setBody(okio.Buffer().write(png)),
                            )
                        }
                        val media =
                            MediaItem(
                                "image-media",
                                "IMAGE",
                                permalink = "https://instagram.com/p/downloaded/",
                                timestamp =
                                    java.time.OffsetDateTime
                                        .now(
                                            java.time.ZoneOffset.UTC,
                                        ).format(knurl.ingestion.pipeline.INSTAGRAM_TIMESTAMP_FORMATTER),
                                mediaUrl = server.url("/image.png").toString(),
                            )
                        CatalogRepository(
                            jdbi,
                        ).upsert(CatalogUpsert(media.id, "downloaded", "downloaded", "IMAGE", null, media.permalink, Instant.now(), null))
                        jdbi.useHandle<Exception> { h ->
                            h.execute("INSERT INTO galleries (instagram_account_id,name) VALUES ('downloaded','selected')")
                            h.execute(
                                "INSERT INTO gallery_items (gallery_id,instagram_media_id) SELECT id,'image-media' FROM galleries WHERE instagram_account_id='downloaded'",
                            )
                        }
                        val graph = mockk<MetaGraphClient>()
                        val tokens = mockk<InstagramAccessTokenProvider>()
                        coEvery { tokens.getAccessToken("downloaded") } returns "token"
                        every { graph.fetchMediaList("downloaded", "token", null) } returns MediaListResponse(listOf(media))
                        every { graph.fetchMediaItem(media.id, "token") } returns media
                        jobs.enqueue("downloaded")
                        val claimed = jobs.claim()!!
                        IngestionCoordinator(jdbi, accounts, jobs, storage, OkHttpClient(), graph, tokens).execute(claimed)
                        jobs.find("downloaded", claimed.id)!!.status shouldBe "SUCCEEDED"
                        jobs.work(claimed.id, "DOWNLOAD", media.id).status shouldBe "SUCCEEDED"
                        knurl.domain.repositories
                            .ObjectKeyRepository(jdbi)
                            .allKnownKeys("downloaded")
                            .size shouldBe 3
                        val gallery =
                            jdbi.withHandle<String, Exception> {
                                it
                                    .createQuery(
                                        "SELECT id::text FROM galleries WHERE instagram_account_id='downloaded'",
                                    ).mapTo(String::class.java)
                                    .one()
                            }
                        val response =
                            app(
                                Request(
                                    Method.GET,
                                    "/api/v1/accounts/downloaded/galleries/$gallery",
                                ).header("Authorization", "Bearer read-token-for-downloaded"),
                            )
                        response.status shouldBe Status.OK
                        response.bodyString().contains("knurl/accounts/downloaded/posts/image-media/") shouldBe true
                    } finally {
                        server.shutdown()
                        storage.acquire("downloaded").use { lease ->
                            lease.client.listObjectsV2 { it.bucket(lease.bucket).prefix(lease.prefix) }.contents().forEach { entry ->
                                lease.client.deleteObject { it.bucket(lease.bucket).key(entry.key()) }
                            }
                        }
                    }
                }
            }
            test("a deletion manifest cannot delete bytes while database references survive a failed phase") {
                register()
                CatalogRepository(
                    jdbi,
                ).upsert(
                    CatalogUpsert(
                        "protected-media",
                        "a",
                        "protected",
                        "IMAGE",
                        null,
                        "https://instagram.com/p/protected/",
                        Instant.now(),
                        null,
                    ),
                )
                jobs.enqueue("a")
                val claimed = jobs.claim()!!
                jobs.ensureWork(claimed.id, "FEED", "a")
                jobs.outcome(jobs.work(claimed.id, "FEED", "a"), "SUCCEEDED")
                jobs.ensureWork(claimed.id, "VANISHED", "a")
                jobs.outcome(jobs.work(claimed.id, "VANISHED", "a"), "FAILED", "DATABASE_DELETE_FAILED")
                jobs.ensureWork(claimed.id, "DELETE", "protected-media", listOf("knurl/accounts/a/catalog/protected-media/thumbnail.webp"))
                val unusedStorage = mockk<knurl.app.storage.AccountStorageManager>()
                IngestionCoordinator(
                    jdbi,
                    accounts,
                    jobs,
                    unusedStorage,
                    OkHttpClient(),
                    mockk<MetaGraphClient>(),
                    mockk<InstagramAccessTokenProvider>(),
                ).execute(claimed)
                jobs.find("a", claimed.id)!!.status shouldBe "PARTIAL"
                jobs.work(claimed.id, "DELETE", "protected-media").status shouldBe "FAILED"
                CatalogRepository(jdbi).allMediaIds("a") shouldBe setOf("protected-media")
                verify(exactly = 0) { unusedStorage.acquire(any()) }
            }
            test("disable cancellation survives immediate re-enable and startup recovery") {
                register()
                jobs.enqueue("a")
                val claimed = jobs.claim()!!
                accounts.update("a", AccountChange(enabled = false))
                accounts.update("a", AccountChange(enabled = true))
                jobs.cancelRequested(claimed.id) shouldBe true
                jobs.recover()
                jobs.find("a", claimed.id)!!.status shouldBe "CANCELLED"
            }
            test("history cleanup removes only old terminal jobs") {
                register()
                val completed = jobs.enqueue("a")
                jobs.finish(jobs.claim()!!, "PARTIAL")
                val queued = jobs.enqueue("a")
                jdbi.useHandle<Exception> {
                    it.execute("UPDATE ingestion_jobs SET completed_at=CURRENT_TIMESTAMP - INTERVAL '31 days' WHERE status='PARTIAL'")
                }
                jobs.prune()
                jobs.find("a", completed.id) shouldBe null
                jobs.find("a", queued.id)!!.status shouldBe "QUEUED"
            }
        }
    })
