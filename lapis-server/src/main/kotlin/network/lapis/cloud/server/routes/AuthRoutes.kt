package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.util.date.GMTDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.SESSION_COOKIE_NAME
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private val logger = KotlinLogging.logger {}

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val memberId: String,
    val displayName: String,
    val role: AccountRole,
)

/**
 * Public login/logout HTTP endpoints (V0.7.1 Authentifizierung) — deliberately outside the Kilua
 * RPC layer, mounted BEFORE any authentication is required, the exact opposite of every RPC
 * service (which resolves the caller via `resolveCurrentMember` as its first step). See
 * [network.lapis.cloud.shared.rpc.IAuthService] KDoc for the full rationale.
 *
 * **Account-enumeration hardening**: an unknown email, a known email with the wrong password, AND
 * a known email whose `account.password_hash` is still `NULL` (a seeded-but-never-logged-in
 * account) all produce the IDENTICAL `401 Unauthorized` response — same status code, same body
 * text (`"Invalid credentials"`), computed via [PasswordHasher.verify]'s own timing-uniform
 * `storedHash == null` handling (see that function's KDoc). A caller can never learn from this
 * endpoint's response whether a given email is registered at all.
 *
 * **Session-fixation**: every successful login always calls [SessionStore.createSession] for a
 * BRAND-NEW token — an existing `lapis_session` cookie value the client happened to send along is
 * never read or reused as the new session's identity; the response simply overwrites it.
 *
 * **Cookie transport**: `HttpOnly` (no JS access, XSS-hardening) + `Secure` (gated by
 * [cookieSecure] — `true` by default, disable ONLY for local plaintext-HTTP dev) + `SameSite=Strict`
 * (CSRF-hardening; Ktor's [Cookie] has no dedicated field for this attribute, so it travels via
 * [Cookie.extensions] — see `io.ktor.http.Cookie` source). `SameSite=Strict` is this wave's
 * INTERIM CSRF control — a real double-submit CSRF token is deferred to the V0.7.3 UI wave (it
 * needs client-side changes this backend-only wave does not make); modern browsers already refuse
 * to attach a `SameSite=Strict` cookie to a cross-site request, which covers the classic CSRF
 * attack shape (forged cross-origin form/fetch) even without a token.
 */
fun Route.registerAuthRoutes(
    rateLimiter: LoginRateLimiter,
    cookieSecure: Boolean,
) {
    post("/api/auth/login") {
        val request =
            runCatching { Json.decodeFromString(LoginRequest.serializer(), call.receiveText()) }.getOrNull()
        if (request == null || request.email.isBlank() || request.password.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "email and password are required")
            return@post
        }

        val normalizedEmail = request.email.trim().lowercase()
        val emailKey = "email:$normalizedEmail"
        val ipKey = "ip:${call.request.local.remoteHost}"
        if (!rateLimiter.checkAllowed(emailKey) || !rateLimiter.checkAllowed(ipKey)) {
            call.respondText("Too many failed login attempts -- try again later", status = HttpStatusCode.TooManyRequests)
            return@post
        }

        val accountRow =
            transaction {
                (MemberTable innerJoin AccountTable)
                    .selectAll()
                    .where { MemberTable.email.lowerCase() eq normalizedEmail }
                    .singleOrNull()
            }

        // See class KDoc "Account-enumeration hardening" -- PasswordHasher.verify always runs a
        // real bcrypt comparison (against a fixed dummy hash if accountRow/passwordHash is null),
        // never short-circuits, so every rejection reason below takes the same code path.
        val passwordOk = PasswordHasher.verify(request.password, accountRow?.get(AccountTable.passwordHash))
        if (accountRow == null || !passwordOk) {
            rateLimiter.recordFailure(emailKey)
            rateLimiter.recordFailure(ipKey)
            call.respondText("Invalid credentials", status = HttpStatusCode.Unauthorized)
            return@post
        }

        rateLimiter.reset(emailKey)
        rateLimiter.reset(ipKey)

        val memberId = accountRow[MemberTable.id]
        val issued = SessionStore.createSession(memberId)
        call.response.cookies.append(
            Cookie(
                name = SESSION_COOKIE_NAME,
                value = issued.rawToken,
                encoding = CookieEncoding.URI_ENCODING,
                maxAge = SessionStore.SESSION_TTL.inWholeSeconds.toInt(),
                path = "/",
                secure = cookieSecure,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
        call.respond(
            LoginResponse(
                memberId = memberId.toString(),
                displayName = accountRow[MemberTable.displayName],
                role = accountRow[AccountTable.role],
            ),
        )
    }

    post("/api/auth/logout") {
        val rawToken = call.request.cookies[SESSION_COOKIE_NAME]
        if (rawToken != null) SessionStore.revoke(rawToken)
        // Idempotent by design -- an absent/unknown/already-revoked cookie still yields 204, same
        // as a successful logout. See SessionStore.revoke KDoc.
        call.response.cookies.append(
            Cookie(
                name = SESSION_COOKIE_NAME,
                value = "",
                path = "/",
                secure = cookieSecure,
                httpOnly = true,
                maxAge = 0,
                expires = GMTDate.START,
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
        call.respond(HttpStatusCode.NoContent)
    }
}
