package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.GremiumMitgliedschaftTable
import network.lapis.cloud.server.db.generated.GremiumTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.GremiumType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * The "who counts" set shared by [GovernanceService.computeQuorum] (headcount quorum),
 * [GovernanceService.castStimme] (Meritokratische Abstimmungen, V0.2.3 LTR-staking eligibility)
 * and `WahlService.openVoting` (Demokratische Wahlen, V0.2.4 eligibility snapshot) -- extracted
 * out of [GovernanceService] (where this originated in V0.2.3, factored out of
 * [GovernanceService.computeQuorum] at the time) into this standalone file so all three call
 * sites share exactly one definition of Gremium/Mitgliederversammlung eligibility rather than
 * risking the democratic and meritocratic paths silently drifting apart.
 *
 * For a [GremiumType.MITGLIEDERVERSAMMLUNG]-typed Gremium, eligibility is "all members with
 * [MemberStatus.AKTIV]" queried directly from [MemberTable]: syncing every member into
 * [GremiumMitgliedschaftTable] on join/leave would be a brittle parallel bookkeeping system.
 * Known limitation of this path: unlike the Gremium path (date-scoped via `since`/`until`), it
 * checks *current* [MemberStatus], not "status as of [scheduledDate]" -- an accepted
 * simplification carried over unchanged from the original V0.2.1 KDoc.
 *
 * Must run inside an already-open `transaction {}` (all call sites do).
 */
internal fun eligibleMemberIds(
    gremiumRow: ResultRow,
    scheduledDate: LocalDate,
): Set<Uuid> {
    val gremiumId = gremiumRow[GremiumTable.id]
    return if (gremiumRow[GremiumTable.type] == GremiumType.MITGLIEDERVERSAMMLUNG) {
        MemberTable
            .selectAll()
            .where { MemberTable.status eq MemberStatus.AKTIV }
            .map { it[MemberTable.id] }
            .toSet()
    } else {
        GremiumMitgliedschaftTable
            .selectAll()
            .where {
                (GremiumMitgliedschaftTable.gremiumId eq gremiumId) and
                    (GremiumMitgliedschaftTable.since lessEq scheduledDate) and
                    (
                        GremiumMitgliedschaftTable.until.isNull() or
                            (GremiumMitgliedschaftTable.until greaterEq scheduledDate)
                    )
            }.map { it[GremiumMitgliedschaftTable.memberId] }
            .toSet()
    }
}
