package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.KonsensierungOptionTable
import network.lapis.cloud.server.db.generated.KonsensierungStimmberechtigtTable
import network.lapis.cloud.server.db.generated.KonsensierungStimmzettelTable
import network.lapis.cloud.server.db.generated.KonsensierungTable
import network.lapis.cloud.server.db.generated.KonsensierungTeilnahmeTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns every member-FK-bearing table of Systemisches Konsensieren (V0.2.5): [KonsensierungTable]
 * (`opened_by`), [KonsensierungOptionTable] (`created_by`), [KonsensierungStimmberechtigtTable]
 * (`member_id`), [KonsensierungTeilnahmeTable] (`member_id`) and [KonsensierungStimmzettelTable]
 * (`member_id`, nullable -- always `NULL` on the `geheim` path, populated only on the non-secret
 * path, same shape as [WahlPersonalData]'s `WahlStimmzettelTable`).
 * [network.lapis.cloud.server.db.generated.KonsensierungWiderstandTable] deliberately has **no**
 * contributor entry -- see [PersonalDataRegistry.noPersonalDataAllowlist] for the written reason:
 * it carries no `member` FK of its own, resolving to a member only two hops away via
 * `konsensierung_stimmzettel`, and only on the non-secret path.
 *
 * Retain-with-reason across the board, same precedent as [WahlPersonalData]: who opened a
 * Konsensierung, who proposed which option, who was eligible to rate, and who participated
 * (without revealing *what* resistance they cast, on the secret path) are all
 * rechenschaftspflichtige electoral records, not purely personal data.
 */
object KonsensierungPersonalData : PersonalDataContributor {
    override val sectionKey = "konsensierung"
    override val displayName = "Systemisches Konsensieren"
    override val coveredTables =
        setOf(
            KonsensierungTable,
            KonsensierungOptionTable,
            KonsensierungStimmberechtigtTable,
            KonsensierungTeilnahmeTable,
            KonsensierungStimmzettelTable,
        )

    override fun export(memberId: Uuid) =
        buildJsonObject {
            putJsonArray("konsensierungenOpened") {
                KonsensierungTable
                    .selectAll()
                    .where { KonsensierungTable.openedBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[KonsensierungTable.id].toString())
                                put("antragId", row[KonsensierungTable.antragId].toString())
                                put("title", row[KonsensierungTable.title])
                                put("status", row[KonsensierungTable.status].name)
                            },
                        )
                    }
            }
            putJsonArray("optionenProposed") {
                KonsensierungOptionTable
                    .selectAll()
                    .where { KonsensierungOptionTable.createdBy eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[KonsensierungOptionTable.id].toString())
                                put("konsensierungId", row[KonsensierungOptionTable.konsensierungId].toString())
                                put("label", row[KonsensierungOptionTable.label])
                            },
                        )
                    }
            }
            putJsonArray("stimmberechtigungen") {
                KonsensierungStimmberechtigtTable
                    .selectAll()
                    .where { KonsensierungStimmberechtigtTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[KonsensierungStimmberechtigtTable.id].toString())
                                put("konsensierungId", row[KonsensierungStimmberechtigtTable.konsensierungId].toString())
                                put("runde", row[KonsensierungStimmberechtigtTable.runde])
                            },
                        )
                    }
            }
            putJsonArray("teilnahmen") {
                KonsensierungTeilnahmeTable
                    .selectAll()
                    .where { KonsensierungTeilnahmeTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[KonsensierungTeilnahmeTable.id].toString())
                                put("konsensierungId", row[KonsensierungTeilnahmeTable.konsensierungId].toString())
                                put("votedAt", row[KonsensierungTeilnahmeTable.votedAt].toString())
                                put("runde", row[KonsensierungTeilnahmeTable.runde])
                            },
                        )
                    }
            }
            putJsonArray("stimmzettelNonSecret") {
                // Only the non-secret path ever has a non-null member_id here -- a geheim
                // Konsensierung's ballots are unreachable from this export by design.
                KonsensierungStimmzettelTable
                    .selectAll()
                    .where { KonsensierungStimmzettelTable.memberId eq memberId }
                    .forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row[KonsensierungStimmzettelTable.id].toString())
                                put("konsensierungId", row[KonsensierungStimmzettelTable.konsensierungId].toString())
                                put("castAt", row[KonsensierungStimmzettelTable.castAt].toString())
                                put("runde", row[KonsensierungStimmzettelTable.runde])
                            },
                        )
                    }
            }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val konsensierungCount = KonsensierungTable.selectAll().where { KonsensierungTable.openedBy eq memberId }.count()
        val optionCount = KonsensierungOptionTable.selectAll().where { KonsensierungOptionTable.createdBy eq memberId }.count()
        val stimmberechtigtCount =
            KonsensierungStimmberechtigtTable.selectAll().where { KonsensierungStimmberechtigtTable.memberId eq memberId }.count()
        val teilnahmeCount = KonsensierungTeilnahmeTable.selectAll().where { KonsensierungTeilnahmeTable.memberId eq memberId }.count()
        val stimmzettelCount =
            KonsensierungStimmzettelTable
                .selectAll()
                .where { KonsensierungStimmzettelTable.memberId eq memberId }
                .count()

        // Kept extremely terse (more so than WahlPersonalData.erase) -- outcomeSummary is a
        // JSON-encoded VARCHAR(4000) column shared by EVERY contributor's outcomes combined
        // (see WahlPersonalData KDoc), and this is already the 8th contributor registered --
        // every extra character here shrinks the budget for every future domain wave too.
        return listOf(
            TableErasureOutcome(
                table = "konsensierung",
                rowsRetained = konsensierungCount.toInt(),
                retentionReason = "Teil des Beschlussvorgangs.",
            ),
            TableErasureOutcome(
                table = "konsensierung_option",
                rowsRetained = optionCount.toInt(),
                retentionReason = "Teil des Verfahrens.",
            ),
            TableErasureOutcome(
                table = "konsensierung_stimmberechtigt",
                rowsRetained = stimmberechtigtCount.toInt(),
                retentionReason = "Stimmberechtigungs-Snapshot.",
            ),
            TableErasureOutcome(
                table = "konsensierung_teilnahme",
                rowsRetained = teilnahmeCount.toInt(),
                retentionReason = "One-Vote-Nachweis, geheim.",
            ),
            TableErasureOutcome(
                table = "konsensierung_stimmzettel",
                rowsRetained = stimmzettelCount.toInt(),
                retentionReason = "Elektorales Kernartefakt.",
            ),
        )
    }
}
