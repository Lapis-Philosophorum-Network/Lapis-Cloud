package network.lapis.cloud.server.db.tables

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DsgvoAuditAction
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ErasureStatus
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * A reviewable erasure workflow (Art. 17 DSGVO), not an instant delete — see
 * `network.lapis.cloud.server.dsgvo.PersonalDataRegistry` and `docs/architecture/dsgvo.adoc` for
 * why (retention duties on e.g. [ContributionTable]). `subject_member_id`/`requested_by`/
 * `decided_by` all reference [MemberTable], which stays a valid FK target forever because
 * erasure anonymizes the member row rather than hard-deleting it.
 */
object ErasureRequestTable : Table("erasure_request") {
    val id = uuid("id")
    val subjectMemberId = uuid("subject_member_id").references(MemberTable.id)
    val requestedAt = datetime("requested_at")
    val requestedBy = uuid("requested_by").references(MemberTable.id)
    val reason = varchar("reason", 1000)
    val mode = enumerationByName<ErasureMode>("mode", 40)
    val status = enumerationByName<ErasureStatus>("status", 20)
    val decidedBy = uuid("decided_by").references(MemberTable.id).nullable()
    val decidedAt = datetime("decided_at").nullable()
    val decisionNote = varchar("decision_note", 1000).nullable()
    val executedAt = datetime("executed_at").nullable()
    val legalHold = bool("legal_hold")

    // JSON array of TableErasureOutcomeDto (counts + retention rationale per table) — never
    // payload. Encoded/decoded in DsgvoService, not here (this file only defines the schema).
    val outcomeSummary = varchar("outcome_summary", 4000).nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Metadata-only, append-only audit trail. Deliberately excluded from the
 * `PersonalDataRegistry` erasure walk — see `PersonalDataRegistry.noPersonalDataAllowlist` and
 * `docs/architecture/dsgvo.adoc` "Audit-Log-Datenschutz" for the accountability-vs-erasure
 * rationale. No `update`/`deleteWhere` call against this table exists anywhere in the codebase —
 * keep it that way; this table is meant to be write-once, read-many.
 */
object DsgvoAuditLogTable : Table("dsgvo_audit_log") {
    val id = uuid("id")
    val occurredAt = datetime("occurred_at")
    val actorMemberId = uuid("actor_member_id").references(MemberTable.id).nullable()
    val actorRole = enumerationByName<AccountRole>("actor_role", 20).nullable()
    val action = enumerationByName<DsgvoAuditAction>("action", 30)
    val subjectMemberId = uuid("subject_member_id").references(MemberTable.id)
    val requestId = uuid("request_id").references(ErasureRequestTable.id).nullable()

    // Counts only, same JSON shape as ErasureRequestTable.outcomeSummary — see that KDoc.
    // Never populated with email/message/file-name payload; PersonalDataCoverageTest's
    // "no payload in the audit log" negative test enforces this.
    val outcomeSummary = varchar("outcome_summary", 4000).nullable()
    val legalBasis = varchar("legal_basis", 500).nullable()

    override val primaryKey = PrimaryKey(id)
}
