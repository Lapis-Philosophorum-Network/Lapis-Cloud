package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.BackupOperationLogDto

/**
 * Backup-/Restore-/Datenexport-Garantie (V0.5.4) -- read-only metadata access over Kilua RPC. The
 * actual full-organization export/restore bundle bytes never travel over RPC at all -- see
 * `network.lapis.cloud.server.routes.registerBackupRoutes` (`/api/backup/export` /
 * `/api/backup/restore`), same dedicated-HTTP-route reasoning `IDsgvoService`/`IDocumentService`
 * already establish for large/streamed payloads.
 *
 * Every method requires ADMIN -- a full-organization export/restore is a hochprivilegiert
 * operation covering every member's data, not just the caller's own (unlike the per-member DSGVO
 * export, which is self-or-ADMIN). See `network.lapis.cloud.server.backup
 * .OrganizationExportService`/`OrganizationRestoreService` KDoc for the full authorization/scope
 * rationale.
 */
@RpcService
interface IBackupService {
    /**
     * Role: ADMIN. Newest-first metadata listing of every recorded export/restore attempt (both
     * SUCCEEDED and FAILED). [limit] is capped server-side at a maximum page size regardless of
     * what is requested -- DoS guard against an unbounded scan of a log that only ever grows, same
     * idiom `IAuditLogService.listAuditLog` already establishes.
     */
    suspend fun listOperations(limit: Int = 50): List<BackupOperationLogDto>
}
