package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.GeneralLedgerDto
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountInput

/**
 * SKR42 chart of accounts + double-entry bookkeeping (V0.3.1, chart swapped from SKR49 in
 * V0.3.1.1) -- see `network.lapis.cloud.server.rpc.AccountingService` for the implementation and
 * `lapis-server/src/main/kuml/10-accounting.kuml.kts` for the schema-shape rationale.
 *
 * Bookkeeping is treasury-only, not member-public (unlike e.g. [IContributionService]'s own-data
 * carve-out) -- every method requires TREASURER/ADMIN. A [JournalEntryDto] may be saved as a
 * [JournalEntryStatus.DRAFT] (incomplete/unbalanced postings allowed, freely editable) or posted
 * directly/transitioned to [JournalEntryStatus.POSTED] (validated balanced -- Σdebit = Σcredit --
 * at that moment, then immutable).
 */
@RpcService
interface IAccountingService {
    /** Role: TREASURER/ADMIN. Rejects a duplicate [LedgerAccountInput.accountNumber]. */
    suspend fun createLedgerAccount(input: LedgerAccountInput): LedgerAccountDto

    /** Role: TREASURER/ADMIN. Deactivates (never deletes) a [LedgerAccountDto]. */
    suspend fun deactivateLedgerAccount(id: String): LedgerAccountDto

    /** Role: TREASURER/ADMIN. */
    suspend fun listLedgerAccounts(activeOnly: Boolean = true): List<LedgerAccountDto>

    /**
     * Role: TREASURER/ADMIN. Saves [input] as [JournalEntryStatus.DRAFT] -- [input].postings may
     * be empty or unbalanced (that is the point of a draft). Use [postDraftEntry] to transition it
     * to [JournalEntryStatus.POSTED] once it balances.
     */
    suspend fun saveDraftEntry(input: JournalEntryInput): JournalEntryDto

    /**
     * Role: TREASURER/ADMIN. Validates [input].postings balanced (at least two lines, at least one
     * debit and one credit, Σdebit = Σcredit) and writes the entry directly as
     * [JournalEntryStatus.POSTED] in one atomic transaction -- an unbalanced attempt is rejected
     * and nothing is persisted.
     */
    suspend fun postJournalEntry(input: JournalEntryInput): JournalEntryDto

    /**
     * Role: TREASURER/ADMIN. Transitions an existing [JournalEntryStatus.DRAFT] entry to
     * [JournalEntryStatus.POSTED] once its current postings balance; rejected (and the entry left
     * unchanged) if they do not.
     */
    suspend fun postDraftEntry(id: String): JournalEntryDto

    /** Role: TREASURER/ADMIN. */
    suspend fun getJournalEntry(id: String): JournalEntryDto

    /** Role: TREASURER/ADMIN. Grundbuch (journal), chronologically ordered by [entryDate]. */
    suspend fun listJournal(
        from: LocalDate? = null,
        to: LocalDate? = null,
        status: JournalEntryStatus? = null,
    ): List<JournalEntryDto>

    /**
     * Role: TREASURER/ADMIN. Hauptbuch (general ledger) for one [LedgerAccountDto], with a running
     * balance whose sign follows that account's [network.lapis.cloud.shared.domain
     * .LedgerAccountType] normal-balance side. Only [JournalEntryStatus.POSTED] postings
     * contribute -- a [JournalEntryStatus.DRAFT] entry's postings are provisional and must not
     * move the ledger.
     */
    suspend fun getGeneralLedgerAccount(
        ledgerAccountId: String,
        from: LocalDate? = null,
        to: LocalDate? = null,
    ): GeneralLedgerDto
}
