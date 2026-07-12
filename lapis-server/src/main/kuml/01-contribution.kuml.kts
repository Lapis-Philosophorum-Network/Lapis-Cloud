// Contribution domain — membership_tier/contribution (V2__contributions.sql).
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified
// against both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.tables.ContributionTables.kt) by SchemaDriftTest. Per
// ADR-0016's designModelStrategy option B, this is a verification-only artifact for now: the
// hand-written Table objects remain the actually-compiled/actually-imported-by-N-files source.
// See docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the
// full rationale (enum-to-VARCHAR type-fidelity gap, Kotlin-object-naming-override gap).
//
// MembershipTier is fully defined *here* (this is its owning domain, first introduced by
// V2__contributions.sql) — Foundation's 00-foundation.kuml.kts separately carries only a
// minimal id-only stub of it (forward reference for member.membership_tier_id). This is the
// first real instance of the cross-domain-stub pattern described in the retrofit plan: this
// file, symmetrically, carries a minimal id-only Member stub (owned by Foundation) purely so
// UmlToErmTransformer can resolve contribution.member_id's association target.
//
// Known, accepted gaps (see SchemaDriftTest for the pinned assertions):
//  - contribution's composite UNIQUE (member_id, membership_tier_id, period_start, period_end)
//    (uq_contribution_member_tier_period in V2__contributions.sql) has no kUML ERM-profile
//    equivalent — «Column».unique only supports single-column uniqueness (TAG_UNIQUE is a
//    boolTag on one attribute's «Column» stereotype; ErmProfileNames has no composite-unique-
//    constraint tag at all). Documented and pinned as a gap rather than silently dropped.
//  - membership_tier.active's `DEFAULT TRUE` and contribution.created_at's implicit
//    application-supplied default are not modelled via defaultValue here (SchemaDriftTest,
//    like foundation's, does not introspect column defaults — only name/nullable/FK shape) —
//    consistent with the established, minimal-scope drift-check pattern from foundation.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Contribution") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by
    // Foundation's own MembershipTier stub. Only exists here so UmlToErmTransformer can resolve
    // contribution.member_id's association target within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val billingInterval = enumOf(name = "BillingInterval") {
        literal(name = "MONTHLY")
        literal(name = "QUARTERLY")
        literal(name = "YEARLY")
    }

    val contributionStatus = enumOf(name = "ContributionStatus") {
        literal(name = "OPEN")
        literal(name = "PAID")
        literal(name = "WAIVED")
        literal(name = "OVERDUE")
    }

    val membershipTier = classOf(name = "MembershipTier") {
        stereotype("Entity") { "tableName" to "membership_tier" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(100)" }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "contributionAmount", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "contribution_amount"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "billingInterval", type = billingInterval) {
            stereotype("Column") { "columnName" to "billing_interval"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "active", type = "Boolean") {
            defaultValue = "TRUE"
            stereotype("Column") { "columnName" to "active" }
        }
    }

    val contribution = classOf(name = "Contribution") {
        stereotype("Entity") { "tableName" to "contribution" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "periodStart", type = "LocalDate") {
            stereotype("Column") { "columnName" to "period_start" }
        }
        attribute(name = "periodEnd", type = "LocalDate") {
            stereotype("Column") { "columnName" to "period_end" }
        }
        attribute(name = "amountDue", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount_due"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "status", type = contributionStatus) {
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "paidAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "paid_at" }
        }
        attribute(name = "paidAmount", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "paid_amount"; "sqlType" to "DECIMAL(12,2)" }
        }
        attribute(name = "note", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "note"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    association(source = member, target = contribution, id = "assoc-member-contribution") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    association(source = membershipTier, target = contribution, id = "assoc-membership-tier-contribution") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "membershipTierId" }
    }
}
