package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.module
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val ADMIN_EMAIL = "amara.admin@example.org"
private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

/**
 * Exercises `/api/auth/login` and `/api/auth/logout` end to end through the REAL, fully-wired
 * [network.lapis.cloud.server.module] (not a throwaway routing block) -- this is the test that
 * would have caught `registerAuthRoutes` never being mounted in [network.lapis.cloud.server.module].
 */
class AuthRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpAuthRoutesTestData(createdMemberIds) }

        fun createTestMemberWithoutPassword(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Auth-Routes Testmitglied ohne Passwort"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                    // passwordHash left unset -- nullable, models a seeded-but-never-logged-in account.
                }
            }
            createdMemberIds += id
            return id
        }

        fun rawTokenFromSetCookie(setCookieHeader: String): String {
            val match = Regex("lapis_session=([^;]+)").find(setCookieHeader)
            return requireNotNull(match) { "no lapis_session cookie in: $setCookieHeader" }.groupValues[1]
        }

        test("login with correct demo credentials succeeds and sets a HttpOnly/Secure/SameSite=Strict session cookie") {
            testApplication {
                application { module() }

                val response =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$ADMIN_EMAIL","password":"${DevSeedData.DEMO_PASSWORD}"}""")
                    }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain ADMIN_ID

                val setCookie = response.headers[HttpHeaders.SetCookie]
                requireNotNull(setCookie)
                setCookie shouldContain "lapis_session="
                setCookie shouldContain "HttpOnly"
                setCookie shouldContain "Secure"
                setCookie shouldContain "SameSite=Strict"
            }
        }

        test("the cookie issued by login actually authenticates a subsequent request via resolveCurrentMember") {
            testApplication {
                application {
                    module()
                    routing {
                        get("/test/whoami") {
                            val current = resolveCurrentMember(call)
                            call.respondText(current.memberId.toString())
                        }
                    }
                }

                val loginResponse =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$ADMIN_EMAIL","password":"${DevSeedData.DEMO_PASSWORD}"}""")
                    }
                val setCookie = requireNotNull(loginResponse.headers[HttpHeaders.SetCookie])
                val rawToken = rawTokenFromSetCookie(setCookie)

                val whoamiResponse =
                    client.get("/test/whoami") { header(HttpHeaders.Cookie, "lapis_session=$rawToken") }
                whoamiResponse.status shouldBe HttpStatusCode.OK
                whoamiResponse.bodyAsText() shouldBe ADMIN_ID
            }
        }

        test("login with a wrong password is rejected with 401 and a generic message") {
            testApplication {
                application { module() }

                val response =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$ADMIN_EMAIL","password":"definitely-the-wrong-password"}""")
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldBe "Invalid credentials"
            }
        }

        test(
            "login with an unknown email is rejected with the IDENTICAL status and body as a wrong password (account-enumeration hardening)",
        ) {
            testApplication {
                application { module() }

                val unknownResponse =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"no-such-account@example.org","password":"whatever-12345"}""")
                    }
                val wrongPasswordResponse =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$ADMIN_EMAIL","password":"definitely-the-wrong-password"}""")
                    }

                unknownResponse.status shouldBe wrongPasswordResponse.status
                unknownResponse.bodyAsText() shouldBe wrongPasswordResponse.bodyAsText()
            }
        }

        test("login against a seeded account with a NULL password hash (never logged in) is rejected identically, not with a 500/NPE") {
            testApplication {
                application { module() }

                val email = "auth-routes-null-hash@example.org"
                createTestMemberWithoutPassword(email)

                val response =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$email","password":"whatever-12345"}""")
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
                response.bodyAsText() shouldBe "Invalid credentials"
            }
        }

        test("login with blank email/password is rejected with 400, before touching the DB or the rate limiter") {
            testApplication {
                application { module() }

                client.post("/api/auth/login") { setBody("""{"email":"","password":"whatever-12345"}""") }.status shouldBe
                    HttpStatusCode.BadRequest
                client.post("/api/auth/login") { setBody("""{"email":"$ADMIN_EMAIL","password":""}""") }.status shouldBe
                    HttpStatusCode.BadRequest
                client.post("/api/auth/login") { setBody("not even json") }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("repeated failed logins for the same email trip the rate limiter -- eventually 429, not endless 401s") {
            testApplication {
                application { module() }

                val statuses =
                    (1..10).map {
                        client
                            .post("/api/auth/login") {
                                setBody("""{"email":"rate-limit-target@example.org","password":"wrong-$it"}""")
                            }.status
                    }
                statuses shouldContain HttpStatusCode.TooManyRequests
            }
        }

        test("logout revokes the session -- a previously valid cookie is rejected afterward") {
            testApplication {
                application {
                    module()
                    routing {
                        get("/test/whoami") {
                            val current = resolveCurrentMember(call)
                            call.respondText(current.memberId.toString())
                        }
                    }
                }

                val loginResponse =
                    client.post("/api/auth/login") {
                        setBody("""{"email":"$ADMIN_EMAIL","password":"${DevSeedData.DEMO_PASSWORD}"}""")
                    }
                val rawToken = rawTokenFromSetCookie(requireNotNull(loginResponse.headers[HttpHeaders.SetCookie]))

                client.get("/test/whoami") { header(HttpHeaders.Cookie, "lapis_session=$rawToken") }.status shouldBe
                    HttpStatusCode.OK

                val logoutResponse = client.post("/api/auth/logout") { header(HttpHeaders.Cookie, "lapis_session=$rawToken") }
                logoutResponse.status shouldBe HttpStatusCode.NoContent

                client.get("/test/whoami") { header(HttpHeaders.Cookie, "lapis_session=$rawToken") }.status shouldBe
                    HttpStatusCode.Unauthorized
            }
        }

        test("logout without any cookie still succeeds (idempotent, never leaks whether a session existed)") {
            testApplication {
                application { module() }

                client.post("/api/auth/logout").status shouldBe HttpStatusCode.NoContent
                client.post("/api/auth/logout") { header(HttpHeaders.Cookie, "lapis_session=never-issued-token") }.status shouldBe
                    HttpStatusCode.NoContent
            }
        }

        test("a stray/attacker-supplied lapis_session cookie sent alongside login is never reused as the new session's identity") {
            testApplication {
                application { module() }

                val loginResponse =
                    client.post("/api/auth/login") {
                        header(HttpHeaders.Cookie, "lapis_session=attacker-chosen-fixed-token")
                        setBody("""{"email":"$ADMIN_EMAIL","password":"${DevSeedData.DEMO_PASSWORD}"}""")
                    }
                loginResponse.status shouldBe HttpStatusCode.OK
                val issuedToken = rawTokenFromSetCookie(requireNotNull(loginResponse.headers[HttpHeaders.SetCookie]))
                issuedToken shouldNotBe "attacker-chosen-fixed-token"
            }
        }
    })

private fun cleanUpAuthRoutesTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        SessionTable.deleteWhere { SessionTable.memberId inList memberIds }
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}
