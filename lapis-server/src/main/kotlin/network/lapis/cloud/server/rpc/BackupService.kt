package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.BackupOperationLogTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BackupOperationLogDto
import network.lapis.cloud.shared.rpc.IBackupService
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** Server-side cap on [IBackupService.listOperations]'s `limit` -- DoS guard, same idiom `AuditLogService`'s own `MAX_PAGE_SIZE` establishes. */
private const val MAX_PAGE_SIZE = 200

/**
 * Read-only metadata access for V0.5.4's `backup_operation_log` -- see
 * `network.lapis.cloud.server.backup.OrganizationExportService`/`OrganizationRestoreService` KDoc
 * for the write path (the only place any row is ever inserted). ADMIN only -- a full-organization
 * export/restore history is itself operationally sensitive (who pulled a full data dump, and when).
 */
class BackupService(
    private val call: ApplicationCall,
) : IBackupService {
    override suspend fun listOperations(limit: Int): List<BackupOperationLogDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val cappedLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        return transaction {
            BackupOperationLogTable
                .selectAll()
                .orderBy(BackupOperationLogTable.startedAt to SortOrder.DESC)
                .limit(cappedLimit)
                .map { it.toBackupOperationLogDto() }
        }
    }

    private fun ResultRow.toBackupOperationLogDto(): BackupOperationLogDto {
        val actorId = this[BackupOperationLogTable.actorMemberId]
        return BackupOperationLogDto(
            id = this[BackupOperationLogTable.id].toString(),
            operationType = this[BackupOperationLogTable.operationType],
            status = this[BackupOperationLogTable.status],
            startedAt = this[BackupOperationLogTable.startedAt],
            finishedAt = this[BackupOperationLogTable.finishedAt],
            bundleFormatVersion = this[BackupOperationLogTable.bundleFormatVersion],
            tableCount = this[BackupOperationLogTable.tableCount],
            totalRowCount = this[BackupOperationLogTable.totalRowCount],
            blobCount = this[BackupOperationLogTable.blobCount],
            blobBytesTotal = this[BackupOperationLogTable.blobBytesTotal],
            bundleSizeBytes = this[BackupOperationLogTable.bundleSizeBytes],
            errorMessage = this[BackupOperationLogTable.errorMessage],
            actorMemberId = actorId.toString(),
            actorMemberDisplayName = memberDisplayName(actorId),
        )
    }

    private fun memberDisplayName(memberId: Uuid): String? =
        MemberTable
            .selectAll()
            .where { MemberTable.id eq memberId }
            .singleOrNull()
            ?.get(MemberTable.displayName)
}
