package network.lapis.cloud.server.security

import at.favre.lib.crypto.bcrypt.BCrypt
import java.security.MessageDigest
import java.util.Base64

/**
 * bcrypt password hashing (V0.7.1 Authentifizierung) — see `at.favre.lib:bcrypt` in
 * `gradle/libs.versions.toml` for why this library over Argon2id (pure-JVM, zero JNI/native code,
 * one Apache-2.0 transitive dependency, matching this repo's hard permissive-license /
 * no-native-deps posture).
 *
 * **72-byte truncation and NUL-byte-stop neutralized via pre-hashing**: bcrypt's underlying
 * Blowfish key schedule only consumes the first 72 bytes of its input AND stops early at the
 * first NUL byte — a raw password longer than 72 bytes (an emoji-heavy passphrase can exceed that
 * in UTF-8 well before 72 *characters*) would silently lose entropy, and a password containing a
 * literal NUL would be truncated even earlier. [preHash] folds the FULL password (arbitrary
 * length, arbitrary bytes) through SHA-256 first and Base64-encodes the digest — a fixed 44 ASCII
 * bytes, no NUL, all of the original password's entropy represented. Applied identically at
 * [hash] and [verify] time; changing this transform would silently invalidate every stored hash.
 *
 * **Timing-safe "no such account" handling**: [verify] with `storedHash == null` (unknown email,
 * or a seeded account whose password was never set) always runs a real bcrypt comparison against
 * [DUMMY_HASH] and always returns `false` — never short-circuits — so "account doesn't exist" and
 * "account exists, wrong password" take the same code path and roughly the same wall-clock time
 * (bcrypt's own cost factor already swamps any residual timing signal). See
 * `network.lapis.cloud.server.routes.registerAuthRoutes` KDoc for the account-enumeration-
 * hardening this feeds.
 */
object PasswordHasher {
    /** Work factor — see this class's own KDoc "Kryptografie" security-checklist entry; a documented, easy-to-find tunable. */
    private const val BCRYPT_COST = 12

    /**
     * A fixed, valid bcrypt hash (cost [BCRYPT_COST], of an arbitrary constant password) never
     * matched by any real user password — used ONLY to keep [verify]'s wall-clock time uniform
     * when there is no real stored hash to compare against. NOT a secret; its only property that
     * matters is "a syntactically valid bcrypt hash string that [preHash]-derived inputs never
     * happen to match".
     */
    private val DUMMY_HASH: String =
        BCrypt.withDefaults().hashToString(BCRYPT_COST, "lapis-cloud-dummy-hash-never-matches".toCharArray())

    /** Hashes [rawPassword] — a fresh bcrypt salt every call (see [BCrypt.withDefaults]'s own contract), so two calls with the same password never produce the same hash. */
    fun hash(rawPassword: String): String = BCrypt.withDefaults().hashToString(BCRYPT_COST, preHash(rawPassword))

    /**
     * `true` iff [rawPassword] matches [storedHash]. `storedHash == null` (no password set yet, or
     * unknown account — callers pass `null` here rather than short-circuiting themselves, see
     * class KDoc "Timing-safe") always compares against [DUMMY_HASH] and always yields `false`.
     */
    fun verify(
        rawPassword: String,
        storedHash: String?,
    ): Boolean {
        val target = storedHash ?: DUMMY_HASH
        val result = BCrypt.verifyer().verify(preHash(rawPassword), target)
        return result.verified && storedHash != null
    }

    /** SHA-256(utf8(rawPassword)), Base64-encoded — see class KDoc "72-byte truncation". A fresh [MessageDigest] instance per call (thread-safe; see codebase security checklist "Kryptografie"). */
    private fun preHash(rawPassword: String): CharArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawPassword.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest).toCharArray()
    }
}
