// Internes Crowdfunding domain (V0.6.1) -- see the concept document
// ("03 Bereiche/Lapis Cloud/Meritokratisches System und Libertaler.md", "Internes Crowdfunding"
// section, vault) for the full fachlich specification this implements.
//
// Two DELIBERATELY SEPARATE mechanisms live in this domain, never conflated:
//  1. **Sichtbarkeits-Gewicht** (visibility weight): LTR-denominated, decays 10%/day, gates new-
//     project submission (a new project's initial weight must be >= the current, decayed weight
//     of the top existing project). Persisted as `crowdfunding_project.initial_weight_ltr`
//     (immutable once submitted) -- the *current*, decayed weight is NEVER persisted, only
//     computed on read (network.lapis.cloud.server.rpc.CrowdfundingWeightDecay.currentWeight),
//     same "derive, don't cache" idiom `ltr_ledger_entry` itself uses for balances. Binding the
//     stake is a real LTR-ledger debit (`ltr_ledger_entry` with entryType=PROJECT_STAKE,
//     referenceType=CROWDFUNDING_PROJECT, referenceId=this project's id) -- see
//     08-ltr-balance.kuml.kts's own header for the ledger-entry shape and the polymorphic-
//     pointer rationale. Only the project's OWN weight (Eigengewicht) is implemented this wave --
//     the concept document's recursive comment-weight extension (a project's total weight also
//     summing every comment's own decaying weight) requires a comment/discussion feature that
//     does not exist anywhere in this codebase yet, and is DELIBERATELY NOT simulated or faked
//     here. See CrowdfundingWeightDecay KDoc for where that extension point is documented.
//  2. **Verteilungs-Korb** (distribution basket): a plain democratic Like/Dislike vote, one per
//     member per project (`crowdfunding_reaction`, unique on (project, member)), completely
//     UNWEIGHTED by LTR. `basketTotal = max(0, likeCount - dislikeCount)` (computed on read, not
//     persisted) drives the monthly EUR pool's proportional split (`crowdfunding_distribution`,
//     see network.lapis.cloud.server.rpc.CrowdfundingService.computeMonthlyDistribution) --
//     structurally unable to interact with the LTR side: no shared table, no shared calculation
//     function.
//
// **Silence-is-Approval**: this codebase has no scheduler/cron-job infrastructure anywhere
// (verified -- no Quartz/cron/scheduled-task dependency exists in lapis-server). The 14-day
// board-review window is therefore NOT enforced by a background job; `status` stays PENDING in
// the database until an explicit `approveProject`/`rejectProject` board action, and
// `CrowdfundingWeightDecay.isAutoApproved(submittedAt, now)` is a pure, side-effect-free function
// computed fresh on every read (`effectiveStatus` in `CrowdfundingProjectDto`) -- exactly the
// same "computed view over a persisted PENDING state" idiom `currentWeight` above already uses.
// A board action attempted after auto-approval already took effect is rejected
// (`ConflictException`) rather than silently accepted, protecting whatever donation activity may
// already be underway.
//
// **`crowdfunding_submission_gate`** is a genesis-singleton lock row -- directly mirrors
// `audit_log_chain_state` (14-audit-log.kuml.kts)/`organization_settings`
// (11-organization-settings.kuml.kts)'s own "exactly one row by convention, seeded once, fixed
// sentinel id" idiom. A `SELECT ... FOR UPDATE` on this one row (never on
// "the current top project", which shifts as projects are submitted and would give every
// concurrent submitAdmin a different lock target) is what serializes concurrent
// `submitProject` calls racing against the same decaying top-weight threshold -- see
// `network.lapis.cloud.server.rpc.CrowdfundingService.submitProject` KDoc for the full mechanism.
// No fachlich data of its own, only the sentinel `id` primary key.
//
// **FK-naming choice for `project_id`** (on both `crowdfunding_reaction` and
// `crowdfunding_distribution`): modelled as a plain «Column» UUID attribute with
// «Column».fkEntity="CrowdfundingProject", NOT a UML association. The class is named
// `CrowdfundingProject` (matching its `crowdfunding_project` table name, this codebase's
// universal class-name-equals-table-name convention -- see e.g. `ExternalDonor`/
// `external_donor`), so an association's class-derived default FK column name would be
// "crowdfunding_project_id", not the shorter "project_id" this domain uses throughout its own
// DTOs/service code. Same "want a different column name than the association default" trigger
// this codebase already uses for `motion.submitter_member_id`/`resolution.recorded_by` (see
// 05-governance.kuml.kts) -- just applied to a non-Member target class here. `member_id` on
// `crowdfunding_reaction`, by contrast, IS the association default and is modelled as a real
// association below.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Crowdfunding") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by every
    // prior domain's own Member stub. Resolves submitter_member_id/reviewed_by/triggered_by's
    // «Column».fkEntity overrides and the member_id association below, all within this
    // single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Literal order is load-bearing: CrowdfundingSchemaDriftTest asserts ErmDataType.Enum.values
    // in exactly this order, matching network.lapis.cloud.shared.domain.CrowdfundingProjectStatus.
    val crowdfundingProjectStatus = enumOf(name = "CrowdfundingProjectStatus") {
        literal(name = "PENDING")
        literal(name = "APPROVED")
        literal(name = "REJECTED")
    }

    // Literal order is load-bearing, same reason as above -- matches
    // network.lapis.cloud.shared.domain.CrowdfundingReactionValue.
    val crowdfundingReactionValue = enumOf(name = "CrowdfundingReactionValue") {
        literal(name = "LIKE")
        literal(name = "DISLIKE")
    }

    val crowdfundingProject = classOf(name = "CrowdfundingProject") {
        stereotype("Entity") { "tableName" to "crowdfunding_project"; "kotlinObjectName" to "CrowdfundingProjectTable" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_crowdfunding_project_status" }
        stereotype("Index") { "columns" to listOf("submitter_member_id"); "name" to "idx_crowdfunding_project_submitter" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(4000)" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "submitter_member_id" -- same
        // idiom as motion.submitter_member_id (05-governance.kuml.kts).
        attribute(name = "submitterMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "submitter_member_id"; "fkEntity" to "Member" }
        }
        // Immutable once submitted -- the *current*, decayed weight is never persisted, only
        // computed on read. See file header.
        attribute(name = "initialWeightLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "initial_weight_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        // Persisted state stays PENDING until an explicit board action -- see file header
        // "Silence-is-Approval". CrowdfundingWeightDecay.isAutoApproved computes the *effective*
        // status on read, never written back here.
        attribute(name = "status", type = crowdfundingProjectStatus) {
            defaultValue = "PENDING"
            stereotype("Column") {
                "columnName" to "status"
                "enumType" to "network.lapis.cloud.shared.domain.CrowdfundingProjectStatus"
            }
        }
        // NOT NULL iff status == REJECTED -- a cross-column invariant enforced only at the
        // service layer (CrowdfundingService.rejectProject), same class of gap as
        // ledger_account.reserve_type/is_cash_register in 10-accounting.kuml.kts (no CHECK
        // constraint expresses it in this repo's style).
        attribute(name = "rejectionReason", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "rejection_reason"; "sqlType" to "VARCHAR(2000)" }
        }
        // Real FK -> member (id), nullable (unreviewed until a board action, or forever null if
        // silence-is-approval takes effect first). Plain «Column» UUID attribute — same idiom as
        // motion.reviewed_by.
        attribute(name = "reviewedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reviewed_by"; "fkEntity" to "Member" }
        }
        attribute(name = "reviewedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reviewed_at" }
        }
        attribute(name = "submittedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "submitted_at" }
        }
    }

    val crowdfundingReaction = classOf(name = "CrowdfundingReaction") {
        stereotype("Entity") { "tableName" to "crowdfunding_reaction"; "kotlinObjectName" to "CrowdfundingReactionTable" }
        stereotype("Index") {
            "columns" to listOf("project_id", "member_id")
            "unique" to true
            "name" to "uq_crowdfunding_reaction_project_member"
        }
        stereotype("Index") { "columns" to listOf("project_id"); "name" to "idx_crowdfunding_reaction_project" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> crowdfunding_project (id), NOT NULL. Plain «Column» UUID attribute — see
        // file header "FK-naming choice for project_id".
        attribute(name = "projectId", type = "UUID") {
            stereotype("Column") { "columnName" to "project_id"; "fkEntity" to "CrowdfundingProject" }
        }
        // Named "reactionValue"/"reaction_value", not "value" -- VALUE is a reserved SQL keyword
        // (H2 rejects an unquoted column literally named "value"; ANSI SQL reserves it too), so
        // the obvious short name could not be used as-is. Default snake_case columnName
        // derivation already yields "reaction_value" -- no explicit override needed.
        attribute(name = "reactionValue", type = crowdfundingReactionValue) {
            stereotype("Column") { "columnName" to "reaction_value"; "enumType" to "network.lapis.cloud.shared.domain.CrowdfundingReactionValue" }
        }
        attribute(name = "castAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "cast_at" }
        }
    }

    // crowdfunding_reaction.member_id -> member (id): association-derived default matches
    // ("member_id"). Safe despite crowdfunding_project ALSO having two member FKs of its own
    // (submitter_member_id/reviewed_by), because those are modelled as plain «Column» attributes
    // on a DIFFERENT entity, never as a competing association here.
    association(source = member, target = crowdfundingReaction, id = "assoc-member-crowdfunding_reaction") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val crowdfundingDistribution = classOf(name = "CrowdfundingDistribution") {
        stereotype("Entity") {
            "tableName" to "crowdfunding_distribution"
            "kotlinObjectName" to "CrowdfundingDistributionTable"
        }
        stereotype("Index") {
            "columns" to listOf("project_id", "period_start", "period_end")
            "unique" to true
            "name" to "uq_crowdfunding_distribution_project_period"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> crowdfunding_project (id), NOT NULL. Plain «Column» UUID attribute — see
        // file header "FK-naming choice for project_id".
        attribute(name = "projectId", type = "UUID") {
            stereotype("Column") { "columnName" to "project_id"; "fkEntity" to "CrowdfundingProject" }
        }
        attribute(name = "periodStart", type = "LocalDate") {
            stereotype("Column") { "columnName" to "period_start" }
        }
        attribute(name = "periodEnd", type = "LocalDate") {
            stereotype("Column") { "columnName" to "period_end" }
        }
        // Snapshot of the basket total at distribution time -- basketTotal itself is otherwise
        // always computed live (see file header point 2); this one column is a deliberate
        // historical snapshot so a later re-computation of live reactions can never silently
        // rewrite what a past distribution was actually based on.
        attribute(name = "basketTotalAtDistribution", type = "Int") {
            stereotype("Column") { "columnName" to "basket_total_at_distribution" }
        }
        attribute(name = "amountEur", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount_eur"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "computedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "computed_at" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "triggered_by" -- same idiom as
        // resolution.recorded_by.
        attribute(name = "triggeredBy", type = "UUID") {
            stereotype("Column") { "columnName" to "triggered_by"; "fkEntity" to "Member" }
        }
    }

    // Genesis-singleton lock row -- see file header. No association/FK of its own.
    val crowdfundingSubmissionGate = classOf(name = "CrowdfundingSubmissionGate") {
        stereotype("Entity") {
            "tableName" to "crowdfunding_submission_gate"
            "kotlinObjectName" to "CrowdfundingSubmissionGateTable"
        }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }
}
