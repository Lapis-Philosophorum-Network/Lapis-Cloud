// DSGVO-Compliance domain (V0.5.5 "DSGVO-Vollausbau") -- processing_agreement/
// technical_organizational_measure/data_protection_impact_assessment/data_breach_incident.
//
// **Top-of-file legal-verification disclaimer, same class as 14-audit-log.kuml.kts /
// PartyDonationComplianceCalculator**: every threshold, deadline, and status-literal set below is
// this wave's current *understanding* of Art. 28/32/33/34/35 DSGVO and the Anlage-zu-§64-BDSG TOM
// categories at the time it was written -- **not a reviewed legal conclusion**. Verify against the
// current DSGVO text and, ideally, a Datenschutzbeauftragter/lawyer before relying on this for a
// real Verein/Partei's actual compliance posture. See BreachDeadlineCalculator/
// PartyDonationComplianceCalculator KDoc for the fuller rationale this codebase gives for "current
// best understanding, explicitly flagged" over either silence or false confidence.
//
// **Scope decision (all four aggregates below): documentation-/workflow-tool support for a human-
// made legal decision, NOT automated legal advice.** avv_status, dpia_required,
// authority_notification_required, risk_likelihood/risk_severity are ALWAYS stored human input --
// never computed, never defaulted from other columns. The only "computed" outputs anywhere in this
// domain (authorityNotificationDeadline/deadlineStatus from BreachDeadlineCalculator, the DPIA risk
// band from DpiaRiskMatrix) are read-time DISPLAY helpers, not persisted columns, and not
// persisted anywhere in this model -- see network.lapis.cloud.server.rpc.BreachDeadlineCalculator /
// DpiaRiskMatrix KDoc.
//
// GENUINELY SEPARATE from the other two DSGVO-adjacent logs already in this codebase (never merged
// with either -- see each file's own header for why):
//   - dsgvo_audit_log (04-dsgvo.kuml.kts): Art. 5(2) accountability record of erasure/export
//     ACTIONS taken on a data subject's personal data.
//   - audit_log_entry (14-audit-log.kuml.kts): GoBD Nachvollziehbarkeit/Unveraenderbarkeit record
//     of security-/legally-relevant financial/governance-data MUTATIONS, hash-chained.
//   - This domain (processing_agreement/technical_organizational_measure/
//     data_protection_impact_assessment/data_breach_incident): ORGANISATIONAL DSGVO-compliance
//     DOCUMENTATION -- who is processing data on the organization's behalf, what protective
//     measures are documented, what risk assessments were done, what incidents were reported. Not
//     a per-subject accountability log, not a hash-chained financial/governance mutation log; a
//     small set of human-maintained registers. created_by/updated_by (+ reported_by on the breach
//     table) supply the "who documented/changed this" accountability directly on each row --
//     deliberately no separate audit trail for edits to these registers (see
//     DsgvoCompliancePersonalData KDoc for the erasure-treatment consequence of that choice).
//
// Cross-domain stubs: id-only Member (Foundation-owned, same pattern as every other domain's own
// Member stub) and id-only Document (02-document.kuml.kts-owned) -- the latter purely so
// UmlToErmTransformer can resolve processing_agreement.document_id's «Column».fkEntity target
// within this single-file evaluation.
//
// Multi-actor-FK-per-table naming collision (created_by + updated_by on three tables; reported_by +
// updated_by on the fourth) -- same "plain «Column» UUID + fkEntity override" idiom already
// established by erasure_request's created_by/requested_by/decided_by trio and
// audit_log_entry/backup_operation_log's actor_member_id (see those files' own headers): a real
// UML association pair here would resolve to "member_id" for at most one of the two/three real
// column names, never both/all together, so every actor-facing column is a plain attribute, never
// an association.
//
// AVV-register <-> PostalMailService coupling (Baustein 1): deliberately WEAK, non-blocking.
// PostalMailService.requirePostalMailEnabled()'s existing postalMailEnabled opt-in gate remains the
// only hard precondition for postal dispatch -- this domain adds only a best-effort, defensively
// runCatching-wrapped WARN log line when postalMailEnabled=true but no SIGNED/non-expired
// processing_agreement row exists for "Letterxpress". See that method's own KDoc for the full
// judgement call and why a hard block was rejected.
//
// data_categories/affected_data_categories are free-text VARCHAR, not a normalized child table --
// this is a documentation tool, not a data-flow modelling tool; normalizing would be over-
// engineering for the four record types this wave introduces. All long free-text fields
// (processing_purpose, *_description, *_assessment, mitigation_measures, outcome_rationale) are
// unbounded «Column».sqlType="text", NOT a capped VARCHAR -- same "any fixed VARCHAR length is just
// a bigger deadline" lesson audit_log_entry.beforeSnapshot/afterSnapshot already encode (see
// 14-audit-log.kuml.kts file header).
//
// "Versioned" TOM/DSFA rows (Baustein 2/3): update-in-place with a monotonically increasing `version`
// Int counter (incremented on every update), NOT a full superseded-row point-in-time history --
// keeps this wave's complexity comparable across all four aggregates. A real superseded-row history
// is a plausible future extension, deliberately deferred (see
// network.lapis.cloud.server.rpc.DsgvoComplianceService KDoc).
//
// DSGVO: created_by/updated_by (all four tables) and reported_by (data_breach_incident) are the
// only member-FK-bearing columns this domain adds -- see
// network.lapis.cloud.server.dsgvo.DsgvoCompliancePersonalData for its export/erasure coverage
// (retained unconditionally as an organizational-accountability record, deliberately flagged as a
// SOFTER retention basis than the GoBD-adjacent tables in 14-audit-log.kuml.kts/
// 15-backup-export.kuml.kts -- see that contributor's own KDoc for the explicit review-pflicht
// note this scope decision carries).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "DsgvoCompliance") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors every other domain's own Member stub. Only exists
    // here so UmlToErmTransformer can resolve this domain's member-referencing «Column».fkEntity
    // targets (created_by/updated_by/reported_by) within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Document-owned stub — id-only, mirrors the cross-domain-stub pattern. Only exists here so
    // UmlToErmTransformer can resolve processing_agreement.document_id's «Column».fkEntity target.
    val document = classOf(name = "Document") {
        stereotype("Entity") { "tableName" to "document"; "kotlinObjectName" to "DocumentTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Baustein 1 (Auftragsverarbeitungsvertraege). Human-input status only -- never inferred from
    // document_id being set or unset. Literal order is load-bearing: DsgvoComplianceSchemaDriftTest
    // pins ErmDataType.Enum.values against network.lapis.cloud.shared.domain.AvvStatus.
    val avvStatus = enumOf(name = "AvvStatus") {
        literal(name = "NONE")
        literal(name = "DRAFT")
        literal(name = "SIGNED")
    }

    // Baustein 2 (TOMs), the eight standard categories per Anlage zu §64 BDSG / Orientierungshilfe
    // der Aufsichtsbehoerden (current understanding, verify -- see file header). Literal order is
    // load-bearing, same pin as above.
    val tomCategory = enumOf(name = "TomCategory") {
        literal(name = "PHYSICAL_ACCESS_CONTROL")
        literal(name = "SYSTEM_ACCESS_CONTROL")
        literal(name = "DATA_ACCESS_CONTROL")
        literal(name = "TRANSFER_CONTROL")
        literal(name = "INPUT_CONTROL")
        literal(name = "ORDER_CONTROL")
        literal(name = "AVAILABILITY_CONTROL")
        literal(name = "SEPARATION_CONTROL")
    }

    // Shared by DSFA risk_likelihood/risk_severity and breach risk_level -- always human input, only
    // ever fed into the read-time DpiaRiskMatrix/BreachDeadlineCalculator DISPLAY helpers, never
    // computed itself. Literal order load-bearing.
    val riskLevel = enumOf(name = "RiskLevel") {
        literal(name = "LOW")
        literal(name = "MEDIUM")
        literal(name = "HIGH")
    }

    // Baustein 3 (DSFA). Literal order load-bearing.
    val dsfaStatus = enumOf(name = "DsfaStatus") {
        literal(name = "DRAFT")
        literal(name = "COMPLETED")
        literal(name = "OUTDATED_REVIEW_DUE")
    }

    // Baustein 4 (Datenpannenmeldung). Literal order load-bearing.
    val breachStatus = enumOf(name = "BreachStatus") {
        literal(name = "REPORTED")
        literal(name = "UNDER_ASSESSMENT")
        literal(name = "NOTIFIED_AUTHORITY")
        literal(name = "NO_NOTIFICATION_REQUIRED")
        literal(name = "CLOSED")
    }

    // ── Baustein 1: AVV-Register ─────────────────────────────────────────────────
    val processingAgreement = classOf(name = "ProcessingAgreement") {
        stereotype("Entity") { "tableName" to "processing_agreement"; "kotlinObjectName" to "ProcessingAgreementTable" }
        stereotype("Index") { "columns" to listOf("avv_status"); "name" to "idx_processing_agreement_status" }
        stereotype("Index") { "columns" to listOf("created_by"); "name" to "idx_processing_agreement_created_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "processorName", type = "String") {
            stereotype("Column") { "columnName" to "processor_name"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "processingPurpose", type = "String") {
            stereotype("Column") { "columnName" to "processing_purpose"; "sqlType" to "text" }
        }
        // Free-text category list, deliberately not a normalized child table -- see file header.
        attribute(name = "dataCategories", type = "String") {
            stereotype("Column") { "columnName" to "data_categories"; "sqlType" to "VARCHAR(2000)" }
        }
        attribute(name = "avvStatus", type = avvStatus) {
            stereotype("Column") { "columnName" to "avv_status"; "enumType" to "network.lapis.cloud.shared.domain.AvvStatus" }
        }
        attribute(name = "signedDate", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "signed_date" }
        }
        attribute(name = "reviewDueDate", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "review_due_date" }
        }
        // Real FK -> document (id), nullable. Plain «Column» UUID attribute -- association-derived
        // naming would still yield "document_id" here (no collision), but every fkEntity-bearing
        // column in this domain uses the explicit form for consistency with the actor columns.
        attribute(name = "documentId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "document_id"; "fkEntity" to "Document" }
        }
        attribute(name = "notes", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "notes"; "sqlType" to "VARCHAR(2000)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // (multi-actor-FK-per-table naming collision with updatedBy).
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_at" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute -- see file header.
        attribute(name = "updatedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    // ── Baustein 2: TOMs ──────────────────────────────────────────────────────────
    val technicalOrganizationalMeasure = classOf(name = "TechnicalOrganizationalMeasure") {
        stereotype("Entity") {
            "tableName" to "technical_organizational_measure"
            "kotlinObjectName" to "TechnicalOrganizationalMeasureTable"
        }
        stereotype("Index") { "columns" to listOf("category"); "name" to "idx_tom_category" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "category", type = tomCategory) {
            stereotype("Column") { "columnName" to "category"; "enumType" to "network.lapis.cloud.shared.domain.TomCategory" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "text" }
        }
        // Monotonically increasing on every update -- see file header "versioned TOM/DSFA rows".
        attribute(name = "version", type = "Int") {
            defaultValue = "1"
            stereotype("Column") { "columnName" to "version" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    // ── Baustein 3: DSFA ──────────────────────────────────────────────────────────
    val dataProtectionImpactAssessment = classOf(name = "DataProtectionImpactAssessment") {
        stereotype("Entity") {
            "tableName" to "data_protection_impact_assessment"
            "kotlinObjectName" to "DataProtectionImpactAssessmentTable"
        }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_dpia_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "processingDescription", type = "String") {
            stereotype("Column") { "columnName" to "processing_description"; "sqlType" to "text" }
        }
        attribute(name = "necessityProportionality", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "necessity_proportionality"; "sqlType" to "text" }
        }
        // Human input only -- never computed. See DpiaRiskMatrix KDoc: the read-time risk BAND
        // derived from this pair is a pure display helper, never fed back into this column.
        attribute(name = "riskLikelihood", type = riskLevel) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "risk_likelihood"; "enumType" to "network.lapis.cloud.shared.domain.RiskLevel" }
        }
        attribute(name = "riskSeverity", type = riskLevel) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "risk_severity"; "enumType" to "network.lapis.cloud.shared.domain.RiskLevel" }
        }
        attribute(name = "riskAssessment", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "risk_assessment"; "sqlType" to "text" }
        }
        attribute(name = "mitigationMeasures", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "mitigation_measures"; "sqlType" to "text" }
        }
        // THE hardest guardrail in this domain: always stored human input, NULL in a draft, never
        // computed from riskLikelihood/riskSeverity -- see file header + DsgvoComplianceService
        // KDoc. Whether a DSFA is legally required under Art. 35 DSGVO is a decision this system
        // documents, never makes.
        attribute(name = "dpiaRequired", type = "Boolean") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "dpia_required" }
        }
        attribute(name = "outcomeRationale", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "outcome_rationale"; "sqlType" to "text" }
        }
        attribute(name = "status", type = dsfaStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.DsfaStatus" }
        }
        attribute(name = "version", type = "Int") {
            defaultValue = "1"
            stereotype("Column") { "columnName" to "version" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }

    // ── Baustein 4: Datenpannenmeldung ────────────────────────────────────────────
    val dataBreachIncident = classOf(name = "DataBreachIncident") {
        stereotype("Entity") { "tableName" to "data_breach_incident"; "kotlinObjectName" to "DataBreachIncidentTable" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_breach_status" }
        stereotype("Index") { "columns" to listOf("reported_by"); "name" to "idx_breach_reported_by" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Kenntnis / discovery time -- the start of the Art. 33(1) 72h clock. See
        // BreachDeadlineCalculator KDoc for the "shows the clock, does not decide the law" scope.
        attribute(name = "discoveredAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "discovered_at" }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "text" }
        }
        attribute(name = "affectedDataCategories", type = "String") {
            stereotype("Column") { "columnName" to "affected_data_categories"; "sqlType" to "VARCHAR(2000)" }
        }
        attribute(name = "estimatedAffectedPersons", type = "Int") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "estimated_affected_persons" }
        }
        attribute(name = "riskAssessment", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "risk_assessment"; "sqlType" to "text" }
        }
        attribute(name = "riskLevel", type = riskLevel) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "risk_level"; "enumType" to "network.lapis.cloud.shared.domain.RiskLevel" }
        }
        // Human input only -- never computed, never auto-flipped by BreachDeadlineCalculator. See
        // file header + DsgvoComplianceService KDoc.
        attribute(name = "authorityNotificationRequired", type = "Boolean") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "authority_notification_required" }
        }
        // Whether/when an actual notification to the Aufsichtsbehoerde happened -- set by a human
        // action recorded here, never sent automatically (no authority API exists/is in scope).
        attribute(name = "authorityNotifiedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "authority_notified_at" }
        }
        attribute(name = "dataSubjectsNotifiedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "data_subjects_notified_at" }
        }
        attribute(name = "status", type = breachStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.BreachStatus" }
        }
        attribute(name = "reportedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "reported_at" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // (multi-actor-FK-per-table naming collision with updatedBy).
        attribute(name = "reportedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "reported_by"; "fkEntity" to "Member" }
        }
        attribute(name = "updatedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_at" }
        }
        attribute(name = "updatedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "updated_by"; "fkEntity" to "Member" }
        }
    }
}
