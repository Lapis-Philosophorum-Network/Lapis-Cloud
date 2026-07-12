package network.lapis.cloud.server.db.tables

import network.lapis.cloud.shared.domain.AbstimmungStatus
import network.lapis.cloud.shared.domain.AntragStatus
import network.lapis.cloud.shared.domain.AnwesenheitStatus
import network.lapis.cloud.shared.domain.BeschlussStatus
import network.lapis.cloud.shared.domain.GremiumRolle
import network.lapis.cloud.shared.domain.GremiumType
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.SitzungsFormat
import network.lapis.cloud.shared.domain.SitzungsStatus
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.date
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Gremien-/Sitzungsverwaltung (V0.2.1) — hand-written, following the same
 * `object : Table`/Flyway-owns-schema pattern as [ContributionTables]/[DocumentTables] (see
 * CLAUDE.md "Vorab-Befund": the `uml-to-exposed` MDA pipeline does not exist for this
 * repository). Schema itself lives in `V6__governance.sql`.
 */
object GremiumTable : Table("gremium") {
    val id = uuid("id")
    val name = varchar("name", 200)

    // Widened from VARCHAR(20) to VARCHAR(30) by V7__antragsverwaltung.sql (V0.2.2): the new
    // GremiumType.MITGLIEDERVERSAMMLUNG value is 21 characters, one over the original limit.
    val type = enumerationByName<GremiumType>("type", 30)
    val description = varchar("description", 1000)
    val active = bool("active")
    val quorumPercent = integer("quorum_percent")
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * No unique constraint on `(gremiumId, memberId)` alone — a person can rejoin later with a new
 * since/until range. Instead a partial-uniqueness-style guard is enforced in
 * `network.lapis.cloud.server.rpc.GovernanceService.addGremiumMitglied`: it rejects a new row if
 * an *active* (`until IS NULL`) membership for that member+gremium already exists.
 */
object GremiumMitgliedschaftTable : Table("gremium_mitgliedschaft") {
    val id = uuid("id")
    val gremiumId = uuid("gremium_id").references(GremiumTable.id)
    val memberId = uuid("member_id").references(MemberTable.id)
    val rolle = enumerationByName<GremiumRolle>("rolle", 20)
    val since = date("since")
    val until = date("until").nullable()

    override val primaryKey = PrimaryKey(id)
}

object SitzungTable : Table("sitzung") {
    val id = uuid("id")
    val gremiumId = uuid("gremium_id").references(GremiumTable.id)
    val title = varchar("title", 300)
    val scheduledAt = datetime("scheduled_at")
    val location = varchar("location", 300).nullable()
    val format = enumerationByName<SitzungsFormat>("format", 20)
    val status = enumerationByName<SitzungsStatus>("status", 20)
    val calledBy = uuid("called_by").references(MemberTable.id).nullable()
    val calledAt = datetime("called_at").nullable()
    val chairMemberId = uuid("chair_member_id").references(MemberTable.id).nullable()
    val minuteTakerMemberId = uuid("minute_taker_member_id").references(MemberTable.id).nullable()

    // Forward-looking hook for once a finalized protocol PDF is uploaded via the existing
    // Dokumentenablage (DocumentTable) — no behavior wired to it yet in this wave.
    val protocolDocumentId = uuid("protocol_document_id").references(DocumentTable.id).nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

object TagesordnungspunktTable : Table("tagesordnungspunkt") {
    val id = uuid("id")
    val sitzungId = uuid("sitzung_id").references(SitzungTable.id)
    val position = integer("position")
    val title = varchar("title", 300)

    // Widened from VARCHAR(1000) to VARCHAR(4000) by V7__antragsverwaltung.sql (V0.2.2):
    // scheduleAntrag populates this from Antrag.begruendung, which is VARCHAR(4000).
    val description = varchar("description", 4000).nullable()
    val presenterMemberId = uuid("presenter_member_id").references(MemberTable.id).nullable()

    override val primaryKey = PrimaryKey(id)
}

object AnwesenheitTable : Table("anwesenheit") {
    val id = uuid("id")
    val sitzungId = uuid("sitzung_id").references(SitzungTable.id)
    val memberId = uuid("member_id").references(MemberTable.id)
    val status = enumerationByName<AnwesenheitStatus>("status", 20)
    val representedByMemberId = uuid("represented_by_member_id").references(MemberTable.id).nullable()
    val note = varchar("note", 500).nullable()
    val recordedAt = datetime("recorded_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * `number` is formatted `"<GremiumType>-<Jahr>-<laufendeNummer>"` (e.g. `"VORSTAND-2026-03"`),
 * computed in `network.lapis.cloud.server.rpc.GovernanceService` — not DB-generated, consistent
 * with the rest of the codebase's simple-transaction style (no DB sequence at this scale).
 */
object BeschlussTable : Table("beschluss") {
    val id = uuid("id")
    val sitzungId = uuid("sitzung_id").references(SitzungTable.id)
    val tagesordnungspunktId = uuid("tagesordnungspunkt_id").references(TagesordnungspunktTable.id).nullable()
    val number = varchar("number", 50)
    val title = varchar("title", 300)
    val text = varchar("text", 4000)
    val votesYes = integer("votes_yes")
    val votesNo = integer("votes_no")
    val votesAbstain = integer("votes_abstain")

    // Snapshotted at decision time via GovernanceService.checkQuorum, not recomputed later —
    // Anwesenheit rows may still change after the fact (corrections), but the historical
    // Beschlussfaehigkeit determination for this specific Beschluss must not silently change too.
    val quorumMet = bool("quorum_met")
    val status = enumerationByName<BeschlussStatus>("status", 20)
    val decidedAt = datetime("decided_at")
    val recordedBy = uuid("recorded_by").references(MemberTable.id)

    // Meritokratische Abstimmungen (V0.2.3): distinguishes this Gremium-Quorum Beschlussbuch
    // (V0.2.1/V0.2.2, the default) from the new Vickrey-basket-auction path. DB DEFAULT
    // 'GREMIUM_QUORUM' (see V8__meritokratische_abstimmungen.sql) keeps recordBeschluss/
    // resolveAntrag call sites — and every pre-existing row — unchanged.
    val resolutionMode = enumerationByName<ResolutionMode>("resolution_mode", 20)

    // Set only when resolutionMode == MERITOKRATISCH, linking back to the Abstimmung whose
    // Vickrey settlement produced this Beschluss. Nullable: a GREMIUM_QUORUM Beschluss has none.
    val abstimmungId = uuid("abstimmung_id").references(AbstimmungTable.id).nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Meritokratische Abstimmungen (V0.2.3): an eBay/Vickrey basket auction opened on a
 * [AntragStatus.TERMINIERT] Antrag — see `network.lapis.cloud.server.rpc.GovernanceService`
 * (openAbstimmung/castStimme/closeAbstimmung/abortAbstimmung) and
 * `network.lapis.cloud.server.rpc.AbstimmungSettlement` (the pure Vickrey settlement function).
 * `winnerOptionId`/`secondPriceLtr` are settlement-audit fields written once at close (null while
 * [AbstimmungStatus.OFFEN], and both stay null on a tie — see `AbstimmungSettlement` KDoc).
 * `beschlussId` is set at close, mirroring [AntragTable.beschlussId]'s "authoritative resolution
 * pointer" role.
 */
object AbstimmungTable : Table("abstimmung") {
    val id = uuid("id")
    val antragId = uuid("antrag_id").references(AntragTable.id)
    val sitzungId = uuid("sitzung_id").references(SitzungTable.id)
    val title = varchar("title", 300)
    val status = enumerationByName<AbstimmungStatus>("status", 30)
    val openedBy = uuid("opened_by").references(MemberTable.id)
    val openedAt = datetime("opened_at")
    val closedAt = datetime("closed_at").nullable()
    val winnerOptionId = uuid("winner_option_id").nullable()
    val secondPriceLtr = decimal("second_price_ltr", 18, 2).nullable()
    val beschlussId = uuid("beschluss_id").references(BeschlussTable.id).nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * A basket. No member FK — baskets themselves carry no personal data, only the
 * [AbstimmungStimmeTable] rows staked into them do. The basket total is deliberately *not*
 * stored here (would drift from the ballots); it is always computed by summing
 * [AbstimmungStimmeTable.stakeLtr] for this option.
 */
object AbstimmungOptionTable : Table("abstimmung_option") {
    val id = uuid("id")
    val abstimmungId = uuid("abstimmung_id").references(AbstimmungTable.id)
    val label = varchar("label", 200)
    val position = integer("position")

    override val primaryKey = PrimaryKey(id)
}

/**
 * The per-member ballot — personal-data-bearing (staked/settled LTR amounts are the member's
 * property record). DB `UNIQUE(abstimmung_id, member_id)` (see the V8 migration) is the
 * anti-ballot-stuffing backstop behind `GovernanceService.castStimme`'s upsert-per-member logic,
 * mirroring [AnwesenheitTable]'s `uq_anwesenheit_member` precedent. `settledLtr` is null while the
 * Abstimmung is [AbstimmungStatus.OFFEN] and computed once at close by
 * `network.lapis.cloud.server.rpc.AbstimmungSettlement.computeVickreySettlement` — losers get 0,
 * never null, once settled.
 */
object AbstimmungStimmeTable : Table("abstimmung_stimme") {
    val id = uuid("id")
    val abstimmungId = uuid("abstimmung_id").references(AbstimmungTable.id)
    val optionId = uuid("option_id").references(AbstimmungOptionTable.id)
    val memberId = uuid("member_id").references(MemberTable.id)
    val stakeLtr = decimal("stake_ltr", 18, 2)
    val settledLtr = decimal("settled_ltr", 18, 2).nullable()
    val castAt = datetime("cast_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Antragsverwaltung (V0.2.2). `beschlussId` is the authoritative "latest resolution" pointer --
 * set (and overwritten) every time `GovernanceService.resolveAntrag` records a new [BeschlussTable]
 * row for this Antrag, so a [AntragStatus.VERTAGT] -> rescheduled -> re-resolved Antrag always
 * points at its current Beschluss without an indirect join through `tagesordnungspunktId` (which
 * changes across reschedules). Schema owned by `V7__antragsverwaltung.sql`.
 */
object AntragTable : Table("antrag") {
    val id = uuid("id")
    val targetGremiumId = uuid("target_gremium_id").references(GremiumTable.id)
    val title = varchar("title", 300)
    val begruendung = varchar("begruendung", 4000)
    val text = varchar("text", 4000)
    val submitterMemberId = uuid("submitter_member_id").references(MemberTable.id)

    // VARCHAR(30), not the usual 20: AntragStatus.ABGELEHNT_VORPRUEFUNG is 21 characters.
    val status = enumerationByName<AntragStatus>("status", 30)
    val submittedAt = datetime("submitted_at")
    val reviewedBy = uuid("reviewed_by").references(MemberTable.id).nullable()
    val reviewedAt = datetime("reviewed_at").nullable()
    val reviewNote = varchar("review_note", 1000).nullable()
    val sitzungId = uuid("sitzung_id").references(SitzungTable.id).nullable()
    val tagesordnungspunktId = uuid("tagesordnungspunkt_id").references(TagesordnungspunktTable.id).nullable()
    val beschlussId = uuid("beschluss_id").references(BeschlussTable.id).nullable()
    val withdrawnAt = datetime("withdrawn_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
