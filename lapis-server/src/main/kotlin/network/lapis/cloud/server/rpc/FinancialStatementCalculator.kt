package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.BalanceSheetDto
import network.lapis.cloud.shared.domain.IncomeStatementDto
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.StatementLineDto
import java.math.BigDecimal

/**
 * Pure GuV (income statement) / Bilanz (balance sheet) derivation, extracted so it is unit-testable
 * without a database -- same "pure logic extracted to a sibling file" idiom as
 * [GeneralLedgerCalculator]/[JournalEntryBalance].
 *
 * Every [AccountBalance.netBalance] passed in is assumed already signed by that account's
 * normal-balance side (see [GeneralLedgerCalculator.normalBalanceSideOf]) -- this object does not
 * re-derive the debit/credit-normal mapping, it only groups/sums/reconciles.
 *
 * **BigDecimal pitfall, deliberately guarded against** (same class of bug as
 * [JournalEntryBalance]'s KDoc): every sum and the [BalanceSheetDto.balanced] flag are compared via
 * `BigDecimal.compareTo`, never `equals`/`==`.
 */
internal object FinancialStatementCalculator {
    private val ZERO = BigDecimal.ZERO

    /**
     * One ledger account's net balance over the caller-chosen scope, already signed by
     * [type]'s normal-balance side -- the caller (`AccountingService.loadAccountBalances`) computes
     * this from summed `PostingTable` rows joined to `JournalEntryTable`/`LedgerAccountTable`.
     */
    data class AccountBalance(
        val id: String,
        val accountNumber: String,
        val name: String,
        val type: LedgerAccountType,
        val accountClass: Int,
        val netBalance: BigDecimal,
    )

    /**
     * GuV over `[from, to]`: filters [balances] to `INCOME`/`EXPENSE`, sorts each group by
     * [AccountBalance.accountNumber], sums, and computes `result = totalIncome - totalExpense`.
     * Accounts with a zero net balance in scope are omitted (they carry no line-item information).
     */
    fun incomeStatement(
        balances: List<AccountBalance>,
        from: LocalDate?,
        to: LocalDate,
    ): IncomeStatementDto {
        val incomeLines = linesOf(balances, LedgerAccountType.INCOME)
        val expenseLines = linesOf(balances, LedgerAccountType.EXPENSE)
        val totalIncome = incomeLines.sumBalances()
        val totalExpense = expenseLines.sumBalances()
        return IncomeStatementDto(
            from = from,
            to = to,
            incomeLines = incomeLines,
            expenseLines = expenseLines,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            result = totalIncome - totalExpense,
        )
    }

    /**
     * Bilanz as of [asOf]: groups [balances] into `ASSET` (Aktiva) versus `LIABILITY`/`EQUITY`
     * (Passiva), and derives [BalanceSheetDto.accumulatedResult] as
     * Σ(`INCOME` netBalance) − Σ(`EXPENSE` netBalance) from that *same* (cumulative-through-[asOf])
     * list -- see class/file KDoc for why this derived equity line is required for [balanced] to
     * ever be `true`. Accounts with a zero net balance in scope are omitted.
     */
    fun balanceSheet(
        balances: List<AccountBalance>,
        asOf: LocalDate,
    ): BalanceSheetDto {
        val assetLines = linesOf(balances, LedgerAccountType.ASSET)
        val liabilityLines = linesOf(balances, LedgerAccountType.LIABILITY)
        val equityLines = linesOf(balances, LedgerAccountType.EQUITY)

        val totalAssets = assetLines.sumBalances()
        val totalLiabilities = liabilityLines.sumBalances()
        val bookedEquity = equityLines.sumBalances()

        val totalIncome = balances.filter { it.type == LedgerAccountType.INCOME }.sumNetBalances()
        val totalExpense = balances.filter { it.type == LedgerAccountType.EXPENSE }.sumNetBalances()
        val accumulatedResult = totalIncome - totalExpense

        val totalEquityAndLiabilities = totalLiabilities + bookedEquity + accumulatedResult

        return BalanceSheetDto(
            asOf = asOf,
            assetLines = assetLines,
            liabilityLines = liabilityLines,
            equityLines = equityLines,
            totalAssets = totalAssets,
            totalLiabilities = totalLiabilities,
            bookedEquity = bookedEquity,
            accumulatedResult = accumulatedResult,
            totalEquityAndLiabilities = totalEquityAndLiabilities,
            balanced = totalAssets.compareTo(totalEquityAndLiabilities) == 0,
        )
    }

    private fun linesOf(
        balances: List<AccountBalance>,
        type: LedgerAccountType,
    ): List<StatementLineDto> =
        balances
            .filter { it.type == type && it.netBalance.compareTo(ZERO) != 0 }
            .sortedBy { it.accountNumber }
            .map {
                StatementLineDto(
                    ledgerAccountId = it.id,
                    accountNumber = it.accountNumber,
                    name = it.name,
                    type = it.type,
                    accountClass = it.accountClass,
                    balance = it.netBalance,
                )
            }

    private fun List<StatementLineDto>.sumBalances(): BigDecimal = fold(ZERO) { acc, line -> acc + line.balance }

    private fun List<AccountBalance>.sumNetBalances(): BigDecimal = fold(ZERO) { acc, balance -> acc + balance.netBalance }
}
