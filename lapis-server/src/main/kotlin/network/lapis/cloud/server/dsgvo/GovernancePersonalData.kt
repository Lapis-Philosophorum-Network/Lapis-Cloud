package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.AbstimmungStimmeTable
import network.lapis.cloud.server.db.generated.AbstimmungTable
import network.lapis.cloud.server.db.generated.AntragTable
import network.lapis.cloud.server.db.generated.AnwesenheitTable
import network.lapis.cloud.server.db.generated.BeschlussTable
import network.lapis.cloud.server.db.generated.GremiumMitgliedschaftTable
import network.lapis.cloud.server.db.generated.GremiumTable
import network.lapis.cloud.server.db.generated.SitzungTable
import network.lapis.cloud.server.db.generated.TagesordnungspunktTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Owns [GremiumMitgliedschaftTable]/[SitzungTable]/[TagesordnungspunktTable]/[AnwesenheitTable]/
 * [BeschlussTable] — the five member-FK-bearing tables of the Gremien-/Sitzungsverwaltung wave
 * (V0.2.1) — plus [AntragTable] (Antragsverwaltung, V0.2.2) and [AbstimmungTable]/
 * [AbstimmungStimmeTable] (Meritokratische Abstimmungen, V0.2.3), the same domain area.
 * [GremiumTable] itself has no member FK and is instead listed in
 * [PersonalDataRegistry.noPersonalDataAllowlist]; so does `abstimmung_option` (no member FK at
 * all -- only the ballots staked into an option carry personal data, not the basket itself).
 * [AntragTable.targetGremiumId] references `gremium`, not `member`, so only
 * `submitter_member_id`/`reviewed_by` are subject to `PersonalDataCoverageTest`'s
 * `information_schema` FK walk — both covered simply by adding [AntragTable] here. Likewise
 * [AbstimmungTable.antragId]/`.sitzungId`/`.beschlussId` reference `antrag`/`sitzung`/`beschluss`,
 * not `member` -- only `opened_by` is a member FK.
 *
 * Retain-with-reason across the board, consistent with [ContributionPersonalData]/
 * [DocumentPersonalData] precedent — governance records (who chaired a meeting, attendance
 * history behind a Beschlussfaehigkeit determination, the resolution text itself, an Antrag's
 * motion text and review rationale, a Vickrey ballot's staked/settled LTR amounts) are
 * organizational/legal-defensibility records (and, for ballots, also the member's property
 * record), not purely personal data, and all FK pointers resolve to the now-anonymized
 * [network.lapis.cloud.server.db.generated.MemberTable] row post-erasure (see
 * [FoundationPersonalData]).
 */
object GovernancePersonalData : PersonalDataContributor {
    override val sectionKey = "governance"
    override val displayName = "Gremien und Sitzungen"
    override val coveredTables =
        setOf(
            GremiumMitgliedschaftTable,
            SitzungTable,
            TagesordnungspunktTable,
            AnwesenheitTable,
            BeschlussTable,
            AntragTable,
            AbstimmungTable,
            AbstimmungStimmeTable,
        )

    override fun export(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("committeeMemberships") {
                (GremiumMitgliedschaftTable innerJoin GremiumTable)
                    .selectAll()
                    .where { GremiumMitgliedschaftTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[GremiumMitgliedschaftTable.id].toString())
                                put("gremiumName", row[GremiumTable.name])
                                put("rolle", row[GremiumMitgliedschaftTable.rolle].name)
                                put("since", row[GremiumMitgliedschaftTable.since].toString())
                                put("until", row[GremiumMitgliedschaftTable.until]?.toString())
                            },
                        )
                    }
            }
            putJsonArray("meetingsCalled") {
                SitzungTable
                    .selectAll()
                    .where { SitzungTable.calledBy eq memberId }
                    .forEach { row -> add(sitzungSummaryJson(row)) }
            }
            putJsonArray("meetingsChaired") {
                SitzungTable
                    .selectAll()
                    .where { SitzungTable.chairMemberId eq memberId }
                    .forEach { row -> add(sitzungSummaryJson(row)) }
            }
            putJsonArray("meetingsAsMinuteTaker") {
                SitzungTable
                    .selectAll()
                    .where { SitzungTable.minuteTakerMemberId eq memberId }
                    .forEach { row -> add(sitzungSummaryJson(row)) }
            }
            putJsonArray("agendaItemsPresented") {
                TagesordnungspunktTable
                    .selectAll()
                    .where { TagesordnungspunktTable.presenterMemberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[TagesordnungspunktTable.id].toString())
                                put("sitzungId", row[TagesordnungspunktTable.sitzungId].toString())
                                put("title", row[TagesordnungspunktTable.title])
                            },
                        )
                    }
            }
            putJsonArray("attendances") {
                AnwesenheitTable
                    .selectAll()
                    .where { AnwesenheitTable.memberId eq memberId }
                    .forEach { row -> add(anwesenheitJson(row)) }
            }
            putJsonArray("attendedAsProxyFor") {
                AnwesenheitTable
                    .selectAll()
                    .where { AnwesenheitTable.representedByMemberId eq memberId }
                    .forEach { row -> add(anwesenheitJson(row)) }
            }
            putJsonArray("resolutionsRecorded") {
                BeschlussTable
                    .selectAll()
                    .where { BeschlussTable.recordedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[BeschlussTable.id].toString())
                                put("sitzungId", row[BeschlussTable.sitzungId].toString())
                                put("number", row[BeschlussTable.number])
                                put("title", row[BeschlussTable.title])
                                put("status", row[BeschlussTable.status].name)
                            },
                        )
                    }
            }
            putJsonArray("antraegeSubmitted") {
                AntragTable
                    .selectAll()
                    .where { AntragTable.submitterMemberId eq memberId }
                    .forEach { row -> add(antragSummaryJson(row)) }
            }
            putJsonArray("antraegeReviewed") {
                AntragTable
                    .selectAll()
                    .where { AntragTable.reviewedBy eq memberId }
                    .forEach { row -> add(antragSummaryJson(row)) }
            }
            putJsonArray("abstimmungenOpened") {
                AbstimmungTable
                    .selectAll()
                    .where { AbstimmungTable.openedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[AbstimmungTable.id].toString())
                                put("antragId", row[AbstimmungTable.antragId].toString())
                                put("title", row[AbstimmungTable.title])
                                put("status", row[AbstimmungTable.status].name)
                            },
                        )
                    }
            }
            putJsonArray("stimmenCast") {
                AbstimmungStimmeTable
                    .selectAll()
                    .where { AbstimmungStimmeTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[AbstimmungStimmeTable.id].toString())
                                put("abstimmungId", row[AbstimmungStimmeTable.abstimmungId].toString())
                                put("optionId", row[AbstimmungStimmeTable.optionId].toString())
                                put("stakeLtr", row[AbstimmungStimmeTable.stakeLtr].toPlainString())
                                put("settledLtr", row[AbstimmungStimmeTable.settledLtr]?.toPlainString())
                                put("castAt", row[AbstimmungStimmeTable.castAt].toString())
                            },
                        )
                    }
            }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val mitgliedschaftCount =
            GremiumMitgliedschaftTable.selectAll().where { GremiumMitgliedschaftTable.memberId eq memberId }.count()

        val sitzungCount =
            SitzungTable
                .selectAll()
                .where {
                    (SitzungTable.calledBy eq memberId) or
                        (SitzungTable.chairMemberId eq memberId) or
                        (SitzungTable.minuteTakerMemberId eq memberId)
                }.count()

        val tagesordnungspunktCount =
            TagesordnungspunktTable.selectAll().where { TagesordnungspunktTable.presenterMemberId eq memberId }.count()

        val anwesenheitCondition =
            (AnwesenheitTable.memberId eq memberId) or (AnwesenheitTable.representedByMemberId eq memberId)
        val anwesenheitCount = AnwesenheitTable.selectAll().where { anwesenheitCondition }.count()
        AnwesenheitTable.update({ anwesenheitCondition }) {
            it[note] = null
        }

        val beschlussCount = BeschlussTable.selectAll().where { BeschlussTable.recordedBy eq memberId }.count()

        val antragCondition = (AntragTable.submitterMemberId eq memberId) or (AntragTable.reviewedBy eq memberId)
        val antragCount = AntragTable.selectAll().where { antragCondition }.count()

        val abstimmungCount = AbstimmungTable.selectAll().where { AbstimmungTable.openedBy eq memberId }.count()

        val stimmeCount = AbstimmungStimmeTable.selectAll().where { AbstimmungStimmeTable.memberId eq memberId }.count()

        return listOf(
            TableErasureOutcome(
                table = "gremium_mitgliedschaft",
                rowsRetained = mitgliedschaftCount.toInt(),
                retentionReason = "Rechenschaftspflichtiger Nachweis, wer wann welches Amt in welchem Gremium innehatte.",
            ),
            TableErasureOutcome(
                table = "sitzung",
                rowsRetained = sitzungCount.toInt(),
                retentionReason = "Sitzungs-Metadaten sind ein Organisationsdatensatz, kein reines Personendatum.",
            ),
            TableErasureOutcome(
                table = "tagesordnungspunkt",
                rowsRetained = tagesordnungspunktCount.toInt(),
                retentionReason = "Tagesordnungs-Inhalt ist ein Organisationsdatensatz, kein reines Personendatum.",
            ),
            TableErasureOutcome(
                table = "anwesenheit",
                rowsRetained = anwesenheitCount.toInt(),
                retentionReason =
                    "Wird als Nachweis der historischen Beschlussfaehigkeit benoetigt; nur die " +
                        "Freitext-Notiz wurde geloescht.",
            ),
            TableErasureOutcome(
                table = "beschluss",
                rowsRetained = beschlussCount.toInt(),
                retentionReason =
                    "Der Beschlusstext selbst ist der materielle Rechtsnachweis (ein Verein muss " +
                        "beweisen koennen, was beschlossen wurde) -- anders als ContributionTable's " +
                        "beilaeufige Notiz waere ein Loeschen hier selbst ein Compliance-Problem.",
            ),
            TableErasureOutcome(
                table = "antrag",
                rowsRetained = antragCount.toInt(),
                retentionReason =
                    "Der Antragstext und die Pruefungsbegruendung sind rechenschaftspflichtige " +
                        "Verwaltungsvorgaenge (wer hat was beantragt, wer hat es wie geprueft) -- " +
                        "analog zum beschluss-Praezedenzfall bleibt auch review_note vollstaendig " +
                        "erhalten, kein Feld wird geloescht.",
            ),
            TableErasureOutcome(
                table = "abstimmung",
                rowsRetained = abstimmungCount.toInt(),
                retentionReason =
                    "Wer eine Abstimmung eroeffnet hat, ist Teil des rechenschaftspflichtigen " +
                        "Beschlussvorgangs -- analog zum beschluss-Praezedenzfall.",
            ),
            TableErasureOutcome(
                table = "abstimmung_stimme",
                rowsRetained = stimmeCount.toInt(),
                retentionReason =
                    "Die gestakte und abgerechnete LTR-Summe ist zugleich Eigentumsnachweis des " +
                        "Mitglieds und Bestandteil der Vickrey-Abrechnung anderer Mitglieder -- " +
                        "Loeschen wuerde beides beschaedigen. Kein Feld wird geloescht.",
            ),
        )
    }
}

private fun sitzungSummaryJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[SitzungTable.id].toString())
        put("title", row[SitzungTable.title])
        put("scheduledAt", row[SitzungTable.scheduledAt].toString())
        put("status", row[SitzungTable.status].name)
    }

private fun anwesenheitJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[AnwesenheitTable.id].toString())
        put("sitzungId", row[AnwesenheitTable.sitzungId].toString())
        put("status", row[AnwesenheitTable.status].name)
        put("recordedAt", row[AnwesenheitTable.recordedAt].toString())
    }

private fun antragSummaryJson(row: ResultRow) =
    buildJsonObject {
        put("id", row[AntragTable.id].toString())
        put("targetGremiumId", row[AntragTable.targetGremiumId].toString())
        put("title", row[AntragTable.title])
        put("status", row[AntragTable.status].name)
        put("submittedAt", row[AntragTable.submittedAt].toString())
    }
