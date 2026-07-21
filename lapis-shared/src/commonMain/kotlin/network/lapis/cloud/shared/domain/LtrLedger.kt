package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * V0.6.1 (Internes Crowdfunding) LTR-Ledger entry kinds -- see
 * `08-ltr-balance.kuml.kts` file header for the full rationale (append-only, signed-amount,
 * single-entry ledger, deliberately NOT the double-entry Journal/Posting shape
 * `10-accounting.kuml.kts` uses for EUR-Buchführung). [MINT]/[PROJECT_STAKE]/[VOTE_STAKE] are the
 * three kinds this wave actually writes; [PROJECT_STAKE_RELEASE] is reserved, defined-but-unused
 * (no release path exists yet for a bound stake -- same limitation applies to [VOTE_STAKE], see
 * that literal's own KDoc). Additively extensible -- cheap to extend, expensive to reorder
 * (literal order is pinned by `LtrLedgerSchemaDriftTest`).
 */
@Serializable
enum class LtrLedgerEntryType {
    MINT,
    PROJECT_STAKE,
    PROJECT_STAKE_RELEASE,

    /**
     * [network.lapis.cloud.server.rpc.GovernanceService.castVoteBallot] binding LTR into a
     * Vote's Vickrey-auction stake (security fix, post-V0.6.1: the original wave documented this
     * write in [network.lapis.cloud.server.economy.LtrBalanceProvider]'s KDoc but never actually
     * implemented it, leaving `freeBalance` blind to open vote stakes -- see that KDoc). Bound,
     * not burned, same as [PROJECT_STAKE]; no release path exists yet for a recast-down,
     * `closeVote` settlement below the full stake, or `abortVote` -- reserved for a later wave,
     * analogous to [PROJECT_STAKE_RELEASE].
     */
    VOTE_STAKE,
}

/**
 * Polymorphic target-table discriminator for [LtrLedgerEntryDto.referenceId] -- mirrors
 * [AuditEntityType]'s own role for `audit_log_entry.entity_id`. [CROWDFUNDING_PROJECT] is this
 * wave's original literal, [VOTE] the security-fix addition for [LtrLedgerEntryType.VOTE_STAKE]
 * (see that literal's KDoc); additively extended by later waves (auction lots, peer members, ...).
 */
@Serializable
enum class LtrLedgerReferenceType { CROWDFUNDING_PROJECT, VOTE }

/**
 * One row of the append-only LTR ledger. [amountLtr] is signed: positive for a credit (e.g.
 * [LtrLedgerEntryType.MINT]), negative for a debit (e.g. [LtrLedgerEntryType.PROJECT_STAKE]) --
 * see `network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider` for the
 * `SUM(amountLtr)` balance derivation this convention supports.
 */
@Serializable
data class LtrLedgerEntryDto(
    val id: String,
    val memberId: String,
    val memberDisplayName: String,
    val entryType: LtrLedgerEntryType,
    val amountLtr: Decimal,
    val referenceType: LtrLedgerReferenceType?,
    val referenceId: String?,
    val note: String?,
    val createdById: String?,
    val createdByDisplayName: String?,
    val createdAt: LocalDateTime,
)

/** A member's current free LTR balance -- always derived (`SUM(amountLtr)`), never a cached/persisted column. */
@Serializable
data class LtrLedgerBalanceDto(
    val memberId: String,
    val freeBalanceLtr: Decimal,
)

/** Role: TREASURER/BOARD/ADMIN. [amountLtr] must be strictly positive -- a MINT is always a credit. */
@Serializable
data class MintLtrInput(
    val memberId: String,
    val amountLtr: Decimal,
    val note: String? = null,
)
