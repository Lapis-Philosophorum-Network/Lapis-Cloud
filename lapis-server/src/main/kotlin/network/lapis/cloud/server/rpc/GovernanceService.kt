package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.tables.AntragTable
import network.lapis.cloud.server.db.tables.AnwesenheitTable
import network.lapis.cloud.server.db.tables.BeschlussTable
import network.lapis.cloud.server.db.tables.GremiumMitgliedschaftTable
import network.lapis.cloud.server.db.tables.GremiumTable
import network.lapis.cloud.server.db.tables.MemberTable
import network.lapis.cloud.server.db.tables.SitzungTable
import network.lapis.cloud.server.db.tables.TagesordnungspunktTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.canRecordForSitzung
import network.lapis.cloud.server.security.canSubmitAntrag
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AntragDto
import network.lapis.cloud.shared.domain.AntragInput
import network.lapis.cloud.shared.domain.AntragPruefungsEntscheidung
import network.lapis.cloud.shared.domain.AntragResolutionInput
import network.lapis.cloud.shared.domain.AntragStatus
import network.lapis.cloud.shared.domain.AnwesenheitDto
import network.lapis.cloud.shared.domain.AnwesenheitInput
import network.lapis.cloud.shared.domain.AnwesenheitStatus
import network.lapis.cloud.shared.domain.BeschlussDto
import network.lapis.cloud.shared.domain.BeschlussInput
import network.lapis.cloud.shared.domain.BeschlussStatus
import network.lapis.cloud.shared.domain.GremiumDto
import network.lapis.cloud.shared.domain.GremiumInput
import network.lapis.cloud.shared.domain.GremiumMitgliedschaftDto
import network.lapis.cloud.shared.domain.GremiumMitgliedschaftInput
import network.lapis.cloud.shared.domain.GremiumType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.ProtocolDraftDto
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.SitzungDetailDto
import network.lapis.cloud.shared.domain.SitzungDto
import network.lapis.cloud.shared.domain.SitzungInput
import network.lapis.cloud.shared.domain.SitzungsStatus
import network.lapis.cloud.shared.domain.TagesordnungspunktDto
import network.lapis.cloud.shared.domain.TagesordnungspunktInput
import network.lapis.cloud.shared.rpc.IGovernanceService
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val BOARD_ROLES = arrayOf(AccountRole.BOARD, AccountRole.ADMIN)

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
 */
class GovernanceService(
    private val call: ApplicationCall,
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
                quorum = computeQuorum(sId, sitzung),
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
            computeQuorum(sId, sitzung)
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
            insertBeschlussRow(sId, gremiumId, sitzung, input, current)
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
                quorum = computeQuorum(sId, sitzung),
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
            val beschluss = insertBeschlussRow(sId, gremiumId, sitzung, beschlussInput, current)
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

    /**
     * Shared insert path for [recordBeschluss] and [resolveAntrag] (V0.2.2) — what actually makes
     * "Antrag resolution links into the existing Beschlussbuch mechanism rather than creating a
     * parallel one" true in code, not just in the DTO shape. Must run inside an already-open
     * `transaction {}` (both call sites do).
     */
    private fun insertBeschlussRow(
        sId: Uuid,
        gremiumId: Uuid,
        sitzung: SitzungDto,
        input: BeschlussInput,
        current: CurrentMember,
    ): BeschlussDto {
        val quorum = computeQuorum(sId, sitzung)
        val gremiumRow = GremiumTable.selectAll().where { GremiumTable.id eq gremiumId }.single()
        val now = nowLocalDateTime()
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
        }
        return BeschlussTable
            .selectAll()
            .where { BeschlussTable.id eq id }
            .single()
            .toBeschlussDto()
    }

    /**
     * Beschlussfaehigkeit "as of" [SitzungDto.scheduledAt]'s date, not "today" — a Beschluss
     * recorded weeks after the meeting must still reflect the quorum situation on the meeting
     * date, not whatever the Gremium's membership looks like when [recordBeschluss] happens to
     * run. `eligibleMemberCount` only counts active [GremiumMitgliedschaftTable] rows for the
     * Gremium (a non-Gremium guest present at the Sitzung does not count toward quorum, even if
     * marked ANWESEND/VERTRETEN in [AnwesenheitTable]) — except for a
     * [GremiumType.MITGLIEDERVERSAMMLUNG]-typed Gremium (V0.2.2), where eligibility is instead
     * "all members with [MemberStatus.AKTIV]" queried directly from [MemberTable]: syncing every
     * member into [GremiumMitgliedschaftTable] on join/leave would be a brittle parallel
     * bookkeeping system. Known limitation of the Mitgliederversammlung path: unlike the Gremium
     * path (date-scoped via `since`/`until`), it checks *current* [MemberStatus], not "status as
     * of the Sitzung date" — acceptable simplification for this wave, flagged for revisit if it
     * becomes a real pain point (e.g. once membership churn is high or historical MV quorum
     * disputes arise).
     */
    private fun computeQuorum(
        sitzungId: Uuid,
        sitzung: SitzungDto,
    ): QuorumResultDto {
        val scheduledDate = sitzung.scheduledAt.date
        val gremiumId = sitzung.gremiumId.toGremiumUuid()
        val gremiumRow = GremiumTable.selectAll().where { GremiumTable.id eq gremiumId }.single()
        val quorumPercent = gremiumRow[GremiumTable.quorumPercent]
        val eligibleMemberIds =
            if (gremiumRow[GremiumTable.type] == GremiumType.MITGLIEDERVERSAMMLUNG) {
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
        val presentCount =
            AnwesenheitTable
                .selectAll()
                .where {
                    (AnwesenheitTable.sitzungId eq sitzungId) and
                        (AnwesenheitTable.status inList listOf(AnwesenheitStatus.ANWESEND, AnwesenheitStatus.VERTRETEN))
                }.map { it[AnwesenheitTable.memberId] }
                .count { it in eligibleMemberIds }
        val eligibleCount = eligibleMemberIds.size
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
     * `"<GremiumType>-<Jahr>-<laufendeNummer>"` (e.g. `"VORSTAND-2026-03"`). The running number
     * is `count(beschluss where gremium = X and year(decidedAt) = Y) + 1`, computed by loading
     * this Gremium's Beschluss rows for the year and filtering in Kotlin rather than a DB-side
     * `EXTRACT(YEAR FROM ...)` — no DB sequence needed at this scale, and avoids a
     * date-function that behaves slightly differently between H2 and Postgres.
     */
    private fun nextBeschlussNumber(
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

    private fun ResultRow.toBeschlussDto(): BeschlussDto =
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
            recordedByDisplayName = memberDisplayName(this[BeschlussTable.recordedBy]).orEmpty(),
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
}
