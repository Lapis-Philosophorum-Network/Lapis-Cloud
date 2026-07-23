package network.lapis.cloud.client

import dev.kilua.rpc.getService
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto

/**
 * V0.7.3 Basis-Mehrseiten-UI: real session-cookie auth replaces the V0.1.5 "acting as" member
 * switcher this object's own KDoc always pointed towards ("Swapping this out for real
 * session-based auth later only touches this file") -- see [AuthHttp] for the login/logout HTTP
 * calls that establish the `lapis_session` cookie, and [rpcService] KDoc for why no client-side
 * Kilua RPC configuration (header injection, request filters) is needed any more.
 */
object AppState {
    /** The current session, or `null` if unauthenticated. */
    var session: SessionInfoDto? = null
        private set

    /** Runs after every [setSession] call -- wired once by `App.start()` to re-render the navbar. */
    var onSessionChange: () -> Unit = {}

    val isAuthenticated: Boolean
        get() = session != null

    fun hasRole(vararg roles: AccountRole): Boolean = session?.role in roles

    /** The single place [session] is ever mutated -- guarantees [onSessionChange] always fires. */
    fun setSession(newSession: SessionInfoDto?) {
        session = newSession
        onSessionChange()
    }
}

/**
 * Plain [getService] call, no `requestFilter`/header injection -- every Kilua RPC call already
 * sends the `lapis_session` cookie automatically (`credentials: "include"` is baked into Kilua
 * RPC's own `CallAgent.getRequestInit`, verified against the pinned kilua-rpc-core-js 0.0.45
 * artifact). The old `X-Member-Id` trusted-header fallback this file used to inject has no effect
 * against a real (non-H2-in-memory) deployment in any case -- see
 * `network.lapis.cloud.server.security.RequestContext.resolveCurrentMember` KDoc.
 */
inline fun <reified T : Any> rpcService(): T = getService()

/**
 * Runs [block]; on failure, shows an error toast and returns `null` instead of propagating.
 * Session-expiry-shaped failures (message matches
 * `network.lapis.cloud.server.security.UnauthenticatedException`'s default message, or a plain
 * "Unauthorized"/401 the generic Kilua RPC exception path can also surface -- see [AppState] KDoc)
 * additionally clear [AppState.session] and route back to the login screen, so a mid-session
 * expiry returns the user cleanly to login instead of leaving a broken, half-loaded screen behind.
 * Every screen's data-loading/mutating coroutine goes through this wrapper -- see the V0.7.3 plan
 * "Mid-session 401s".
 */
suspend fun <T> guarded(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val message = e.message?.takeIf { it.isNotBlank() } ?: "Unbekannter Fehler"
        if (message.contains("Missing, invalid, or expired session") || message.contains("Unauthorized")) {
            AppState.setSession(null)
            notifyError("Sitzung abgelaufen -- bitte erneut anmelden.")
            navigateTo(Routes.LOGIN)
        } else {
            notifyError(message)
        }
        null
    }
