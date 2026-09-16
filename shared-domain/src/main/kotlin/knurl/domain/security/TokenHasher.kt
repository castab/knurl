package knurl.domain.security

import java.security.MessageDigest
import java.util.Base64

/**
 * Hashes opaque bearer secrets (the per-account admin token) before they are persisted, so a
 * database read alone - backup, replica, log, or a compromised ops account - never yields a
 * credential usable against the live admin API. SHA-256 is one-way: verification re-hashes the
 * candidate and compares digests, it never recovers the original token, which is fine here
 * because nothing ever needs the admin token back in plaintext once it's stored.
 *
 * Contrast with [CredentialCipher], used for the Instagram access token, which *does* need to be
 * recovered in full to be replayed to Meta's Graph API and therefore can't be hashed.
 */
object TokenHasher {
    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    /** Constant-time digest comparison, so verifying a wrong token doesn't leak timing info. */
    fun matches(
        candidateHash: String,
        storedHash: String,
    ): Boolean = MessageDigest.isEqual(candidateHash.toByteArray(Charsets.UTF_8), storedHash.toByteArray(Charsets.UTF_8))
}
