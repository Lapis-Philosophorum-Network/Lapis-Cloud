package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.WahlFreigabeTable
import network.lapis.cloud.server.db.generated.WahlKandidaturTable
import network.lapis.cloud.server.db.generated.WahlStimmzettelTable
import network.lapis.cloud.server.db.generated.WahlTable
import network.lapis.cloud.server.db.generated.WahlTeilnahmeTable
import network.lapis.cloud.server.db.generated.WahlWahlberechtigtTable
import network.lapis.cloud.server.db.generated.WahlWahlvorstandTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns every member-FK-bearing table of Demokratische Wahlen (V0.2.4): [WahlTable] (`opened_by`),
 * [WahlKandidaturTable] (`member_id` + free-text `motivation_text`), [WahlWahlvorstandTable]
 * (`member_id`), [WahlWahlberechtigtTable] (`member_id`), [WahlTeilnahmeTable] (`member_id`),
 * [WahlFreigabeTable] (`member_id`) and [WahlStimmzettelTable] (`member_id`, nullable -- always
 * `NULL` on the `geheim` path, populated only on the non-secret path).
 * [network.lapis.cloud.server.db.generated.WahlOptionTable] and
 * [network.lapis.cloud.server.db.generated.WahlStimmzettelAuswahlTable] deliberately have **no**
 * contributor entry -- see [PersonalDataRegistry.noPersonalDataAllowlist] for the written reason
 * each is allowlisted instead: neither carries a `member` FK of its own (an option resolves to a
 * member only one hop away via `wahl_kandidatur`; a selection resolves to one only two hops away
 * via `wahl_stimmzettel`).
 *
 * Retain-with-reason across the board, same precedent as [GovernancePersonalData]: who opened a
 * Wahl, who stood as a candidate (and why), who served on a Wahlvorstand, who was eligible to
 * vote, who approved counting, and who participated (without revealing *what* they voted, on the
 * secret path -- and even on the non-secret path, the ballot itself is an electoral record kept
 * for the same reason [GovernancePersonalData] retains `abstimmung_stimme`) are all
 * rechenschaftspflichtige electoral records, not purely personal data.
 */
object WahlPersonalData : PersonalDataContributor {
    override val sectionKey = "wahlen"
    override val displayName = "Demokratische Wahlen"
    override val coveredTables =
        setOf(
            WahlTable,
            WahlKandidaturTable,
            WahlWahlvorstandTable,
            WahlWahlberechtigtTable,
            WahlTeilnahmeTable,
            WahlFreigabeTable,
            WahlStimmzettelTable,
        )

    override fun export(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("wahlenOpened") {
                WahlTable
                    .selectAll()
                    .where { WahlTable.openedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[WahlTable.id].toString())
                                put("antragId", row[WahlTable.antragId].toString())
                                put("title", row[WahlTable.title])
                                put("status", row[WahlTable.status].name)
                            },
                        )
                    }
            }
            putJsonArray("kandidaturen") {
                WahlKandidaturTable
                    .selectAll()
                    .where { WahlKandidaturTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[WahlKandidaturTable.id].toString())
                                put("wahlId", row[WahlKandidaturTable.wahlId].toString())
                                put("motivationText", row[WahlKandidaturTable.motivationText])
                                put("submittedAt", row[WahlKandidaturTable.submittedAt].toString())
                                put("withdrawnAt", row[WahlKandidaturTable.withdrawnAt]?.toString())
                            },
                        )
                    }
            }
            putJsonArray("wahlvorstandAppointments") {
                WahlWahlvorstandTable
                    .selectAll()
                    .where { WahlWahlvorstandTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[WahlWahlvorstandTable.id].toString())
                                put("wahlId", row[WahlWahlvorstandTable.wahlId].toString())
                                put("appointedAt", row[WahlWahlvorstandTable.appointedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("wahlberechtigungen") {
                WahlWahlberechtigtTable
                    .selectAll()
                    .where { WahlWahlberechtigtTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[WahlWahlberechtigtTable.id].toString())
                                put("wahlId", row[WahlWahlberechtigtTable.wahlId].toString())
                            },
                        )
                    }
            }
            putJsonArray("teilnahmen") {
                WahlTeilnahmeTable
                    .selectAll()
                    .where { WahlTeilnahmeTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[WahlTeilnahmeTable.id].toString())
                                put("wahlId", row[WahlTeilnahmeTable.wahlId].toString())
                                put("votedAt", row[WahlTeilnahmeTable.votedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("auszaehlungFreigaben") {
                WahlFreigabeTable
                    .selectAll()
                    .where { WahlFreigabeTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[WahlFreigabeTable.id].toString())
                                put("wahlId", row[WahlFreigabeTable.wahlId].toString())
                                put("approvedAt", row[WahlFreigabeTable.approvedAt].toString())
                            },
                        )
                    }
            }
            putJsonArray("stimmzettelNonSecret") {
                // Only the non-secret path ever has a non-null member_id here -- a geheim Wahl's
                // ballots are unreachable from this export by design (see WahlTable KDoc).
                WahlStimmzettelTable
                    .selectAll()
                    .where { WahlStimmzettelTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[WahlStimmzettelTable.id].toString())
                                put("wahlId", row[WahlStimmzettelTable.wahlId].toString())
                                put("castAt", row[WahlStimmzettelTable.castAt].toString())
                            },
                        )
                    }
            }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val wahlCount = WahlTable.selectAll().where { WahlTable.openedBy eq memberId }.count()

        val kandidaturCount = WahlKandidaturTable.selectAll().where { WahlKandidaturTable.memberId eq memberId }.count()

        val wahlvorstandCount = WahlWahlvorstandTable.selectAll().where { WahlWahlvorstandTable.memberId eq memberId }.count()

        val wahlberechtigtCount = WahlWahlberechtigtTable.selectAll().where { WahlWahlberechtigtTable.memberId eq memberId }.count()

        val teilnahmeCount = WahlTeilnahmeTable.selectAll().where { WahlTeilnahmeTable.memberId eq memberId }.count()

        val freigabeCount = WahlFreigabeTable.selectAll().where { WahlFreigabeTable.memberId eq memberId }.count()

        val stimmzettelCount = WahlStimmzettelTable.selectAll().where { WahlStimmzettelTable.memberId eq memberId }.count()

        // Kept deliberately terse (unlike GovernancePersonalData's longer prose): outcomeSummary
        // is JSON-encoded into ErasureRequestTable.outcomeSummary, a VARCHAR(4000) column shared
        // by every contributor's outcomes combined -- verbose per-table reasons here would risk
        // exceeding that shared budget once a 7-table contributor like this one is added in.
        return listOf(
            TableErasureOutcome(
                table = "wahl",
                rowsRetained = wahlCount.toInt(),
                retentionReason = "Eroeffnung ist Teil des rechenschaftspflichtigen Beschlussvorgangs.",
            ),
            TableErasureOutcome(
                table = "wahl_kandidatur",
                rowsRetained = kandidaturCount.toInt(),
                retentionReason = "Kandidatur/Motivationstext sind Teil des oeffentlichen Wahlverfahrens.",
            ),
            TableErasureOutcome(
                table = "wahl_wahlvorstand",
                rowsRetained = wahlvorstandCount.toInt(),
                retentionReason = "Nachweis, wer den Wahlvorstand einer Wahl bildete.",
            ),
            TableErasureOutcome(
                table = "wahl_wahlberechtigt",
                rowsRetained = wahlberechtigtCount.toInt(),
                retentionReason = "Eingefrorene Wahlberechtigungs-Momentaufnahme, Teil des Wahlnachweises.",
            ),
            TableErasureOutcome(
                table = "wahl_teilnahme",
                rowsRetained = teilnahmeCount.toInt(),
                retentionReason = "One-Member-One-Vote-Nachweis fuer den geheimen Pfad, kein Stimminhalt.",
            ),
            TableErasureOutcome(
                table = "wahl_freigabe",
                rowsRetained = freigabeCount.toInt(),
                retentionReason = "Nachweis des Vier-Augen-Prinzips vor jeder Auszaehlung.",
            ),
            TableErasureOutcome(
                table = "wahl_stimmzettel",
                rowsRetained = stimmzettelCount.toInt(),
                retentionReason = "Der Stimmzettel ist das elektorale Kernartefakt, analog abstimmung_stimme.",
            ),
        )
    }
}
