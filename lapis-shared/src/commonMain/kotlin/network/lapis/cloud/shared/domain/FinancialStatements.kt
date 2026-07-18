package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/*
 * V0.3.2 reporting DTOs -- Gewinn- und Verlustrechnung (GuV / income statement), Bilanz (balance
 * sheet) and Jahresabschluss (annual financial statement), all derived purely from the existing
 * POSTED postings via network.lapis.cloud.server.rpc.FinancialStatementCalculator. None of these
 * are persisted -- same "derived reporting DTO" status as GeneralLedgerDto/GeneralLedgerLineDto,
 * which are likewise absent from the 10-accounting.kuml.kts model.
 *
 * No opening-balance/carryforward support exists yet (see GeneralLedgerDto's KDoc and the
 * .kuml.kts file header's "Explicit non-goals": fiscal-year close/Saldenvortrag is out of scope).
 * The deliberate consequence for BalanceSheetDto: because INCOME/EXPENSE are never closed to
 * EQUITY, a Bilanz only balances (Aktiva == Passiva) if the accumulated result
 * (ΣINCOME − ΣEXPENSE through the cutoff date) is shown as its own derived equity line -- see
 * BalanceSheetDto.accumulatedResult.
 */

/**
 * One net-balance line of a statement, already signed by the account's normal-balance side (see
 * [LedgerAccountType] KDoc / `GeneralLedgerCalculator.normalBalanceSideOf`). Accounts with no
 * in-scope postings in the requested period are omitted -- these lines are purely derived from the
 * journal, not a complete chart-of-accounts listing.
 */
@Serializable
data class StatementLineDto(
    val ledgerAccountId: String,
    val accountNumber: String,
    val name: String,
    val type: LedgerAccountType,
    val accountClass: Int,
    val balance: Decimal,
)

/**
 * Gewinn- und Verlustrechnung (GuV): the flow of `INCOME`/`EXPENSE` postings over `[from, to]`
 * (both inclusive; `from == null` means "since inception"). [result] = [totalIncome] −
 * [totalExpense]; a negative [result] is a Jahresfehlbetrag (net loss).
 */
@Serializable
data class IncomeStatementDto(
    val from: LocalDate?,
    val to: LocalDate,
    val incomeLines: List<StatementLineDto>,
    val expenseLines: List<StatementLineDto>,
    val totalIncome: Decimal,
    val totalExpense: Decimal,
    val result: Decimal,
)

/**
 * Bilanz (balance sheet) as of [asOf], computed as the *cumulative* stock of `POSTED` postings
 * since inception through [asOf] -- deliberately NOT windowed to a fiscal year, because there is no
 * Saldenvortrag/opening-balance mechanism to seed a later period with. [accumulatedResult] is the
 * cumulative Σ`INCOME` − Σ`EXPENSE` through [asOf] -- since `INCOME`/`EXPENSE` are never closed to
 * `EQUITY` in this wave, this derived line is required for the sheet to balance at all.
 * [totalEquityAndLiabilities] = [totalLiabilities] + [bookedEquity] + [accumulatedResult].
 * [balanced] is `true` iff [totalAssets] equals [totalEquityAndLiabilities] (compared with
 * `BigDecimal.compareTo`, not `equals`) -- this is guaranteed by the server-enforced
 * Σdebit = Σcredit invariant and is asserted as a regression guard.
 */
@Serializable
data class BalanceSheetDto(
    val asOf: LocalDate,
    val assetLines: List<StatementLineDto>,
    val liabilityLines: List<StatementLineDto>,
    val equityLines: List<StatementLineDto>,
    val totalAssets: Decimal,
    val totalLiabilities: Decimal,
    val bookedEquity: Decimal,
    val accumulatedResult: Decimal,
    val totalEquityAndLiabilities: Decimal,
    val balanced: Boolean,
)

/**
 * One [GemeinnuetzigkeitSphere]'s slice of a [FourSphereIncomeStatementDto] -- same
 * income/expense/result shape as [IncomeStatementDto], but scoped to postings booked to [sphere]
 * only. Zero-filled (all lines/totals empty/zero) if [sphere] had no in-scope activity -- see
 * [FourSphereIncomeStatementDto.spheres] KDoc for why zero-fill (not omission) is required here.
 */
@Serializable
data class SphereResultDto(
    val sphere: GemeinnuetzigkeitSphere,
    val incomeLines: List<StatementLineDto>,
    val expenseLines: List<StatementLineDto>,
    val totalIncome: Decimal,
    val totalExpense: Decimal,
    val result: Decimal,
)

/**
 * Vier-Sphären-Ergebnisrechnung (V0.3.3): the same `INCOME`/`EXPENSE` flow over `[from, to]` as
 * [IncomeStatementDto], re-aggregated by [network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere]
 * instead of collapsed across all four. This is the payoff of the mandatory per-posting
 * `Posting.sphere` column -- see `10-accounting.kuml.kts`'s file header and
 * [network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere] KDoc for the legal background
 * (§§ 51-68 AO strict-separation requirement).
 *
 * [spheres] ALWAYS contains exactly four [SphereResultDto] entries, one per
 * [network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere] literal, in that enum's declaration
 * order -- zero-filled (not omitted) if a sphere had no activity in `[from, to]`, so callers/UI can
 * always render a fixed four-row layout without a presence check. [result] equals the sum of the
 * four [SphereResultDto.result] values, and also equals the plain [IncomeStatementDto.result] for
 * the identical `[from, to]` window over the same postings -- sphere is purely an orthogonal
 * re-aggregation, not a different scope.
 *
 * Deliberately NOT a balance sheet split, and NOT a §64 Abs.3 AO Freigrenze/taxability evaluation
 * of the wirtschaftlicher Geschäftsbetrieb surplus -- both are out of scope for V0.3.3 (see the
 * wave plan) and deferred to later waves.
 */
@Serializable
data class FourSphereIncomeStatementDto(
    val from: LocalDate?,
    val to: LocalDate,
    val spheres: List<SphereResultDto>,
    val totalIncome: Decimal,
    val totalExpense: Decimal,
    val result: Decimal,
)

/**
 * Jahresabschluss (annual financial statement) for [fiscalYear], assuming Geschäftsjahr =
 * Kalenderjahr (a non-calendar fiscal year would need a period-range overload later). Bundles the
 * GuV of `[periodStart, periodEnd]` with the Bilanz as of [periodEnd]. Exposes two deliberately
 * distinct figures rather than conflating them: [periodResult] (== `incomeStatement.result`, this
 * year's flow) and [accumulatedResult] (== `balanceSheet.accumulatedResult`, the inception-
 * cumulative stock) -- these coincide in the very first fiscal year and legitimately diverge from
 * the second year on.
 */
@Serializable
data class AnnualFinancialStatementDto(
    val fiscalYear: Int,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val incomeStatement: IncomeStatementDto,
    val balanceSheet: BalanceSheetDto,
    val periodResult: Decimal,
    val accumulatedResult: Decimal,
)
