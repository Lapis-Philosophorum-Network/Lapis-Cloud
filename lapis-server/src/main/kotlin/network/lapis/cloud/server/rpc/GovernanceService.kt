package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.tables.AbstimmungOptionTable
import network.lapis.cloud.server.db.tables.AbstimmungStimmeTable
import network.lapis.cloud.server.db.tables.AbstimmungTable
import network.lapis.cloud.server.db.tables.AntragTable
import network.lapis.cloud.server.db.tables.AnwesenheitTable
import network.lapis.cloud.server.db.tables.BeschlussTable
import network.lapis.cloud.server.db.tables.GremiumMitgliedschaftTable
import network.lapis.cloud.server.db.tables.GremiumTable
import network.lapis.cloud.server.db.tables.MemberTable
import network.lapis.cloud.server.db.tables.SitzungTable
import network.lapis.cloud.server.db.tables.TagesordnungspunktTable
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.economy.PlaceholderLtrBalanceProvider
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.canRecordForSitzung
import network.lapis.cloud.server.security.canSubmitAntrag
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AbstimmungDto
import network.lapis.cloud.shared.domain.AbstimmungOpenInput
import network.lapis.cloud.shared.domain.AbstimmungOptionDto
import network.lapis.cloud.shared.domain.AbstimmungStatus
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AntragDto
import network.lapis.cloud.shared.domain.AntragInput
import network.lapis.cloud.shared.domain.AntragPruefungsEntscheidung
import network.lapis.cloud.shared.domain.AntragResolutionInput
import network.lapis.cloud.shared.domain.AntragStatus
import network.lapis.cloud.shared.domain.AnwesenheitDto
import network.lapis.cloud.shared.domain.AnwesenheitInput
import network.lapis.cloud.shared.domain.BeschlussDto
import network.lapis.cloud.shared.domain.BeschlussInput
import network.lapis.cloud.shared.domain.BeschlussStatus
import network.lapis.cloud.shared.domain.GremiumDto
import network.lapis.cloud.shared.domain.GremiumInput
import network.lapis.cloud.shared.domain.GremiumMitgliedschaftDto
import network.lapis.cloud.shared.domain.GremiumMitgliedschaftInput
import network.lapis.cloud.shared.domain.ProtocolDraftDto
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.SitzungDetailDto
import network.lapis.cloud.shared.domain.SitzungDto
import network.lapis.cloud.shared.domain.SitzungInput
import network.lapis.cloud.shared.domain.SitzungsStatus
import network.lapis.cloud.shared.domain.StimmeDto
import network.lapis.cloud.shared.domain.StimmeInput
import network.lapis.cloud.shared.domain.TagesordnungspunktDto
import network.lapis.cloud.shared.domain.TagesordnungspunktInput
import network.lapis.cloud.shared.rpc.IGovernanceService
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

/** Meritokratische Abstimmungen (V0.2.3): server-side floor, never trusted from a client. */
private val MIN_STAKE_LTR: BigDecimal = BigDecimal("0.01")
private val ZERO_LTR: BigDecimal = BigDecimal.ZERO.setScale(2)

/**
 * DoS caps on [network.lapis.cloud.shared.domain.AbstimmungOpenInput.optionLabels] — well above
 * any realistic Sachentscheidung's option count/label length, but bounded so a careless or
 * malicious caller cannot make `openAbstimmung` insert an unbounded number of
 * [network.lapis.cloud.server.db.tables.AbstimmungOptionTable] rows or exceed that table's
 * `label VARCHAR(200)` column with a confusing DB-level error instead of a clean 409.
 */
private const val MAX_ABSTIMMUNG_OPTIONS = 50
private const val MAX_OPTION_LABEL_LENGTH = 200

/**
 * Gremien- und Sitzungsverwaltung (V0.2.1). Reads (`listGremien`/`getSitzungDetail`/
 * `listBeschluesse`/etc.) only require a resolvable [network.lapis.cloud.server.security
 * .CurrentMember] (any authenticated member) — see [IGovernanceService] KDoc for why this is a
 * deliberate simplification versus [network.lapis.cloud.shared.domain.DocumentAccessLevel]'s
 * tiered model. Writes that manage a specific Gremium's meetings/agenda/attendance/resolutions
 * require that Gremium's leadership role or global BOARD/ADMIN, checked via
 * [network.lapis.cloud.server.security.canRecordForSitzung] (see `GovernanceAuthorization.kt`).
 *
 * Member display names for the multiple-FK-to-member tables ([SitzungTable] has three: called
 * by/chair/minute-taker; [AnwesenheitTable] has two: attendee/proxy) are resolved via
 * [memberDisplayName], a small follow-up lookup per id, rather than aliased multi-joins — kept
 * simple and correct rather than optimized, consistent with this codebase's "simple-transaction"
 * style (see [ContributionService]/[MailingService]). Single-member-FK joins ([GremiumTable] via
 * [SitzungTable]/[GremiumMitgliedschaftTable] via `member`) still use a plain `innerJoin`.
 *
 * Meritokratische Abstimmungen (V0.2.3): [ltrBalanceProvider] defaults to
 * [PlaceholderLtrBalanceProvider] so `Application.module`'s single-arg
 * `GovernanceService(call)` construction is unaffected; V0.6's ledger-backed implementation only
 * has to change that one default, not every call site. See [LtrBalanceProvider] KDoc for the
 * read-only-in-this-wave boundary.
 */
class GovernanceService(
    private val call: ApplicationCall,
    private val ltrBalanceProvider: LtrBalanceProvider = PlaceholderLtrBalanceProvider(),
) : IGovernanceService {
    override suspend fun listGremien(activeOnly: Boolean): List<GremiumDto> {
        resolveCurrentMember(call)
        return transaction {
            val baseQuery = GremiumTable.selectAll()
            val query = if (activeOnly) baseQuery.where { GremiumTable.active eq true } else baseQuery
            query.map { it.toGremiumDto() }
        }
    }

    override suspend fun createGremium(input: GremiumInput): GremiumDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val now = nowLocalDateTime()
        return transaction {
            val id = Uuid.random()
            GremiumTable.insert {
                it[GremiumTable.id] = id
                it[name] = input.name
                it[type] = input.type
                it[description] = input.description
                it[active] = input.active
                it[quorumPercent] = input.quorumPercent
                it[createdAt] = now
            }
            GremiumTable
                .selectAll()
                .where { GremiumTable.id eq id }
                .single()
                .toGremiumDto()
        }
    }

    override suspend fun updateGremium(
        id: String,
        input: GremiumInput,
    ): GremiumDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val gremiumId = id.toGremiumUuid()
        return transaction {
            val updated =
                GremiumTable.update({ GremiumTable.id eq gremiumId }) {
                    it[name] = input.name
                    it[type] = input.type
                    it[description] = input.description
                    it[active] = input.active
                    it[quorumPercent] = input.quorumPercent
                }
            if (updated == 0) throw NotFoundException("Gremium $id not found")
            GremiumTable
                .selectAll()
                .where { GremiumTable.id eq gremiumId }
                .single()
                .toGremiumDto()
        }
    }

    override suspend fun listGremiumMitglieder(
        gremiumId: String,
        activeOnly: Boolean,
    ): List<GremiumMitgliedschaftDto> {
        resolveCurrentMember(call)
        val gId = gremiumId.toGremiumUuid()
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>(GremiumMitgliedschaftTable.gremiumId eq gId)
            if (activeOnly) conditions += GremiumMitgliedschaftTable.until.isNull()
            (GremiumMitgliedschaftTable innerJoin MemberTable)
                .selectAll()
                .where { conditions.reduce { a, b -> a and b } }
                .map { it.toGremiumMitgliedschaftDto() }
        }
    }

    override suspend fun addGremiumMitglied(
        gremiumId: String,
        input: GremiumMitgliedschaftInput,
    ): GremiumMitgliedschaftDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val gId = gremiumId.toGremiumUuid()
        val memberId = input.memberId.toMemberUuid()
        return transaction {
            GremiumTable.selectAll().where { GremiumTable.id eq gId }.singleOrNull()
                ?: throw NotFoundException("Gremium $gremiumId not found")
            val activeExists =
                GremiumMitgliedschaftTable
                    .selectAll()
                    .where {
                        (GremiumMitgliedschaftTable.gremiumId eq gId) and
                            (GremiumMitgliedschaftTable.memberId eq memberId) and
                            (GremiumMitgliedschaftTable.until.isNull())
                    }.count() > 0
            if (activeExists) {
                throw ConflictException(
                    "Member ${input.memberId} already has an active membership in Gremium $gremiumId",
                )
            }
            val id = Uuid.random()
            GremiumMitgliedschaftTable.insert {
                it[GremiumMitgliedschaftTable.id] = id
                it[GremiumMitgliedschaftTable.gremiumId] = gId
                it[GremiumMitgliedschaftTable.memberId] = memberId
                it[rolle] = input.rolle
                it[since] = input.since
                it[until] = null
            }
            (GremiumMitgliedschaftTable innerJoin MemberTable)
                .selectAll()
                .where { GremiumMitgliedschaftTable.id eq id }
                .single()
                .toGremiumMitgliedschaftDto()
        }
    }

    override suspend fun endGremiumMitgliedschaft(
        mitgliedschaftId: String,
        until: LocalDate,
    ): GremiumMitgliedschaftDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*BOARD_ROLES)
        val id = mitgliedschaftId.toMitgliedschaftUuid()
        return transaction {
            val updated =
                GremiumMitgliedschaftTable.update({ GremiumMitgliedschaftTable.id eq id }) {
                    it[GremiumMitgliedschaftTable.until] = until
                }
            if (updated == 0) throw NotFoundException("GremiumMitgliedschaft $mitgliedschaftId not found")
            (GremiumMitgliedschaftTable innerJoin MemberTable)
                .selectAll()
                .where { GremiumMitgliedschaftTable.id eq id }
                .single()
                .toGremiumMitgliedschaftDto()
        }
    }

    override suspend fun listSitzungen(
        gremiumId: String?,
        status: SitzungsStatus?,
    ): List<SitzungDto> {
        resolveCurrentMember(call)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (gremiumId != null) conditions += (SitzungTable.gremiumId eq gremiumId.toGremiumUuid())
            if (status != null) conditions += (SitzungTable.status eq status)
            val baseQuery = (SitzungTable innerJoin GremiumTable).selectAll()
            val query = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            query.map { it.toSitzungDto() }
        }
    }

    override suspend fun getSitzungDetail(sitzungId: String): SitzungDetailDto {
        resolveCurrentMember(call)
        val sId = sitzungId.toSitzungUuid()
        return transaction {
            val sitzung = loadSitzung(sId)
            SitzungDetailDto(
                sitzung = sitzung,
                tagesordnung = loadTagesordnung(sId),
                anwesenheit = loadAnwesenheit(sId),
                beschluesse = loadBeschluesse(sId),
                quorum = computeQuorum(sId, sitzung.gremiumId.toGremiumUuid(), sitzung.scheduledAt.date),
            )
        }
    }

    override suspend fun createSitzung(input: SitzungInput): SitzungDto {
        val current = resolveCurrentMember(call)
        val gId = input.gremiumId.toGremiumUuid()
        return transaction {
            GremiumTable.selectAll().where { GremiumTable.id eq gId }.singleOrNull()
                ?: throw NotFoundException("Gremium ${input.gremiumId} not found")
            if (!current.canRecordForSitzung(gId)) throw ForbiddenException()
            val id = Uuid.random()
            val now = nowLocalDateTime()
            SitzungTable.insert {
                it[SitzungTable.id] = id
                it[SitzungTable.gremiumId] = gId
                it[title] = input.title
                it[scheduledAt] = input.scheduledAt
                it[location] = input.location
                it[format] = input.format
                it[status] = SitzungsStatus.GEPLANT
                it[calledBy] = current.memberId
                it[calledAt] = now
                it[chairMemberId] = input.chairMemberId?.let(Uuid::parse)
                it[minuteTakerMemberId] = input.minuteTakerMemberId?.let(Uuid::parse)
                it[protocolDocumentId] = null
                it[createdAt] = now
            }
            loadSitzung(id)
        }
    }

    override suspend fun updateSitzungStatus(
        sitzungId: String,
        status: SitzungsStatus,
    ): SitzungDto {
        val current = resolveCurrentMember(call)
        val sId = sitzungId.toSitzungUuid()
        return transaction {
            val gremiumId = requireSitzungGremiumId(sId)
            if (!current.canRecordForSitzung(gremiumId)) throw ForbiddenException()
            SitzungTable.update({ SitzungTable.id eq sId }) { it[SitzungTable.status] = status }
            loadSitzung(sId)
        }
    }

    override suspend fun addTagesordnungspunkt(
        sitzungId: String,
        input: TagesordnungspunktInput,
    ): TagesordnungspunktDto {
        val current = resolveCurrentMember(call)
        val sId = sitzungId.toSitzungUuid()
        return transaction {
            val gremiumId = requireSitzungGremiumId(sId)
            if (!current.canRecordForSitzung(gremiumId)) throw ForbiddenException()
            insertTagesordnungspunkt(sId, input)
        }
    }

    override suspend fun removeTagesordnungspunkt(id: String) {
        val current = resolveCurrentMember(call)
        val topId = id.toTagesordnungspunktUuid()
        transaction {
            val row =
                TagesordnungspunktTable.selectAll().where { TagesordnungspunktTable.id eq topId }.singleOrNull()
                    ?: throw NotFoundException("Tagesordnungspunkt $id not found")
            val gremiumId = requireSitzungGremiumId(row[TagesordnungspunktTable.sitzungId])
            if (!current.canRecordForSitzung(gremiumId)) throw ForbiddenException()
            TagesordnungspunktTable.deleteWhere { TagesordnungspunktTable.id eq topId }
        }
    }

    override suspend fun recordAttendance(
        sitzungId: String,
        input: AnwesenheitInput,
    ): AnwesenheitDto {
        val current = resolveCurrentMember(call)
        val sId = sitzungId.toSitzungUuid()
        val memberId = input.memberId.toMemberUuid()
        return transaction {
            val gremiumId = requireSitzungGremiumId(sId)
            if (!current.canRecordForSitzung(gremiumId)) throw ForbiddenException()
            val now = nowLocalDateTime()
            val existing =
                AnwesenheitTable
                    .selectAll()
                    .where { (AnwesenheitTable.sitzungId eq sId) and (AnwesenheitTable.memberId eq memberId) }
                    .singleOrNull()
            val id =
                if (existing == null) {
                    val newId = Uuid.random()
                    AnwesenheitTable.insert {
                        it[AnwesenheitTable.id] = newId
                        it[AnwesenheitTable.sitzungId] = sId
                        it[AnwesenheitTable.memberId] = memberId
                        it[status] = input.status
                        it[representedByMemberId] = input.representedByMemberId?.let(Uuid::parse)
                        it[note] = input.note
                        it[recordedAt] = now
                    }
                    newId
                } else {
                    val existingId = existing[AnwesenheitTable.id]
                    AnwesenheitTable.update({ AnwesenheitTable.id eq existingId }) {
                        it[status] = input.status
                        it[representedByMemberId] = input.representedByMemberId?.let(Uuid::parse)
                        it[note] = input.note
                        it[recordedAt] = now
                    }
                    existingId
                }
            AnwesenheitTable
                .selectAll()
                .where { AnwesenheitTable.id eq id }
                .single()
                .toAnwesenheitDto()
        }
    }

    override suspend fun getAttendance(sitzungId: String): List<AnwesenheitDto> {
        resolveCurrentMember(call)
        val sId = sitzungId.toSitzungUuid()
        return transaction { loadAnwesenheit(sId) }
    }

    override suspend fun checkQuorum(sitzungId: String): QuorumResultDto {
        resolveCurrentMember(call)
        val sId = sitzungId.toSitzungUuid()
        return transaction {
            val sitzung = loadSitzung(sId)
            computeQuorum(sId, sitzung.gremiumId.toGremiumUuid(), sitzung.scheduledAt.date)
        }
    }

    override suspend fun recordBeschluss(
        sitzungId: String,
        input: BeschlussInput,
    ): BeschlussDto {
        val current = resolveCurrentMember(call)
        val sId = sitzungId.toSitzungUuid()
        return transaction {
            val gremiumId = requireSitzungGremiumId(sId)
            if (!current.canRecordForSitzung(gremiumId)) throw ForbiddenException()
            val sitzung = loadSitzung(sId)
            insertBeschlussRow(sId, gremiumId, sitzung.scheduledAt.date, input, current)
        }
    }

    override suspend fun listBeschluesse(
        gremiumId: String?,
        sitzungId: String?,
    ): List<BeschlussDto> {
        resolveCurrentMember(call)
        return transaction {
            when {
                sitzungId != null -> {
                    val sId = sitzungId.toSitzungUuid()
                    BeschlussTable.selectAll().where { BeschlussTable.sitzungId eq sId }.map { it.toBeschlussDto() }
                }
                gremiumId != null -> {
                    val gId = gremiumId.toGremiumUuid()
                    (BeschlussTable innerJoin SitzungTable)
                        .selectAll()
                        .where { SitzungTable.gremiumId eq gId }
                        .map { it.toBeschlussDto() }
                }
                else -> BeschlussTable.selectAll().map { it.toBeschlussDto() }
            }
        }
    }

    override suspend fun generateProtocolDraft(sitzungId: String): ProtocolDraftDto {
        resolveCurrentMember(call)
        val sId = sitzungId.toSitzungUuid()
        return transaction {
            val sitzung = loadSitzung(sId)
            ProtocolDraftDto(
                sitzung = sitzung,
                anwesenheit = loadAnwesenheit(sId),
                tagesordnung = loadTagesordnung(sId),
                beschluesse = loadBeschluesse(sId),
                quorum = computeQuorum(sId, sitzung.gremiumId.toGremiumUuid(), sitzung.scheduledAt.date),
                generatedAt = nowLocalDateTime(),
            )
        }
    }

    override suspend fun submitAntrag(input: AntragInput): AntragDto {
        val current = resolveCurrentMember(call)
        val gId = input.targetGremiumId.toGremiumUuid()
        return transaction {
            GremiumTable.selectAll().where { GremiumTable.id eq gId }.singleOrNull()
                ?: throw NotFoundException("Gremium ${input.targetGremiumId} not found")
            if (!current.canSubmitAntrag(gId)) throw ForbiddenException()
            val id = Uuid.random()
            val now = nowLocalDateTime()
            AntragTable.insert {
                it[AntragTable.id] = id
                it[targetGremiumId] = gId
                it[title] = input.title
                it[begruendung] = input.begruendung
                it[text] = input.text
                it[submitterMemberId] = current.memberId
                it[status] = AntragStatus.EINGEREICHT
                it[submittedAt] = now
                it[reviewedBy] = null
                it[reviewedAt] = null
                it[reviewNote] = null
                it[sitzungId] = null
                it[tagesordnungspunktId] = null
                it[beschlussId] = null
                it[withdrawnAt] = null
            }
            loadAntrag(id)
        }
    }

    override suspend fun listAntraege(
        targetGremiumId: String?,
        status: AntragStatus?,
    ): List<AntragDto> {
        resolveCurrentMember(call)
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()
            if (targetGremiumId != null) conditions += (AntragTable.targetGremiumId eq targetGremiumId.toGremiumUuid())
            if (status != null) conditions += (AntragTable.status eq status)
            val baseQuery = (AntragTable innerJoin GremiumTable).selectAll()
            val query = if (conditions.isEmpty()) baseQuery else baseQuery.where { conditions.reduce { a, b -> a and b } }
            query.map { it.toAntragDto() }
        }
    }

    override suspend fun getAntrag(id: String): AntragDto {
        resolveCurrentMember(call)
        val aId = id.toAntragUuid()
        return transaction { loadAntrag(aId) }
    }

    override suspend fun withdrawAntrag(id: String): AntragDto {
        val current = resolveCurrentMember(call)
        val aId = id.toAntragUuid()
        return transaction {
            val row =
                AntragTable.selectAll().where { AntragTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Antrag $id not found")
            val gremiumId = row[AntragTable.targetGremiumId]
            val submitterId = row[AntragTable.submitterMemberId]
            val status = row[AntragTable.status]
            val submitterWithdrawingOwnPending = current.memberId == submitterId && status == AntragStatus.EINGEREICHT
            if (!submitterWithdrawingOwnPending && !current.canRecordForSitzung(gremiumId)) throw ForbiddenException()
            if (status == AntragStatus.ZURUECKGEZOGEN) throw ConflictException("Antrag $id already withdrawn")
            AntragTable.update({ AntragTable.id eq aId }) {
                it[AntragTable.status] = AntragStatus.ZURUECKGEZOGEN
                it[withdrawnAt] = nowLocalDateTime()
            }
            loadAntrag(aId)
        }
    }

    override suspend fun reviewAntrag(
        id: String,
        decision: AntragPruefungsEntscheidung,
        note: String?,
    ): AntragDto {
        val current = resolveCurrentMember(call)
        val aId = id.toAntragUuid()
        return transaction {
            val row =
                AntragTable.selectAll().where { AntragTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Antrag $id not found")
            val gremiumId = row[AntragTable.targetGremiumId]
            if (!current.canRecordForSitzung(gremiumId)) throw ForbiddenException()
            val status = row[AntragTable.status]
            if (status != AntragStatus.EINGEREICHT) {
                throw ConflictException("Antrag $id is $status, expected EINGEREICHT")
            }
            val newStatus =
                when (decision) {
                    AntragPruefungsEntscheidung.ANNEHMEN -> AntragStatus.GEPRUEFT
                    AntragPruefungsEntscheidung.ABLEHNEN -> AntragStatus.ABGELEHNT_VORPRUEFUNG
                }
            val now = nowLocalDateTime()
            AntragTable.update({ AntragTable.id eq aId }) {
                it[AntragTable.status] = newStatus
                it[reviewedBy] = current.memberId
                it[reviewedAt] = now
                it[reviewNote] = note
            }
            loadAntrag(aId)
        }
    }

    override suspend fun scheduleAntrag(
        id: String,
        sitzungId: String,
        position: Int,
    ): AntragDto {
        val current = resolveCurrentMember(call)
        val aId = id.toAntragUuid()
        val sId = sitzungId.toSitzungUuid()
        return transaction {
            val row =
                AntragTable.selectAll().where { AntragTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Antrag $id not found")
            val gremiumId = row[AntragTable.targetGremiumId]
            if (!current.canRecordForSitzung(gremiumId)) throw ForbiddenException()
            val status = row[AntragTable.status]
            if (status != AntragStatus.GEPRUEFT && status != AntragStatus.VERTAGT) {
                throw ConflictException("Antrag $id is $status, expected GEPRUEFT or VERTAGT")
            }
            val sitzungRow =
                SitzungTable.selectAll().where { SitzungTable.id eq sId }.singleOrNull()
                    ?: throw NotFoundException("Sitzung $sitzungId not found")
            if (sitzungRow[SitzungTable.gremiumId] != gremiumId) {
                throw ConflictException("Sitzung $sitzungId does not belong to Antrag $id's target Gremium")
            }
            if (sitzungRow[SitzungTable.status] != SitzungsStatus.GEPLANT) {
                throw ConflictException("Sitzung $sitzungId is not GEPLANT")
            }
            val top =
                insertTagesordnungspunkt(
                    sId,
                    TagesordnungspunktInput(
                        position = position,
                        title = row[AntragTable.title],
                        description = row[AntragTable.begruendung],
                        presenterMemberId = row[AntragTable.submitterMemberId].toString(),
                    ),
                )
            AntragTable.update({ AntragTable.id eq aId }) {
                it[AntragTable.status] = AntragStatus.TERMINIERT
                it[AntragTable.sitzungId] = sId
                it[AntragTable.tagesordnungspunktId] = Uuid.parse(top.id)
            }
            loadAntrag(aId)
        }
    }

    override suspend fun resolveAntrag(
        id: String,
        input: AntragResolutionInput,
    ): AntragDto {
        val current = resolveCurrentMember(call)
        val aId = id.toAntragUuid()
        return transaction {
            val row =
                AntragTable.selectAll().where { AntragTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Antrag $id not found")
            val gremiumId = row[AntragTable.targetGremiumId]
            if (!current.canRecordForSitzung(gremiumId)) throw ForbiddenException()
            if (row[AntragTable.status] != AntragStatus.TERMINIERT) {
                throw ConflictException("Antrag $id is ${row[AntragTable.status]}, expected TERMINIERT")
            }
            val sId = row[AntragTable.sitzungId] ?: throw ConflictException("Antrag $id has no scheduled Sitzung")
            val topId = row[AntragTable.tagesordnungspunktId]
            val sitzung = loadSitzung(sId)
            val beschlussInput =
                BeschlussInput(
                    tagesordnungspunktId = topId?.toString(),
                    title = row[AntragTable.title],
                    text = row[AntragTable.text],
                    votesYes = input.votesYes,
                    votesNo = input.votesNo,
                    votesAbstain = input.votesAbstain,
                    status = input.status,
                )
            val beschluss = insertBeschlussRow(sId, gremiumId, sitzung.scheduledAt.date, beschlussInput, current)
            val newAntragStatus =
                when (input.status) {
                    BeschlussStatus.ANGENOMMEN -> AntragStatus.BESCHLOSSEN
                    BeschlussStatus.ABGELEHNT -> AntragStatus.ABGELEHNT
                    BeschlussStatus.VERTAGT -> AntragStatus.VERTAGT
                }
            AntragTable.update({ AntragTable.id eq aId }) {
                it[AntragTable.status] = newAntragStatus
                it[AntragTable.beschlussId] = Uuid.parse(beschluss.id)
            }
            loadAntrag(aId)
        }
    }

    override suspend fun openAbstimmung(input: AbstimmungOpenInput): AbstimmungDto {
        val current = resolveCurrentMember(call)
        val aId = input.antragId.toAntragUuid()
        return transaction {
            val antragRow =
                AntragTable.selectAll().where { AntragTable.id eq aId }.singleOrNull()
                    ?: throw NotFoundException("Antrag ${input.antragId} not found")
            val gremiumId = antragRow[AntragTable.targetGremiumId]
            if (!current.canRecordForSitzung(gremiumId)) throw ForbiddenException()
            if (antragRow[AntragTable.status] != AntragStatus.TERMINIERT) {
                throw ConflictException("Antrag ${input.antragId} is ${antragRow[AntragTable.status]}, expected TERMINIERT")
            }
            val sId =
                antragRow[AntragTable.sitzungId]
                    ?: throw ConflictException("Antrag ${input.antragId} has no scheduled Sitzung")
            val hasActiveAbstimmung =
                AbstimmungTable
                    .selectAll()
                    .where {
                        (AbstimmungTable.antragId eq aId) and
                            (AbstimmungTable.status inList listOf(AbstimmungStatus.OFFEN, AbstimmungStatus.GESCHLOSSEN))
                    }.count() > 0
            if (hasActiveAbstimmung) {
                throw ConflictException("Antrag ${input.antragId} already has an open or resolved Abstimmung")
            }
            val distinctLabels =
                input.optionLabels
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
            if (distinctLabels.size < 2) {
                throw ConflictException("openAbstimmung requires at least 2 distinct non-blank option labels")
            }
            if (distinctLabels.size > MAX_ABSTIMMUNG_OPTIONS) {
                throw ConflictException("openAbstimmung accepts at most $MAX_ABSTIMMUNG_OPTIONS distinct option labels")
            }
            if (distinctLabels.any { it.length > MAX_OPTION_LABEL_LENGTH }) {
                throw ConflictException("Option labels must be at most $MAX_OPTION_LABEL_LENGTH characters")
            }
            val id = Uuid.random()
            val now = nowLocalDateTime()
            AbstimmungTable.insert {
                it[AbstimmungTable.id] = id
                it[AbstimmungTable.antragId] = aId
                it[AbstimmungTable.sitzungId] = sId
                it[title] = antragRow[AntragTable.title]
                it[status] = AbstimmungStatus.OFFEN
                it[openedBy] = current.memberId
                it[openedAt] = now
                it[closedAt] = null
                it[winnerOptionId] = null
                it[secondPriceLtr] = null
                it[beschlussId] = null
            }
            distinctLabels.forEachIndexed { index, label ->
                AbstimmungOptionTable.insert {
                    it[AbstimmungOptionTable.id] = Uuid.random()
                    it[AbstimmungOptionTable.abstimmungId] = id
                    it[AbstimmungOptionTable.label] = label
                    it[position] = index
                }
            }
            loadAbstimmung(id)
        }
    }

    /**
     * Eligibility mirrors [computeQuorum]'s [eligibleMemberIds] set for the Abstimmung's
     * underlying Sitzung -- staking LTR into a basket is, per the concept document, a right
     * exercised by the same constituency that would otherwise cast a headcount vote on this
     * Antrag, not an org-wide free-for-all (see the implementation plan's open decision point
     * (c) for the alternative considered and deferred).
     */
    override suspend fun castStimme(input: StimmeInput): StimmeDto {
        val current = resolveCurrentMember(call)
        val abId = input.abstimmungId.toAbstimmungUuid()
        val optId = input.optionId.toAbstimmungOptionUuid()
        return transaction {
            val abstimmungRow =
                AbstimmungTable.selectAll().where { AbstimmungTable.id eq abId }.singleOrNull()
                    ?: throw NotFoundException("Abstimmung ${input.abstimmungId} not found")
            if (abstimmungRow[AbstimmungTable.status] != AbstimmungStatus.OFFEN) {
                throw ConflictException(
                    "Abstimmung ${input.abstimmungId} is ${abstimmungRow[AbstimmungTable.status]}, expected OFFEN",
                )
            }
            AbstimmungOptionTable
                .selectAll()
                .where { (AbstimmungOptionTable.id eq optId) and (AbstimmungOptionTable.abstimmungId eq abId) }
                .singleOrNull()
                ?: throw NotFoundException("Option ${input.optionId} does not belong to Abstimmung ${input.abstimmungId}")

            val sitzung = loadSitzung(abstimmungRow[AbstimmungTable.sitzungId])
            val gremiumRow =
                GremiumTable.selectAll().where { GremiumTable.id eq sitzung.gremiumId.toGremiumUuid() }.single()
            val eligible = eligibleMemberIds(gremiumRow, sitzung.scheduledAt.date)
            if (current.memberId !in eligible) throw ForbiddenException()

            val stake = input.stakeLtr
            if (stake.scale() > 2) throw ConflictException("stakeLtr must have at most 2 decimal places")
            val normalizedStake = stake.setScale(2, RoundingMode.UNNECESSARY)
            if (normalizedStake < MIN_STAKE_LTR) throw ConflictException("stakeLtr must be at least $MIN_STAKE_LTR")
            val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
            if (normalizedStake > freeBalance) {
                throw ConflictException("stakeLtr $normalizedStake exceeds free LTR balance $freeBalance")
            }

            val now = nowLocalDateTime()
            val existing =
                AbstimmungStimmeTable
                    .selectAll()
                    .where {
                        (AbstimmungStimmeTable.abstimmungId eq abId) and (AbstimmungStimmeTable.memberId eq current.memberId)
                    }.singleOrNull()
            val id =
                if (existing == null) {
                    val newId = Uuid.random()
                    AbstimmungStimmeTable.insert {
                        it[AbstimmungStimmeTable.id] = newId
                        it[AbstimmungStimmeTable.abstimmungId] = abId
                        it[AbstimmungStimmeTable.optionId] = optId
                        it[AbstimmungStimmeTable.memberId] = current.memberId
                        it[stakeLtr] = normalizedStake
                        it[settledLtr] = null
                        it[castAt] = now
                    }
                    newId
                } else {
                    val existingId = existing[AbstimmungStimmeTable.id]
                    AbstimmungStimmeTable.update({ AbstimmungStimmeTable.id eq existingId }) {
                        it[AbstimmungStimmeTable.optionId] = optId
                        it[stakeLtr] = normalizedStake
                        it[settledLtr] = null
                        it[castAt] = now
                    }
                    existingId
                }
            AbstimmungStimmeTable
                .selectAll()
                .where { AbstimmungStimmeTable.id eq id }
                .single()
                .toStimmeDto()
        }
    }

    /**
     * Runs the Vickrey settlement ([computeVickreySettlement]) and writes it into the same
     * Beschlussbuch [recordBeschluss]/[resolveAntrag] use, tagged
     * [ResolutionMode.MERITOKRATISCH] -- see [insertBeschlussRow]. `votesYes`/`votesNo` are
     * populated informationally only for the default 2-option JA/NEIN shape (headcount of ballots
     * per label, not LTR-weighted); any other option count leaves them at `0/0/0` since the
     * weighted result lives in the Abstimmung itself, not in headcount fields designed for the
     * Gremium-Quorum path. The winning *basket's* label decides [BeschlussStatus]: a basket
     * labelled `"NEIN"` (case-insensitive) resolves [BeschlussStatus.ABGELEHNT], any other winning
     * basket (including a >2-option Sachentscheidung's winning project) resolves
     * [BeschlussStatus.ANGENOMMEN], and a tie (no winner) resolves [BeschlussStatus.VERTAGT] --
     * documented decision point (a) from the implementation plan.
     */
    override suspend fun closeAbstimmung(abstimmungId: String): AbstimmungDto {
        val current = resolveCurrentMember(call)
        val abId = abstimmungId.toAbstimmungUuid()
        return transaction {
            val abstimmungRow =
                AbstimmungTable.selectAll().where { AbstimmungTable.id eq abId }.singleOrNull()
                    ?: throw NotFoundException("Abstimmung $abstimmungId not found")
            val antragRow =
                AntragTable.selectAll().where { AntragTable.id eq abstimmungRow[AbstimmungTable.antragId] }.single()
            val gremiumId = antragRow[AntragTable.targetGremiumId]
            if (!current.canRecordForSitzung(gremiumId)) throw ForbiddenException()
            // Re-checked inside the transaction: guards against a concurrent second close (or a
            // cast-vs-close race) between the read above and this point.
            if (abstimmungRow[AbstimmungTable.status] != AbstimmungStatus.OFFEN) {
                throw ConflictException("Abstimmung $abstimmungId is ${abstimmungRow[AbstimmungTable.status]}, expected OFFEN")
            }

            val optionRows = AbstimmungOptionTable.selectAll().where { AbstimmungOptionTable.abstimmungId eq abId }.toList()
            val optionIds = optionRows.map { it[AbstimmungOptionTable.id] }
            val labelByOptionId = optionRows.associate { it[AbstimmungOptionTable.id] to it[AbstimmungOptionTable.label] }
            val stimmeRows = AbstimmungStimmeTable.selectAll().where { AbstimmungStimmeTable.abstimmungId eq abId }.toList()
            val ballots =
                stimmeRows.map {
                    Ballot(
                        memberId = it[AbstimmungStimmeTable.memberId],
                        optionId = it[AbstimmungStimmeTable.optionId],
                        stake = it[AbstimmungStimmeTable.stakeLtr],
                    )
                }
            val settlement = computeVickreySettlement(ballots, optionIds)
            val now = nowLocalDateTime()

            stimmeRows.forEach { row ->
                val stimmeId = row[AbstimmungStimmeTable.id]
                val memberId = row[AbstimmungStimmeTable.memberId]
                val settled = settlement.charges[memberId] ?: ZERO_LTR
                AbstimmungStimmeTable.update({ AbstimmungStimmeTable.id eq stimmeId }) {
                    it[settledLtr] = settled
                }
            }

            val beschlussStatus =
                when (val winnerId = settlement.winnerOptionId) {
                    null -> BeschlussStatus.VERTAGT
                    else -> {
                        val winnerLabel = labelByOptionId.getValue(winnerId)
                        if (winnerLabel.equals("NEIN", ignoreCase = true)) BeschlussStatus.ABGELEHNT else BeschlussStatus.ANGENOMMEN
                    }
                }
            val (votesYes, votesNo) =
                if (optionRows.size == 2) {
                    val yes = stimmeRows.count { labelByOptionId[it[AbstimmungStimmeTable.optionId]].equals("JA", ignoreCase = true) }
                    val no = stimmeRows.count { labelByOptionId[it[AbstimmungStimmeTable.optionId]].equals("NEIN", ignoreCase = true) }
                    yes to no
                } else {
                    0 to 0
                }

            val sId = abstimmungRow[AbstimmungTable.sitzungId]
            val sitzung = loadSitzung(sId)
            val beschlussInput =
                BeschlussInput(
                    tagesordnungspunktId = antragRow[AntragTable.tagesordnungspunktId]?.toString(),
                    title = antragRow[AntragTable.title],
                    text = antragRow[AntragTable.text],
                    votesYes = votesYes,
                    votesNo = votesNo,
                    votesAbstain = 0,
                    status = beschlussStatus,
                )
            val beschluss =
                insertBeschlussRow(
                    sId,
                    gremiumId,
                    sitzung.scheduledAt.date,
                    beschlussInput,
                    current,
                    resolutionMode = ResolutionMode.MERITOKRATISCH,
                    abstimmungId = abId,
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

            AbstimmungTable.update({ AbstimmungTable.id eq abId }) {
                it[status] = AbstimmungStatus.GESCHLOSSEN
                it[closedAt] = now
                it[winnerOptionId] = settlement.winnerOptionId
                it[secondPriceLtr] = settlement.secondPrice
                it[beschlussId] = Uuid.parse(beschluss.id)
            }
            loadAbstimmung(abId)
        }
    }

    override suspend fun abortAbstimmung(abstimmungId: String): AbstimmungDto {
        val current = resolveCurrentMember(call)
        val abId = abstimmungId.toAbstimmungUuid()
        return transaction {
            val abstimmungRow =
                AbstimmungTable.selectAll().where { AbstimmungTable.id eq abId }.singleOrNull()
                    ?: throw NotFoundException("Abstimmung $abstimmungId not found")
            val antragRow =
                AntragTable.selectAll().where { AntragTable.id eq abstimmungRow[AbstimmungTable.antragId] }.single()
            if (!current.canRecordForSitzung(antragRow[AntragTable.targetGremiumId])) throw ForbiddenException()
            if (abstimmungRow[AbstimmungTable.status] != AbstimmungStatus.OFFEN) {
                throw ConflictException("Abstimmung $abstimmungId is ${abstimmungRow[AbstimmungTable.status]}, expected OFFEN")
            }
            AbstimmungTable.update({ AbstimmungTable.id eq abId }) {
                it[status] = AbstimmungStatus.ABGEBROCHEN
                it[closedAt] = nowLocalDateTime()
            }
            loadAbstimmung(abId)
        }
    }

    override suspend fun getAbstimmung(abstimmungId: String): AbstimmungDto {
        resolveCurrentMember(call)
        val abId = abstimmungId.toAbstimmungUuid()
        return transaction { loadAbstimmung(abId) }
    }

    override suspend fun listStimmen(abstimmungId: String): List<StimmeDto> {
        resolveCurrentMember(call)
        val abId = abstimmungId.toAbstimmungUuid()
        return transaction {
            AbstimmungTable.selectAll().where { AbstimmungTable.id eq abId }.singleOrNull()
                ?: throw NotFoundException("Abstimmung $abstimmungId not found")
            AbstimmungStimmeTable
                .selectAll()
                .where { AbstimmungStimmeTable.abstimmungId eq abId }
                .map { it.toStimmeDto() }
        }
    }

    private fun loadSitzung(id: Uuid): SitzungDto =
        (SitzungTable innerJoin GremiumTable)
            .selectAll()
            .where { SitzungTable.id eq id }
            .singleOrNull()
            ?.toSitzungDto()
            ?: throw NotFoundException("Sitzung $id not found")

    private fun requireSitzungGremiumId(sitzungId: Uuid): Uuid =
        SitzungTable
            .selectAll()
            .where { SitzungTable.id eq sitzungId }
            .singleOrNull()
            ?.get(SitzungTable.gremiumId)
            ?: throw NotFoundException("Sitzung $sitzungId not found")

    private fun loadTagesordnung(sitzungId: Uuid): List<TagesordnungspunktDto> =
        TagesordnungspunktTable
            .selectAll()
            .where { TagesordnungspunktTable.sitzungId eq sitzungId }
            .orderBy(TagesordnungspunktTable.position, SortOrder.ASC)
            .map { it.toTagesordnungspunktDto() }

    private fun loadAnwesenheit(sitzungId: Uuid): List<AnwesenheitDto> =
        AnwesenheitTable
            .selectAll()
            .where { AnwesenheitTable.sitzungId eq sitzungId }
            .map { it.toAnwesenheitDto() }

    private fun loadBeschluesse(sitzungId: Uuid): List<BeschlussDto> =
        BeschlussTable
            .selectAll()
            .where { BeschlussTable.sitzungId eq sitzungId }
            .map { it.toBeschlussDto() }

    private fun loadAntrag(id: Uuid): AntragDto =
        (AntragTable innerJoin GremiumTable)
            .selectAll()
            .where { AntragTable.id eq id }
            .singleOrNull()
            ?.toAntragDto()
            ?: throw NotFoundException("Antrag $id not found")

    private fun loadAbstimmung(id: Uuid): AbstimmungDto =
        AbstimmungTable
            .selectAll()
            .where { AbstimmungTable.id eq id }
            .singleOrNull()
            ?.toAbstimmungDto()
            ?: throw NotFoundException("Abstimmung $id not found")

    /**
     * Shared insert path for [addTagesordnungspunkt] and [scheduleAntrag] (V0.2.2) — the latter
     * populates [TagesordnungspunktInput] from the Antrag (`title`/`begruendung`/submitter) rather
     * than duplicating the position-collision check and insert logic. Must run inside an
     * already-open `transaction {}` (both call sites do).
     */
    private fun insertTagesordnungspunkt(
        sId: Uuid,
        input: TagesordnungspunktInput,
    ): TagesordnungspunktDto {
        val positionTaken =
            TagesordnungspunktTable
                .selectAll()
                .where {
                    (TagesordnungspunktTable.sitzungId eq sId) and (TagesordnungspunktTable.position eq input.position)
                }.count() > 0
        if (positionTaken) throw ConflictException("Position ${input.position} already used for Sitzung $sId")
        val id = Uuid.random()
        TagesordnungspunktTable.insert {
            it[TagesordnungspunktTable.id] = id
            it[TagesordnungspunktTable.sitzungId] = sId
            it[position] = input.position
            it[title] = input.title
            it[description] = input.description
            it[presenterMemberId] = input.presenterMemberId?.let(Uuid::parse)
        }
        return TagesordnungspunktTable
            .selectAll()
            .where { TagesordnungspunktTable.id eq id }
            .single()
            .toTagesordnungspunktDto()
    }

    private fun memberDisplayName(memberId: Uuid?): String? =
        memberId?.let { id ->
            MemberTable
                .selectAll()
                .where { MemberTable.id eq id }
                .singleOrNull()
                ?.get(MemberTable.displayName)
        }

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun ResultRow.toGremiumDto(): GremiumDto =
        GremiumDto(
            id = this[GremiumTable.id].toString(),
            name = this[GremiumTable.name],
            type = this[GremiumTable.type],
            description = this[GremiumTable.description],
            active = this[GremiumTable.active],
            quorumPercent = this[GremiumTable.quorumPercent],
            createdAt = this[GremiumTable.createdAt],
        )

    private fun ResultRow.toGremiumMitgliedschaftDto(): GremiumMitgliedschaftDto =
        GremiumMitgliedschaftDto(
            id = this[GremiumMitgliedschaftTable.id].toString(),
            gremiumId = this[GremiumMitgliedschaftTable.gremiumId].toString(),
            memberId = this[GremiumMitgliedschaftTable.memberId].toString(),
            memberDisplayName = this[MemberTable.displayName],
            rolle = this[GremiumMitgliedschaftTable.rolle],
            since = this[GremiumMitgliedschaftTable.since],
            until = this[GremiumMitgliedschaftTable.until],
        )

    private fun ResultRow.toSitzungDto(): SitzungDto =
        SitzungDto(
            id = this[SitzungTable.id].toString(),
            gremiumId = this[SitzungTable.gremiumId].toString(),
            gremiumName = this[GremiumTable.name],
            title = this[SitzungTable.title],
            scheduledAt = this[SitzungTable.scheduledAt],
            location = this[SitzungTable.location],
            format = this[SitzungTable.format],
            status = this[SitzungTable.status],
            calledById = this[SitzungTable.calledBy]?.toString(),
            calledByDisplayName = memberDisplayName(this[SitzungTable.calledBy]),
            calledAt = this[SitzungTable.calledAt],
            chairMemberId = this[SitzungTable.chairMemberId]?.toString(),
            chairDisplayName = memberDisplayName(this[SitzungTable.chairMemberId]),
            minuteTakerMemberId = this[SitzungTable.minuteTakerMemberId]?.toString(),
            minuteTakerDisplayName = memberDisplayName(this[SitzungTable.minuteTakerMemberId]),
            protocolDocumentId = this[SitzungTable.protocolDocumentId]?.toString(),
            createdAt = this[SitzungTable.createdAt],
        )

    private fun ResultRow.toTagesordnungspunktDto(): TagesordnungspunktDto =
        TagesordnungspunktDto(
            id = this[TagesordnungspunktTable.id].toString(),
            sitzungId = this[TagesordnungspunktTable.sitzungId].toString(),
            position = this[TagesordnungspunktTable.position],
            title = this[TagesordnungspunktTable.title],
            description = this[TagesordnungspunktTable.description],
            presenterMemberId = this[TagesordnungspunktTable.presenterMemberId]?.toString(),
            presenterDisplayName = memberDisplayName(this[TagesordnungspunktTable.presenterMemberId]),
        )

    private fun ResultRow.toAnwesenheitDto(): AnwesenheitDto =
        AnwesenheitDto(
            id = this[AnwesenheitTable.id].toString(),
            sitzungId = this[AnwesenheitTable.sitzungId].toString(),
            memberId = this[AnwesenheitTable.memberId].toString(),
            memberDisplayName = memberDisplayName(this[AnwesenheitTable.memberId]).orEmpty(),
            status = this[AnwesenheitTable.status],
            representedByMemberId = this[AnwesenheitTable.representedByMemberId]?.toString(),
            representedByDisplayName = memberDisplayName(this[AnwesenheitTable.representedByMemberId]),
            note = this[AnwesenheitTable.note],
            recordedAt = this[AnwesenheitTable.recordedAt],
        )

    /**
     * Follow-up-queries the options ([AbstimmungOptionTable]) and their computed basket totals
     * (summed from [AbstimmungStimmeTable], never stored) for this Abstimmung — same
     * "simple-transaction" style as [memberDisplayName], not an optimized single join.
     */
    private fun ResultRow.toAbstimmungDto(): AbstimmungDto {
        val abstimmungId = this[AbstimmungTable.id]
        val stakesByOption =
            AbstimmungStimmeTable
                .selectAll()
                .where { AbstimmungStimmeTable.abstimmungId eq abstimmungId }
                .groupBy({ it[AbstimmungStimmeTable.optionId] }, { it[AbstimmungStimmeTable.stakeLtr] })
                .mapValues { (_, stakes) -> stakes.fold(ZERO_LTR) { acc, stake -> acc + stake } }
        val options =
            AbstimmungOptionTable
                .selectAll()
                .where { AbstimmungOptionTable.abstimmungId eq abstimmungId }
                .orderBy(AbstimmungOptionTable.position, SortOrder.ASC)
                .map { optRow ->
                    val optionId = optRow[AbstimmungOptionTable.id]
                    AbstimmungOptionDto(
                        id = optionId.toString(),
                        abstimmungId = abstimmungId.toString(),
                        label = optRow[AbstimmungOptionTable.label],
                        position = optRow[AbstimmungOptionTable.position],
                        basketTotalLtr = stakesByOption[optionId] ?: ZERO_LTR,
                    )
                }
        return AbstimmungDto(
            id = abstimmungId.toString(),
            antragId = this[AbstimmungTable.antragId].toString(),
            sitzungId = this[AbstimmungTable.sitzungId].toString(),
            title = this[AbstimmungTable.title],
            status = this[AbstimmungTable.status],
            options = options,
            winnerOptionId = this[AbstimmungTable.winnerOptionId]?.toString(),
            secondPriceLtr = this[AbstimmungTable.secondPriceLtr],
            openedById = this[AbstimmungTable.openedBy].toString(),
            openedByDisplayName = memberDisplayName(this[AbstimmungTable.openedBy]).orEmpty(),
            openedAt = this[AbstimmungTable.openedAt],
            closedAt = this[AbstimmungTable.closedAt],
            beschlussId = this[AbstimmungTable.beschlussId]?.toString(),
        )
    }

    private fun ResultRow.toStimmeDto(): StimmeDto =
        StimmeDto(
            id = this[AbstimmungStimmeTable.id].toString(),
            abstimmungId = this[AbstimmungStimmeTable.abstimmungId].toString(),
            optionId = this[AbstimmungStimmeTable.optionId].toString(),
            memberId = this[AbstimmungStimmeTable.memberId].toString(),
            memberDisplayName = memberDisplayName(this[AbstimmungStimmeTable.memberId]).orEmpty(),
            stakeLtr = this[AbstimmungStimmeTable.stakeLtr],
            settledLtr = this[AbstimmungStimmeTable.settledLtr],
            castAt = this[AbstimmungStimmeTable.castAt],
        )

    private fun ResultRow.toAntragDto(): AntragDto =
        AntragDto(
            id = this[AntragTable.id].toString(),
            targetGremiumId = this[AntragTable.targetGremiumId].toString(),
            targetGremiumName = this[GremiumTable.name],
            targetGremiumType = this[GremiumTable.type],
            title = this[AntragTable.title],
            begruendung = this[AntragTable.begruendung],
            text = this[AntragTable.text],
            submitterMemberId = this[AntragTable.submitterMemberId].toString(),
            submitterDisplayName = memberDisplayName(this[AntragTable.submitterMemberId]).orEmpty(),
            status = this[AntragTable.status],
            submittedAt = this[AntragTable.submittedAt],
            reviewedById = this[AntragTable.reviewedBy]?.toString(),
            reviewedByDisplayName = memberDisplayName(this[AntragTable.reviewedBy]),
            reviewedAt = this[AntragTable.reviewedAt],
            reviewNote = this[AntragTable.reviewNote],
            sitzungId = this[AntragTable.sitzungId]?.toString(),
            tagesordnungspunktId = this[AntragTable.tagesordnungspunktId]?.toString(),
            beschlussId = this[AntragTable.beschlussId]?.toString(),
        )

    private fun String.toGremiumUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toMemberUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toSitzungUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toMitgliedschaftUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toTagesordnungspunktUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toAntragUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toAbstimmungUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }

    private fun String.toAbstimmungOptionUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
