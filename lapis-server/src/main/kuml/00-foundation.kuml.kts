// Foundation domain — member/account (V1__foundation.sql), plus the forward-referenced
// membership_tier_id FK column added by V2__contributions.sql.
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified
// against both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.tables.FoundationTables.kt) by SchemaDriftTest. Per ADR-0016's
// designModelStrategy option B, this is a verification-only artifact for now: the hand-written
// Table objects remain the actually-compiled/imported-by-N-files source. See
// docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the
// full rationale (enum-to-VARCHAR type-fidelity gap, Kotlin-object-naming-override gap).
//
// Every attribute carries an explicit «Column»{columnName} tag (not just the ones that need
// overriding) — establishes the per-file naming-tag convention this retrofit's later domain
// waves reuse, and keeps the generated-vs-hand-written structural diff trivial to reason about.
//
// Known, accepted gap: account.member_id is UNIQUE in the hand-written table
// (.references(MemberTable.id).uniqueIndex()) and in V1__foundation.sql (UNIQUE REFERENCES
// member (id)), but UmlToErmTransformer.addForeignKey always synthesizes association-derived FK
// columns with unique=false (no «Column» stereotype can be applied to a UML-association-derived
// attribute — only to explicitly declared attributes, and «FK» is only applicable to
// associations, not attributes). SchemaDriftTest special-cases this one column rather than
// silently ignoring the mismatch or dropping the FK constraint by modelling memberId as a plain
// column instead of an association.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Foundation") {
    applyProfile(ermMappingProfile)

    val memberStatus = enumOf(name = "MemberStatus") {
        literal(name = "ANTRAG")
        literal(name = "AKTIV")
        literal(name = "GAST")
        literal(name = "AUSGETRETEN")
    }

    val accountRole = enumOf(name = "AccountRole") {
        literal(name = "MEMBER")
        literal(name = "BOARD")
        literal(name = "TREASURER")
        literal(name = "ADMIN")
    }

    val membershipTier = classOf(name = "MembershipTier") {
        stereotype("Entity") { "tableName" to "membership_tier"; "kotlinObjectName" to "MembershipTierTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "displayName", type = "String") {
            stereotype("Column") { "columnName" to "display_name"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "email", type = "String") {
            stereotype("Column") { "columnName" to "email"; "sqlType" to "VARCHAR(320)"; "unique" to true }
        }
        attribute(name = "status", type = memberStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.MemberStatus" }
        }
        attribute(name = "joinedAt", type = "LocalDate") {
            stereotype("Column") { "columnName" to "joined_at" }
        }
        attribute(name = "anonymizedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "anonymized_at" }
        }
    }

    association(source = membershipTier, target = member, id = "assoc-member-membership-tier") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "membershipTierId" }
    }

    val account = classOf(name = "Account") {
        stereotype("Entity") { "tableName" to "account"; "kotlinObjectName" to "AccountTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "passwordHash", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "password_hash"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "oidcSubject", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "oidc_subject"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "role", type = accountRole) {
            stereotype("Column") { "columnName" to "role"; "enumType" to "network.lapis.cloud.shared.domain.AccountRole" }
        }
    }

    association(source = member, target = account, id = "assoc-member-account") {
        source { multiplicity("1") }
        target { multiplicity("0..1"); role = "memberId" }
    }
}
