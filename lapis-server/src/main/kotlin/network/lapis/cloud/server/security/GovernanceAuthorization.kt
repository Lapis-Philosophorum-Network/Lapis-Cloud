package network.lapis.cloud.server.security

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.tables.GremiumMitgliedschaftTable
import network.lapis.cloud.server.db.tables.GremiumTable
import network.lapis.cloud.server.db.tables.MemberTable
import network.lapis.cloud.shared.domain.GremiumRolle
import network.lapis.cloud.shared.domain.GremiumType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Authorization helpers for Gremien-/Sitzungsverwaltung (V0.2.1). [Gremium][network.lapis.cloud
 * .shared.domain.GremiumDto] create/update stays BOARD/ADMIN-only ([requireRole], existing
 * pattern in `network.lapis.cloud.server.rpc.GovernanceService`) since committee structure itself
 * is an org-wide governance decision. Sitzung/Tagesordnung/Anwesenheit/Beschluss management uses
 * the functions below, which resolve the Gremium behind the resource and check for an *active*
 * (as-of-today) [GremiumRolle] membership entitled to that action, OR global BOARD/ADMIN via
 * [isPrivileged].
 *
 * Each function opens its own `transaction {}`, mirroring [resolveCurrentMember]'s style —
 * Exposed transactions nest without opening a second physical transaction, so calling these from
 * inside an already-open `transaction {}` (as `GovernanceService` typically does) is safe.
 */
fun CurrentMember.canManageGremium(gremiumId: Uuid): Boolean =
    isPrivileged || hasGremiumRolle(gremiumId, GremiumRolle.VORSITZ, GremiumRolle.STELLV_VORSITZ)

fun CurrentMember.canRecordForSitzung(gremiumId: Uuid): Boolean =
    isPrivileged ||
        hasGremiumRolle(gremiumId, GremiumRolle.VORSITZ, GremiumRolle.STELLV_VORSITZ, GremiumRolle.SCHRIFTFUEHRUNG)

/**
 * Antragsverwaltung (V0.2.2) submission rule. Asymmetric on purpose: submitting *to* the
 * Mitgliederversammlung is a broad participation right (any [MemberStatus.AKTIV] member, mirroring
 * every member's stake in a general assembly), while submitting *to* a specific Gremium requires
 * an active membership of that Gremium — any [GremiumRolle], not just leadership, so a
 * rank-and-file committee member can propose something to their own committee, but a non-member
 * cannot.
 */
fun CurrentMember.canSubmitAntrag(targetGremiumId: Uuid): Boolean {
    if (isPrivileged) return true
    val gremiumType =
        transaction {
            GremiumTable
                .selectAll()
                .where { GremiumTable.id eq targetGremiumId }
                .singleOrNull()
                ?.get(GremiumTable.type)
        } ?: return false
    return if (gremiumType == GremiumType.MITGLIEDERVERSAMMLUNG) {
        transaction {
            MemberTable
                .selectAll()
                .where { (MemberTable.id eq memberId) and (MemberTable.status eq MemberStatus.AKTIV) }
                .count() > 0
        }
    } else {
        hasGremiumRolle(targetGremiumId, *GremiumRolle.entries.toTypedArray())
    }
}

private fun CurrentMember.hasGremiumRolle(
    gremiumId: Uuid,
    vararg roles: GremiumRolle,
): Boolean {
    val today =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .date
    return transaction {
        GremiumMitgliedschaftTable
            .selectAll()
            .where {
                (GremiumMitgliedschaftTable.gremiumId eq gremiumId) and
                    (GremiumMitgliedschaftTable.memberId eq memberId) and
                    (GremiumMitgliedschaftTable.rolle inList roles.toList()) and
                    (GremiumMitgliedschaftTable.since lessEq today) and
                    (
                        GremiumMitgliedschaftTable.until.isNull() or
                            (GremiumMitgliedschaftTable.until greaterEq today)
                    )
            }.count() > 0
    }
}
