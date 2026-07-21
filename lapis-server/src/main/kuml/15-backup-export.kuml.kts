// BackupExport domain (V0.5.4) -- backup_operation_log.
//
// Records every completed (or failed) full-organization export/restore attempt -- deliberately
// NOT part of the GoBD hash-chain audit log (14-audit-log.kuml.kts / AuditLogRecorder): that
// chain's AuditEntityType is legally bounded to JOURNAL_ENTRY/PARTY_DONATION_VERDICT/RESOLUTION/
// BOARD_MEMBERSHIP (see that file's own header) -- financial/legal revision-safety records with
// their own retention basis. A backup/restore operation is an operational event, not a financial-
// or governance-legal one; it gets its own small, simple, non-chained log table instead, mirroring
// DsgvoAuditLogTable's (04-dsgvo.kuml.kts) simpler one-shot-insert pattern rather than the
// hash-chained one.
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified against
// both the real Flyway-migrated H2 schema and the hand-written BackupOperationLogTable Exposed
// object by BackupSchemaDriftTest. Per ADR-0016's designModelStrategy option B, this is a
// verification-only artifact for now: the hand-written Table object remains the actually-
// compiled/actually-imported-by-N-files source. See docs/architecture/domain-model.adoc and
// CLAUDE.md's kUML-Repo-Konventionen (vault) for the full rationale.
//
// Cross-domain stub: a minimal id-only Member stub (Foundation-owned), same pattern as
// contribution's/document's/communication's/postal-mail's own Member stub, purely so
// UmlToErmTransformer can resolve this domain's actor_member_id-shaped FK association within this
// single-file evaluation.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "BackupExport") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors the cross-domain-stub pattern established by
    // contribution's/document's/communication's/postal-mail's own Member stub. Only exists here so
    // UmlToErmTransformer can resolve this domain's Member-targeting association within this
    // single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val backupOperationType = enumOf(name = "BackupOperationType") {
        literal(name = "EXPORT")
        literal(name = "RESTORE")
    }
    val backupOperationStatus = enumOf(name = "BackupOperationStatus") {
        literal(name = "SUCCEEDED")
        literal(name = "FAILED")
    }

    val backupOperationLog = classOf(name = "BackupOperationLog") {
        stereotype("Entity") { "tableName" to "backup_operation_log"; "kotlinObjectName" to "BackupOperationLogTable" }
        stereotype("Index") { "columns" to listOf("actor_member_id"); "name" to "idx_backup_operation_log_actor" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "operationType", type = backupOperationType) {
            stereotype("Column") { "columnName" to "operation_type"; "enumType" to "network.lapis.cloud.shared.domain.BackupOperationType" }
        }
        attribute(name = "status", type = backupOperationStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.BackupOperationStatus" }
        }
        attribute(name = "startedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "started_at" }
        }
        attribute(name = "finishedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "finished_at" }
        }
        attribute(name = "bundleFormatVersion", type = "Int") {
            stereotype("Column") { "columnName" to "bundle_format_version" }
        }
        attribute(name = "tableCount", type = "Int") {
            stereotype("Column") { "columnName" to "table_count" }
        }
        attribute(name = "totalRowCount", type = "Long") {
            stereotype("Column") { "columnName" to "total_row_count" }
        }
        attribute(name = "blobCount", type = "Int") {
            stereotype("Column") { "columnName" to "blob_count" }
        }
        attribute(name = "blobBytesTotal", type = "Long") {
            stereotype("Column") { "columnName" to "blob_bytes_total" }
        }
        attribute(name = "bundleSizeBytes", type = "Long") {
            stereotype("Column") { "columnName" to "bundle_size_bytes" }
        }
        attribute(name = "errorMessage", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "error_message"; "sqlType" to "VARCHAR(2000)" }
        }
        // Real FK -> member (id), NOT NULL: only an authenticated ADMIN member can trigger either
        // operation type, so an actor always exists (unlike AuditLogEntryTable's nullable
        // actorMemberId reserved for a future SYSTEM actor).
        attribute(name = "actorMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "actor_member_id"; "fkEntity" to "Member" }
        }
    }
}
