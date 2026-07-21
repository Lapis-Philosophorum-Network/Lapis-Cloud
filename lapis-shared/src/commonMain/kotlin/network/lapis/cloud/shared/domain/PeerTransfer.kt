package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Self-reported legal characterization of a [PeerTransferInput]/[ArbitrationTransferInput] --
 * "aktuelle Annahme, vor Produktiveinsatz zu verifizieren", same disclaimer class
 * [network.lapis.cloud.server.rpc.PartyDonationComplianceCalculator]'s own KDoc carries. The
 * platform performs NO automatic steuerrechtliche Einordnung: this is exactly, and only, the
 * sender's own declared choice, stored verbatim.
 */
@Serializable
enum class PeerTransferCharacterization { SCHENKUNG, HONORAR, PRIVATVERKAUF, SONSTIGES }

/**
 * Role: MEMBER+ (any authenticated member). Always debits the CALLING member's own account --
 * there is no "on behalf of" sender field here, see [ArbitrationTransferInput] for the separate,
 * privileged variant. [recipientMemberId] must be an existing member -- Gast-Empfaenger is a
 * later, V0.7-dependent extension (Gast/guest identity does not exist in this codebase yet), not
 * simulated here. Mindesttransfersumme is enforced server-side, not modelled as a client-facing
 * constant on this DTO.
 */
@Serializable
data class PeerTransferInput(
    val recipientMemberId: String,
    val amountLtr: Decimal,
    val characterization: PeerTransferCharacterization,
    val purpose: String? = null,
)

/**
 * Role: TREASURER/BOARD/ADMIN only. The privileged correction path for a Schiedsanordnung (see
 * `18-peer-transfer.kuml.kts` file header "no storno/revert" and the concept document's
 * "Korrektur ausschliesslich ueber Schiedsverfahren" section) -- transfers between any two
 * EXISTING members, not limited to the caller's own account. [purpose] is a MANDATORY field
 * (unlike [PeerTransferInput.purpose]) -- the Schiedsanordnungs-Referenz (e.g. "Schiedsanordnung
 * Az. xyz") must always be documented; the server additionally rejects a blank string at runtime,
 * not only at the type level.
 */
@Serializable
data class ArbitrationTransferInput(
    val senderMemberId: String,
    val recipientMemberId: String,
    val amountLtr: Decimal,
    val characterization: PeerTransferCharacterization,
    val purpose: String,
)

/** Result of a completed transfer -- [initiatedById]/[initiatedByDisplayName] are null for a self-initiated [PeerTransferInput] transfer, set for an [ArbitrationTransferInput] correction. */
@Serializable
data class PeerTransferResultDto(
    val transferId: String,
    val senderMemberId: String,
    val senderDisplayName: String,
    val recipientMemberId: String,
    val recipientDisplayName: String,
    val amountLtr: Decimal,
    val characterization: PeerTransferCharacterization,
    val purpose: String?,
    val initiatedById: String?,
    val initiatedByDisplayName: String?,
    val outEntryId: String,
    val inEntryId: String,
    val createdAt: LocalDateTime,
)
