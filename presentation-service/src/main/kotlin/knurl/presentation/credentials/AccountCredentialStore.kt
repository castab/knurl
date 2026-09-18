package knurl.presentation.credentials

import knurl.domain.repositories.AccountTokenVerification
import knurl.domain.repositories.verifyAccountToken
import java.util.concurrent.atomic.AtomicReference

/** One account's two token hashes. Plaintext is hashed on arrival and never retained. */
data class AccountCredentials(
    val adminTokenHash: String,
    val readTokenHash: String,
)

/**
 * The set of accounts this process serves and the tokens it accepts for each, held in memory.
 *
 * Both credential modes converge here: `LOCAL` fills it once from configuration, `HTTP` replaces it
 * wholesale on every fetch from the control plane. Routes are unaware of which - they receive a
 * [knurl.presentation.routes.VerifyAccountToken], and [verifyAdminToken]/[verifyReadToken] match
 * that shape exactly.
 *
 * Replacement is a whole-snapshot swap through an [AtomicReference], never a mutation of a live
 * map, so a request reading during a refresh sees either the complete old set or the complete new
 * one. A partially-applied refresh would mean an account briefly authorizing against one token kind
 * but not the other.
 */
class AccountCredentialStore {
    private val snapshot = AtomicReference<Map<String, AccountCredentials>>(emptyMap())

    fun replaceAll(credentials: Map<String, AccountCredentials>) {
        snapshot.set(credentials.toMap())
    }

    fun accountIds(): Set<String> = snapshot.get().keys

    fun verifyAdminToken(
        accountId: String,
        candidateToken: String?,
    ): AccountTokenVerification = verifyAccountToken(snapshot.get()[accountId]?.adminTokenHash, candidateToken)

    fun verifyReadToken(
        accountId: String,
        candidateToken: String?,
    ): AccountTokenVerification = verifyAccountToken(snapshot.get()[accountId]?.readTokenHash, candidateToken)
}
