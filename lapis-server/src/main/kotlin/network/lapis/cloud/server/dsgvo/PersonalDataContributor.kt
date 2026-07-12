package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.JsonElement
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.Table
import kotlin.uuid.Uuid

/**
 * Per-table erasure outcome, reported by [PersonalDataContributor.erase] and aggregated into
 * `ErasureRequestTable.outcomeSummary` / `DsgvoAuditLogTable.outcomeSummary` (both in
 * `network.lapis.cloud.server.db.tables.DsgvoTables`) by
 * `network.lapis.cloud.server.rpc.DsgvoService`. Counts only — never payload, see
 * `docs/architecture/dsgvo.adoc` "Audit-Log-Datenschutz".
 */
data class TableErasureOutcome(
    val table: String,
    val rowsAnonymized: Int = 0,
    val rowsDeleted: Int = 0,
    val rowsRetained: Int = 0,
    val retentionReason: String? = null,
)

/**
 * Extension point every domain area implements once, next to the tables it owns, for the tables
 * that carry data about a member. See [PersonalDataRegistry] KDoc for how contributors are wired
 * up — and, more importantly, for why registering one here alone does **not** prevent a future
 * table from going uncovered; `PersonalDataCoverageTest`'s `information_schema` walk is the
 * actual enforcement mechanism.
 *
 * [export] and [erase] both run inside the caller's `transaction {}` (see
 * `network.lapis.cloud.server.rpc.DsgvoService` / `network.lapis.cloud.server.routes.DsgvoRoutes`)
 * — implementations must not open their own `transaction {}`. Both use typed Exposed query
 * builders exclusively, never dynamic SQL string-building over table names (SQL-injection
 * hygiene, house rule).
 */
interface PersonalDataContributor {
    /** Stable machine key — becomes the export JSON section key and the audit-trail label. */
    val sectionKey: String
    val displayName: String

    /**
     * The Exposed [Table] objects this contributor owns and that carry data about a member.
     * Checked for double-registration by [PersonalDataRegistry]'s init block, and cross-checked
     * against `information_schema` by `PersonalDataCoverageTest`.
     */
    val coveredTables: Set<Table>

    /** Reads all of [memberId]'s data this contributor owns. Runs inside the caller's transaction. */
    fun export(memberId: Uuid): JsonElement

    /**
     * De-identifies/deletes this contributor's data for [memberId] per [mode]; returns one
     * [TableErasureOutcome] per table in [coveredTables] the contributor actually touched.
     */
    fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome>
}
