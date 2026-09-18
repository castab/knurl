package knurl.presentation.credentials

import knurl.domain.repositories.AccountRepository
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Loads credentials into [store] and keeps every loaded account anchored in the database.
 *
 * Anchoring is what makes the control plane a real provisioning path rather than a token courier:
 * `instagram_accounts` is the foreign key `galleries` and `instagram_posts` reference and the queue
 * `ingestion-service` claims from, so an account that appears in a fetch becomes an account this
 * deployment can create galleries for and will start syncing. Without it, an account the control
 * plane knows about authorizes fine and then fails with a foreign-key violation on first write.
 */
class CredentialRefresher(
    private val source: CredentialSource,
    private val store: AccountCredentialStore,
    private val accountRepository: AccountRepository,
    private val bootAttempts: Int = 5,
    private val bootBackoffMillis: Long = 2_000,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private val log = LoggerFactory.getLogger(CredentialRefresher::class.java)

    // Guards *triggering* a refresh, never held across the fetch itself - a slow control plane must
    // not block every other refresh attempt behind it for the length of an HTTP timeout.
    private val trigger = ReentrantLock()
    private val issued = AtomicLong(0)
    private val applied = AtomicLong(0)

    /**
     * Applies one fetch. Concurrent callers each fetch, but a result is discarded if a newer fetch
     * already landed - otherwise a slow refresh returning after a fast one would quietly reinstate
     * the credentials the newer fetch had just superseded.
     *
     * @return true if this call's result became the live snapshot.
     */
    fun refresh(): Boolean {
        val sequence = trigger.withLock { issued.incrementAndGet() }
        val credentials = source.fetch()

        val accepted =
            trigger.withLock {
                if (sequence <= applied.get()) return@withLock false
                applied.set(sequence)
                store.replaceAll(credentials)
                true
            }

        if (!accepted) {
            log.debug("Discarding credential fetch {} - a newer fetch already applied", sequence)
            return false
        }

        credentials.keys.forEach(accountRepository::ensureAccount)
        log.info("Loaded credentials for {} account(s)", credentials.size)
        return true
    }

    /**
     * Populates the store before the server accepts traffic, retrying a transient failure.
     *
     * Retries rather than failing on the first error because the common case is a control plane and
     * this service restarting together; giving up immediately turns an ordering coincidence into a
     * crash loop. Once the attempts are spent it does throw: serving with an empty store would make
     * every request a 404 that looks exactly like a deleted account.
     */
    fun loadAtStartup() {
        repeat(bootAttempts) { attempt ->
            val failure =
                runCatching { refresh() }.exceptionOrNull()
                    ?: return
            if (attempt == bootAttempts - 1) throw failure
            val backoff = bootBackoffMillis * (attempt + 1)
            log.warn(
                "Credential fetch failed at startup (attempt {} of {}), retrying in {}ms: {}",
                attempt + 1,
                bootAttempts,
                backoff,
                failure.message,
            )
            sleep(backoff)
        }
    }
}
