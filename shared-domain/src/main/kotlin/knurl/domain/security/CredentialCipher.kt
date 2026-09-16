package knurl.domain.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val AES_256_KEY_BYTES = 32
private const val TRANSFORMATION = "AES/GCM/NoPadding"

/**
 * AES-256-GCM encryption for `auth_config.access_token_encrypted` - the one credential that
 * cannot be hashed the way [TokenHasher] hashes the admin token, because ingestion-service must
 * recover it in full to replay it to Meta's Graph API on every sync cycle.
 *
 * The key comes from `CREDENTIAL_ENCRYPTION_KEY` (32 raw bytes, base64-encoded), configured
 * independently of the database - it is never stored alongside the ciphertext it protects, so a
 * database dump alone (the same threat [TokenHasher] defends against) does not yield a usable
 * access token.
 *
 * Each call to [encrypt] draws a fresh random IV (GCM never reuses a key+IV pair safely) and
 * prepends it to the ciphertext, so [decrypt] needs nothing beyond the stored value and the key.
 */
class CredentialCipher(
    base64Key: String,
) {
    private val keySpec: SecretKeySpec
    private val secureRandom = SecureRandom()

    init {
        val keyBytes =
            try {
                Base64.getDecoder().decode(base64Key)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("CREDENTIAL_ENCRYPTION_KEY must be valid base64")
            }
        require(keyBytes.size == AES_256_KEY_BYTES) {
            "CREDENTIAL_ENCRYPTION_KEY must decode to $AES_256_KEY_BYTES bytes (AES-256), was ${keyBytes.size}"
        }
        keySpec = SecretKeySpec(keyBytes, "AES")
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(encoded: String): String {
        val combined = Base64.getDecoder().decode(encoded)
        require(combined.size > GCM_IV_BYTES) { "Encrypted credential payload is too short to contain an IV" }
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        /** Generates a fresh base64-encoded AES-256 key suitable for `CREDENTIAL_ENCRYPTION_KEY`. */
        fun generateKey(): String = Base64.getEncoder().encodeToString(ByteArray(AES_256_KEY_BYTES).also(SecureRandom()::nextBytes))
    }
}
