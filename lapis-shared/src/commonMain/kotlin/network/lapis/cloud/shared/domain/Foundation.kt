package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Foundation stub (see CLAUDE.md "Vorab-Befund"): V0.1.2-V0.1.4 (Mitglieder-Stammdaten,
 * Beitritts-/Austrittsworkflow, Auth/Session) do not exist yet. [MemberStatus] and
 * [AccountRole] are modelled here only as granularly as V0.1.5 (Beitraege, Dokumente,
 * Kommunikation) needs them as foreign keys / authorization checks. A real member
 * management wave replaces this stub without breaking the foreign keys defined against it.
 *
 * V0.7.2 Beitritts-/Registrierungs-Workflow delivers the actual admission/exit lifecycle this
 * stub always anticipated (see `network.lapis.cloud.shared.rpc.IRegistrationService`):
 * [ANTRAG] -> [AKTIV] (board-approved) or [ANTRAG] -> [ABGELEHNT] (board-rejected, retained with
 * a reason -- never silently reused as [AUSGETRETEN], which means something structurally
 * different: "left after having been admitted"), and [AKTIV] -> [AUSGETRETEN]
 * (member-initiated self-service exit, no board approval needed -- "Eintritt und Austritt sind
 * ausschliesslich Willenserklaerungen der Vertragspartner"). [GAST] is deliberately UNCHANGED and
 * unused by this wave -- it is a separate, larger, still-unbuilt pre-membership guest-identity
 * concept (see the V0.6.4 Politiker-Profile guest-rating-basket scope cut), not a target for the
 * Austritt transition.
 */
@Serializable
enum class MemberStatus { ANTRAG, AKTIV, GAST, AUSGETRETEN, ABGELEHNT }

@Serializable
enum class AccountRole { MEMBER, BOARD, TREASURER, ADMIN }

/**
 * [street]/[postalCode]/[city]/[country] (V0.4.1) are a minimal, single, nullable postal address
 * -- needed by the Serienbrief/PDF engine (Beitragsrechnung/Spendenbescheinigung/Einladung all
 * mail-merge a member's postal address) and reused as-is by V0.4.2's later postal (Letterxpress)
 * dispatch. All default to `null` so existing call sites stay source-compatible. Not every member
 * has provided an address yet, and an email-only member may never need one.
 *
 * [dateOfBirth]/[nationality] (V0.5.2) are the two beneficial-owner fields a Transparenzregister
 * (§20 GwG) entry requires beyond name/residence (already covered by the address fields above) --
 * see `network.lapis.cloud.shared.domain.BeneficialOwnerDataGapDto`. Both default to `null` for the
 * same source-compatibility reason as the address fields; not every member is a board member.
 *
 * [reviewedById]/[reviewedAt]/[rejectionReason] (V0.7.2) are the board's own admission-decision
 * metadata -- set by `IRegistrationService.approveApplication`/`rejectApplication` (same shape as
 * `CrowdfundingProjectDto`'s own reviewedBy/reviewedAt/rejectionReason fields). All three stay
 * `null` for a member who was created directly (`IRegistrationService.createMemberDirect`, no
 * approval step) or who has not yet been decided ([MemberStatus.ANTRAG]).
 */
@Serializable
data class MemberDto(
    val id: String,
    val displayName: String,
    val email: String,
    val status: MemberStatus,
    val joinedAt: LocalDate,
    val role: AccountRole,
    val street: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val country: String? = null,
    val dateOfBirth: LocalDate? = null,
    val nationality: String? = null,
    val reviewedById: String? = null,
    val reviewedAt: LocalDateTime? = null,
    val rejectionReason: String? = null,
)

/**
 * Reduced projection of [MemberDto] for the unauthenticated "current member" picker
 * (see [network.lapis.cloud.shared.rpc.IMemberService.listMembers]). Deliberately excludes
 * [MemberDto.email] and [MemberDto.role] — those are PII / authorization-relevant fields that
 * must not be readable by a caller who hasn't authenticated yet.
 */
@Serializable
data class MemberSummaryDto(
    val id: String,
    val displayName: String,
)
