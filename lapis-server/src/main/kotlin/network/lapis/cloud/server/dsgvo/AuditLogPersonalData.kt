package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns [AuditLogEntryTable] -- the only member-FK-bearing table the V0.5.3 GoBD audit-log domain
 * adds (`actor_member_id`). See `network.lapis.cloud.server.audit.AuditLogRecorder` KDoc for the
 * write path and `14-audit-log.kuml.kts`'s file header for the domain rationale.
 *
 * **Retained unconditionally, regardless of [ErasureMode] -- no field is ever cleared, EVEN
 * stronger than [AccountingPersonalData]'s own retain-with-reason.** GoBD Nachvollziehbarkeit
 * requires knowing WHO made a change, permanently -- anonymizing or deleting `actor_member_id`
 * would not just fail to protect the actor's privacy (they remain identifiable via every
 * [AuditLogEntryTable.beforeSnapshot]/[AuditLogEntryTable.afterSnapshot] payload they authored
 * anyway, which are themselves retained as part of the accompanying, GoBD-retained
 * `journal_entry`/`resolution`/`board_membership` record) -- it would actively undermine the very
 * accountability trail this table exists to provide (Art. 17(3)(b) DSGVO: erasure does not apply
 * where processing is necessary for compliance with a legal obligation).
 *
 * Current understanding, not a reviewed legal conclusion -- see `14-audit-log.kuml.kts`'s
 * top-of-file disclaimer for the same "verify against the current GoBD text and a lawyer/
 * Steuerberater before relying on this for a real Verein/Partei" caveat, which applies here too.
 *
 * [export] deliberately returns only [AuditLogEntryTable.id]/`sequenceNumber`/`occurredAt`/
 * `entityType`/`entityId`/`action` for the rows where [memberId] was the actor -- NOT
 * `beforeSnapshot`/`afterSnapshot`. A snapshot can legitimately reference a DIFFERENT person's data
 * (e.g. a `JournalEntrySnapshot.donorMemberId` naming some other member as the donor a treasurer
 * booked a donation for) -- exporting the raw snapshot into `memberId`'s own DSGVO export would
 * leak that third party's data into an unrelated data subject's export. A member wanting the full
 * detail of a JournalEntry/Resolution/BoardMembership they were *involved in* (not just the actor
 * who recorded it) already has that via [AccountingPersonalData]/[GovernancePersonalData]/
 * [BoardMembershipPersonalData]'s own exports.
 */
object AuditLogPersonalData : PersonalDataContributor {
    override val sectionKey = "auditLog"
    override val displayName = "Audit-Log (GoBD)"
    override val coveredTables = setOf(AuditLogEntryTable)

    override fun export(memberId: Uuid) =
        buildJsonArray {
            AuditLogEntryTable
                .selectAll()
                .where { AuditLogEntryTable.actorMemberId eq memberId }
                .forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row[AuditLogEntryTable.id].toString())
                            put("sequenceNumber", row[AuditLogEntryTable.sequenceNumber])
                            put("occurredAt", row[AuditLogEntryTable.occurredAt].toString())
                            put("entityType", row[AuditLogEntryTable.entityType].name)
                            put("entityId", row[AuditLogEntryTable.entityId].toString())
                            put("action", row[AuditLogEntryTable.action].name)
                        },
                    )
                }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val total = AuditLogEntryTable.selectAll().where { AuditLogEntryTable.actorMemberId eq memberId }.count()
        return listOf(
            TableErasureOutcome(
                table = "audit_log_entry",
                rowsRetained = total.toInt(),
                retentionReason =
                    "GoBD Nachvollziehbarkeit/Unveraenderbarkeit -- the actor identity is the core " +
                        "of the accountability trail this table exists to provide; it is never " +
                        "cleared or anonymized, and the row itself is append-only/immutable by " +
                        "construction (see AuditLogRecorder).",
            ),
        )
    }
}
