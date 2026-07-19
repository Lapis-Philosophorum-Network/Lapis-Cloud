package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import network.lapis.cloud.shared.domain.BoardChangeType
import network.lapis.cloud.shared.domain.CommitteeRole
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * [CommitteeRole]s that can only be held by one member at a time -- CHAIR/DEPUTY_CHAIR/SECRETARY
 * are named seats; MEMBER/ASSESSOR are ordinary board seats several people hold concurrently.
 * [recordBoardJoin] uses this to detect a "displaced incumbent" (a contested election unseating a
 * sitting CHAIR, e.g.) -- see that function's KDoc.
 */
private val SINGLE_HOLDER_COMMITTEE_ROLES = setOf(CommitteeRole.CHAIR, CommitteeRole.DEPUTY_CHAIR, CommitteeRole.SECRETARY)

/**
 * V0.5.2 §20 GwG Transparenzregister beneficial-owner event recording -- the single place
 * [network.lapis.cloud.server.rpc.ElectionService.tally]'s `EXECUTIVE_BOARD` winner-seating branch,
 * [network.lapis.cloud.server.rpc.GovernanceService.addCommitteeMember]/[network.lapis.cloud.server
 * .rpc.GovernanceService.endCommitteeMembership] (when the target Committee is `EXECUTIVE_BOARD`
 * -- co-option/removal outside an election), and [BoardMembershipService]'s own administrative
 * appoint/end actions all hook into, so a "Vorstandsaenderung" always produces at least one
 * [BoardMembershipTable] mutation plus one [TransparenzregisterReminderTable] row, regardless of
 * which of these paths triggered it. [recordBoardJoin] can additionally produce a second,
 * *displaced-incumbent* mutation+reminder pair -- see that function's KDoc.
 *
 * Transaction-free by contract, same as [ResolutionBook] -- every function here must run inside
 * the caller's already-open `transaction {}` (this is what lets `ElectionService.tally` call
 * [recordBoardJoin] without nesting a second transaction).
 */
object BoardMembershipEvents {
    /**
     * Closes any currently-open (`endedAt == null`) [BoardMembershipTable] row for [memberId] as of
     * [startedAt] -- WITHOUT emitting a `LEFT` reminder for it, so a re-election of an incumbent
     * (same or new [role]) produces exactly one `JOINED` reminder for the new term, not a
     * LEFT+JOINED pair. Flagged for legal review: a lawyer should confirm this single-reminder
     * choice is enough to prompt the manual register update on a role change.
     *
     * Displaced-incumbent handling: [role] may be a [SINGLE_HOLDER_COMMITTEE_ROLES] seat
     * (CHAIR/DEPUTY_CHAIR/SECRETARY), which by definition can only be held by one member at a
     * time. If some OTHER member currently holds an open [BoardMembershipTable] row for that same
     * [role] (a contested election unseating a sitting incumbent, e.g. `targetRole = CHAIR` and
     * `winner != incumbent`), that row is closed too and a `LEFT` reminder is emitted for it --
     * this genuinely is the other half of the Vorstandsaenderung, and without it the departed
     * beneficial owner would never be flagged for removal from the real Transparenzregister while
     * [BoardMembershipTable] would incorrectly show two simultaneous holders of the same seat.
     * MEMBER/ASSESSOR are ordinary (non-single-holder) seats and are exempt from this check.
     *
     * Then inserts a fresh [BoardMembershipTable] row and a `JOINED`
     * [TransparenzregisterReminderTable] row. Returns the new [BoardMembershipTable] row's id.
     */
    fun recordBoardJoin(
        memberId: Uuid,
        role: CommitteeRole,
        startedAt: LocalDate,
        now: LocalDateTime,
    ): Uuid {
        BoardMembershipTable.update({
            (BoardMembershipTable.memberId eq memberId) and (BoardMembershipTable.endedAt.isNull())
        }) {
            it[endedAt] = startedAt
        }
        if (role in SINGLE_HOLDER_COMMITTEE_ROLES) {
            val displacedRows =
                BoardMembershipTable
                    .selectAll()
                    .where {
                        (BoardMembershipTable.committeeRole eq role) and
                            (BoardMembershipTable.endedAt.isNull()) and
                            (BoardMembershipTable.memberId neq memberId)
                    }.toList()
            displacedRows.forEach { row ->
                val displacedId = row[BoardMembershipTable.id]
                BoardMembershipTable.update({ BoardMembershipTable.id eq displacedId }) {
                    it[endedAt] = startedAt
                }
                TransparenzregisterReminderTable.insert {
                    it[TransparenzregisterReminderTable.id] = Uuid.random()
                    it[triggeredAt] = now
                    it[TransparenzregisterReminderTable.memberId] = row[BoardMembershipTable.memberId]
                    it[committeeRole] = role
                    it[changeType] = BoardChangeType.LEFT
                    it[resolved] = false
                    it[resolvedAt] = null
                    it[resolvedBy] = null
                }
            }
        }
        val id = Uuid.random()
        BoardMembershipTable.insert {
            it[BoardMembershipTable.id] = id
            it[BoardMembershipTable.memberId] = memberId
            it[committeeRole] = role
            it[BoardMembershipTable.startedAt] = startedAt
            it[endedAt] = null
        }
        TransparenzregisterReminderTable.insert {
            it[TransparenzregisterReminderTable.id] = Uuid.random()
            it[triggeredAt] = now
            it[TransparenzregisterReminderTable.memberId] = memberId
            it[committeeRole] = role
            it[changeType] = BoardChangeType.JOINED
            it[resolved] = false
            it[resolvedAt] = null
            it[resolvedBy] = null
        }
        return id
    }

    /**
     * Ends the [BoardMembershipTable] row identified by [boardMembershipId] as of [endedAt] and
     * inserts a `LEFT` [TransparenzregisterReminderTable] row. Throws [NotFoundException] if that
     * row does not exist, [ConflictException] if it is already ended.
     */
    fun recordBoardLeave(
        boardMembershipId: Uuid,
        endedAt: LocalDate,
        now: LocalDateTime,
    ) {
        val row =
            BoardMembershipTable
                .selectAll()
                .where { BoardMembershipTable.id eq boardMembershipId }
                .singleOrNull()
                ?: throw NotFoundException("BoardMembership $boardMembershipId not found")
        if (row[BoardMembershipTable.endedAt] != null) {
            throw ConflictException("BoardMembership $boardMembershipId already ended")
        }
        BoardMembershipTable.update({ BoardMembershipTable.id eq boardMembershipId }) {
            it[BoardMembershipTable.endedAt] = endedAt
        }
        TransparenzregisterReminderTable.insert {
            it[TransparenzregisterReminderTable.id] = Uuid.random()
            it[triggeredAt] = now
            it[memberId] = row[BoardMembershipTable.memberId]
            it[committeeRole] = row[BoardMembershipTable.committeeRole]
            it[changeType] = BoardChangeType.LEFT
            it[resolved] = false
            it[resolvedAt] = null
            it[resolvedBy] = null
        }
    }
}
