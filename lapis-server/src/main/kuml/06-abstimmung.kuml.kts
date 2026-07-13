// Abstimmung domain — abstimmung/abstimmung_option/abstimmung_stimme
// (V8__meritokratische_abstimmungen.sql). Technically still defined in the same hand-written
// GovernanceTables.kt file as governance (AbstimmungTable/AbstimmungOptionTable/
// AbstimmungStimmeTable), but modelled as its own .kuml.kts domain script here — per the
// retrofit plan's per-domain file layout (05-governance vs 06-abstimmung as separate waves) —
// because it FK-depends on Antrag and Sitzung (both governance-owned) and Member
// (foundation-owned), and closes the cycle back into Beschluss (governance-owned) via
// beschluss_id. Treating it as a separate generation unit keeps the FK-dependency graph a clean
// DAG at the tooling level even though the two hand-written Kotlin files don't split this way.
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified against
// both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.tables.GovernanceTables.kt — AbstimmungTable/
// AbstimmungOptionTable/AbstimmungStimmeTable) by AbstimmungSchemaDriftTest. Per ADR-0016's
// designModelStrategy option B, this is a verification-only artifact for now: the hand-written
// Table objects remain the actually-compiled/actually-imported-by-N-files source. See
// docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the full
// rationale (enum-to-VARCHAR type-fidelity gap, Kotlin-object-naming-override gap).
//
// Cross-domain stubs: minimal id-only Antrag, Sitzung, Member (all governance-/foundation-owned)
// stubs, same pattern as prior domains' stubs — purely so UmlToErmTransformer can resolve this
// domain's real FK associations within this single-file evaluation. Beschluss is ALSO stubbed
// (id-only) so abstimmung.beschluss_id -> beschluss can be declared as a real, clean forward «FK»
// association from this side — see 05-governance.kuml.kts's own header comment: the governance
// script could not declare beschluss.abstimmung_id as an association back into this
// not-yet-modelled domain (would have been a genuine cycle), so it left it as a plain column and
// deferred the real association to this file instead, where the forward direction
// (abstimmung -> beschluss) has no such problem since Beschluss already exists here as a stub.
//
// abstimmung.antrag_id -> antrag (id), NOT NULL: association-derived default ("antrag_id")
// matches the real column name exactly — modelled as a real UML association.
// abstimmung.sitzung_id -> sitzung (id), NOT NULL: association-derived default ("sitzung_id")
// matches the real column name exactly — modelled as a real UML association.
// abstimmung.beschluss_id -> beschluss (id), nullable: association-derived default
// ("beschluss_id") matches the real column name exactly — modelled as a real UML association.
// abstimmung.opened_by -> member (id), NOT NULL: same naming-gap class already discovered in
// document/communication/dsgvo/governance — association-to-FK naming would derive "member_id",
// not the real schema's "opened_by". Modelled as a plain «Column» UUID attribute.
// abstimmung.winner_option_id: plain nullable UUID «Column» attribute with NO FK constraint in the
// real schema (hand-written AbstimmungTable.winnerOptionId has no .references() call either) —
// same circular-reference-avoidance workaround already used for document.current_version_id and
// beschluss.abstimmung_id/wahl_id: abstimmung_option itself FK-references abstimmung, so a real FK
// the other way would be circular. Modelled the same way here, as a plain attribute.
// abstimmung_option.abstimmung_id -> abstimmung (id), NOT NULL: association-derived default
// matches — modelled as a real UML association.
// abstimmung_stimme.abstimmung_id -> abstimmung (id), NOT NULL: association-derived default
// matches — modelled as a real UML association.
// abstimmung_stimme.option_id -> abstimmung_option (id), NOT NULL: association-derived default
// would be "abstimmung_option_id", not the real schema's "option_id" — same naming-gap class,
// modelled as a plain «Column» UUID attribute.
// abstimmung_stimme.member_id -> member (id), NOT NULL: association-derived default matches (no
// competing member-FK on this entity, unlike governance's sitzung/anwesenheit multi-FK cases) —
// modelled as a real UML association.
//
// AbstimmungStatus is the sole enum column in this domain (OFFEN/GESCHLOSSEN/ABGEBROCHEN, 11 chars
// max), modelled with an explicit «Column».sqlType="VARCHAR(30)" override — same
// mechanism/rationale as every prior domain's enum columns (real V8 schema has a plain VARCHAR(30)
// column, no CHECK constraint).
//
// abstimmung_stimme's composite UNIQUE constraint (uq_abstimmung_stimme_member, UNIQUE
// (abstimmung_id, member_id)) has no kUML ERM-profile equivalent (only single-column
// «Column».unique exists) — same accepted-gap class as contribution/document/communication/
// governance's own composite UNIQUE constraints, pinned explicitly in AbstimmungSchemaDriftTest
// rather than silently ignored.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Abstimmung") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by
    // contribution's/document's/communication's/dsgvo's/governance's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the abstimmung.antrag_id association can resolve.
    val antrag = classOf(name = "Antrag") {
        stereotype("Entity") { "tableName" to "antrag"; "kotlinObjectName" to "AntragTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the abstimmung.sitzung_id association can resolve.
    val sitzung = classOf(name = "Sitzung") {
        stereotype("Entity") { "tableName" to "sitzung"; "kotlinObjectName" to "SitzungTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the abstimmung.beschluss_id association can
    // resolve. This is the clean forward direction of the abstimmung<->beschluss cycle — see the
    // file header comment for why 05-governance.kuml.kts declares beschluss.abstimmung_id as a
    // plain column instead of the (circular, at that point) association.
    val beschluss = classOf(name = "Beschluss") {
        stereotype("Entity") { "tableName" to "beschluss"; "kotlinObjectName" to "BeschlussTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val abstimmungStatus = enumOf(name = "AbstimmungStatus") {
        literal(name = "OFFEN")
        literal(name = "GESCHLOSSEN")
        literal(name = "ABGEBROCHEN")
    }

    val abstimmung = classOf(name = "Abstimmung") {
        stereotype("Entity") { "tableName" to "abstimmung"; "kotlinObjectName" to "AbstimmungTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "status", type = abstimmungStatus) {
            stereotype("Column") { "columnName" to "status" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "opened_by".
        attribute(name = "openedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "opened_by" }
        }
        attribute(name = "openedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "opened_at" }
        }
        attribute(name = "closedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "closed_at" }
        }
        // No FK constraint in the real schema either (see the file header comment: circular with
        // abstimmung_option, which itself FK-references abstimmung).
        attribute(name = "winnerOptionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "winner_option_id" }
        }
        attribute(name = "secondPriceLtr", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "second_price_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
    }

    // abstimmung.antrag_id -> antrag (id): association-derived default matches.
    association(source = antrag, target = abstimmung, id = "assoc-antrag-abstimmung") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "antragId" }
    }

    // abstimmung.sitzung_id -> sitzung (id): association-derived default matches.
    association(source = sitzung, target = abstimmung, id = "assoc-sitzung-abstimmung") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "sitzungId" }
    }

    // abstimmung.beschluss_id -> beschluss (id): association-derived default matches. Nullable —
    // the clean forward direction of the abstimmung<->beschluss cycle (see file header comment).
    association(source = beschluss, target = abstimmung, id = "assoc-beschluss-abstimmung") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "beschlussId" }
    }

    val abstimmungOption = classOf(name = "AbstimmungOption") {
        stereotype("Entity") { "tableName" to "abstimmung_option"; "kotlinObjectName" to "AbstimmungOptionTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "label", type = "String") {
            stereotype("Column") { "columnName" to "label"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "position", type = "Int") {
            stereotype("Column") { "columnName" to "position" }
        }
    }

    // abstimmung_option.abstimmung_id -> abstimmung (id): association-derived default matches.
    association(source = abstimmung, target = abstimmungOption, id = "assoc-abstimmung-option") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "abstimmungId" }
    }

    val abstimmungStimme = classOf(name = "AbstimmungStimme") {
        stereotype("Entity") { "tableName" to "abstimmung_stimme"; "kotlinObjectName" to "AbstimmungStimmeTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> abstimmung_option (id), NOT NULL. Plain «Column» UUID attribute —
        // association-to-FK naming would derive "abstimmung_option_id", not the real schema's
        // "option_id".
        attribute(name = "optionId", type = "UUID") {
            stereotype("Column") { "columnName" to "option_id" }
        }
        attribute(name = "stakeLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "stake_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        attribute(name = "settledLtr", type = "BigDecimal") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "settled_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        attribute(name = "castAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "cast_at" }
        }
    }

    // abstimmung_stimme.abstimmung_id -> abstimmung (id): association-derived default matches.
    association(source = abstimmung, target = abstimmungStimme, id = "assoc-abstimmung-stimme") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "abstimmungId" }
    }

    // abstimmung_stimme.member_id -> member (id): association-derived default matches (no
    // competing member-FK on this entity).
    association(source = member, target = abstimmungStimme, id = "assoc-member-abstimmung-stimme") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }
}
