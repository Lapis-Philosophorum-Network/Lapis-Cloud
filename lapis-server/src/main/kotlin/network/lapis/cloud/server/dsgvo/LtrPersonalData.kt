package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.tables.LtrBalanceTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [LtrBalanceTable] — the V0.2.3 LTR-balance placeholder (see that table's KDoc; V0.6
 * replaces it with the full ledger, at which point this contributor's `coveredTables` moves to
 * the new ledger table(s)). Retain-with-reason, mirroring [ContributionPersonalData]'s
 * precedent: an LTR balance is the member's property record, not incidental notes — anonymizing
 * or deleting it on erasure would either destroy a member's earned property or corrupt the
 * economic bookkeeping other members' Vickrey settlements may reference. `member_id` stays intact
 * and now resolves to the anonymized [network.lapis.cloud.server.db.tables.MemberTable] row post-
 * erasure, same as every other retain-with-reason contributor.
 */
object LtrPersonalData : PersonalDataContributor {
    override val sectionKey = "ltrBalance"
    override val displayName = "LTR-Kontostand"
    override val coveredTables = setOf(LtrBalanceTable)

    override fun export(memberId: Uuid) =
        buildJsonObject {
            val row = LtrBalanceTable.selectAll().where { LtrBalanceTable.memberId eq memberId }.singleOrNull()
            put("balanceLtr", row?.get(LtrBalanceTable.balanceLtr)?.toPlainString())
            put("updatedAt", row?.get(LtrBalanceTable.updatedAt)?.toString())
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val total = LtrBalanceTable.selectAll().where { LtrBalanceTable.memberId eq memberId }.count()
        return listOf(
            TableErasureOutcome(
                table = "ltr_balance",
                rowsRetained = total.toInt(),
                retentionReason =
                    "Der LTR-Kontostand ist ein Eigentumsnachweis des Mitglieds und Grundlage " +
                        "vergangener Vickrey-Abrechnungen -- Loeschen wuerde Eigentum vernichten " +
                        "bzw. die wirtschaftliche Nachvollziehbarkeit anderer Mitglieder beeintraechtigen.",
            ),
        )
    }
}
