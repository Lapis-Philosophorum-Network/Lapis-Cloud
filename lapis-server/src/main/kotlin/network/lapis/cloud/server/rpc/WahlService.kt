package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.AntragTable
import network.lapis.cloud.server.db.generated.GremiumMitgliedschaftTable
import network.lapis.cloud.server.db.generated.GremiumTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SitzungTable
import network.lapis.cloud.server.db.generated.WahlFreigabeTable
import network.lapis.cloud.server.db.generated.WahlKandidaturTable
import network.lapis.cloud.server.db.generated.WahlOptionTable
import network.lapis.cloud.server.db.generated.WahlStimmzettelAuswahlTable
import network.lapis.cloud.server.db.generated.WahlStimmzettelTable
import network.lapis.cloud.server.db.generated.WahlTable
import network.lapis.cloud.server.db.generated.WahlTeilnahmeTable
import network.lapis.cloud.server.db.generated.WahlWahlberechtigtTable
import network.lapis.cloud.server.db.generated.WahlWahlvorstandTable
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.canManageWahl
import network.lapis.cloud.server.security.canStandAsCandidate
import network.lapis.cloud.server.security.isWahlvorstand
import network.lapis.cloud.server.security.isWahlvorstandMember
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AntragStatus
import network.lapis.cloud.shared.domain.BeschlussInput
import network.lapis.cloud.shared.domain.BeschlussStatus
import network.lapis.cloud.shared.domain.GremiumRolle
import network.lapis.cloud.shared.domain.GremiumType
import network.lapis.cloud.shared.domain.KandidaturDto
import network.lapis.cloud.shared.domain.KandidaturInput
import network.lapis.cloud.shared.domain.ReceiptVerificationDto
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.StimmzettelCastResultDto
import network.lapis.cloud.shared.domain.StimmzettelDto
import network.lapis.cloud.shared.domain.StimmzettelInput
import network.lapis.cloud.shared.domain.WahlAntwort
import network.lapis.cloud.shared.domain.WahlDto
import network.lapis.cloud.shared.domain.WahlErgebnisDto
import network.lapis.cloud.shared.domain.WahlOpenInput
import network.lapis.cloud.shared.domain.WahlOptionDto
import network.lapis.cloud.shared.domain.WahlStatus
import network.lapis.cloud.shared.domain.WahlTyp
import network.lapis.cloud.shared.domain.WahlvorstandDto
import network.lapis.cloud.shared.rpc.IWahlService
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Server-side floor on [WahlOpenInput.tallyThreshold] -- at least one named Vier-Augen approval must be required. */
private const val MIN_TALLY_THRESHOLD = 1
private const val MIN_WAHLVORSTAND_SIZE = 3
private const val MAX_WAHLVORSTAND_SIZE = 25
private const val RECEIPT_CODE_BYTES = 20 // 160 bits, comfortably above the >=128-bit KDoc floor.
private const val RECEIPT_CODE_MAX_ATTEMPTS = 5

private val secureRandom = SecureRandom()

/**
 * Demokratische Wahlen (V0.2.4): one-person-one-vote elections/ballots. Implements [IWahlService]
 * -- see that interface's KDoc for the full lifecycle
 * (`openWahl` -> `appointWahlvorstand`/`submitKandidatur` -> `freigebenKandidatenliste` ->
 * `openVoting` -> `castStimme` -> `closeVoting` -> `freigebenAuszaehlung` -> `auszaehlen`) and
 * `03 Bereiche/Lapis Cloud/Demokratische Wahlen.md` for the concept document. Reuses
 * [insertBeschlussRow]/[computeQuorum] (extracted to `BeschlussBook.kt` in this same wave) and
 * [eligibleMemberIds] (extracted to `GremiumEligibility.kt`) so a Wahl's tally lands in the same
 * Beschlussbuch [GovernanceService] writes to, tagged [ResolutionMode.DEMOKRATISCH].
 *
 * Same "simple-transaction" style as [GovernanceService]: follow-up queries per row
 * ([memberDisplayName], option vote counts) rather than aliased multi-joins.
 */
class WahlService(
    private val call: ApplicationCall,
) : IWahlService {
    override suspend fun openWahl(input: WahlOpenInput): WahlDto {
        val current = resolveCurrentMember(call)
        val aId = input.antragId.toUuidOrNotFound("Antrag")
        return transaction {
            val antragRow =
                AntragTable.selectAll().where { AntragTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Antrag ${input.antragId} not found")
            val gremiumId = antragRow[AntragTable.targetGremiumId]
            if (!current.canManageWahl(gremiumId)) throw ForbiddenException()
            if (antragRow[AntragTable.status] != AntragStatus.TERMINIERT) {
                throw ConflictException("Antrag ${input.antragId} is ${antragRow[AntragTable.status]}, expected TERMINIERT")
            }
            val sId = antragRow[AntragTable.sitzungId] ?: throw ConflictException("Antrag ${input.antragId} has no scheduled Sitzung")

            val hasActiveWahl =
                WahlTable
                    .selectAll()
                    .where { (WahlTable.antragId eq aId) and (WahlTable.status neq WahlStatus.ABGEBROCHEN) }
                    .count() > 0
            if (hasActiveWahl) {
                throw ConflictException("Antrag ${input.antragId} already has an open or resolved Wahl")
            }

            if (input.wahlTyp == WahlTyp.LISTENWAHL || input.wahlTyp == WahlTyp.RANGLISTENWAHL) {
                throw ConflictException("${input.wahlTyp} is reserved for forward compatibility and not supported in V0.2.4")
            }
            if (input.requiredMajorityPercent !in 1..100) {
                throw ConflictException("requiredMajorityPercent must be in 1..100, got ${input.requiredMajorityPercent}")
            }
            if (input.tallyThreshold < MIN_TALLY_THRESHOLD) {
                throw ConflictException("tallyThreshold must be at least $MIN_TALLY_THRESHOLD")
            }

            val zielGremiumId = input.zielGremiumId?.toUuidOrNotFound("Gremium")
            when (input.wahlTyp) {
                WahlTyp.JA_NEIN -> {
                    if (zielGremiumId != null) {
                        throw ConflictException("zielGremiumId must be null for WahlTyp.JA_NEIN, which seats nobody")
                    }
                }
                WahlTyp.EINZELWAHL, WahlTyp.MEHRFACHWAHL -> {
                    if (zielGremiumId == null) {
                        throw ConflictException("zielGremiumId is required for WahlTyp.${input.wahlTyp}")
                    }
                    GremiumTable.selectAll().where { GremiumTable.id eq zielGremiumId }.singleOrNull()
                        ?: throw NotFoundException("Gremium ${input.zielGremiumId} not found")
                    if (input.wahlTyp == WahlTyp.EINZELWAHL && input.sitzeCount != 1) {
                        throw ConflictException("sitzeCount must be 1 for WahlTyp.EINZELWAHL, got ${input.sitzeCount}")
                    }
                    if (input.sitzeCount < 1) {
                        throw ConflictException("sitzeCount must be at least 1, got ${input.sitzeCount}")
                    }
                }
                WahlTyp.LISTENWAHL, WahlTyp.RANGLISTENWAHL -> Unit // unreachable, rejected above
            }

            val id = Uuid.random()
            val now = nowLocalDateTime()
            WahlTable.insert {
                it[WahlTable.id] = id
                it[WahlTable.antragId] = aId
                it[WahlTable.sitzungId] = sId
                it[title] = antragRow[AntragTable.title]
                it[wahlTyp] = input.wahlTyp
                it[geheim] = input.geheim
                it[sitzeCount] = input.sitzeCount
                it[WahlTable.zielGremiumId] = zielGremiumId
                it[zielRolle] = input.zielRolle
                it[requiredMajorityPercent] = input.requiredMajorityPercent
                it[status] = WahlStatus.VORBEREITUNG
                it[openedBy] = current.memberId
                it[openedAt] = now
                it[candidateListApprovedAt] = null
                it[votingOpenedAt] = null
                it[votingClosedAt] = null
                it[tallyThreshold] = input.tallyThreshold
                it[tallyRunAt] = null
                it[beschlussId] = null
            }
            if (input.wahlTyp == WahlTyp.JA_NEIN) {
                listOf(WahlAntwort.JA, WahlAntwort.NEIN, WahlAntwort.ENTHALTUNG).forEachIndexed { index, antwort ->
                    WahlOptionTable.insert {
                        it[WahlOptionTable.id] = Uuid.random()
                        it[WahlOptionTable.wahlId] = id
                        it[label] = antwort.name
                        it[position] = index
                        it[kandidaturId] = null
                    }
                }
            }
            loadWahl(id)
        }
    }

    override suspend fun appointWahlvorstand(
        wahlId: String,
        memberIds: List<String>,
    ): List<WahlvorstandDto> {
        val current = resolveCurrentMember(call)
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            val wahlRow = requireWahlRow(wId)
            if (!current.canManageWahl(requireAntragGremiumId(wahlRow[WahlTable.antragId]))) throw ForbiddenException()
            if (wahlRow[WahlTable.status] != WahlStatus.VORBEREITUNG) {
                throw ConflictException("Wahl $wahlId is ${wahlRow[WahlTable.status]}, expected VORBEREITUNG")
            }
            val distinctIds = memberIds.map { it.toUuidOrNotFound("Member") }.distinct()
            if (distinctIds.size < MIN_WAHLVORSTAND_SIZE || distinctIds.size > MAX_WAHLVORSTAND_SIZE) {
                throw ConflictException(
                    "appointWahlvorstand requires between $MIN_WAHLVORSTAND_SIZE and $MAX_WAHLVORSTAND_SIZE distinct members, " +
                        "got ${distinctIds.size}",
                )
            }
            distinctIds.forEach { mId ->
                MemberTable.selectAll().where { MemberTable.id eq mId }.singleOrNull()
                    ?: throw NotFoundException("Member $mId not found")
            }
            val zielGremiumId = wahlRow[WahlTable.zielGremiumId]
            if (zielGremiumId != null) {
                val zielGremiumType = GremiumTable.selectAll().where { GremiumTable.id eq zielGremiumId }.single()[GremiumTable.type]
                if (zielGremiumType == GremiumType.VORSTAND) {
                    val vorstandMemberIds =
                        GremiumMitgliedschaftTable
                            .selectAll()
                            .where {
                                (GremiumMitgliedschaftTable.gremiumId eq zielGremiumId) and (GremiumMitgliedschaftTable.until.isNull())
                            }.map { it[GremiumMitgliedschaftTable.memberId] }
                            .toSet()
                    val conflicting = distinctIds.filter { it in vorstandMemberIds }
                    if (conflicting.isNotEmpty()) {
                        throw ConflictException(
                            "Members $conflicting are active members of target Vorstand Gremium $zielGremiumId and cannot " +
                                "also serve on its Wahlvorstand",
                        )
                    }
                }
            }
            WahlWahlvorstandTable.deleteWhere { WahlWahlvorstandTable.wahlId eq wId }
            val now = nowLocalDateTime()
            distinctIds.forEach { mId ->
                WahlWahlvorstandTable.insert {
                    it[WahlWahlvorstandTable.id] = Uuid.random()
                    it[WahlWahlvorstandTable.wahlId] = wId
                    it[WahlWahlvorstandTable.memberId] = mId
                    it[appointedAt] = now
                }
            }
            loadWahlvorstand(wId)
        }
    }

    override suspend fun listWahlvorstand(wahlId: String): List<WahlvorstandDto> {
        resolveCurrentMember(call)
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            requireWahlRow(wId)
            loadWahlvorstand(wId)
        }
    }

    override suspend fun submitKandidatur(
        wahlId: String,
        input: KandidaturInput,
    ): KandidaturDto {
        val current = resolveCurrentMember(call)
        if (!current.canStandAsCandidate()) throw ForbiddenException()
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            val wahlRow = requireWahlRow(wId)
            if (wahlRow[WahlTable.status] != WahlStatus.VORBEREITUNG) {
                throw ConflictException("Wahl $wahlId is ${wahlRow[WahlTable.status]}, expected VORBEREITUNG")
            }
            val wahlTyp = wahlRow[WahlTable.wahlTyp]
            if (wahlTyp != WahlTyp.EINZELWAHL && wahlTyp != WahlTyp.MEHRFACHWAHL) {
                throw ConflictException("Wahl $wahlId is $wahlTyp, Kandidaturen only apply to EINZELWAHL/MEHRFACHWAHL")
            }
            val hasActiveCandidacy =
                WahlKandidaturTable
                    .selectAll()
                    .where {
                        (WahlKandidaturTable.wahlId eq wId) and
                            (WahlKandidaturTable.memberId eq current.memberId) and
                            (WahlKandidaturTable.withdrawnAt.isNull())
                    }.count() > 0
            if (hasActiveCandidacy) throw ConflictException("Member ${current.memberId} already has an active Kandidatur for Wahl $wahlId")
            val id = Uuid.random()
            WahlKandidaturTable.insert {
                it[WahlKandidaturTable.id] = id
                it[WahlKandidaturTable.wahlId] = wId
                it[WahlKandidaturTable.memberId] = current.memberId
                it[motivationText] = input.motivationText
                it[submittedAt] = nowLocalDateTime()
                it[withdrawnAt] = null
            }
            loadKandidatur(id)
        }
    }

    override suspend fun withdrawKandidatur(id: String): KandidaturDto {
        val current = resolveCurrentMember(call)
        val kId = id.toUuidOrNotFound("Kandidatur")
        return transaction {
            val row =
                WahlKandidaturTable.selectAll().where { WahlKandidaturTable.id eq kId }.singleOrNull()
                    ?: throw NotFoundException("Kandidatur $id not found")
            val wId = row[WahlKandidaturTable.wahlId]
            val wahlRow = requireWahlRow(wId)
            val candidateId = row[WahlKandidaturTable.memberId]
            val selfWhileVorbereitung = current.memberId == candidateId && wahlRow[WahlTable.status] == WahlStatus.VORBEREITUNG
            val gremiumId = requireAntragGremiumId(wahlRow[WahlTable.antragId])
            if (!selfWhileVorbereitung && !current.canManageWahl(gremiumId)) throw ForbiddenException()
            if (row[WahlKandidaturTable.withdrawnAt] != null) throw ConflictException("Kandidatur $id already withdrawn")
            WahlKandidaturTable.update({ WahlKandidaturTable.id eq kId }) {
                it[withdrawnAt] = nowLocalDateTime()
            }
            loadKandidatur(kId)
        }
    }

    override suspend fun listKandidaturen(wahlId: String): List<KandidaturDto> {
        resolveCurrentMember(call)
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            requireWahlRow(wId)
            WahlKandidaturTable
                .selectAll()
                .where { WahlKandidaturTable.wahlId eq wId }
                .map { it.toKandidaturDto() }
        }
    }

    override suspend fun freigebenKandidatenliste(wahlId: String): WahlDto {
        val current = resolveCurrentMember(call)
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            val wahlRow = requireWahlRow(wId)
            if (!current.canManageWahl(requireAntragGremiumId(wahlRow[WahlTable.antragId]))) throw ForbiddenException()
            if (wahlRow[WahlTable.status] != WahlStatus.VORBEREITUNG) {
                throw ConflictException("Wahl $wahlId is ${wahlRow[WahlTable.status]}, expected VORBEREITUNG")
            }
            val candidacies =
                WahlKandidaturTable
                    .selectAll()
                    .where { (WahlKandidaturTable.wahlId eq wId) and (WahlKandidaturTable.withdrawnAt.isNull()) }
                    .orderBy(WahlKandidaturTable.submittedAt, SortOrder.ASC)
                    .toList()
            if (candidacies.isEmpty()) throw ConflictException("Wahl $wahlId has no non-withdrawn Kandidatur to freigeben")
            candidacies.forEachIndexed { index, row ->
                val label = memberDisplayName(row[WahlKandidaturTable.memberId]).orEmpty()
                WahlOptionTable.insert {
                    it[WahlOptionTable.id] = Uuid.random()
                    it[WahlOptionTable.wahlId] = wId
                    it[WahlOptionTable.label] = label
                    it[position] = index
                    it[kandidaturId] = row[WahlKandidaturTable.id]
                }
            }
            WahlTable.update({ WahlTable.id eq wId }) {
                it[status] = WahlStatus.KANDIDATENLISTE_FREIGEGEBEN
                it[candidateListApprovedAt] = nowLocalDateTime()
            }
            loadWahl(wId)
        }
    }

    override suspend fun openVoting(wahlId: String): WahlDto {
        val current = resolveCurrentMember(call)
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            val wahlRow = requireWahlRow(wId)
            if (!current.isWahlvorstand(wId)) throw ForbiddenException()
            val expectedStatus =
                if (wahlRow[WahlTable.wahlTyp] == WahlTyp.JA_NEIN) WahlStatus.VORBEREITUNG else WahlStatus.KANDIDATENLISTE_FREIGEGEBEN
            if (wahlRow[WahlTable.status] != expectedStatus) {
                throw ConflictException("Wahl $wahlId is ${wahlRow[WahlTable.status]}, expected $expectedStatus")
            }
            val gremiumId = requireAntragGremiumId(wahlRow[WahlTable.antragId])
            val sitzungRow = SitzungTable.selectAll().where { SitzungTable.id eq wahlRow[WahlTable.sitzungId] }.single()
            val scheduledDate = sitzungRow[SitzungTable.scheduledAt].date
            val gremiumRow = GremiumTable.selectAll().where { GremiumTable.id eq gremiumId }.single()
            val eligible = eligibleMemberIds(gremiumRow, scheduledDate)
            eligible.forEach { mId ->
                WahlWahlberechtigtTable.insert {
                    it[WahlWahlberechtigtTable.id] = Uuid.random()
                    it[WahlWahlberechtigtTable.wahlId] = wId
                    it[WahlWahlberechtigtTable.memberId] = mId
                }
            }
            WahlTable.update({ WahlTable.id eq wId }) {
                it[status] = WahlStatus.OFFEN
                it[votingOpenedAt] = nowLocalDateTime()
            }
            loadWahl(wId)
        }
    }

    override suspend fun castStimme(input: StimmzettelInput): StimmzettelCastResultDto {
        val current = resolveCurrentMember(call)
        val wId = input.wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            val wahlRow = requireWahlRow(wId)
            if (wahlRow[WahlTable.status] != WahlStatus.OFFEN) {
                throw ConflictException("Wahl ${input.wahlId} is ${wahlRow[WahlTable.status]}, expected OFFEN")
            }
            val eligible =
                WahlWahlberechtigtTable
                    .selectAll()
                    .where { (WahlWahlberechtigtTable.wahlId eq wId) and (WahlWahlberechtigtTable.memberId eq current.memberId) }
                    .count() > 0
            if (!eligible) throw ForbiddenException()

            val wahlTyp = wahlRow[WahlTable.wahlTyp]
            val selectedOptionIds: List<Uuid> =
                if (wahlTyp == WahlTyp.JA_NEIN) {
                    val antwort = input.antwort
                    if (antwort == null || input.selectedOptionIds.isNotEmpty()) {
                        throw ConflictException("A JA_NEIN ballot must set antwort and leave selectedOptionIds empty")
                    }
                    val optionRow =
                        WahlOptionTable
                            .selectAll()
                            .where { (WahlOptionTable.wahlId eq wId) and (WahlOptionTable.label eq antwort.name) }
                            .single()
                    listOf(optionRow[WahlOptionTable.id])
                } else {
                    if (input.antwort != null) {
                        throw ConflictException("A personnel ballot must leave antwort null")
                    }
                    val distinctSelections = input.selectedOptionIds.map { it.toUuidOrNotFound("WahlOption") }.distinct()
                    if (distinctSelections.size != input.selectedOptionIds.size) {
                        throw ConflictException("selectedOptionIds must not contain duplicates")
                    }
                    if (distinctSelections.isEmpty() || distinctSelections.size > wahlRow[WahlTable.sitzeCount]) {
                        throw ConflictException(
                            "selectedOptionIds must contain between 1 and ${wahlRow[WahlTable.sitzeCount]} distinct options, " +
                                "got ${distinctSelections.size}",
                        )
                    }
                    val validOptionIds =
                        WahlOptionTable
                            .selectAll()
                            .where { WahlOptionTable.wahlId eq wId }
                            .map { it[WahlOptionTable.id] }
                            .toSet()
                    if (!validOptionIds.containsAll(distinctSelections)) {
                        throw ConflictException("selectedOptionIds must all belong to Wahl ${input.wahlId}")
                    }
                    distinctSelections
                }

            val geheim = wahlRow[WahlTable.geheim]
            val now = nowLocalDateTime()
            // De-anonymization guard: wahl_teilnahme.voted_at (carries member_id) and
            // wahl_stimmzettel.cast_at (the anonymous ballot) must NOT be derived from the same
            // instant for a geheim Wahl -- ballots are serialized, so a bit-identical timestamp on
            // both rows would let anyone join `voted_at = cast_at` and re-link every "secret"
            // ballot back to its voter, defeating the whole point of WahlTeilnahmeTable being the
            // only member-bearing row on this path (see WahlTables KDoc). Coarsening the ballot's
            // own timestamp down to the day (calendar date, no time-of-day) breaks that 1:1 join --
            // many ballots cast on the same day for the same Wahl now share an identical cast_at,
            // while voted_at keeps full precision for legitimate "did this member vote" audit
            // purposes. Non-secret ballots have no anonymity to protect (member_id is stored
            // in the clear on wahl_stimmzettel itself), so they keep full timestamp precision.
            val castAt = if (geheim) LocalDateTime(now.date, LocalTime(0, 0)) else now
            try {
                if (geheim) {
                    val alreadyVoted =
                        WahlTeilnahmeTable
                            .selectAll()
                            .where { (WahlTeilnahmeTable.wahlId eq wId) and (WahlTeilnahmeTable.memberId eq current.memberId) }
                            .count() > 0
                    if (alreadyVoted) throw ConflictException("Member ${current.memberId} already voted in Wahl ${input.wahlId}")
                    WahlTeilnahmeTable.insert {
                        it[WahlTeilnahmeTable.id] = Uuid.random()
                        it[WahlTeilnahmeTable.wahlId] = wId
                        it[WahlTeilnahmeTable.memberId] = current.memberId
                        it[votedAt] = now
                    }
                } else {
                    val alreadyVoted =
                        WahlStimmzettelTable
                            .selectAll()
                            .where { (WahlStimmzettelTable.wahlId eq wId) and (WahlStimmzettelTable.memberId eq current.memberId) }
                            .count() > 0
                    if (alreadyVoted) throw ConflictException("Member ${current.memberId} already voted in Wahl ${input.wahlId}")
                }

                val stimmzettelId = Uuid.random()
                val receiptCode = generateUniqueReceiptCode(wId)
                WahlStimmzettelTable.insert {
                    it[WahlStimmzettelTable.id] = stimmzettelId
                    it[WahlStimmzettelTable.wahlId] = wId
                    it[WahlStimmzettelTable.memberId] = if (geheim) null else current.memberId
                    it[WahlStimmzettelTable.receiptCode] = receiptCode
                    it[WahlStimmzettelTable.castAt] = castAt
                }
                selectedOptionIds.forEach { optionId ->
                    WahlStimmzettelAuswahlTable.insert {
                        it[WahlStimmzettelAuswahlTable.id] = Uuid.random()
                        it[WahlStimmzettelAuswahlTable.stimmzettelId] = stimmzettelId
                        it[WahlStimmzettelAuswahlTable.optionId] = optionId
                    }
                }
                StimmzettelCastResultDto(
                    id = stimmzettelId.toString(),
                    castAt = castAt,
                    receiptCode = if (geheim) receiptCode else null,
                )
            } catch (e: ExposedSQLException) {
                // The application-level pre-checks above are racy under concurrency on their own;
                // the DB-level UNIQUE(wahl_id, member_id) constraint (on wahl_teilnahme for the
                // secret path, on wahl_stimmzettel for the non-secret path) is the real backstop.
                // A caught violation here always means "someone already voted" -- surface it as a
                // ConflictException, letting the whole transaction roll back.
                throw ConflictException("Member ${current.memberId} already voted in Wahl ${input.wahlId}")
            }
        }
    }

    override suspend fun closeVoting(wahlId: String): WahlDto {
        val current = resolveCurrentMember(call)
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            requireWahlRow(wId)
            if (!current.isWahlvorstand(wId)) throw ForbiddenException()
            val statusNow = WahlTable.selectAll().where { WahlTable.id eq wId }.single()[WahlTable.status]
            if (statusNow != WahlStatus.OFFEN) throw ConflictException("Wahl $wahlId is $statusNow, expected OFFEN")
            WahlTable.update({ WahlTable.id eq wId }) {
                it[status] = WahlStatus.GESCHLOSSEN
                it[votingClosedAt] = nowLocalDateTime()
            }
            loadWahl(wId)
        }
    }

    override suspend fun freigebenAuszaehlung(wahlId: String): WahlDto {
        val current = resolveCurrentMember(call)
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            val wahlRow = requireWahlRow(wId)
            if (!current.isWahlvorstandMember(wId)) throw ForbiddenException()
            if (wahlRow[WahlTable.status] != WahlStatus.GESCHLOSSEN) {
                throw ConflictException("Wahl $wahlId is ${wahlRow[WahlTable.status]}, expected GESCHLOSSEN")
            }
            val alreadyApproved =
                WahlFreigabeTable
                    .selectAll()
                    .where { (WahlFreigabeTable.wahlId eq wId) and (WahlFreigabeTable.memberId eq current.memberId) }
                    .count() > 0
            if (alreadyApproved) throw ConflictException("Member ${current.memberId} already approved Auszaehlung for Wahl $wahlId")
            WahlFreigabeTable.insert {
                it[WahlFreigabeTable.id] = Uuid.random()
                it[WahlFreigabeTable.wahlId] = wId
                it[WahlFreigabeTable.memberId] = current.memberId
                it[approvedAt] = nowLocalDateTime()
            }
            loadWahl(wId)
        }
    }

    override suspend fun auszaehlen(wahlId: String): WahlErgebnisDto {
        val current = resolveCurrentMember(call)
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            val wahlRow = requireWahlRow(wId)
            if (!current.isWahlvorstand(wId)) throw ForbiddenException()
            if (wahlRow[WahlTable.status] != WahlStatus.GESCHLOSSEN) {
                throw ConflictException("Wahl $wahlId is ${wahlRow[WahlTable.status]}, expected GESCHLOSSEN")
            }
            val approvalCount = WahlFreigabeTable.selectAll().where { WahlFreigabeTable.wahlId eq wId }.count()
            val threshold = wahlRow[WahlTable.tallyThreshold]
            if (approvalCount < threshold) {
                throw ConflictException("Wahl $wahlId has $approvalCount/$threshold required Auszaehlung approvals")
            }

            val optionRows =
                WahlOptionTable
                    .selectAll()
                    .where { WahlOptionTable.wahlId eq wId }
                    .orderBy(WahlOptionTable.position)
                    .toList()
            val optionIds = optionRows.map { it[WahlOptionTable.id] }
            val labelByOptionId = optionRows.associate { it[WahlOptionTable.id] to it[WahlOptionTable.label] }
            val auswahlRows =
                (WahlStimmzettelAuswahlTable innerJoin WahlStimmzettelTable)
                    .selectAll()
                    .where { WahlStimmzettelTable.wahlId eq wId }
                    .toList()
            val selectionsByStimmzettel =
                auswahlRows
                    .groupBy({ it[WahlStimmzettelAuswahlTable.stimmzettelId] }, { it[WahlStimmzettelAuswahlTable.optionId] })

            val wahlTyp = wahlRow[WahlTable.wahlTyp]
            val ergebnis: WahlErgebnisDto
            val beschlussStatus: BeschlussStatus
            val votesYes: Int
            val votesNo: Int
            val votesAbstain: Int

            if (wahlTyp == WahlTyp.JA_NEIN) {
                val stimmen =
                    selectionsByStimmzettel.values.map { selectedIds ->
                        WahlAntwort.valueOf(labelByOptionId.getValue(selectedIds.single()))
                    }
                val jaNein = computeJaNeinErgebnis(stimmen, wahlRow[WahlTable.requiredMajorityPercent])
                val jaOptionId = optionRows.single { it[WahlOptionTable.label] == WahlAntwort.JA.name }[WahlOptionTable.id]
                val neinOptionId = optionRows.single { it[WahlOptionTable.label] == WahlAntwort.NEIN.name }[WahlOptionTable.id]
                val enthaltungOptionId = optionRows.single { it[WahlOptionTable.label] == WahlAntwort.ENTHALTUNG.name }[WahlOptionTable.id]
                val winnerOptionIds =
                    if (jaNein.tie) {
                        emptyList()
                    } else if (jaNein.majorityMet) {
                        listOf(jaOptionId.toString())
                    } else {
                        listOf(neinOptionId.toString())
                    }
                ergebnis =
                    WahlErgebnisDto(
                        wahlId = wahlId,
                        winnerOptionIds = winnerOptionIds,
                        tie = jaNein.tie,
                        majorityMet = jaNein.majorityMet,
                        perOptionVotes =
                            mapOf(
                                jaOptionId.toString() to jaNein.ja,
                                neinOptionId.toString() to jaNein.nein,
                                enthaltungOptionId.toString() to jaNein.enthaltung,
                            ),
                    )
                beschlussStatus =
                    if (jaNein.tie) {
                        BeschlussStatus.VERTAGT
                    } else if (jaNein.majorityMet) {
                        BeschlussStatus.ANGENOMMEN
                    } else {
                        BeschlussStatus.ABGELEHNT
                    }
                votesYes = jaNein.ja
                votesNo = jaNein.nein
                votesAbstain = jaNein.enthaltung
            } else {
                val stimmen = selectionsByStimmzettel.values.map { WahlStimme(optionIds = it) }
                val personenwahl = computePersonenwahlErgebnis(stimmen, optionIds, wahlRow[WahlTable.sitzeCount])
                // EINZELWAHL requires an absolute majority of the votes cast, not merely a
                // plurality -- see `03 Bereiche/Lapis Cloud/Demokratische Wahlen.md` Wahltypen
                // table ("Absolute Mehrheit, ggf. Stichwahl"). computePersonenwahlErgebnis alone
                // implements top-n-by-plurality (correct for MEHRFACHWAHL, insufficient for
                // EINZELWAHL on its own), so the majority check is layered on top here, reusing
                // the same requiredMajorityPercent field the JA_NEIN branch above already applies.
                // Only meaningful in the genuinely *contested* case (more candidates than seats) --
                // the undersubscribed single-candidate case is left to computePersonenwahlErgebnis's
                // own documented "uncontested seat needs no ballot" convention. A winner who fails
                // the majority requirement resolves the whole Wahl to VERTAGT (no winner seated),
                // signalling that a Stichwahl (runoff) is required -- same "tie is the safe,
                // non-manipulable default" philosophy as a seat-cutoff tie.
                val contested = optionIds.size > wahlRow[WahlTable.sitzeCount]
                val einzelwahlMajorityMet =
                    if (wahlTyp == WahlTyp.EINZELWAHL && contested && !personenwahl.tie && personenwahl.winnerOptionIds.isNotEmpty()) {
                        val totalVotes = personenwahl.voteCounts.values.sum()
                        val winnerVotes = personenwahl.voteCounts.getValue(personenwahl.winnerOptionIds.single())
                        val requiredPercent = wahlRow[WahlTable.requiredMajorityPercent]
                        totalVotes > 0 && winnerVotes.toLong() * 100 >= requiredPercent.toLong() * totalVotes
                    } else {
                        true
                    }
                val effectiveTie = personenwahl.tie || !einzelwahlMajorityMet
                val effectiveWinnerOptionIds = if (effectiveTie) emptyList() else personenwahl.winnerOptionIds
                ergebnis =
                    WahlErgebnisDto(
                        wahlId = wahlId,
                        winnerOptionIds = effectiveWinnerOptionIds.map { it.toString() },
                        tie = effectiveTie,
                        majorityMet = null,
                        perOptionVotes = personenwahl.voteCounts.mapKeys { (optionId, _) -> optionId.toString() },
                    )
                beschlussStatus = if (effectiveTie) BeschlussStatus.VERTAGT else BeschlussStatus.ANGENOMMEN
                votesYes = 0
                votesNo = 0
                votesAbstain = 0

                if (!effectiveTie && effectiveWinnerOptionIds.isNotEmpty()) {
                    val zielGremiumId =
                        wahlRow[WahlTable.zielGremiumId]
                            ?: throw ConflictException("Wahl $wahlId has no zielGremiumId to seat winners into")
                    val zielRolle = wahlRow[WahlTable.zielRolle] ?: GremiumRolle.MITGLIED
                    val kandidaturIdByOptionId = optionRows.associate { it[WahlOptionTable.id] to it[WahlOptionTable.kandidaturId] }
                    val today =
                        Clock.System
                            .now()
                            .toLocalDateTime(TimeZone.currentSystemDefault())
                            .date
                    effectiveWinnerOptionIds.forEach { winnerOptionId ->
                        val kandidaturId =
                            kandidaturIdByOptionId[winnerOptionId]
                                ?: throw ConflictException("Winning option $winnerOptionId has no Kandidatur")
                        val winnerMemberId =
                            WahlKandidaturTable
                                .selectAll()
                                .where { WahlKandidaturTable.id eq kandidaturId }
                                .single()[WahlKandidaturTable.memberId]
                        // Guarded seat, mirroring the single-active-membership invariant
                        // GovernanceService.addGremiumMitglied enforces (GovernanceService.kt
                        // ~200-212): an incumbent who wins re-election (or is elected into a new
                        // Rolle, e.g. a sitting MITGLIED elected VORSITZ) already has an active
                        // (until == null) row for this Gremium. Closing it before inserting the
                        // fresh term -- instead of an unconditional insert -- prevents a second
                        // concurrent active membership row for the same member+Gremium, which
                        // would silently corrupt membership history in a legally-relevant
                        // Vereins-/Parteiverwaltung (a later removeGremiumMitglied/
                        // endGremiumMitgliedschaft closes only one row, leaving a phantom active
                        // membership behind).
                        GremiumMitgliedschaftTable.update({
                            (GremiumMitgliedschaftTable.gremiumId eq zielGremiumId) and
                                (GremiumMitgliedschaftTable.memberId eq winnerMemberId) and
                                (GremiumMitgliedschaftTable.until.isNull())
                        }) {
                            it[until] = today
                        }
                        GremiumMitgliedschaftTable.insert {
                            it[GremiumMitgliedschaftTable.id] = Uuid.random()
                            it[GremiumMitgliedschaftTable.gremiumId] = zielGremiumId
                            it[GremiumMitgliedschaftTable.memberId] = winnerMemberId
                            it[rolle] = zielRolle
                            it[since] = today
                            it[until] = null
                        }
                    }
                }
            }

            val sitzung = SitzungTable.selectAll().where { SitzungTable.id eq wahlRow[WahlTable.sitzungId] }.single()
            val gremiumId = requireAntragGremiumId(wahlRow[WahlTable.antragId])
            val antragRow = AntragTable.selectAll().where { AntragTable.id eq wahlRow[WahlTable.antragId] }.single()
            val beschlussInput =
                BeschlussInput(
                    tagesordnungspunktId = antragRow[AntragTable.tagesordnungspunktId]?.toString(),
                    title = antragRow[AntragTable.title],
                    text = antragRow[AntragTable.text],
                    votesYes = votesYes,
                    votesNo = votesNo,
                    votesAbstain = votesAbstain,
                    status = beschlussStatus,
                )
            val beschluss =
                insertBeschlussRow(
                    wahlRow[WahlTable.sitzungId],
                    gremiumId,
                    sitzung[SitzungTable.scheduledAt].date,
                    beschlussInput,
                    current,
                    resolutionMode = ResolutionMode.DEMOKRATISCH,
                    wahlId = wId,
                )

            val newAntragStatus =
                when (beschlussStatus) {
                    BeschlussStatus.ANGENOMMEN -> AntragStatus.BESCHLOSSEN
                    BeschlussStatus.ABGELEHNT -> AntragStatus.ABGELEHNT
                    BeschlussStatus.VERTAGT -> AntragStatus.VERTAGT
                }
            AntragTable.update({ AntragTable.id eq antragRow[AntragTable.id] }) {
                it[AntragTable.status] = newAntragStatus
                it[AntragTable.beschlussId] = Uuid.parse(beschluss.id)
            }

            WahlTable.update({ WahlTable.id eq wId }) {
                it[status] = WahlStatus.AUSGEZAEHLT
                it[tallyRunAt] = nowLocalDateTime()
                it[WahlTable.beschlussId] = Uuid.parse(beschluss.id)
            }
            ergebnis
        }
    }

    override suspend fun abortWahl(wahlId: String): WahlDto {
        val current = resolveCurrentMember(call)
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            val wahlRow = requireWahlRow(wId)
            if (!current.canManageWahl(requireAntragGremiumId(wahlRow[WahlTable.antragId]))) throw ForbiddenException()
            if (wahlRow[WahlTable.status] == WahlStatus.AUSGEZAEHLT || wahlRow[WahlTable.status] == WahlStatus.ABGEBROCHEN) {
                throw ConflictException("Wahl $wahlId is already ${wahlRow[WahlTable.status]}")
            }
            WahlTable.update({ WahlTable.id eq wId }) { it[status] = WahlStatus.ABGEBROCHEN }
            loadWahl(wId)
        }
    }

    override suspend fun getWahl(wahlId: String): WahlDto {
        resolveCurrentMember(call)
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction { loadWahl(wId) }
    }

    override suspend fun listWahlen(
        antragId: String?,
        status: WahlStatus?,
    ): List<WahlDto> {
        resolveCurrentMember(call)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (antragId != null) conditions += (WahlTable.antragId eq antragId.toUuidOrNotFound("Antrag"))
            if (status != null) conditions += (WahlTable.status eq status)
            val baseQuery = WahlTable.selectAll()
            val query = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            query.map { it.toWahlDto() }
        }
    }

    override suspend fun listStimmzettel(wahlId: String): List<StimmzettelDto> {
        resolveCurrentMember(call)
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            val wahlRow = requireWahlRow(wId)
            // Pre-tally secrecy gate: same invariant as WahlOptionDto.voteCount (held at 0 until
            // AUSGEZAEHLT) and verifyReceipt (optionLabel null until AUSGEZAEHLT). Without this,
            // any authenticated member could enumerate every anonymized ballot's plaintext choice
            // while a geheim Wahl is still OFFEN/GESCHLOSSEN and tally it themselves, learning a
            // partial result mid-vote -- see StimmzettelDto KDoc.
            val revealLabels = !wahlRow[WahlTable.geheim] || wahlRow[WahlTable.status] == WahlStatus.AUSGEZAEHLT
            WahlStimmzettelTable
                .selectAll()
                .where { WahlStimmzettelTable.wahlId eq wId }
                .map { it.toStimmzettelDto(revealLabels) }
        }
    }

    override suspend fun verifyReceipt(
        wahlId: String,
        receiptCode: String,
    ): ReceiptVerificationDto {
        resolveCurrentMember(call)
        val wId = wahlId.toUuidOrNotFound("Wahl")
        return transaction {
            val wahlRow = requireWahlRow(wId)
            val stimmzettelRow =
                WahlStimmzettelTable
                    .selectAll()
                    .where { (WahlStimmzettelTable.wahlId eq wId) and (WahlStimmzettelTable.receiptCode eq receiptCode) }
                    .singleOrNull()
                    ?: return@transaction ReceiptVerificationDto(found = false, optionLabel = null)
            val optionLabel =
                if (wahlRow[WahlTable.status] == WahlStatus.AUSGEZAEHLT) {
                    (WahlStimmzettelAuswahlTable innerJoin WahlOptionTable)
                        .selectAll()
                        .where { WahlStimmzettelAuswahlTable.stimmzettelId eq stimmzettelRow[WahlStimmzettelTable.id] }
                        .map { it[WahlOptionTable.label] }
                        .joinToString(", ")
                        .ifBlank { null }
                } else {
                    null
                }
            ReceiptVerificationDto(found = true, optionLabel = optionLabel)
        }
    }

    private fun requireWahlRow(wahlId: Uuid): ResultRow =
        WahlTable.selectAll().where { WahlTable.id eq wahlId }.singleOrNull() ?: throw NotFoundException("Wahl $wahlId not found")

    private fun requireAntragGremiumId(antragId: Uuid): Uuid =
        AntragTable.selectAll().where { AntragTable.id eq antragId }.single()[AntragTable.targetGremiumId]

    private fun loadWahl(id: Uuid): WahlDto =
        WahlTable
            .selectAll()
            .where { WahlTable.id eq id }
            .singleOrNull()
            ?.toWahlDto() ?: throw NotFoundException("Wahl $id not found")

    private fun loadWahlvorstand(wahlId: Uuid): List<WahlvorstandDto> =
        WahlWahlvorstandTable
            .selectAll()
            .where { WahlWahlvorstandTable.wahlId eq wahlId }
            .map { it.toWahlvorstandDto() }

    private fun loadKandidatur(id: Uuid): KandidaturDto =
        WahlKandidaturTable
            .selectAll()
            .where { WahlKandidaturTable.id eq id }
            .single()
            .toKandidaturDto()

    private fun memberDisplayName(memberId: Uuid?): String? =
        memberId?.let { mId ->
            MemberTable
                .selectAll()
                .where { MemberTable.id eq mId }
                .singleOrNull()
                ?.get(MemberTable.displayName)
        }

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    /**
     * Retries a small, bounded number of times on a receipt-code collision (astronomically
     * unlikely at [RECEIPT_CODE_BYTES] bytes of [SecureRandom] entropy, but checked rather than
     * assumed) -- see [WahlStimmzettelTable] KDoc.
     */
    private fun generateUniqueReceiptCode(wahlId: Uuid): String {
        repeat(RECEIPT_CODE_MAX_ATTEMPTS) {
            val bytes = ByteArray(RECEIPT_CODE_BYTES)
            secureRandom.nextBytes(bytes)
            val candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val exists =
                WahlStimmzettelTable
                    .selectAll()
                    .where { (WahlStimmzettelTable.wahlId eq wahlId) and (WahlStimmzettelTable.receiptCode eq candidate) }
                    .count() > 0
            if (!exists) return candidate
        }
        throw ConflictException("Failed to generate a unique receipt code for Wahl $wahlId after $RECEIPT_CODE_MAX_ATTEMPTS attempts")
    }

    private fun ResultRow.toWahlDto(): WahlDto {
        val wahlId = this[WahlTable.id]
        val status = this[WahlTable.status]
        val optionRows =
            WahlOptionTable
                .selectAll()
                .where { WahlOptionTable.wahlId eq wahlId }
                .orderBy(WahlOptionTable.position)
                .toList()
        val voteCountByOptionId =
            if (status == WahlStatus.AUSGEZAEHLT) {
                val optionIds = optionRows.map { it[WahlOptionTable.id] }
                WahlStimmzettelAuswahlTable
                    .selectAll()
                    .where { WahlStimmzettelAuswahlTable.optionId inList optionIds }
                    .groupingBy { it[WahlStimmzettelAuswahlTable.optionId] }
                    .eachCount()
            } else {
                emptyMap()
            }
        val options =
            optionRows.map { optRow ->
                val optionId = optRow[WahlOptionTable.id]
                WahlOptionDto(
                    id = optionId.toString(),
                    wahlId = wahlId.toString(),
                    label = optRow[WahlOptionTable.label],
                    position = optRow[WahlOptionTable.position],
                    kandidaturId = optRow[WahlOptionTable.kandidaturId]?.toString(),
                    voteCount = voteCountByOptionId[optionId] ?: 0,
                )
            }
        val zielGremiumId = this[WahlTable.zielGremiumId]
        return WahlDto(
            id = wahlId.toString(),
            antragId = this[WahlTable.antragId].toString(),
            sitzungId = this[WahlTable.sitzungId].toString(),
            title = this[WahlTable.title],
            wahlTyp = this[WahlTable.wahlTyp],
            geheim = this[WahlTable.geheim],
            sitzeCount = this[WahlTable.sitzeCount],
            zielGremiumId = zielGremiumId?.toString(),
            zielGremiumName =
                zielGremiumId?.let { gId ->
                    GremiumTable
                        .selectAll()
                        .where { GremiumTable.id eq gId }
                        .singleOrNull()
                        ?.get(GremiumTable.name)
                },
            zielRolle = this[WahlTable.zielRolle],
            requiredMajorityPercent = this[WahlTable.requiredMajorityPercent],
            status = status,
            openedById = this[WahlTable.openedBy].toString(),
            openedByDisplayName = memberDisplayName(this[WahlTable.openedBy]).orEmpty(),
            openedAt = this[WahlTable.openedAt],
            candidateListApprovedAt = this[WahlTable.candidateListApprovedAt],
            votingOpenedAt = this[WahlTable.votingOpenedAt],
            votingClosedAt = this[WahlTable.votingClosedAt],
            tallyThreshold = this[WahlTable.tallyThreshold],
            tallyRunAt = this[WahlTable.tallyRunAt],
            beschlussId = this[WahlTable.beschlussId]?.toString(),
            options = options,
        )
    }

    private fun ResultRow.toWahlvorstandDto(): WahlvorstandDto =
        WahlvorstandDto(
            id = this[WahlWahlvorstandTable.id].toString(),
            wahlId = this[WahlWahlvorstandTable.wahlId].toString(),
            memberId = this[WahlWahlvorstandTable.memberId].toString(),
            memberDisplayName = memberDisplayName(this[WahlWahlvorstandTable.memberId]).orEmpty(),
            appointedAt = this[WahlWahlvorstandTable.appointedAt],
        )

    private fun ResultRow.toKandidaturDto(): KandidaturDto =
        KandidaturDto(
            id = this[WahlKandidaturTable.id].toString(),
            wahlId = this[WahlKandidaturTable.wahlId].toString(),
            memberId = this[WahlKandidaturTable.memberId].toString(),
            memberDisplayName = memberDisplayName(this[WahlKandidaturTable.memberId]).orEmpty(),
            motivationText = this[WahlKandidaturTable.motivationText],
            submittedAt = this[WahlKandidaturTable.submittedAt],
            withdrawnAt = this[WahlKandidaturTable.withdrawnAt],
        )

    private fun ResultRow.toStimmzettelDto(revealLabels: Boolean): StimmzettelDto {
        val stimmzettelId = this[WahlStimmzettelTable.id]
        val memberId = this[WahlStimmzettelTable.memberId]
        val labels =
            if (revealLabels) {
                (WahlStimmzettelAuswahlTable innerJoin WahlOptionTable)
                    .selectAll()
                    .where { WahlStimmzettelAuswahlTable.stimmzettelId eq stimmzettelId }
                    .orderBy(WahlOptionTable.position, SortOrder.ASC)
                    .map { it[WahlOptionTable.label] }
            } else {
                emptyList()
            }
        return StimmzettelDto(
            id = stimmzettelId.toString(),
            wahlId = this[WahlStimmzettelTable.wahlId].toString(),
            memberId = memberId?.toString(),
            memberDisplayName = memberDisplayName(memberId),
            selectedOptionLabels = labels,
            castAt = this[WahlStimmzettelTable.castAt],
        )
    }

    private fun String.toUuidOrNotFound(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }
}
