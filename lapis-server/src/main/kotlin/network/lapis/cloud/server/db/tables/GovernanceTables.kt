package network.lapis.cloud.server.db.tables

import network.lapis.cloud.shared.domain.AnwesenheitStatus
import network.lapis.cloud.shared.domain.BeschlussStatus
import network.lapis.cloud.shared.domain.GremiumRolle
import network.lapis.cloud.shared.domain.GremiumType
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
    val type = enumerationByName<GremiumType>("type", 20)
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
    val description = varchar("description", 1000).nullable()
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

    override val primaryKey = PrimaryKey(id)
}
