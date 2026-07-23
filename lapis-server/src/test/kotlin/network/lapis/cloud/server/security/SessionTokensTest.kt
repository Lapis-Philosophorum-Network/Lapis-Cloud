package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

/** Pure tests of [SessionTokens] -- no DB access. */
class SessionTokensTest :
    FunSpec({
        test("newRawToken() is URL/cookie-safe (Base64URL, no padding)") {
            val token = SessionTokens.newRawToken()
            token shouldMatch Regex("^[A-Za-z0-9_-]+$")
            (token.contains("=")) shouldBe false
        }

        test("newRawToken() is unguessable -- two calls never collide") {
            val first = SessionTokens.newRawToken()
            val second = SessionTokens.newRawToken()
            first shouldNotBe second
        }

        test("hash() is deterministic -- same input always yields the same output") {
            val token = SessionTokens.newRawToken()
            SessionTokens.hash(token) shouldBe SessionTokens.hash(token)
        }

        test("hash() is a 64-char lowercase hex string (SHA-256)") {
            val hash = SessionTokens.hash(SessionTokens.newRawToken())
            hash.length shouldBe 64
            hash shouldMatch Regex("^[0-9a-f]{64}$")
        }

        test("hash() of two different tokens never collides") {
            val hashA = SessionTokens.hash(SessionTokens.newRawToken())
            val hashB = SessionTokens.hash(SessionTokens.newRawToken())
            hashA shouldNotBe hashB
        }
    })
