package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.GeneralLedgerLineDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingSide
import java.math.BigDecimal

/**
 * Pure Hauptbuch (general ledger) running-balance calculation, extracted so it is unit-testable
 * without a database -- same "pure logic extracted to a sibling file" idiom as
 * [JournalEntryBalance]/[ResolutionBook]/[CommitteeEligibility].
 *
 * Normal-balance sign convention (see [LedgerAccountType] KDoc):
 *  - Debit-normal accounts ([LedgerAccountType.ASSET]/[LedgerAccountType.EXPENSE]): a
 *    [PostingSide.DEBIT] line *increases* the balance, [PostingSide.CREDIT] decreases it.
 *  - Credit-normal accounts ([LedgerAccountType.LIABILITY]/[LedgerAccountType.EQUITY]/
 *    [LedgerAccountType.INCOME]): a [PostingSide.CREDIT] line *increases* the balance,
 *    [PostingSide.DEBIT] decreases it.
 */
internal object GeneralLedgerCalculator {
    /** One chronologically-ordered posting line, before the running balance is computed. */
    data class LedgerLine(
        val journalEntryId: String,
        val entryDate: LocalDate,
        val description: String,
        val side: PostingSide,
        val amount: BigDecimal,
    )

    /**
     * Folds [lines] (assumed already sorted chronologically by the caller -- this function does
     * not re-sort) into [GeneralLedgerLineDto]s carrying a running balance, starting from
     * [opening]. [normalBalanceSide] is [PostingSide.DEBIT] for debit-normal account types
     * ([LedgerAccountType.ASSET]/[LedgerAccountType.EXPENSE]) and [PostingSide.CREDIT] for
     * credit-normal ones -- see class KDoc.
     */
    fun runningBalances(
        lines: List<LedgerLine>,
        normalBalanceSide: PostingSide,
        opening: BigDecimal = BigDecimal.ZERO,
    ): List<GeneralLedgerLineDto> {
        var running = opening
        return lines.map { line ->
            val signedAmount = if (line.side == normalBalanceSide) line.amount else line.amount.negate()
            running += signedAmount
            GeneralLedgerLineDto(
                journalEntryId = line.journalEntryId,
                entryDate = line.entryDate,
                description = line.description,
                side = line.side,
                amount = line.amount,
                runningBalance = running,
            )
        }
    }

    /** [LedgerAccountType.ASSET]/[LedgerAccountType.EXPENSE] are debit-normal, everything else is credit-normal. */
    fun normalBalanceSideOf(type: LedgerAccountType): PostingSide =
        when (type) {
            LedgerAccountType.ASSET, LedgerAccountType.EXPENSE -> PostingSide.DEBIT
            LedgerAccountType.LIABILITY, LedgerAccountType.EQUITY, LedgerAccountType.INCOME -> PostingSide.CREDIT
        }
}
