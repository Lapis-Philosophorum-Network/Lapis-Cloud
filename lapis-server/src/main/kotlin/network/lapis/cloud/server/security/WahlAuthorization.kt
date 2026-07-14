package network.lapis.cloud.server.security

import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.WahlWahlvorstandTable
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Authorization helpers for Demokratische Wahlen (V0.2.4). [canManageWahl] reuses
 * [canRecordForSitzung]'s Gremium-leadership-or-privileged rule verbatim -- managing a Wahl's
 * lifecycle (opening it, appointing the Wahlvorstand, freigeben-ing the Kandidatenliste, aborting
 * it) is the same kind of "who runs this Gremium's business" decision [canRecordForSitzung]
 * already governs for Sitzungen/Beschluesse/Abstimmungen. `gremiumId` here is always the hosting
 * Antrag's own target Gremium (see `network.lapis.cloud.shared.domain.WahlOpenInput` KDoc), not
 * necessarily [network.lapis.cloud.shared.domain.WahlDto.zielGremiumId].
 */
fun CurrentMember.canManageWahl(gremiumId: Uuid): Boolean = canRecordForSitzung(gremiumId)

/**
 * Wahlvorstand-or-privileged gate used by `WahlService.openVoting`/`closeVoting`/`auszaehlen` --
 * these are operational steps a BOARD/ADMIN override is expected to be able to perform, same
 * convention as every other privileged-bypass check in this codebase. Contrast with
 * [isWahlvorstandMember], which deliberately does *not* bypass for privileged roles (used only by
 * `freigebenAuszaehlung`, where the whole point is a genuine named Vier-Augen approval count).
 */
fun CurrentMember.isWahlvorstand(wahlId: Uuid): Boolean = isPrivileged || isWahlvorstandMember(wahlId)

/** Strict membership check, no privileged bypass -- see [isWahlvorstand] KDoc for why. */
fun CurrentMember.isWahlvorstandMember(wahlId: Uuid): Boolean =
    transaction {
        WahlWahlvorstandTable
            .selectAll()
            .where { (WahlWahlvorstandTable.wahlId eq wahlId) and (WahlWahlvorstandTable.memberId eq memberId) }
            .count() > 0
    }

/**
 * Self-nomination eligibility for [network.lapis.cloud.shared.domain.WahlTyp.EINZELWAHL]/
 * [network.lapis.cloud.shared.domain.WahlTyp.MEHRFACHWAHL] -- mirrors [canSubmitAntrag]'s
 * Mitgliederversammlung branch (any [MemberStatus.AKTIV] member), since standing as a candidate
 * for a personnel election is, like submitting to the Mitgliederversammlung, a broad participation
 * right of active membership, not scoped to a specific Gremium's own membership. No third-party
 * nomination flow exists in this wave (self-nomination only).
 */
fun CurrentMember.canStandAsCandidate(): Boolean {
    if (isPrivileged) return true
    return transaction {
        MemberTable
            .selectAll()
            .where { (MemberTable.id eq memberId) and (MemberTable.status eq MemberStatus.AKTIV) }
            .count() > 0
    }
}
