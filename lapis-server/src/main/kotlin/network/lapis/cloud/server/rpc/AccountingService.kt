package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.GeneralLedgerDto
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountDto
import network.lapis.cloud.shared.domain.LedgerAccountInput
import network.lapis.cloud.shared.domain.PostingDto
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.rpc.IAccountingService
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val TREASURY_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)
private val ACCOUNTING_READ_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)

/**
 * SKR42 chart of accounts + double-entry bookkeeping (V0.3.1, chart swapped from SKR49 in
 * V0.3.1.1). Implements [IAccountingService] --
 * see that interface's KDoc for the full lifecycle. Bookkeeping is treasury-only, not
 * member-public: every method requires at least [ACCOUNTING_READ_ROLES] (TREASURER/BOARD/ADMIN),
 * every write requires [TREASURY_ROLES] (TREASURER/ADMIN) -- same "TREASURY_ROLES + requireRole"
 * idiom [ContributionService] establishes.
 *
 * The balance invariant (Σdebit = Σcredit) is validated via the pure [JournalEntryBalance] helper
 * and enforced inside the same `transaction {}` that writes the [network.lapis.cloud.server.db
 * .generated.JournalEntryTable]/[network.lapis.cloud.server.db.generated.PostingTable] rows -- an
 * unbalanced attempt throws [ConflictException] and the whole transaction rolls back, so a
 * partially-written unbalanced entry can never be observed.
 *
 * DSGVO: [network.lapis.cloud.server.dsgvo.AccountingPersonalData] owns
 * [JournalEntryTable.createdBy] -- see that contributor's KDoc for why GoBD/§257 HGB retention
 * overrides DSGVO erasure here (accounting records are never anonymized/deleted).
 */
class AccountingService(
    private val call: ApplicationCall,
) : IAccountingService {
    override suspend fun createLedgerAccount(input: LedgerAccountInput): LedgerAccountDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        return transaction {
            val duplicate =
                LedgerAccountTable
                    .selectAll()
                    .where { LedgerAccountTable.accountNumber eq input.accountNumber }
                    .count() > 0
            if (duplicate) {
                throw ConflictException("LedgerAccount with accountNumber ${input.accountNumber} already exists")
            }
            val id = Uuid.random()
            try {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = input.accountNumber
                    it[name] = input.name
                    it[accountClass] = input.accountClass
                    it[type] = input.type
                    it[active] = input.active
                }
            } catch (e: ExposedSQLException) {
                // Application-level pre-check above is racy under concurrency on its own -- the
                // DB-level UNIQUE (uq_ledger_account_number) is the real backstop, same
                // "pre-check + ExposedSQLException backstop" idiom as ElectionService's receipt
                // code / duplicate-vote handling.
                throw ConflictException("LedgerAccount with accountNumber ${input.accountNumber} already exists")
            }
            loadLedgerAccount(id)
        }
    }

    override suspend fun deactivateLedgerAccount(id: String): LedgerAccountDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        val accountId = id.toAccountingUuid("LedgerAccount")
        return transaction {
            val updated =
                LedgerAccountTable.update({ LedgerAccountTable.id eq accountId }) {
                    it[active] = false
                }
            if (updated == 0) throw NotFoundException("LedgerAccount $id not found")
            loadLedgerAccount(accountId)
        }
    }

    override suspend fun listLedgerAccounts(activeOnly: Boolean): List<LedgerAccountDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        return transaction {
            val baseQuery = LedgerAccountTable.selectAll()
            val query = if (activeOnly) baseQuery.where { LedgerAccountTable.active eq true } else baseQuery
            query.orderBy(LedgerAccountTable.accountNumber, SortOrder.ASC).map { it.toLedgerAccountDto() }
        }
    }

    override suspend fun saveDraftEntry(input: JournalEntryInput): JournalEntryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        return transaction {
            insertJournalEntry(input, current.memberId, JournalEntryStatus.DRAFT, postedAt = null)
        }
    }

    override suspend fun postJournalEntry(input: JournalEntryInput): JournalEntryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        return transaction {
            requireBalanced(input.postings)
            requireActiveLedgerAccounts(input.postings.map { it.ledgerAccountId.toAccountingUuid("LedgerAccount") })
            insertJournalEntry(input, current.memberId, JournalEntryStatus.POSTED, postedAt = nowLocalDateTime())
        }
    }

    override suspend fun postDraftEntry(id: String): JournalEntryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*TREASURY_ROLES)
        val entryId = id.toAccountingUuid("JournalEntry")
        return transaction {
            val entryRow = requireJournalEntryRow(entryId)
            if (entryRow[JournalEntryTable.status] != JournalEntryStatus.DRAFT) {
                throw ConflictException("JournalEntry $id is ${entryRow[JournalEntryTable.status]}, expected DRAFT")
            }
            val postings =
                PostingTable
                    .selectAll()
                    .where { PostingTable.journalEntryId eq entryId }
                    .map { row ->
                        PostingInput(
                            ledgerAccountId = row[PostingTable.ledgerAccountId].toString(),
                            side = row[PostingTable.side],
                            amount = row[PostingTable.amount],
                        )
                    }
            requireBalanced(postings)
            requireActiveLedgerAccounts(postings.map { it.ledgerAccountId.toAccountingUuid("LedgerAccount") })
            JournalEntryTable.update({ JournalEntryTable.id eq entryId }) {
                it[status] = JournalEntryStatus.POSTED
                it[postedAt] = nowLocalDateTime()
            }
            loadJournalEntry(entryId)
        }
    }

    override suspend fun getJournalEntry(id: String): JournalEntryDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        val entryId = id.toAccountingUuid("JournalEntry")
        return transaction { loadJournalEntry(entryId) }
    }

    override suspend fun listJournal(
        from: LocalDate?,
        to: LocalDate?,
        status: JournalEntryStatus?,
    ): List<JournalEntryDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (from != null) conditions += (JournalEntryTable.entryDate greaterEq from)
            if (to != null) conditions += (JournalEntryTable.entryDate lessEq to)
            if (status != null) conditions += (JournalEntryTable.status eq status)
            val baseQuery = JournalEntryTable.selectAll()
            val query = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            query
                .orderBy(JournalEntryTable.entryDate to SortOrder.ASC, JournalEntryTable.createdAt to SortOrder.ASC)
                .map { it[JournalEntryTable.id] }
                .map { loadJournalEntry(it) }
        }
    }

    override suspend fun getGeneralLedgerAccount(
        ledgerAccountId: String,
        from: LocalDate?,
        to: LocalDate?,
    ): GeneralLedgerDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_READ_ROLES)
        val accountId = ledgerAccountId.toAccountingUuid("LedgerAccount")
        return transaction {
            val accountRow =
                LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
                    ?: throw NotFoundException("LedgerAccount $ledgerAccountId not found")

            val conditions =
                mutableListOf<Op<Boolean>>(
                    PostingTable.ledgerAccountId eq accountId,
                    // Only POSTED entries move the ledger -- a DRAFT entry's postings are
                    // provisional and must never contribute to a running balance.
                    JournalEntryTable.status eq JournalEntryStatus.POSTED,
                )
            if (from != null) conditions += (JournalEntryTable.entryDate greaterEq from)
            if (to != null) conditions += (JournalEntryTable.entryDate lessEq to)

            val rows =
                (PostingTable innerJoin JournalEntryTable)
                    .selectAll()
                    .where { conditions.reduce { a, b -> a and b } }
                    .orderBy(JournalEntryTable.entryDate to SortOrder.ASC, JournalEntryTable.createdAt to SortOrder.ASC)
                    .toList()

            val lines =
                rows.map { row ->
                    GeneralLedgerCalculator.LedgerLine(
                        journalEntryId = row[JournalEntryTable.id].toString(),
                        entryDate = row[JournalEntryTable.entryDate],
                        description = row[JournalEntryTable.description],
                        side = row[PostingTable.side],
                        amount = row[PostingTable.amount],
                    )
                }
            val type = accountRow[LedgerAccountTable.type]
            val normalSide = GeneralLedgerCalculator.normalBalanceSideOf(type)
            val ledgerLines = GeneralLedgerCalculator.runningBalances(lines, normalSide)

            GeneralLedgerDto(
                ledgerAccountId = accountId.toString(),
                accountNumber = accountRow[LedgerAccountTable.accountNumber],
                name = accountRow[LedgerAccountTable.name],
                type = type,
                openingBalance = BigDecimal.ZERO,
                closingBalance = ledgerLines.lastOrNull()?.runningBalance ?: BigDecimal.ZERO,
                lines = ledgerLines,
            )
        }
    }

    /** Inserts a new [JournalEntryTable] row plus its [PostingTable] rows in the caller's transaction. */
    private fun insertJournalEntry(
        input: JournalEntryInput,
        createdBy: Uuid,
        status: JournalEntryStatus,
        postedAt: LocalDateTime?,
    ): JournalEntryDto {
        val id = Uuid.random()
        JournalEntryTable.insert {
            it[JournalEntryTable.id] = id
            it[entryDate] = input.entryDate
            it[description] = input.description
            it[voucherReference] = input.voucherReference
            it[JournalEntryTable.createdBy] = createdBy
            it[JournalEntryTable.status] = status
            it[JournalEntryTable.postedAt] = postedAt
            it[createdAt] = nowLocalDateTime()
        }
        input.postings.forEach { posting ->
            PostingTable.insert {
                it[PostingTable.id] = Uuid.random()
                it[journalEntryId] = id
                it[ledgerAccountId] = posting.ledgerAccountId.toAccountingUuid("LedgerAccount")
                it[side] = posting.side
                it[amount] = posting.amount
            }
        }
        return loadJournalEntry(id)
    }

    /** Throws [ConflictException] naming the imbalance reason if [postings] does not balance -- see [JournalEntryBalance]. */
    private fun requireBalanced(postings: List<PostingInput>) {
        val result = JournalEntryBalance.validateBalanced(postings)
        if (!result.balanced) throw ConflictException(result.reason ?: "Journal entry not balanced")
    }

    /** Every referenced [LedgerAccountTable] row must exist and be [LedgerAccountTable.active]. */
    private fun requireActiveLedgerAccounts(ledgerAccountIds: List<Uuid>) {
        ledgerAccountIds.distinct().forEach { accountId ->
            val row =
                LedgerAccountTable.selectAll().where { LedgerAccountTable.id eq accountId }.singleOrNull()
                    ?: throw NotFoundException("LedgerAccount $accountId not found")
            if (!row[LedgerAccountTable.active]) {
                throw ConflictException("LedgerAccount $accountId is not active")
            }
        }
    }

    private fun requireJournalEntryRow(id: Uuid): ResultRow =
        JournalEntryTable.selectAll().where { JournalEntryTable.id eq id }.singleOrNull()
            ?: throw NotFoundException("JournalEntry $id not found")

    private fun loadLedgerAccount(id: Uuid): LedgerAccountDto =
        LedgerAccountTable
            .selectAll()
            .where { LedgerAccountTable.id eq id }
            .singleOrNull()
            ?.toLedgerAccountDto() ?: throw NotFoundException("LedgerAccount $id not found")

    private fun loadJournalEntry(id: Uuid): JournalEntryDto {
        val entryRow = requireJournalEntryRow(id)
        val postings =
            (PostingTable innerJoin LedgerAccountTable)
                .selectAll()
                .where { PostingTable.journalEntryId eq id }
                .map { it.toPostingDto() }
        return entryRow.toJournalEntryDto(postings)
    }

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .singleOrNull()
            ?.get(MemberTable.displayName)
            .orEmpty()

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun String.toAccountingUuid(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }

    private fun ResultRow.toLedgerAccountDto(): LedgerAccountDto =
        LedgerAccountDto(
            id = this[LedgerAccountTable.id].toString(),
            accountNumber = this[LedgerAccountTable.accountNumber],
            name = this[LedgerAccountTable.name],
            accountClass = this[LedgerAccountTable.accountClass],
            type = this[LedgerAccountTable.type],
            active = this[LedgerAccountTable.active],
        )

    private fun ResultRow.toPostingDto(): PostingDto =
        PostingDto(
            id = this[PostingTable.id].toString(),
            ledgerAccountId = this[PostingTable.ledgerAccountId].toString(),
            ledgerAccountNumber = this[LedgerAccountTable.accountNumber],
            ledgerAccountName = this[LedgerAccountTable.name],
            side = this[PostingTable.side],
            amount = this[PostingTable.amount],
        )

    private fun ResultRow.toJournalEntryDto(postings: List<PostingDto>): JournalEntryDto {
        val createdBy = this[JournalEntryTable.createdBy]
        return JournalEntryDto(
            id = this[JournalEntryTable.id].toString(),
            entryDate = this[JournalEntryTable.entryDate],
            description = this[JournalEntryTable.description],
            voucherReference = this[JournalEntryTable.voucherReference],
            createdBy = createdBy.toString(),
            createdByDisplayName = memberDisplayName(createdBy),
            status = this[JournalEntryTable.status],
            postedAt = this[JournalEntryTable.postedAt],
            createdAt = this[JournalEntryTable.createdAt],
            postings = postings,
        )
    }
}
