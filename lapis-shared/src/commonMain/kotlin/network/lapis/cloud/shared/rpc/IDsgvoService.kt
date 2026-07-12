package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.DsgvoAuditLogEntryDto
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ErasureRequestDto
import network.lapis.cloud.shared.domain.ErasureStatus
import network.lapis.cloud.shared.domain.ExportManifestDto

/**
 * DSGVO-Basis (Art. 15/17/20 DSGVO): Auskunft, Loeschung als reviewbarer Workflow, Audit-Trail.
 *
 * The actual Auskunftsbuendel (full export payload) travels over the dedicated HTTP route
 * `GET /api/dsgvo/members/{id}/export` (see `network.lapis.cloud.server.routes.registerDsgvoRoutes`
 * KDoc) — same reasoning as [IDocumentService] not carrying file bytes: it can grow large and
 * Kilua RPC is tuned for small typed payloads. This RPC surface only exposes the lightweight
 * [ExportManifestDto] (row counts per section) plus the erasure workflow and audit-log reads.
 *
 * Every entity that carries member-referencing personal data participates in export/erasure
 * automatically, as long as it is registered as a
 * `network.lapis.cloud.server.dsgvo.PersonalDataContributor` in
 * `network.lapis.cloud.server.dsgvo.PersonalDataRegistry` — see that object's KDoc for the
 * `information_schema`-based test that forces every future FK-to-`member` table to either be
 * covered or explicitly allowlisted, so this list cannot silently rot.
 */
@RpcService
interface IDsgvoService {
    /** Self-service for the caller's own data, otherwise ADMIN only. Art. 15/20 DSGVO. */
    suspend fun exportManifest(memberId: String): ExportManifestDto

    /**
     * Self-service to request erasure of the caller's own data, otherwise ADMIN only. Creates a
     * [ErasureStatus.REQUESTED] request — never deletes anything by itself. Art. 17 DSGVO.
     */
    suspend fun requestErasure(
        subjectMemberId: String,
        reason: String,
        mode: ErasureMode = ErasureMode.ANONYMIZE,
    ): ErasureRequestDto

    /** Role: ADMIN. */
    suspend fun listErasureRequests(status: ErasureStatus? = null): List<ErasureRequestDto>

    /**
     * Role: ADMIN. Moves a [ErasureStatus.REQUESTED] request to [ErasureStatus.APPROVED] (if
     * [approve]) or the terminal [ErasureStatus.REJECTED] (if not). Does not itself touch any
     * personal data — that only happens in [executeErasure].
     */
    suspend fun decideErasure(
        requestId: String,
        approve: Boolean,
        note: String? = null,
    ): ErasureRequestDto

    /**
     * Role: ADMIN. The irreversible step — only callable on a [ErasureStatus.APPROVED] request.
     * Iterates every registered `PersonalDataContributor` for the subject and moves the request
     * to [ErasureStatus.COMPLETED].
     */
    suspend fun executeErasure(requestId: String): ErasureRequestDto

    /** Role: ADMIN. Metadata/counts only — see [DsgvoAuditLogEntryDto] KDoc. */
    suspend fun listAuditLog(subjectMemberId: String? = null): List<DsgvoAuditLogEntryDto>
}
