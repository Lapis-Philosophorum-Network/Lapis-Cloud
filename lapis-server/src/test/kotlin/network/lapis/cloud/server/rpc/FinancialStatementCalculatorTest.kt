package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.LedgerAccountType
import java.math.BigDecimal

/**
 * Pure tests of [FinancialStatementCalculator] -- no DB access anywhere in this file, same
 * rationale as [GeneralLedgerCalculatorTest]/[JournalEntryBalanceTest].
 */
class FinancialStatementCalculatorTest :
    FunSpec({
        fun balance(
            accountNumber: String,
            type: LedgerAccountType,
            amount: String,
            accountClass: Int = 0,
            id: String = "id-$accountNumber",
        ) = FinancialStatementCalculator.AccountBalance(
            id = id,
            accountNumber = accountNumber,
            name = "Konto $accountNumber",
            type = type,
            accountClass = accountClass,
            netBalance = BigDecimal(amount),
        )

        val from = LocalDate(2026, 1, 1)
        val to = LocalDate(2026, 12, 31)

        // ---- IncomeStatement (GuV) ------------------------------------------------------------

        test("GuV: income minus expense equals result") {
            val balances =
                listOf(
                    balance("4000", LedgerAccountType.INCOME, "500.00", accountClass = 4),
                    balance("6000", LedgerAccountType.EXPENSE, "300.00", accountClass = 6),
                )
            val result = FinancialStatementCalculator.incomeStatement(balances, from, to)
            result.totalIncome.compareTo(BigDecimal("500.00")) shouldBe 0
            result.totalExpense.compareTo(BigDecimal("300.00")) shouldBe 0
            result.result.compareTo(BigDecimal("200.00")) shouldBe 0
            result.from shouldBe from
            result.to shouldBe to
        }

        test("GuV: only income, no expense") {
            val balances = listOf(balance("4000", LedgerAccountType.INCOME, "150.00"))
            val result = FinancialStatementCalculator.incomeStatement(balances, from, to)
            result.totalExpense.compareTo(BigDecimal.ZERO) shouldBe 0
            result.result.compareTo(BigDecimal("150.00")) shouldBe 0
        }

        test("GuV: only expense, no income -- negative result (Jahresfehlbetrag)") {
            val balances = listOf(balance("6000", LedgerAccountType.EXPENSE, "80.00"))
            val result = FinancialStatementCalculator.incomeStatement(balances, from, to)
            result.totalIncome.compareTo(BigDecimal.ZERO) shouldBe 0
            result.result.compareTo(BigDecimal("-80.00")) shouldBe 0
        }

        test("GuV: empty balances yield all-zero result") {
            val result = FinancialStatementCalculator.incomeStatement(emptyList(), from, to)
            result.totalIncome.compareTo(BigDecimal.ZERO) shouldBe 0
            result.totalExpense.compareTo(BigDecimal.ZERO) shouldBe 0
            result.result.compareTo(BigDecimal.ZERO) shouldBe 0
            result.incomeLines shouldBe emptyList()
            result.expenseLines shouldBe emptyList()
        }

        test("GuV: income and expense lines are sorted by accountNumber") {
            val balances =
                listOf(
                    balance("4200", LedgerAccountType.INCOME, "10.00"),
                    balance("4000", LedgerAccountType.INCOME, "20.00"),
                    balance("6310", LedgerAccountType.EXPENSE, "5.00"),
                    balance("6000", LedgerAccountType.EXPENSE, "7.00"),
                )
            val result = FinancialStatementCalculator.incomeStatement(balances, from, to)
            result.incomeLines.map { it.accountNumber } shouldBe listOf("4000", "4200")
            result.expenseLines.map { it.accountNumber } shouldBe listOf("6000", "6310")
        }

        test("GuV: scale/compareTo correctness -- 100.00 vs 100.0 must not break sums") {
            val balances =
                listOf(
                    balance("4000", LedgerAccountType.INCOME, "100.00"),
                    balance("4001", LedgerAccountType.INCOME, "100.0"),
                )
            val result = FinancialStatementCalculator.incomeStatement(balances, from, to)
            result.totalIncome.compareTo(BigDecimal("200.00")) shouldBe 0
        }

        test("GuV: zero-movement accounts are omitted from the line lists") {
            val balances =
                listOf(
                    balance("4000", LedgerAccountType.INCOME, "0.00"),
                    balance("4001", LedgerAccountType.INCOME, "10.00"),
                )
            val result = FinancialStatementCalculator.incomeStatement(balances, from, to)
            result.incomeLines.map { it.accountNumber } shouldBe listOf("4001")
        }

        // ---- BalanceSheet (Bilanz) -------------------------------------------------------------

        test("Bilanz: ASSET is Aktiva; LIABILITY/EQUITY are Passiva; balances via accumulated result") {
            val balances =
                listOf(
                    balance("0920", LedgerAccountType.ASSET, "1000.00"),
                    balance("1600", LedgerAccountType.LIABILITY, "200.00"),
                    balance("2900", LedgerAccountType.EQUITY, "300.00"),
                    balance("4000", LedgerAccountType.INCOME, "800.00"),
                    balance("6000", LedgerAccountType.EXPENSE, "300.00"),
                )
            val asOf = LocalDate(2026, 12, 31)
            val sheet = FinancialStatementCalculator.balanceSheet(balances, asOf)

            sheet.asOf shouldBe asOf
            sheet.totalAssets.compareTo(BigDecimal("1000.00")) shouldBe 0
            sheet.totalLiabilities.compareTo(BigDecimal("200.00")) shouldBe 0
            sheet.bookedEquity.compareTo(BigDecimal("300.00")) shouldBe 0
            // accumulatedResult = 800 income - 300 expense = 500
            sheet.accumulatedResult.compareTo(BigDecimal("500.00")) shouldBe 0
            // totalEquityAndLiabilities = 200 + 300 + 500 = 1000 == totalAssets
            sheet.totalEquityAndLiabilities.compareTo(BigDecimal("1000.00")) shouldBe 0
            sheet.balanced shouldBe true
        }

        test("Bilanz: net loss (negative accumulatedResult) still balances") {
            val balances =
                listOf(
                    balance("0920", LedgerAccountType.ASSET, "700.00"),
                    balance("1600", LedgerAccountType.LIABILITY, "0.00"),
                    balance("2900", LedgerAccountType.EQUITY, "1000.00"),
                    balance("4000", LedgerAccountType.INCOME, "100.00"),
                    balance("6000", LedgerAccountType.EXPENSE, "400.00"),
                )
            val sheet = FinancialStatementCalculator.balanceSheet(balances, LocalDate(2026, 12, 31))
            // accumulatedResult = 100 - 400 = -300
            sheet.accumulatedResult.compareTo(BigDecimal("-300.00")) shouldBe 0
            // totalEquityAndLiabilities = 0 + 1000 + (-300) = 700 == totalAssets
            sheet.totalEquityAndLiabilities.compareTo(BigDecimal("700.00")) shouldBe 0
            sheet.balanced shouldBe true
        }

        test("Bilanz: empty balances -- all zero, still balanced") {
            val sheet = FinancialStatementCalculator.balanceSheet(emptyList(), LocalDate(2026, 12, 31))
            sheet.totalAssets.compareTo(BigDecimal.ZERO) shouldBe 0
            sheet.totalLiabilities.compareTo(BigDecimal.ZERO) shouldBe 0
            sheet.bookedEquity.compareTo(BigDecimal.ZERO) shouldBe 0
            sheet.accumulatedResult.compareTo(BigDecimal.ZERO) shouldBe 0
            sheet.totalEquityAndLiabilities.compareTo(BigDecimal.ZERO) shouldBe 0
            sheet.balanced shouldBe true
        }
    })
