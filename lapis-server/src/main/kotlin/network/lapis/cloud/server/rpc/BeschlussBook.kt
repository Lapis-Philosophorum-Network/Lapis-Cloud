package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.AnwesenheitTable
import network.lapis.cloud.server.db.generated.BeschlussTable
import network.lapis.cloud.server.db.generated.GremiumTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SitzungTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AnwesenheitStatus
import network.lapis.cloud.shared.domain.BeschlussDto
import network.lapis.cloud.shared.domain.BeschlussInput
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.ResolutionMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Shared Beschlussbuch write path -- originally private to [GovernanceService] (`computeQuorum`/
 * `nextBeschlussNumber`/`insertBeschlussRow`/`ResultRow.toBeschlussDto`), extracted here
 * (Demokratische Wahlen, V0.2.4) so `WahlService.auszaehlen` can write into the *same*
 * Beschlussbuch [GovernanceService.recordBeschluss]/[GovernanceService.resolveAntrag]/
 * [GovernanceService.closeAbstimmung] already use, tagged [ResolutionMode.DEMOKRATISCH], without
 * duplicating the quorum-snapshot/Beschluss-numbering logic. Behavior for the pre-existing three
 * call sites is unchanged by this extraction -- only the parameter shape of [insertBeschlussRow]
 * and [computeQuorum] moved from taking a full `SitzungDto` to taking the `gremiumId`/
 * `scheduledDate` primitives it actually needs, so `WahlService` (which only ever looks up a
 * Sitzung's raw row, never builds a full `SitzungDto`) does not have to construct one just to
 * call this.
 *
 * Must run inside an already-open `transaction {}` (all call sites do).
 */
internal fun computeQuorum(
    sitzungId: Uuid,
    gremiumId: Uuid,
    scheduledDate: LocalDate,
): QuorumResultDto {
    val gremiumRow = GremiumTable.selectAll().where { GremiumTable.id eq gremiumId }.single()
    val quorumPercent = gremiumRow[GremiumTable.quorumPercent]
    val eligible = eligibleMemberIds(gremiumRow, scheduledDate)
    val presentCount =
        AnwesenheitTable
            .selectAll()
            .where {
                (AnwesenheitTable.sitzungId eq sitzungId) and
                    (AnwesenheitTable.status inList listOf(AnwesenheitStatus.ANWESEND, AnwesenheitStatus.VERTRETEN))
            }.map { it[AnwesenheitTable.memberId] }
            .count { it in eligible }
    val eligibleCount = eligible.size
    val requiredCount = ceil(eligibleCount * quorumPercent / 100.0).toInt()
    return QuorumResultDto(
        sitzungId = sitzungId.toString(),
        eligibleMemberCount = eligibleCount,
        presentCount = presentCount,
        requiredCount = requiredCount,
        quorumPercent = quorumPercent,
        met = presentCount >= requiredCount,
    )
}

/**
 * `"<GremiumType>-<Jahr>-<laufendeNummer>"` (e.g. `"VORSTAND-2026-03"`). The running number is
 * `count(beschluss where gremium = X and year(decidedAt) = Y) + 1`, computed by loading this
 * Gremium's Beschluss rows for the year and filtering in Kotlin rather than a DB-side
 * `EXTRACT(YEAR FROM ...)` -- no DB sequence needed at this scale, and avoids a date-function that
 * behaves slightly differently between H2 and Postgres.
 */
internal fun nextBeschlussNumber(
    gremiumId: Uuid,
    gremiumTypeName: String,
    year: Int,
): String {
    val countThisYear =
        (BeschlussTable innerJoin SitzungTable)
            .selectAll()
            .where { SitzungTable.gremiumId eq gremiumId }
            .count { it[BeschlussTable.decidedAt].year == year }
    return "$gremiumTypeName-$year-${(countThisYear + 1).toString().padStart(2, '0')}"
}

/**
 * Shared insert path for [GovernanceService.recordBeschluss] and [GovernanceService.resolveAntrag]
 * (V0.2.2), extended in V0.2.3 to also serve [GovernanceService.closeAbstimmung], in V0.2.4 to
 * also serve `WahlService.auszaehlen`, and in V0.2.5 to also serve
 * `KonsensierungService.auswerten` -- what actually makes "resolution links into the existing
 * Beschlussbuch mechanism rather than creating a parallel one" true in code, not just in the DTO
 * shape. [resolutionMode]/[abstimmungId]/[wahlId]/[konsensierungId] default to the pre-V0.2.3
 * Gremium-Quorum shape so [GovernanceService.recordBeschluss]/[GovernanceService.resolveAntrag]
 * call sites stay source-compatible.
 *
 * `quorumMet` is still snapshotted for a [ResolutionMode.MERITOKRATISCH]/[ResolutionMode
 * .DEMOKRATISCH]/[ResolutionMode.SYSTEMISCHER_KONSENS] Beschluss too (for the historical record),
 * even though the outcome itself is decided by LTR baskets, one-person-one-vote ballots or lowest
 * cumulative resistance, not by this headcount figure -- documented decision point carried over
 * from the V0.2.3 implementation plan's "Quorum interaction" note; a minimum-participation guard
 * on Abstimmungen/Wahlen/Konsensierungen is deferred.
 */
internal fun insertBeschlussRow(
    sId: Uuid,
    gremiumId: Uuid,
    scheduledDate: LocalDate,
    input: BeschlussInput,
    current: CurrentMember,
    resolutionMode: ResolutionMode = ResolutionMode.GREMIUM_QUORUM,
    abstimmungId: Uuid? = null,
    wahlId: Uuid? = null,
    konsensierungId: Uuid? = null,
): BeschlussDto {
    val quorum = computeQuorum(sId, gremiumId, scheduledDate)
    val gremiumRow = GremiumTable.selectAll().where { GremiumTable.id eq gremiumId }.single()
    val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val number = nextBeschlussNumber(gremiumId, gremiumRow[GremiumTable.type].name, now.year)
    val id = Uuid.random()
    BeschlussTable.insert {
        it[BeschlussTable.id] = id
        it[BeschlussTable.sitzungId] = sId
        it[tagesordnungspunktId] = input.tagesordnungspunktId?.let(Uuid::parse)
        it[BeschlussTable.number] = number
        it[title] = input.title
        it[text] = input.text
        it[votesYes] = input.votesYes
        it[votesNo] = input.votesNo
        it[votesAbstain] = input.votesAbstain
        it[quorumMet] = quorum.met
        it[status] = input.status
        it[decidedAt] = now
        it[recordedBy] = current.memberId
        it[BeschlussTable.resolutionMode] = resolutionMode
        it[BeschlussTable.abstimmungId] = abstimmungId
        it[BeschlussTable.wahlId] = wahlId
        it[BeschlussTable.konsensierungId] = konsensierungId
    }
    return BeschlussTable
        .selectAll()
        .where { BeschlussTable.id eq id }
        .single()
        .toBeschlussDto()
}

internal fun ResultRow.toBeschlussDto(): BeschlussDto =
    BeschlussDto(
        id = this[BeschlussTable.id].toString(),
        sitzungId = this[BeschlussTable.sitzungId].toString(),
        tagesordnungspunktId = this[BeschlussTable.tagesordnungspunktId]?.toString(),
        number = this[BeschlussTable.number],
        title = this[BeschlussTable.title],
        text = this[BeschlussTable.text],
        votesYes = this[BeschlussTable.votesYes],
        votesNo = this[BeschlussTable.votesNo],
        votesAbstain = this[BeschlussTable.votesAbstain],
        quorumMet = this[BeschlussTable.quorumMet],
        status = this[BeschlussTable.status],
        decidedAt = this[BeschlussTable.decidedAt],
        recordedById = this[BeschlussTable.recordedBy].toString(),
        recordedByDisplayName = beschlussRecorderDisplayName(this[BeschlussTable.recordedBy]).orEmpty(),
        resolutionMode = this[BeschlussTable.resolutionMode],
        abstimmungId = this[BeschlussTable.abstimmungId]?.toString(),
        wahlId = this[BeschlussTable.wahlId]?.toString(),
        konsensierungId = this[BeschlussTable.konsensierungId]?.toString(),
    )

private fun beschlussRecorderDisplayName(memberId: Uuid?): String? =
    memberId?.let { id ->
        MemberTable
            .selectAll()
            .where { MemberTable.id eq id }
            .singleOrNull()
            ?.get(MemberTable.displayName)
    }
