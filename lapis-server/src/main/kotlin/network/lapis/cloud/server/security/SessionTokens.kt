package network.lapis.cloud.server.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Raw session-token generation/hashing (V0.7.1 Authentifizierung). The raw token is the
 * bearer-usable secret handed to the client (cookie/`Authorization: Bearer`); [SessionTable] only
 * ever stores [hash] of it — see [SessionStore] KDoc "Only a hash of the token is ever stored".
 */
object SessionTokens {
    /** 256-bit — cryptographically unguessable, comfortably beyond any practical brute-force/enumeration budget. */
    private const val TOKEN_BYTES = 32

    private val secureRandom = SecureRandom()

    /** A fresh, cryptographically random raw token, Base64URL-encoded without padding (URL/cookie-safe, no `=`/`+`/`/`). */
    fun newRawToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** SHA-256 of [rawToken] as a lowercase hex string (64 chars) — a fresh [MessageDigest] instance per call (thread-safe; see codebase security checklist "Kryptografie"). */
    fun hash(rawToken: String): String {
        val digestBytes = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray(Charsets.UTF_8))
        return digestBytes.joinToString("") { byte -> "%02x".format(byte) }
    }
}
