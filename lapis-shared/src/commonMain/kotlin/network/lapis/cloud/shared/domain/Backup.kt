package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Backup-/Restore-/Datenexport-Garantie (V0.5.4) -- see
 * `network.lapis.cloud.server.backup.OrganizationExportService`/`OrganizationRestoreService` KDoc
 * for the full design and `lapis-server/src/main/kuml/15-backup-export.kuml.kts`'s file header for
 * why this is a small, separate, non-hash-chained log rather than an extension of
 * `AuditEntityType` (V0.5.3).
 *
 * Additively extensible, same "cheap to extend, expensive to reorder" note every other domain enum
 * in this codebase carries; literal order here is load-bearing (`BackupSchemaDriftTest` pins it
 * against `15-backup-export.kuml.kts`'s `backupOperationType` enum).
 */
@Serializable
enum class BackupOperationType { EXPORT, RESTORE }

/** Literal order load-bearing, see [BackupOperationType] KDoc -- pinned against `backupOperationStatus`. */
@Serializable
enum class BackupOperationStatus { SUCCEEDED, FAILED }

/**
 * Lightweight metadata row for one full-organization export/restore attempt -- counts/timestamps
 * only, never payload (mirrors `DsgvoAuditLogTable`'s own "counts only" convention). The actual
 * bundle bytes travel exclusively over `/api/backup/export` and `/api/backup/restore`
 * (`network.lapis.cloud.server.routes.registerBackupRoutes`), never through this DTO or any other
 * RPC payload.
 */
@Serializable
data class BackupOperationLogDto(
    val id: String,
    val operationType: BackupOperationType,
    val status: BackupOperationStatus,
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime,
    val bundleFormatVersion: Int,
    val tableCount: Int,
    val totalRowCount: Long,
    val blobCount: Int,
    val blobBytesTotal: Long,
    val bundleSizeBytes: Long,
    val errorMessage: String?,
    val actorMemberId: String,
    val actorMemberDisplayName: String?,
)
