package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.AntragTable
import network.lapis.cloud.server.db.generated.GremiumTable
import network.lapis.cloud.server.db.generated.KonsensierungOptionTable
import network.lapis.cloud.server.db.generated.KonsensierungStimmberechtigtTable
import network.lapis.cloud.server.db.generated.KonsensierungStimmzettelTable
import network.lapis.cloud.server.db.generated.KonsensierungTable
import network.lapis.cloud.server.db.generated.KonsensierungTeilnahmeTable
import network.lapis.cloud.server.db.generated.KonsensierungWiderstandTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SitzungTable
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.canManageKonsensierung
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AntragStatus
import network.lapis.cloud.shared.domain.BeschlussInput
import network.lapis.cloud.shared.domain.BeschlussStatus
import network.lapis.cloud.shared.domain.KonsensierungDto
import network.lapis.cloud.shared.domain.KonsensierungOptionDto
import network.lapis.cloud.shared.domain.KonsensierungStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.SkErgebnisDto
import network.lapis.cloud.shared.domain.SkOpenInput
import network.lapis.cloud.shared.domain.SkOptionErgebnisDto
import network.lapis.cloud.shared.domain.SkOptionInput
import network.lapis.cloud.shared.domain.SkStimmzettelCastResultDto
import network.lapis.cloud.shared.domain.SkStimmzettelDto
import network.lapis.cloud.shared.domain.SkStimmzettelInput
import network.lapis.cloud.shared.domain.SkVerbindlichkeit
import network.lapis.cloud.shared.rpc.IKonsensierungService
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Soft cap: [KonsensierungDto.zuVieleOptionenWarnung] flips true past this many options, but addOption still succeeds. */
private const val MAX_OPTIONEN_SOFT = 10

/** Hard cap: `addOption` throws a [ConflictException] once this many options already exist. */
private const val MAX_OPTIONEN_HARD = 25

private const val PASSIVLOESUNG_LABEL = "Passivloesung (Status quo)"
private const val RECEIPT_CODE_BYTES = 20 // 160 bits, comfortably above the >=128-bit KDoc floor -- same as WahlService.
private const val RECEIPT_CODE_MAX_ATTEMPTS = 5

private val secureRandom = SecureRandom()
private val GK_SCHWELLE_RANGE = BigDecimal.ZERO..BigDecimal.ONE

/**
 * Systemisches Konsensieren (V0.2.5): lowest-cumulative-resistance consensus tool. Implements
 * [IKonsensierungService] -- see that interface's KDoc for the full lifecycle (`openKonsensierung`
 * -> `addOption`/`removeOption` -> `freezeOptionen` -> `castWiderstand` -> `closeBewertung` ->
 * `auswerten`, with `reopenBewertung` as the Diskussion-und-Wiederabstimmung loop back into
 * `castWiderstand`) and `03 Bereiche/Lapis Cloud/Systemisches Konsensieren.md` for the concept
 * document. Reuses [insertBeschlussRow] (`BeschlussBook.kt`) and [eligibleMemberIds]
 * (`GremiumEligibility.kt`) so a Konsensierung's tally lands in the same Beschlussbuch
 * [GovernanceService]/`WahlService` write to, tagged [ResolutionMode.SYSTEMISCHER_KONSENS] --
 * only when [network.lapis.cloud.shared.domain.SkVerbindlichkeit.BESCHLUSS].
 *
 * Anonymity is a practical DB-level table-split, not cryptography -- the identical mechanism
 * `WahlService.castStimme` already uses (`konsensierung_stimmzettel.member_id` is nullable and
 * always `NULL` on the `geheim` path; `konsensierung_teilnahme` carries the "this member rated"
 * proof instead; ballot timestamps are coarsened to the calendar date to avoid a
 * `voted_at = cast_at` re-identification join -- see `09-konsensierung.kuml.kts`'s file header
 * for the full rationale). The one structural addition over Wahl's shape is the `runde` column on
 * every participation-tracking table, letting a `reopenBewertung`'d Bewertungsrunde keep prior
 * rounds' rows (DSGVO retention) while a tally only ever counts the *current* `runde`.
 *
 * Same "simple-transaction" style as [GovernanceService]/`WahlService`: follow-up queries per row
 * ([memberDisplayName], option lists) rather than aliased multi-joins.
 */
class KonsensierungService(
    private val call: ApplicationCall,
) : IKonsensierungService {
    override suspend fun openKonsensierung(input: SkOpenInput): KonsensierungDto {
        val current = resolveCurrentMember(call)
        val aId = input.antragId.toUuidOrNotFound("Antrag")
        return transaction {
            val antragRow =
                AntragTable.selectAll().where { AntragTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Antrag ${input.antragId} not found")
            val gremiumId = antragRow[AntragTable.targetGremiumId]
            if (!current.canManageKonsensierung(gremiumId)) throw ForbiddenException()
            if (antragRow[AntragTable.status] != AntragStatus.TERMINIERT) {
                throw ConflictException("Antrag ${input.antragId} is ${antragRow[AntragTable.status]}, expected TERMINIERT")
            }
            val sId = antragRow[AntragTable.sitzungId] ?: throw ConflictException("Antrag ${input.antragId} has no scheduled Sitzung")

            val hasActive =
                KonsensierungTable
                    .selectAll()
                    .where { (KonsensierungTable.antragId eq aId) and (KonsensierungTable.status neq KonsensierungStatus.ABGEBROCHEN) }
                    .count() > 0
            if (hasActive) {
                throw ConflictException("Antrag ${input.antragId} already has an open or resolved Konsensierung")
            }

            if (input.skalaMax < 1) throw ConflictException("skalaMax must be at least 1, got ${input.skalaMax}")
            if (input.maxRunden < 1) throw ConflictException("maxRunden must be at least 1, got ${input.maxRunden}")
            if (input.gkTragfaehigSchwelle !in GK_SCHWELLE_RANGE) {
                throw ConflictException("gkTragfaehigSchwelle must be in 0..1, got ${input.gkTragfaehigSchwelle}")
            }
            if (input.gkWarnSchwelle !in GK_SCHWELLE_RANGE) {
                throw ConflictException("gkWarnSchwelle must be in 0..1, got ${input.gkWarnSchwelle}")
            }

            val id = Uuid.random()
            val now = nowLocalDateTime()
            KonsensierungTable.insert {
                it[KonsensierungTable.id] = id
                it[KonsensierungTable.antragId] = aId
                it[KonsensierungTable.sitzungId] = sId
                it[title] = antragRow[AntragTable.title]
                it[status] = KonsensierungStatus.SAMMLUNG
                it[geheim] = input.geheim
                it[skalaMax] = input.skalaMax
                it[aggregation] = input.aggregation
                it[tiebreakRegel] = input.tiebreakRegel
                it[gkTragfaehigSchwelle] = input.gkTragfaehigSchwelle
                it[gkWarnSchwelle] = input.gkWarnSchwelle
                it[passivloesungAuto] = input.passivloesungAuto
                it[verbindlichkeit] = input.verbindlichkeit
                it[maxRunden] = input.maxRunden
                it[runde] = 1
                it[winnerOptionId] = null
                it[openedBy] = current.memberId
                it[openedAt] = now
                it[bewertungOpenedAt] = null
                it[bewertungClosedAt] = null
                it[tallyRunAt] = null
                it[beschlussId] = null
            }
            if (input.passivloesungAuto) {
                KonsensierungOptionTable.insert {
                    it[KonsensierungOptionTable.id] = Uuid.random()
                    it[KonsensierungOptionTable.konsensierungId] = id
                    it[label] = PASSIVLOESUNG_LABEL
                    it[position] = 0
                    it[isPassivloesung] = true
                    it[createdBy] = current.memberId
                }
            }
            loadKonsensierung(id)
        }
    }

    override suspend fun addOption(
        konsensierungId: String,
        input: SkOptionInput,
    ): KonsensierungOptionDto {
        val current = resolveCurrentMember(call)
        val kId = konsensierungId.toUuidOrNotFound("Konsensierung")
        return transaction {
            val row = requireKonsensierungRow(kId)
            if (row[KonsensierungTable.status] != KonsensierungStatus.SAMMLUNG) {
                throw ConflictException("Konsensierung $konsensierungId is ${row[KonsensierungTable.status]}, expected SAMMLUNG")
            }
            val gremiumId = requireAntragGremiumId(row[KonsensierungTable.antragId])
            if (!current.isPrivileged) {
                val eligible = eligibleMembersOf(gremiumId, row[KonsensierungTable.sitzungId])
                if (current.memberId !in eligible) throw ForbiddenException()
            }
            val optionCount = KonsensierungOptionTable.selectAll().where { KonsensierungOptionTable.konsensierungId eq kId }.count()
            if (optionCount >= MAX_OPTIONEN_HARD) {
                throw ConflictException("Konsensierung $konsensierungId already has $MAX_OPTIONEN_HARD options (hard cap)")
            }
            val nextPosition =
                (
                    KonsensierungOptionTable
                        .selectAll()
                        .where { KonsensierungOptionTable.konsensierungId eq kId }
                        .maxOfOrNull { it[KonsensierungOptionTable.position] } ?: -1
                ) + 1
            val id = Uuid.random()
            KonsensierungOptionTable.insert {
                it[KonsensierungOptionTable.id] = id
                it[KonsensierungOptionTable.konsensierungId] = kId
                it[label] = input.label
                it[position] = nextPosition
                it[isPassivloesung] = false
                it[createdBy] = current.memberId
            }
            loadOption(id)
        }
    }

    override suspend fun removeOption(optionId: String): KonsensierungDto {
        val current = resolveCurrentMember(call)
        val oId = optionId.toUuidOrNotFound("KonsensierungOption")
        return transaction {
            val optionRow =
                KonsensierungOptionTable.selectAll().where { KonsensierungOptionTable.id eq oId }.singleOrNull()
                    ?: throw NotFoundException("KonsensierungOption $optionId not found")
            val kId = optionRow[KonsensierungOptionTable.konsensierungId]
            val row = requireKonsensierungRow(kId)
            if (row[KonsensierungTable.status] != KonsensierungStatus.SAMMLUNG) {
                throw ConflictException(
                    "Konsensierung ${row[KonsensierungTable.id]} is ${row[KonsensierungTable.status]}, expected SAMMLUNG",
                )
            }
            if (optionRow[KonsensierungOptionTable.isPassivloesung]) {
                throw ConflictException("The Passivloesung option cannot be removed")
            }
            val gremiumId = requireAntragGremiumId(row[KonsensierungTable.antragId])
            val isProposer = optionRow[KonsensierungOptionTable.createdBy] == current.memberId
            if (!isProposer && !current.canManageKonsensierung(gremiumId)) throw ForbiddenException()
            KonsensierungOptionTable.deleteWhere { KonsensierungOptionTable.id eq oId }
            loadKonsensierung(kId)
        }
    }

    override suspend fun listOptions(konsensierungId: String): List<KonsensierungOptionDto> {
        resolveCurrentMember(call)
        val kId = konsensierungId.toUuidOrNotFound("Konsensierung")
        return transaction {
            requireKonsensierungRow(kId)
            KonsensierungOptionTable
                .selectAll()
                .where { KonsensierungOptionTable.konsensierungId eq kId }
                .orderBy(KonsensierungOptionTable.position)
                .map { it.toKonsensierungOptionDto() }
        }
    }

    override suspend fun freezeOptionen(konsensierungId: String): KonsensierungDto {
        val current = resolveCurrentMember(call)
        val kId = konsensierungId.toUuidOrNotFound("Konsensierung")
        return transaction {
            val row = requireKonsensierungRow(kId)
            val gremiumId = requireAntragGremiumId(row[KonsensierungTable.antragId])
            if (!current.canManageKonsensierung(gremiumId)) throw ForbiddenException()
            if (row[KonsensierungTable.status] != KonsensierungStatus.SAMMLUNG) {
                throw ConflictException("Konsensierung $konsensierungId is ${row[KonsensierungTable.status]}, expected SAMMLUNG")
            }
            val optionCount = KonsensierungOptionTable.selectAll().where { KonsensierungOptionTable.konsensierungId eq kId }.count()
            if (optionCount == 0L) throw ConflictException("Konsensierung $konsensierungId has no options to freeze")

            snapshotEligibility(kId, gremiumId, row[KonsensierungTable.sitzungId], row[KonsensierungTable.runde])
            KonsensierungTable.update({ KonsensierungTable.id eq kId }) {
                it[status] = KonsensierungStatus.BEWERTUNG
                it[bewertungOpenedAt] = nowLocalDateTime()
            }
            loadKonsensierung(kId)
        }
    }

    override suspend fun castWiderstand(input: SkStimmzettelInput): SkStimmzettelCastResultDto {
        val current = resolveCurrentMember(call)
        val kId = input.konsensierungId.toUuidOrNotFound("Konsensierung")
        return transaction {
            val row = requireKonsensierungRow(kId)
            if (row[KonsensierungTable.status] != KonsensierungStatus.BEWERTUNG) {
                throw ConflictException("Konsensierung ${input.konsensierungId} is ${row[KonsensierungTable.status]}, expected BEWERTUNG")
            }
            val runde = row[KonsensierungTable.runde]
            val eligible =
                KonsensierungStimmberechtigtTable
                    .selectAll()
                    .where {
                        (KonsensierungStimmberechtigtTable.konsensierungId eq kId) and
                            (KonsensierungStimmberechtigtTable.memberId eq current.memberId) and
                            (KonsensierungStimmberechtigtTable.runde eq runde)
                    }.count() > 0
            if (!eligible) throw ForbiddenException()

            val validOptionIds =
                KonsensierungOptionTable
                    .selectAll()
                    .where { KonsensierungOptionTable.konsensierungId eq kId }
                    .map { it[KonsensierungOptionTable.id] }
                    .toSet()
            val inputOptionIds =
                input.widerstaende.keys
                    .map { it.toUuidOrNotFound("KonsensierungOption") }
                    .toSet()
            if (inputOptionIds != validOptionIds) {
                throw ConflictException(
                    "Ballot must rate exactly the ${validOptionIds.size} frozen option(s) once each, got ${inputOptionIds.size}",
                )
            }
            val skalaMax = row[KonsensierungTable.skalaMax]
            val widerstaendeByOption = input.widerstaende.mapKeys { (optionIdStr, _) -> Uuid.parse(optionIdStr) }
            widerstaendeByOption.values.forEach { wert ->
                if (wert !in 0..skalaMax) throw ConflictException("Resistance value must be in 0..$skalaMax, got $wert")
            }

            val geheim = row[KonsensierungTable.geheim]
            val now = nowLocalDateTime()
            // De-anonymization guard: same day-coarsening rationale as WahlService.castStimme --
            // see 09-konsensierung.kuml.kts's file header for the full rationale.
            val castAt = if (geheim) LocalDateTime(now.date, LocalTime(0, 0)) else now
            try {
                if (geheim) {
                    val alreadyRated =
                        KonsensierungTeilnahmeTable
                            .selectAll()
                            .where {
                                (KonsensierungTeilnahmeTable.konsensierungId eq kId) and
                                    (KonsensierungTeilnahmeTable.memberId eq current.memberId) and
                                    (KonsensierungTeilnahmeTable.runde eq runde)
                            }.count() > 0
                    if (alreadyRated) {
                        throw ConflictException(
                            "Member ${current.memberId} already rated Konsensierung ${input.konsensierungId} in round $runde",
                        )
                    }
                    KonsensierungTeilnahmeTable.insert {
                        it[KonsensierungTeilnahmeTable.id] = Uuid.random()
                        it[KonsensierungTeilnahmeTable.konsensierungId] = kId
                        it[KonsensierungTeilnahmeTable.memberId] = current.memberId
                        it[votedAt] = now
                        it[KonsensierungTeilnahmeTable.runde] = runde
                    }
                } else {
                    val alreadyRated =
                        KonsensierungStimmzettelTable
                            .selectAll()
                            .where {
                                (KonsensierungStimmzettelTable.konsensierungId eq kId) and
                                    (KonsensierungStimmzettelTable.memberId eq current.memberId) and
                                    (KonsensierungStimmzettelTable.runde eq runde)
                            }.count() > 0
                    if (alreadyRated) {
                        throw ConflictException(
                            "Member ${current.memberId} already rated Konsensierung ${input.konsensierungId} in round $runde",
                        )
                    }
                }

                val stimmzettelId = Uuid.random()
                val receiptCode = generateUniqueReceiptCode(kId)
                KonsensierungStimmzettelTable.insert {
                    it[KonsensierungStimmzettelTable.id] = stimmzettelId
                    it[KonsensierungStimmzettelTable.konsensierungId] = kId
                    it[KonsensierungStimmzettelTable.memberId] = if (geheim) null else current.memberId
                    it[KonsensierungStimmzettelTable.receiptCode] = receiptCode
                    it[KonsensierungStimmzettelTable.castAt] = castAt
                    it[KonsensierungStimmzettelTable.runde] = runde
                }
                widerstaendeByOption.forEach { (optId, wert) ->
                    KonsensierungWiderstandTable.insert {
                        it[KonsensierungWiderstandTable.id] = Uuid.random()
                        it[KonsensierungWiderstandTable.wert] = wert
                        it[KonsensierungWiderstandTable.stimmzettelId] = stimmzettelId
                        it[KonsensierungWiderstandTable.optionId] = optId
                    }
                }
                SkStimmzettelCastResultDto(
                    id = stimmzettelId.toString(),
                    castAt = castAt,
                    receiptCode = if (geheim) receiptCode else null,
                )
            } catch (e: ExposedSQLException) {
                // Application-level pre-checks above are racy under concurrency; the DB-level
                // UNIQUE(konsensierung_id, member_id, runde) constraint (on konsensierung_teilnahme
                // for the secret path, on konsensierung_stimmzettel for the non-secret path) is the
                // real backstop -- same convention as WahlService.castStimme.
                throw ConflictException("Member ${current.memberId} already rated Konsensierung ${input.konsensierungId} in round $runde")
            }
        }
    }

    override suspend fun closeBewertung(konsensierungId: String): KonsensierungDto {
        val current = resolveCurrentMember(call)
        val kId = konsensierungId.toUuidOrNotFound("Konsensierung")
        return transaction {
            val row = requireKonsensierungRow(kId)
            val gremiumId = requireAntragGremiumId(row[KonsensierungTable.antragId])
            if (!current.canManageKonsensierung(gremiumId)) throw ForbiddenException()
            if (row[KonsensierungTable.status] != KonsensierungStatus.BEWERTUNG) {
                throw ConflictException("Konsensierung $konsensierungId is ${row[KonsensierungTable.status]}, expected BEWERTUNG")
            }
            KonsensierungTable.update({ KonsensierungTable.id eq kId }) {
                it[status] = KonsensierungStatus.GESCHLOSSEN
                it[bewertungClosedAt] = nowLocalDateTime()
            }
            loadKonsensierung(kId)
        }
    }

    override suspend fun auswerten(konsensierungId: String): SkErgebnisDto {
        val current = resolveCurrentMember(call)
        val kId = konsensierungId.toUuidOrNotFound("Konsensierung")
        return transaction {
            val row = requireKonsensierungRow(kId)
            val gremiumId = requireAntragGremiumId(row[KonsensierungTable.antragId])
            if (!current.canManageKonsensierung(gremiumId)) throw ForbiddenException()
            if (row[KonsensierungTable.status] != KonsensierungStatus.GESCHLOSSEN) {
                throw ConflictException("Konsensierung $konsensierungId is ${row[KonsensierungTable.status]}, expected GESCHLOSSEN")
            }
            val runde = row[KonsensierungTable.runde]

            val optionRows =
                KonsensierungOptionTable
                    .selectAll()
                    .where { KonsensierungOptionTable.konsensierungId eq kId }
                    .orderBy(KonsensierungOptionTable.position)
                    .toList()
            val optionIds = optionRows.map { it[KonsensierungOptionTable.id] }
            val passivloesungOptionId =
                optionRows.firstOrNull { it[KonsensierungOptionTable.isPassivloesung] }?.get(
                    KonsensierungOptionTable.id,
                )

            val stimmzettelIds =
                KonsensierungStimmzettelTable
                    .selectAll()
                    .where { (KonsensierungStimmzettelTable.konsensierungId eq kId) and (KonsensierungStimmzettelTable.runde eq runde) }
                    .map { it[KonsensierungStimmzettelTable.id] }
            val widerstandRows =
                if (stimmzettelIds.isEmpty()) {
                    emptyList()
                } else {
                    KonsensierungWiderstandTable
                        .selectAll()
                        .where { KonsensierungWiderstandTable.stimmzettelId inList stimmzettelIds }
                        .toList()
                }
            val widerstaendeByStimmzettel =
                widerstandRows.groupBy(
                    { it[KonsensierungWiderstandTable.stimmzettelId] },
                    { it[KonsensierungWiderstandTable.optionId] to it[KonsensierungWiderstandTable.wert] },
                )
            val stimmen = stimmzettelIds.map { szId -> SkStimme(widerstaende = widerstaendeByStimmzettel[szId].orEmpty().toMap()) }

            val ergebnis =
                computeKonsensierungErgebnis(
                    stimmen = stimmen,
                    optionIds = optionIds,
                    skalaMax = row[KonsensierungTable.skalaMax],
                    aggregation = row[KonsensierungTable.aggregation],
                    tiebreak = row[KonsensierungTable.tiebreakRegel],
                    gkTragfaehigSchwelle = row[KonsensierungTable.gkTragfaehigSchwelle].toDouble(),
                    gkWarnSchwelle = row[KonsensierungTable.gkWarnSchwelle].toDouble(),
                )

            KonsensierungTable.update({ KonsensierungTable.id eq kId }) {
                it[status] = KonsensierungStatus.AUSGEWERTET
                it[tallyRunAt] = nowLocalDateTime()
                it[winnerOptionId] = ergebnis.gewinnerOptionId
            }

            if (row[KonsensierungTable.verbindlichkeit] == SkVerbindlichkeit.BESCHLUSS) {
                // Only a genuinely undecided result (gewinnerOptionId == null -- WIEDERHOLUNG or
                // zero ballots) maps to VERTAGT. ergebnis.tie alone is not the right signal here:
                // it stays true even when NIEDRIGSTER_MAXWIDERSTAND/NIEDRIGSTE_STDABW resolved a
                // concrete winner despite a raw KW tie -- see SkErgebnis KDoc.
                val beschlussStatus =
                    when {
                        ergebnis.gewinnerOptionId == null -> BeschlussStatus.VERTAGT
                        ergebnis.gewinnerOptionId == passivloesungOptionId -> BeschlussStatus.ABGELEHNT
                        else -> BeschlussStatus.ANGENOMMEN
                    }
                val sitzung = SitzungTable.selectAll().where { SitzungTable.id eq row[KonsensierungTable.sitzungId] }.single()
                val antragRow = AntragTable.selectAll().where { AntragTable.id eq row[KonsensierungTable.antragId] }.single()
                val beschlussInput =
                    BeschlussInput(
                        tagesordnungspunktId = antragRow[AntragTable.tagesordnungspunktId]?.toString(),
                        title = antragRow[AntragTable.title],
                        text = antragRow[AntragTable.text],
                        votesYes = 0,
                        votesNo = 0,
                        votesAbstain = 0,
                        status = beschlussStatus,
                    )
                val beschluss =
                    insertBeschlussRow(
                        row[KonsensierungTable.sitzungId],
                        gremiumId,
                        sitzung[SitzungTable.scheduledAt].date,
                        beschlussInput,
                        current,
                        resolutionMode = ResolutionMode.SYSTEMISCHER_KONSENS,
                        konsensierungId = kId,
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
                KonsensierungTable.update({ KonsensierungTable.id eq kId }) {
                    it[KonsensierungTable.beschlussId] = Uuid.parse(beschluss.id)
                }
            }

            toSkErgebnisDto(kId, ergebnis)
        }
    }

    override suspend fun reopenBewertung(konsensierungId: String): KonsensierungDto {
        val current = resolveCurrentMember(call)
        val kId = konsensierungId.toUuidOrNotFound("Konsensierung")
        return transaction {
            val row = requireKonsensierungRow(kId)
            val gremiumId = requireAntragGremiumId(row[KonsensierungTable.antragId])
            if (!current.canManageKonsensierung(gremiumId)) throw ForbiddenException()
            val status = row[KonsensierungTable.status]
            if (status != KonsensierungStatus.GESCHLOSSEN && status != KonsensierungStatus.AUSGEWERTET) {
                throw ConflictException("Konsensierung $konsensierungId is $status, expected GESCHLOSSEN or AUSGEWERTET")
            }
            if (row[KonsensierungTable.beschlussId] != null) {
                throw ConflictException(
                    "Konsensierung $konsensierungId already has a binding Beschluss (${row[KonsensierungTable.beschlussId]}) " +
                        "recorded -- reopening would orphan it in the Beschlussbuch",
                )
            }
            val runde = row[KonsensierungTable.runde]
            val maxRunden = row[KonsensierungTable.maxRunden]
            if (runde >= maxRunden) {
                throw ConflictException("Konsensierung $konsensierungId already reached maxRunden=$maxRunden")
            }
            val newRunde = runde + 1
            snapshotEligibility(kId, gremiumId, row[KonsensierungTable.sitzungId], newRunde)
            KonsensierungTable.update({ KonsensierungTable.id eq kId }) {
                it[KonsensierungTable.status] = KonsensierungStatus.BEWERTUNG
                it[KonsensierungTable.runde] = newRunde
                it[bewertungOpenedAt] = nowLocalDateTime()
                it[bewertungClosedAt] = null
                it[tallyRunAt] = null
                it[winnerOptionId] = null
            }
            loadKonsensierung(kId)
        }
    }

    override suspend fun abortKonsensierung(konsensierungId: String): KonsensierungDto {
        val current = resolveCurrentMember(call)
        val kId = konsensierungId.toUuidOrNotFound("Konsensierung")
        return transaction {
            val row = requireKonsensierungRow(kId)
            val gremiumId = requireAntragGremiumId(row[KonsensierungTable.antragId])
            if (!current.canManageKonsensierung(gremiumId)) throw ForbiddenException()
            val status = row[KonsensierungTable.status]
            if (status == KonsensierungStatus.AUSGEWERTET || status == KonsensierungStatus.ABGEBROCHEN) {
                throw ConflictException("Konsensierung $konsensierungId is already $status")
            }
            KonsensierungTable.update({ KonsensierungTable.id eq kId }) { it[KonsensierungTable.status] = KonsensierungStatus.ABGEBROCHEN }
            loadKonsensierung(kId)
        }
    }

    override suspend fun getKonsensierung(konsensierungId: String): KonsensierungDto {
        resolveCurrentMember(call)
        val kId = konsensierungId.toUuidOrNotFound("Konsensierung")
        return transaction { loadKonsensierung(kId) }
    }

    override suspend fun listKonsensierungen(
        antragId: String?,
        status: KonsensierungStatus?,
    ): List<KonsensierungDto> {
        resolveCurrentMember(call)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (antragId != null) conditions += (KonsensierungTable.antragId eq antragId.toUuidOrNotFound("Antrag"))
            if (status != null) conditions += (KonsensierungTable.status eq status)
            val baseQuery = KonsensierungTable.selectAll()
            val query = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            query.map { it.toKonsensierungDto() }
        }
    }

    override suspend fun listWiderstaende(konsensierungId: String): List<SkStimmzettelDto> {
        resolveCurrentMember(call)
        val kId = konsensierungId.toUuidOrNotFound("Konsensierung")
        return transaction {
            val row = requireKonsensierungRow(kId)
            // Pre-tally secrecy gate: same invariant as WahlService.listStimmzettel's
            // revealLabels -- see SkStimmzettelDto KDoc.
            val revealValues = !row[KonsensierungTable.geheim] || row[KonsensierungTable.status] == KonsensierungStatus.AUSGEWERTET
            val runde = row[KonsensierungTable.runde]
            KonsensierungStimmzettelTable
                .selectAll()
                .where { (KonsensierungStimmzettelTable.konsensierungId eq kId) and (KonsensierungStimmzettelTable.runde eq runde) }
                .map { it.toSkStimmzettelDto(revealValues) }
        }
    }

    private fun requireKonsensierungRow(konsensierungId: Uuid): ResultRow =
        KonsensierungTable
            .selectAll()
            .where { KonsensierungTable.id eq konsensierungId }
            .singleOrNull() ?: throw NotFoundException("Konsensierung $konsensierungId not found")

    private fun requireAntragGremiumId(antragId: Uuid): Uuid =
        AntragTable.selectAll().where { AntragTable.id eq antragId }.single()[AntragTable.targetGremiumId]

    private fun eligibleMembersOf(
        gremiumId: Uuid,
        sitzungId: Uuid,
    ): Set<Uuid> {
        val sitzungRow = SitzungTable.selectAll().where { SitzungTable.id eq sitzungId }.single()
        val gremiumRow = GremiumTable.selectAll().where { GremiumTable.id eq gremiumId }.single()
        return eligibleMemberIds(gremiumRow, sitzungRow[SitzungTable.scheduledAt].date)
    }

    /** Snapshots current eligibility into `konsensierung_stimmberechtigt` for [runde] -- shared by [freezeOptionen] and [reopenBewertung]. */
    private fun snapshotEligibility(
        konsensierungId: Uuid,
        gremiumId: Uuid,
        sitzungId: Uuid,
        runde: Int,
    ) {
        val eligible = eligibleMembersOf(gremiumId, sitzungId)
        eligible.forEach { mId ->
            KonsensierungStimmberechtigtTable.insert {
                it[id] = Uuid.random()
                it[KonsensierungStimmberechtigtTable.konsensierungId] = konsensierungId
                it[memberId] = mId
                it[KonsensierungStimmberechtigtTable.runde] = runde
            }
        }
    }

    private fun loadKonsensierung(id: Uuid): KonsensierungDto =
        KonsensierungTable
            .selectAll()
            .where { KonsensierungTable.id eq id }
            .singleOrNull()
            ?.toKonsensierungDto() ?: throw NotFoundException("Konsensierung $id not found")

    private fun loadOption(id: Uuid): KonsensierungOptionDto =
        KonsensierungOptionTable
            .selectAll()
            .where { KonsensierungOptionTable.id eq id }
            .single()
            .toKonsensierungOptionDto()

    private fun memberDisplayName(memberId: Uuid?): String? =
        memberId?.let { mId ->
            MemberTable
                .selectAll()
                .where { MemberTable.id eq mId }
                .singleOrNull()
                ?.get(MemberTable.displayName)
        }

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    /** Retries a small, bounded number of times on a receipt-code collision -- see [WahlService]'s equivalent KDoc. */
    private fun generateUniqueReceiptCode(konsensierungId: Uuid): String {
        repeat(RECEIPT_CODE_MAX_ATTEMPTS) {
            val bytes = ByteArray(RECEIPT_CODE_BYTES)
            secureRandom.nextBytes(bytes)
            val candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val exists =
                KonsensierungStimmzettelTable
                    .selectAll()
                    .where {
                        (KonsensierungStimmzettelTable.konsensierungId eq konsensierungId) and
                            (KonsensierungStimmzettelTable.receiptCode eq candidate)
                    }.count() > 0
            if (!exists) return candidate
        }
        throw ConflictException(
            "Failed to generate a unique receipt code for Konsensierung $konsensierungId after $RECEIPT_CODE_MAX_ATTEMPTS attempts",
        )
    }

    private fun ResultRow.toKonsensierungDto(): KonsensierungDto {
        val kId = this[KonsensierungTable.id]
        val optionRows =
            KonsensierungOptionTable
                .selectAll()
                .where { KonsensierungOptionTable.konsensierungId eq kId }
                .orderBy(KonsensierungOptionTable.position)
                .toList()
        return KonsensierungDto(
            id = kId.toString(),
            antragId = this[KonsensierungTable.antragId].toString(),
            sitzungId = this[KonsensierungTable.sitzungId].toString(),
            title = this[KonsensierungTable.title],
            status = this[KonsensierungTable.status],
            geheim = this[KonsensierungTable.geheim],
            skalaMax = this[KonsensierungTable.skalaMax],
            aggregation = this[KonsensierungTable.aggregation],
            tiebreakRegel = this[KonsensierungTable.tiebreakRegel],
            gkTragfaehigSchwelle = this[KonsensierungTable.gkTragfaehigSchwelle],
            gkWarnSchwelle = this[KonsensierungTable.gkWarnSchwelle],
            passivloesungAuto = this[KonsensierungTable.passivloesungAuto],
            verbindlichkeit = this[KonsensierungTable.verbindlichkeit],
            maxRunden = this[KonsensierungTable.maxRunden],
            runde = this[KonsensierungTable.runde],
            winnerOptionId = this[KonsensierungTable.winnerOptionId]?.toString(),
            openedById = this[KonsensierungTable.openedBy].toString(),
            openedByDisplayName = memberDisplayName(this[KonsensierungTable.openedBy]).orEmpty(),
            openedAt = this[KonsensierungTable.openedAt],
            bewertungOpenedAt = this[KonsensierungTable.bewertungOpenedAt],
            bewertungClosedAt = this[KonsensierungTable.bewertungClosedAt],
            tallyRunAt = this[KonsensierungTable.tallyRunAt],
            beschlussId = this[KonsensierungTable.beschlussId]?.toString(),
            options = optionRows.map { it.toKonsensierungOptionDto() },
            zuVieleOptionenWarnung = optionRows.size > MAX_OPTIONEN_SOFT,
        )
    }

    private fun ResultRow.toKonsensierungOptionDto(): KonsensierungOptionDto =
        KonsensierungOptionDto(
            id = this[KonsensierungOptionTable.id].toString(),
            konsensierungId = this[KonsensierungOptionTable.konsensierungId].toString(),
            label = this[KonsensierungOptionTable.label],
            position = this[KonsensierungOptionTable.position],
            isPassivloesung = this[KonsensierungOptionTable.isPassivloesung],
            createdById = this[KonsensierungOptionTable.createdBy].toString(),
            createdByDisplayName = memberDisplayName(this[KonsensierungOptionTable.createdBy]).orEmpty(),
        )

    private fun ResultRow.toSkStimmzettelDto(revealValues: Boolean): SkStimmzettelDto {
        val stimmzettelId = this[KonsensierungStimmzettelTable.id]
        val memberId = this[KonsensierungStimmzettelTable.memberId]
        val widerstaende =
            if (revealValues) {
                KonsensierungWiderstandTable
                    .selectAll()
                    .where { KonsensierungWiderstandTable.stimmzettelId eq stimmzettelId }
                    .associate { it[KonsensierungWiderstandTable.optionId].toString() to it[KonsensierungWiderstandTable.wert] }
            } else {
                emptyMap()
            }
        return SkStimmzettelDto(
            id = stimmzettelId.toString(),
            konsensierungId = this[KonsensierungStimmzettelTable.konsensierungId].toString(),
            memberId = memberId?.toString(),
            memberDisplayName = memberDisplayName(memberId),
            widerstaende = widerstaende,
            castAt = this[KonsensierungStimmzettelTable.castAt],
            runde = this[KonsensierungStimmzettelTable.runde],
        )
    }

    private fun toSkErgebnisDto(
        konsensierungId: Uuid,
        ergebnis: SkErgebnis,
    ): SkErgebnisDto =
        SkErgebnisDto(
            konsensierungId = konsensierungId.toString(),
            optionErgebnisse =
                ergebnis.optionErgebnisse.map {
                    SkOptionErgebnisDto(
                        optionId = it.optionId.toString(),
                        kumulierterWiderstand = it.kumulierterWiderstand,
                        mittlererWiderstand = it.mittlererWiderstand,
                        maxWiderstand = it.maxWiderstand,
                        standardabweichung = it.standardabweichung,
                        konsensIndex = it.konsensIndex,
                        verteilung = it.verteilung,
                    )
                },
            gewinnerOptionId = ergebnis.gewinnerOptionId?.toString(),
            tie = ergebnis.tie,
            tiebreakAngewendet = ergebnis.tiebreakAngewendet,
            konsensTragfaehig = ergebnis.konsensTragfaehig,
            gruppenkonfliktWarnung = ergebnis.gruppenkonfliktWarnung,
            keineBewertungen = ergebnis.keineBewertungen,
        )

    private fun String.toUuidOrNotFound(kind: String): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid $kind id: $this") }
}
