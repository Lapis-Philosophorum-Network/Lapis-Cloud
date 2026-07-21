package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * DSGVO-Vollausbau (V0.5.5) -- AVV-Register/TOMs/DSFA-Vorlage/Datenpannenmeldung, building on the
 * DSGVO basis from V0.1.6 ([ErasureMode]/[ErasureStatus] etc.). See
 * `network.lapis.cloud.server.rpc.DsgvoComplianceService` KDoc for the full write-path design and
 * `lapis-server/src/main/kuml/16-dsgvo-compliance.kuml.kts`'s file header for the bounded-scope
 * rationale and legal-verification disclaimer.
 *
 * **Documentation-/workflow-tool support for a human-made legal decision, never automated legal
 * advice** -- [AvvStatus], [DpiaAssessmentDto.dpiaRequired], and
 * [DataBreachIncidentDto.authorityNotificationRequired] are always stored human input, never
 * computed. [DpiaAssessmentDto.riskBand] and [DataBreachIncidentDto.authorityNotificationDeadline]/
 * [DataBreachIncidentDto.deadlineStatus] are the only computed fields anywhere in this file --
 * read-time DISPLAY helpers only (see `network.lapis.cloud.server.rpc.DpiaRiskMatrix`/
 * `BreachDeadlineCalculator` KDoc), never fed back into a persisted human-input column.
 */
@Serializable
enum class AvvStatus { NONE, DRAFT, SIGNED }

/**
 * The eight standard Technische-und-organisatorische-Massnahmen categories per Anlage zu §64 BDSG /
 * Orientierungshilfe der Aufsichtsbehoerden -- current understanding, verify against the current
 * text (see `16-dsgvo-compliance.kuml.kts` file header). Literal order is load-bearing
 * (`DsgvoComplianceSchemaDriftTest` pins it against the `.kuml.kts` model's own `tomCategory` enum).
 */
@Serializable
enum class TomCategory {
    PHYSICAL_ACCESS_CONTROL,
    SYSTEM_ACCESS_CONTROL,
    DATA_ACCESS_CONTROL,
    TRANSFER_CONTROL,
    INPUT_CONTROL,
    ORDER_CONTROL,
    AVAILABILITY_CONTROL,
    SEPARATION_CONTROL,
}

/**
 * Shared by [DpiaAssessmentDto.riskLikelihood]/[DpiaAssessmentDto.riskSeverity] and
 * [DataBreachIncidentDto.riskLevel] -- always human input, only ever fed into the read-time
 * `DpiaRiskMatrix`/display helpers, never computed itself.
 */
@Serializable
enum class RiskLevel { LOW, MEDIUM, HIGH }

@Serializable
enum class DsfaStatus { DRAFT, COMPLETED, OUTDATED_REVIEW_DUE }

@Serializable
enum class BreachStatus { REPORTED, UNDER_ASSESSMENT, NOTIFIED_AUTHORITY, NO_NOTIFICATION_REQUIRED, CLOSED }

/**
 * Read-time-only classification of how close/overdue a [DataBreachIncidentDto] is to the Art. 33(1)
 * 72h authority-notification window -- see `network.lapis.cloud.server.rpc.BreachDeadlineCalculator`
 * KDoc. Never persisted.
 */
@Serializable
enum class BreachDeadlineStatus { WITHIN_WINDOW, DUE_SOON, OVERDUE, SATISFIED }

/**
 * Read-time-only display band derived from [DpiaAssessmentDto.riskLikelihood] x
 * [DpiaAssessmentDto.riskSeverity] -- see `network.lapis.cloud.server.rpc.DpiaRiskMatrix` KDoc.
 * **Not** an Art. 35 DSGVO necessity determination; never persisted.
 */
@Serializable
enum class DpiaRiskBand { LOW, MEDIUM, HIGH, CRITICAL }

/**
 * One AVV-register row (Baustein 1) -- a Drittdienst-Verarbeiter (e.g. Letterxpress) and the
 * organization's Auftragsverarbeitungsvertrag status for it. [active] is server-computed at read
 * time ([AvvStatus.SIGNED] AND ([reviewDueDate] is `null` or not yet in the past) -- never stored,
 * always freshly derived so a passed review date is reflected immediately without a background job.
 */
@Serializable
data class ProcessingAgreementDto(
    val id: String,
    val processorName: String,
    val processingPurpose: String,
    val dataCategories: String,
    val avvStatus: AvvStatus,
    val signedDate: LocalDate?,
    val reviewDueDate: LocalDate?,
    val documentId: String?,
    val notes: String?,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val createdByDisplayName: String?,
    val updatedAt: LocalDateTime?,
    val updatedBy: String?,
    val active: Boolean,
)

@Serializable
data class ProcessingAgreementInput(
    val processorName: String,
    val processingPurpose: String,
    val dataCategories: String,
    val avvStatus: AvvStatus,
    val signedDate: LocalDate? = null,
    val reviewDueDate: LocalDate? = null,
    val documentId: String? = null,
    val notes: String? = null,
)

/** One TOM-documentation row (Baustein 2). [version] increments by one on every update. */
@Serializable
data class TechnicalOrganizationalMeasureDto(
    val id: String,
    val category: TomCategory,
    val title: String,
    val description: String,
    val version: Int,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val createdByDisplayName: String?,
    val updatedAt: LocalDateTime?,
    val updatedBy: String?,
)

@Serializable
data class TechnicalOrganizationalMeasureInput(
    val category: TomCategory,
    val title: String,
    val description: String,
)

/**
 * One DSFA row (Baustein 3). [dpiaRequired] is `null` in a draft and, once set, is always the
 * human-entered outcome -- never derived from [riskLikelihood]/[riskSeverity]. [riskBand] is the
 * read-time-only `DpiaRiskMatrix` display helper (`null` whenever either risk input is `null`).
 * [version] increments by one on every update.
 */
@Serializable
data class DpiaAssessmentDto(
    val id: String,
    val title: String,
    val processingDescription: String,
    val necessityProportionality: String?,
    val riskLikelihood: RiskLevel?,
    val riskSeverity: RiskLevel?,
    val riskBand: DpiaRiskBand?,
    val riskAssessment: String?,
    val mitigationMeasures: String?,
    val dpiaRequired: Boolean?,
    val outcomeRationale: String?,
    val status: DsfaStatus,
    val version: Int,
    val createdAt: LocalDateTime,
    val createdBy: String,
    val createdByDisplayName: String?,
    val updatedAt: LocalDateTime?,
    val updatedBy: String?,
)

@Serializable
data class DpiaAssessmentInput(
    val title: String,
    val processingDescription: String,
    val necessityProportionality: String? = null,
    val riskLikelihood: RiskLevel? = null,
    val riskSeverity: RiskLevel? = null,
    val riskAssessment: String? = null,
    val mitigationMeasures: String? = null,
    val dpiaRequired: Boolean? = null,
    val outcomeRationale: String? = null,
    val status: DsfaStatus = DsfaStatus.DRAFT,
)

/**
 * One reported data-breach incident (Baustein 4). [authorityNotificationDeadline]/[deadlineStatus]
 * are the read-time-only `BreachDeadlineCalculator` display helpers (see that object's KDoc) --
 * [authorityNotificationRequired] (the human legal-necessity call) and [authorityNotifiedAt] (the
 * human action record) remain independent, always-human-set fields.
 */
@Serializable
data class DataBreachIncidentDto(
    val id: String,
    val discoveredAt: LocalDateTime,
    val description: String,
    val affectedDataCategories: String,
    val estimatedAffectedPersons: Int?,
    val riskAssessment: String?,
    val riskLevel: RiskLevel?,
    val authorityNotificationRequired: Boolean?,
    val authorityNotifiedAt: LocalDateTime?,
    val dataSubjectsNotifiedAt: LocalDateTime?,
    val status: BreachStatus,
    val reportedAt: LocalDateTime,
    val reportedBy: String,
    val reportedByDisplayName: String?,
    val updatedAt: LocalDateTime?,
    val updatedBy: String?,
    val authorityNotificationDeadline: LocalDateTime,
    val deadlineStatus: BreachDeadlineStatus,
)

@Serializable
data class DataBreachIncidentInput(
    val discoveredAt: LocalDateTime,
    val description: String,
    val affectedDataCategories: String,
    val estimatedAffectedPersons: Int? = null,
    val riskAssessment: String? = null,
    val riskLevel: RiskLevel? = null,
    val authorityNotificationRequired: Boolean? = null,
    val authorityNotifiedAt: LocalDateTime? = null,
    val dataSubjectsNotifiedAt: LocalDateTime? = null,
    val status: BreachStatus = BreachStatus.REPORTED,
)
