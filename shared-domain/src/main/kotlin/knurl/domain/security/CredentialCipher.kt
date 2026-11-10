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
 * AES-256-GCM for recoverable Instagram, admin, and S3 secret credentials. Each encryption uses
 * a fresh random IV, prepended to the authenticated ciphertext. CREDENTIAL_ENCRYPTION_KEY is
 * 32 base64-encoded raw bytes kept outside the database; a database dump cannot decrypt secrets.
 * Read tokens remain plaintext. Token fingerprints enforce uniqueness, not secret recovery.
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
