package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * SKR42 chart of accounts + double-entry bookkeeping (V0.3.1, chart swapped from SKR49 in
 * V0.3.1.1) -- see `network.lapis.cloud.server.rpc.AccountingService` KDoc for the full lifecycle
 * and `lapis-server/src/main/kuml/10-accounting.kuml.kts`'s file header for the SKR42
 * Kontenklasse background and why the Gemeinnützigkeit sphere is NOT derivable from it.
 *
 * Normal-balance semantic: [ASSET]/[EXPENSE] accounts are debit-normal (a debit increases the
 * balance), [LIABILITY]/[EQUITY]/[INCOME] accounts are credit-normal (a credit increases the
 * balance) -- drives `network.lapis.cloud.server.rpc.GeneralLedgerCalculator`'s running-balance
 * sign, and, in a later wave (V0.3.2), the P&L-vs-balance-sheet split.
 */
@Serializable
enum class LedgerAccountType { ASSET, LIABILITY, EQUITY, INCOME, EXPENSE }

/** Soll/Haben. */
@Serializable
enum class PostingSide { DEBIT, CREDIT }

/**
 * [DRAFT] entries may be incomplete/unbalanced and remain freely editable.
 * [POSTED] entries are immutable and were validated balanced (Σdebit = Σcredit) at the moment
 * they transitioned -- see `network.lapis.cloud.server.rpc.JournalEntryBalance` KDoc. Extend with
 * e.g. `REVERSED` in a later Storno wave -- additive, cheap (pre-1.0, one regenerated baseline, no
 * production data to migrate).
 */
@Serializable
enum class JournalEntryStatus { DRAFT, POSTED }

/**
 * One SKR42 Konto. [accountNumber] is the five-digit (or shorter, some system accounts are
 * shorter) SKR42 number whose leading digit is the Kontenklasse (0-9) -- see the `.kuml.kts` file
 * header for why the Gemeinnützigkeit sphere is NOT derivable from that class under SKR42.
 * [accountClass] is that leading digit, carried as its own field for reporting purposes rather
 * than re-parsed from [accountNumber] on every read.
 */
@Serializable
data class LedgerAccountDto(
    val id: String,
    val accountNumber: String,
    val name: String,
    val accountClass: Int,
    val type: LedgerAccountType,
    val active: Boolean,
)

@Serializable
data class LedgerAccountInput(
    val accountNumber: String,
    val name: String,
    val accountClass: Int,
    val type: LedgerAccountType,
    val active: Boolean = true,
)

/** One Soll/Haben line of a [JournalEntryDto] -- always references an active [LedgerAccountDto]. */
@Serializable
data class PostingDto(
    val id: String,
    val ledgerAccountId: String,
    val ledgerAccountNumber: String,
    val ledgerAccountName: String,
    val side: PostingSide,
    val amount: Decimal,
)

@Serializable
data class PostingInput(
    val ledgerAccountId: String,
    val side: PostingSide,
    val amount: Decimal,
)

@Serializable
data class JournalEntryDto(
    val id: String,
    val entryDate: LocalDate,
    val description: String,
    val voucherReference: String?,
    val createdBy: String,
    val createdByDisplayName: String,
    val status: JournalEntryStatus,
    val postedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val postings: List<PostingDto>,
)

/**
 * [postings] must contain at least two lines with at least one [PostingSide.DEBIT] and one
 * [PostingSide.CREDIT] line, and Σdebit must equal Σcredit for `postJournalEntry`/
 * `postDraftEntry` to succeed -- see `network.lapis.cloud.server.rpc.JournalEntryBalance` KDoc.
 * `saveDraftEntry` accepts an incomplete/unbalanced set of [postings] (that is the point of a
 * draft).
 */
@Serializable
data class JournalEntryInput(
    val entryDate: LocalDate,
    val description: String,
    val voucherReference: String? = null,
    val postings: List<PostingInput> = emptyList(),
)

/** One row of the Hauptbuch (general ledger) for a single [GeneralLedgerDto.ledgerAccountId]. */
@Serializable
data class GeneralLedgerLineDto(
    val journalEntryId: String,
    val entryDate: LocalDate,
    val description: String,
    val side: PostingSide,
    val amount: Decimal,
    val runningBalance: Decimal,
)

/**
 * The Hauptbuch (general ledger) for one [LedgerAccountDto], chronologically ordered.
 * [openingBalance] is always `0` in this wave (no opening-balance/carryforward support yet --
 * see the `.kuml.kts` file header "Explicit non-goals"); [closingBalance] equals the last
 * [GeneralLedgerLineDto.runningBalance], or [openingBalance] if [lines] is empty.
 */
@Serializable
data class GeneralLedgerDto(
    val ledgerAccountId: String,
    val accountNumber: String,
    val name: String,
    val type: LedgerAccountType,
    val openingBalance: Decimal,
    val closingBalance: Decimal,
    val lines: List<GeneralLedgerLineDto>,
)
