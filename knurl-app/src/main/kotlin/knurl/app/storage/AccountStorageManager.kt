package knurl.app.storage

import knurl.domain.models.AccountChange
import knurl.domain.models.AccountSummary
import knurl.domain.models.accountObjectPrefix
import knurl.domain.repositories.AccountConflict
import knurl.domain.repositories.AccountRepository
import knurl.presentation.s3.Presigner
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import java.time.Duration

/** Bounded, versioned leases prevent rotation from closing an in-flight upload or reusing old signed URLs. */
class AccountStorageManager(
    private val accounts: AccountRepository,
    private val ttl: Duration,
) : AutoCloseable {
    private class Entry(
        val accountId: String,
        val version: Long,
        val bucket: String,
        val client: S3Client,
        val presigner: Presigner,
    ) {
        var users = 0

        fun close() {
            client.close()
            presigner.close()
        }
    }

    private val entries = LinkedHashMap<Pair<String, Long>, Entry>(16, 0.75f, true)
    private var closed = false

    fun updateAccount(
        id: String,
        change: AccountChange,
    ): AccountSummary =
        synchronized(entries) {
            val old = accounts.find(id)
            change.storage?.let { s ->
                val destinationChanged =
                    old != null &&
                        (
                            old.bucketName != s.bucketName || old.region != s.region || old.endpoint != s.endpoint ||
                                old.pathStyleAccess != s.pathStyleAccess
                        )
                if (destinationChanged &&
                    entries.any { it.key.first == id && it.value.users > 0 }
                ) {
                    throw AccountConflict("storage destination is in use")
                }
            }
            accounts.update(id, change)
        }

    inner class Lease internal constructor(
        private val entryKey: Pair<String, Long>,
        val bucket: String,
        val client: S3Client,
        val presigner: Presigner,
    ) : AutoCloseable {
        private var released = false
        val prefix: String get() = accountObjectPrefix(entryKey.first)

        fun beforeUpload() = accounts.markStorageUsed(entryKey.first)

        override fun close() {
            synchronized(entries) {
                if (!released) {
                    released = true
                    entries[entryKey]?.let { e ->
                        e.users--
                        if (closed && e.users == 0) {
                            entries.remove(entryKey)
                            e.close()
                        }
                    }
                }
            }
        }
    }

    fun acquire(accountId: String): Lease {
        synchronized(entries) {
            val storage = accounts.storage(accountId)
            check(!closed) { "storage is shutting down" }
            val key = accountId to storage.version
            entries.entries
                .filter {
                    it.key.first == accountId && it.key != key && it.value.users == 0
                }.map { it.key }
                .forEach { entries.remove(it)?.close() }
            val entry =
                entries[key] ?: run {
                    if (entries.size >= 16) {
                        val idle = entries.entries.firstOrNull { it.value.users == 0 } ?: throw StorageBusyException()
                        entries.remove(idle.key)?.close()
                    }
                    val s = storage.settings
                    val builder =
                        S3Client
                            .builder()
                            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                            .region(Region.of(s.region))
                            .httpClientBuilder(UrlConnectionHttpClient.builder())
                            .credentialsProvider(
                                StaticCredentialsProvider.create(AwsBasicCredentials.create(s.accessKeyId, s.secretAccessKey)),
                            ).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(s.pathStyleAccess).build())
                            .overrideConfiguration { it.apiCallTimeout(Duration.ofMinutes(5)).apiCallAttemptTimeout(Duration.ofMinutes(2)) }
                    s.endpoint?.let { builder.endpointOverride(URI(it)) }
                    val client = builder.build()
                    val signer =
                        try {
                            Presigner.create(s, ttl, allowedPrefix = accountObjectPrefix(accountId))
                        } catch (e: Exception) {
                            client.close()
                            throw e
                        }
                    Entry(accountId, storage.version, s.bucketName, client, signer).also { entries[key] = it }
                }
            entry.users++
            return Lease(key, entry.bucket, entry.client, entry.presigner)
        }
    }

    override fun close() {
        synchronized(entries) {
            closed = true
            entries.entries
                .filter { it.value.users == 0 }
                .map { it.key }
                .forEach { entries.remove(it)?.close() }
        }
    }
}

class StorageBusyException : RuntimeException("storage resources busy")
