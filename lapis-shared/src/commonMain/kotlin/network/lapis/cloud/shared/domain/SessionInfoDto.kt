package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * "Whoami" projection of the caller's currently resolved session (V0.7.1 Authentifizierung) --
 * see [network.lapis.cloud.shared.rpc.IAuthService.getSessionInfo]. Deliberately minimal: just
 * enough for a client to display "logged in as X (role)" and know when its session will expire --
 * NOT a full [MemberDto] (no email/address/beneficial-owner fields here; use
 * [network.lapis.cloud.shared.rpc.IMemberService.getCurrentMember] for that).
 */
@Serializable
data class SessionInfoDto(
    val memberId: String,
    val displayName: String,
    val role: AccountRole,
    val expiresAt: LocalDateTime,
)
