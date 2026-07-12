// Wahl domain — wahl/wahl_kandidatur/wahl_option/wahl_wahlvorstand/wahl_wahlberechtigt/
// wahl_teilnahme/wahl_freigabe/wahl_stimmzettel/wahl_stimmzettel_auswahl
// (V9__demokratische_wahlen.sql). Second-largest domain (202 lines of hand-written
// network.lapis.cloud.server.db.tables.WahlTables.kt) — one-person-one-vote elections/ballots for
// personnel (EINZELWAHL/MEHRFACHWAHL/LISTENWAHL/RANGLISTENWAHL) and Ja/Nein (JA_NEIN) decisions,
// structurally distinct from abstimmung (06-abstimmung.kuml.kts's LTR-weighted eBay/Vickrey
// basket auction).
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified against
// both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.tables.WahlTables.kt) by WahlSchemaDriftTest. Per ADR-0016's
// designModelStrategy option B, this is a verification-only artifact for now: the hand-written
// Table objects remain the actually-compiled/actually-imported-by-N-files source. See
// docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the full
// rationale (enum-to-VARCHAR type-fidelity gap, Kotlin-object-naming-override gap).
//
// Cross-domain stubs: minimal id-only Member (foundation-owned), Antrag/Sitzung/Gremium/Beschluss
// (governance-owned) stubs — same pattern as prior domains' stubs, purely so
// UmlToErmTransformer can resolve this domain's real FK associations within this single-file
// evaluation. Beschluss is stubbed so wahl.beschluss_id -> beschluss can be declared as a real,
// clean forward «FK» association from this side — mirrors 06-abstimmung.kuml.kts's own
// beschluss stub/association exactly: 05-governance.kuml.kts's beschluss entity already declares
// beschluss.wahl_id as a plain nullable UUID «Column» attribute (not an association) because Wahl
// didn't exist in that domain's own script — see that file's header comment for the full
// cycle-avoidance rationale. This file closes the second leg of that same two-domain cycle, the
// clean forward direction (wahl -> beschluss), with no cycle problem in this direction since
// Beschluss already exists here as a stub.
//
// Table-declaration order in this script deliberately mirrors V9__demokratische_wahlen.sql's own
// dependency-driven (not alphabetical) creation order: wahl_kandidatur is declared before
// wahl_option, because wahl_option.kandidatur_id references wahl_kandidatur (the migration's own
// file header calls this out explicitly: "wahl_kandidatur must exist before wahl_option is
// created"). For SQL DDL generation this ordering would matter (irrelevant here since V9's SQL
// stays untouched — Flyway migrations are immutable history); for Exposed/Kotlin object
// generation top-level objects can reference each other regardless of declaration order. Kept the
// same order anyway for readability/consistency with the SQL and the hand-written
// WahlTables.kt's own declaration order.
//
// Ballot secrecy (privacy rationale, meaningful business rule — do NOT "fix" wahl_stimmzettel.
// member_id's nullability, a naive schema reader might assume it should be NOT NULL like most
// other member_id FKs in this file): wahl_stimmzettel.member_id is nullable and always NULL for a
// geheim (secret) Wahl — there is no FK-joinable link back to wahl_teilnahme (the "this member
// voted" proof) in that case. This is a practical DB-level table split, not cryptography — no
// homomorphic encryption/mix-net/threshold-signature scheme exists in this codebase. One-member-
// one-vote enforcement differs between the two ballot paths on purpose:
// - Non-secret (geheim = false): UNIQUE (wahl_id, member_id) on wahl_stimmzettel is the backstop
//   (composite unique — accepted gap, pinned below, same as prior domains' composite uniques).
// - Secret (geheim = true): wahl_stimmzettel.member_id is always NULL, so that table's own unique
//   constraint is a no-op there. wahl_teilnahme's own UNIQUE (wahl_id, member_id) is the real
//   DB-level backstop for the secret path instead — see WahlTable KDoc / WahlService.castStimme.
//
// FK-column-naming-mismatch fallbacks (same gap class already discovered in document/
// communication/dsgvo/governance/abstimmung — association-derived default name
// snake_case(singular(targetClass))+"_id" doesn't match the real column name), modelled as plain
// «Column» UUID attributes instead of UML associations:
// - wahl.ziel_gremium_id -> gremium (id), nullable: default would be "gremium_id".
// - wahl.opened_by -> member (id), NOT NULL: default would be "member_id".
// - wahl_option.kandidatur_id -> wahl_kandidatur (id), nullable: default would be
//   "wahl_kandidatur_id".
// - wahl_stimmzettel_auswahl.stimmzettel_id -> wahl_stimmzettel (id), NOT NULL: default would be
//   "wahl_stimmzettel_id".
// - wahl_stimmzettel_auswahl.option_id -> wahl_option (id), NOT NULL: default would be
//   "wahl_option_id".
// Their real FK existence/target/nullability is still independently pinned via
// WahlSchemaDriftTest's information_schema introspection against the real migrated schema.
//
// FKs that DO match the association-derived default and are modelled as real UML associations:
// wahl.antrag_id, wahl.sitzung_id, wahl.beschluss_id (forward direction, see above),
// wahl_kandidatur.wahl_id/member_id, wahl_option.wahl_id, wahl_wahlvorstand.wahl_id/member_id,
// wahl_wahlberechtigt.wahl_id/member_id, wahl_teilnahme.wahl_id/member_id,
// wahl_freigabe.wahl_id/member_id, wahl_stimmzettel.wahl_id/member_id. None of these entities has
// more than one competing member-FK, so — unlike governance's sitzung (N=4) / dsgvo's
// erasure_request (N=3) — the first-declared-association-claims-the-bare-default mechanism never
// causes a collision problem here.
//
// WahlTyp and WahlStatus are this domain's own enum types, modelled with explicit
// «Column».sqlType overrides (VARCHAR(20)/VARCHAR(30) respectively) — same mechanism/rationale as
// every prior domain's enum columns (real V9 schema has plain VARCHAR columns, no CHECK
// constraints). GremiumRolle is reused from governance (05-governance.kuml.kts) for
// wahl.ziel_rolle — kUML has no cross-file model-import (confirmed by every prior domain's own
// enum re-declarations), so the enum literal set is duplicated here too, exactly like the
// entity-stub pattern for shared entities.
//
// Composite UNIQUE gaps (no kUML ERM-profile equivalent — only single-column «Column».unique
// exists), pinned explicitly in WahlSchemaDriftTest rather than silently ignored, same accepted-
// gap class as contribution/document/communication/governance/abstimmung's own composite uniques:
// uq_wahl_wahlvorstand_member (wahl_id, member_id), uq_wahl_wahlberechtigt_member
// (wahl_id, member_id), uq_wahl_teilnahme_member (wahl_id, member_id), uq_wahl_freigabe_member
// (wahl_id, member_id), uq_wahl_stimmzettel_member (wahl_id, member_id).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Wahl") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by
    // every prior domain's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the wahl.antrag_id association can resolve.
    val antrag = classOf(name = "Antrag") {
        stereotype("Entity") { "tableName" to "antrag" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the wahl.sitzung_id association can resolve.
    val sitzung = classOf(name = "Sitzung") {
        stereotype("Entity") { "tableName" to "sitzung" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only. wahl.ziel_gremium_id is a plain «Column» attribute (name
    // mismatch, see file header), so this stub isn't the target of any real association here —
    // kept anyway for documentation/consistency, mirroring 05-governance.kuml.kts's own
    // unused-today Document stub.
    val gremium = classOf(name = "Gremium") {
        stereotype("Entity") { "tableName" to "gremium" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the wahl.beschluss_id association can resolve.
    // This is the clean forward direction of the wahl<->beschluss cycle — see the file header
    // comment for why 05-governance.kuml.kts declares beschluss.wahl_id as a plain column instead
    // of the (circular, at that point) association. Mirrors 06-abstimmung.kuml.kts's own
    // beschluss stub/association exactly.
    val beschluss = classOf(name = "Beschluss") {
        stereotype("Entity") { "tableName" to "beschluss" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val wahlTyp = enumOf(name = "WahlTyp") {
        literal(name = "JA_NEIN")
        literal(name = "EINZELWAHL")
        literal(name = "MEHRFACHWAHL")
        literal(name = "LISTENWAHL")
        literal(name = "RANGLISTENWAHL")
    }

    val wahlStatus = enumOf(name = "WahlStatus") {
        literal(name = "VORBEREITUNG")
        literal(name = "KANDIDATENLISTE_FREIGEGEBEN")
        literal(name = "OFFEN")
        literal(name = "GESCHLOSSEN")
        literal(name = "AUSGEZAEHLT")
        literal(name = "ABGEBROCHEN")
    }

    // Reused from governance (05-governance.kuml.kts) — duplicated here since kUML has no
    // cross-file model-import (confirmed finding, every prior domain re-declares shared enums the
    // same way).
    val gremiumRolle = enumOf(name = "GremiumRolle") {
        literal(name = "VORSITZ")
        literal(name = "STELLV_VORSITZ")
        literal(name = "SCHRIFTFUEHRUNG")
        literal(name = "MITGLIED")
        literal(name = "BEISITZ")
    }

    val wahl = classOf(name = "Wahl") {
        stereotype("Entity") { "tableName" to "wahl" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "wahlTyp", type = wahlTyp) {
            stereotype("Column") { "columnName" to "wahl_typ"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "geheim", type = "Boolean") {
            stereotype("Column") { "columnName" to "geheim" }
        }
        attribute(name = "sitzeCount", type = "Int") {
            stereotype("Column") { "columnName" to "sitze_count" }
        }
        // Real FK -> gremium (id), nullable. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "gremium_id", not the real schema's "ziel_gremium_id".
        attribute(name = "zielGremiumId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "ziel_gremium_id" }
        }
        attribute(name = "zielRolle", type = gremiumRolle) {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "ziel_rolle"; "sqlType" to "VARCHAR(20)" }
        }
        attribute(name = "requiredMajorityPercent", type = "Int") {
            stereotype("Column") { "columnName" to "required_majority_percent" }
        }
        attribute(name = "status", type = wahlStatus) {
            stereotype("Column") { "columnName" to "status"; "sqlType" to "VARCHAR(30)" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "opened_by".
        attribute(name = "openedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "opened_by" }
        }
        attribute(name = "openedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "opened_at" }
        }
        attribute(name = "candidateListApprovedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "candidate_list_approved_at" }
        }
        attribute(name = "votingOpenedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "voting_opened_at" }
        }
        attribute(name = "votingClosedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "voting_closed_at" }
        }
        attribute(name = "tallyThreshold", type = "Int") {
            stereotype("Column") { "columnName" to "tally_threshold" }
        }
        attribute(name = "tallyRunAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "tally_run_at" }
        }
    }

    // wahl.antrag_id -> antrag (id): association-derived default matches.
    association(source = antrag, target = wahl, id = "assoc-antrag-wahl") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "antragId" }
    }

    // wahl.sitzung_id -> sitzung (id): association-derived default matches.
    association(source = sitzung, target = wahl, id = "assoc-sitzung-wahl") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "sitzungId" }
    }

    // wahl.beschluss_id -> beschluss (id): association-derived default matches. Nullable — the
    // clean forward direction of the wahl<->beschluss cycle (see file header comment).
    association(source = beschluss, target = wahl, id = "assoc-beschluss-wahl") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "beschlussId" }
    }

    // wahl_kandidatur must be declared before wahl_option (see file header comment) —
    // wahl_option.kandidatur_id references it.
    val wahlKandidatur = classOf(name = "WahlKandidatur") {
        stereotype("Entity") { "tableName" to "wahl_kandidatur" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "motivationText", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "motivation_text"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "submittedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "submitted_at" }
        }
        attribute(name = "withdrawnAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "withdrawn_at" }
        }
    }

    // wahl_kandidatur.wahl_id -> wahl (id): association-derived default matches.
    association(source = wahl, target = wahlKandidatur, id = "assoc-wahl-kandidatur") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "wahlId" }
    }

    // wahl_kandidatur.member_id -> member (id): association-derived default matches (no competing
    // member-FK on this entity).
    association(source = member, target = wahlKandidatur, id = "assoc-member-wahl-kandidatur") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val wahlOption = classOf(name = "WahlOption") {
        stereotype("Entity") { "tableName" to "wahl_option" }

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
        // Real FK -> wahl_kandidatur (id), nullable. Plain «Column» UUID attribute —
        // association-to-FK naming would derive "wahl_kandidatur_id", not the real schema's
        // "kandidatur_id".
        attribute(name = "kandidaturId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "kandidatur_id" }
        }
    }

    // wahl_option.wahl_id -> wahl (id): association-derived default matches.
    association(source = wahl, target = wahlOption, id = "assoc-wahl-option") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "wahlId" }
    }

    val wahlWahlvorstand = classOf(name = "WahlWahlvorstand") {
        stereotype("Entity") { "tableName" to "wahl_wahlvorstand" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "appointedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "appointed_at" }
        }
    }

    // wahl_wahlvorstand.wahl_id -> wahl (id): association-derived default matches.
    association(source = wahl, target = wahlWahlvorstand, id = "assoc-wahl-wahlvorstand") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "wahlId" }
    }

    // wahl_wahlvorstand.member_id -> member (id): association-derived default matches (no
    // competing member-FK on this entity).
    association(source = member, target = wahlWahlvorstand, id = "assoc-member-wahl-wahlvorstand") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val wahlWahlberechtigt = classOf(name = "WahlWahlberechtigt") {
        stereotype("Entity") { "tableName" to "wahl_wahlberechtigt" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // wahl_wahlberechtigt.wahl_id -> wahl (id): association-derived default matches.
    association(source = wahl, target = wahlWahlberechtigt, id = "assoc-wahl-wahlberechtigt") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "wahlId" }
    }

    // wahl_wahlberechtigt.member_id -> member (id): association-derived default matches (no
    // competing member-FK on this entity).
    association(source = member, target = wahlWahlberechtigt, id = "assoc-member-wahl-wahlberechtigt") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val wahlTeilnahme = classOf(name = "WahlTeilnahme") {
        stereotype("Entity") { "tableName" to "wahl_teilnahme" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "votedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "voted_at" }
        }
    }

    // wahl_teilnahme.wahl_id -> wahl (id): association-derived default matches.
    association(source = wahl, target = wahlTeilnahme, id = "assoc-wahl-teilnahme") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "wahlId" }
    }

    // wahl_teilnahme.member_id -> member (id): association-derived default matches (no competing
    // member-FK on this entity).
    association(source = member, target = wahlTeilnahme, id = "assoc-member-wahl-teilnahme") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val wahlFreigabe = classOf(name = "WahlFreigabe") {
        stereotype("Entity") { "tableName" to "wahl_freigabe" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "approvedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "approved_at" }
        }
    }

    // wahl_freigabe.wahl_id -> wahl (id): association-derived default matches.
    association(source = wahl, target = wahlFreigabe, id = "assoc-wahl-freigabe") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "wahlId" }
    }

    // wahl_freigabe.member_id -> member (id): association-derived default matches (no competing
    // member-FK on this entity).
    association(source = member, target = wahlFreigabe, id = "assoc-member-wahl-freigabe") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    // Ballot secrecy: memberId is nullable and always NULL for a geheim (secret) Wahl — see file
    // header comment for the full privacy rationale. This is a meaningful business rule a naive
    // schema reader could "fix" by mistake (most other member_id FKs in this domain are NOT NULL).
    val wahlStimmzettel = classOf(name = "WahlStimmzettel") {
        stereotype("Entity") { "tableName" to "wahl_stimmzettel" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "receiptCode", type = "String") {
            stereotype("Column") { "columnName" to "receipt_code"; "sqlType" to "VARCHAR(40)" }
        }
        attribute(name = "castAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "cast_at" }
        }
    }

    // wahl_stimmzettel.wahl_id -> wahl (id): association-derived default matches.
    association(source = wahl, target = wahlStimmzettel, id = "assoc-wahl-stimmzettel") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "wahlId" }
    }

    // wahl_stimmzettel.member_id -> member (id), nullable: association-derived default matches
    // (no competing member-FK on this entity). Nullable for ballot secrecy — see file header.
    association(source = member, target = wahlStimmzettel, id = "assoc-member-wahl-stimmzettel") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val wahlStimmzettelAuswahl = classOf(name = "WahlStimmzettelAuswahl") {
        stereotype("Entity") { "tableName" to "wahl_stimmzettel_auswahl" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> wahl_stimmzettel (id), NOT NULL. Plain «Column» UUID attribute —
        // association-to-FK naming would derive "wahl_stimmzettel_id", not the real schema's
        // "stimmzettel_id".
        attribute(name = "stimmzettelId", type = "UUID") {
            stereotype("Column") { "columnName" to "stimmzettel_id" }
        }
        // Real FK -> wahl_option (id), NOT NULL. Plain «Column» UUID attribute —
        // association-to-FK naming would derive "wahl_option_id", not the real schema's
        // "option_id".
        attribute(name = "optionId", type = "UUID") {
            stereotype("Column") { "columnName" to "option_id" }
        }
    }
}
