package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.ArbitrationTransferInput
import network.lapis.cloud.shared.domain.PeerTransferInput
import network.lapis.cloud.shared.domain.PeerTransferResultDto

/**
 * V0.6.3 direkte LTR-Peer-to-Peer-Uebertragung -- see `18-peer-transfer.kuml.kts` file header for
 * the full fachlich model. No Storno/Revert/Cancel endpoint exists here, and none is planned --
 * a completed transfer is final by design (see concept document "Unwiderruflichkeit"); the only
 * correction path is [executeArbitrationTransfer], a REGULAR transfer in the opposite direction.
 *
 * **Scope-cut (Gast-Empfaenger)**: [PeerTransferInput.recipientMemberId]/
 * [ArbitrationTransferInput.recipientMemberId] must be an existing member -- a Gast (guest)
 * recipient is a later, V0.7-dependent extension (no Gast identity model exists in this codebase
 * yet), never simulated or improvised here.
 *
 * **Scope-cut (visibility)**: the concept document envisions full public visibility under a
 * pseudonym; this codebase has no pseudonym-display layer at all. Reads are therefore NOT exposed
 * here -- every transfer's two `ltr_ledger_entry` rows (PEER_TRANSFER_OUT/PEER_TRANSFER_IN)
 * already surface through the existing `ILtrLedgerService.listMyEntries`/`listMemberEntries`
 * history (own entries for every member, all entries for TREASURER/BOARD/ADMIN) -- reused as-is,
 * deliberately not duplicated into a second, parallel read path here. Full public pseudonymous
 * visibility remains a later extension.
 *
 * **Scope-cut (AML)**: no anti-money-laundering monitoring is performed or planned -- LTR has no
 * automatic Fiat-Konvertierbarkeit and is therefore unsuited as a "Waschvehikel" (see concept
 * document).
 */
@RpcService
interface IPeerTransferService {
    /**
     * Role: MEMBER+. Always debits the caller's own account -- see [PeerTransferInput] KDoc.
     * Mindesttransfersumme 0.01 LTR (Spamschutz). No platform provision -- the full amount always
     * reaches the recipient.
     */
    suspend fun transferLtr(input: PeerTransferInput): PeerTransferResultDto

    /**
     * Role: TREASURER/BOARD/ADMIN. Transfers between any two EXISTING members (not limited to the
     * caller's own account) -- the privileged Schiedsverfahren correction path, see
     * [ArbitrationTransferInput] KDoc. A deliberately separate RPC method, never a variant of
     * [transferLtr] with an optional "on behalf of" parameter, so the stricter authorization here
     * can never accidentally leak into the self-service path.
     */
    suspend fun executeArbitrationTransfer(input: ArbitrationTransferInput): PeerTransferResultDto
}
