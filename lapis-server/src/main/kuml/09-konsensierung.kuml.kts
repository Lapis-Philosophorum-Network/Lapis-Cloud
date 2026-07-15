// Systemisches Konsensieren domain (SK, V0.2.5) — konsensierung/konsensierung_option/
// konsensierung_stimmberechtigt/konsensierung_teilnahme/konsensierung_stimmzettel/
// konsensierung_widerstand (generated into V1__baseline.sql alongside every other domain — see
// 87563ff, which replaced the 9 hand-written per-domain migrations with one generated baseline).
// Third, orthogonal counting logic hung off the same Antrag/Sitzung/Beschlussbuch governance
// spine as 06-abstimmung.kuml.kts (LTR-weighted eBay/Vickrey basket auction) and
// 07-wahl.kuml.kts (one-person-one-vote elections): here the winner is the option with the
// LOWEST cumulative resistance (Kumulierter Widerstand / KW), not the highest stake or vote
// count. See `network.lapis.cloud.server.rpc.KonsensierungService` KDoc for the full lifecycle
// and `03 Bereiche/Lapis Cloud/Systemisches Konsensieren.md` for the concept document this
// implements.
//
// Naming decision: the concept note's "SK-Abstimmung/SK-Option/SK-Widerstandswert" collide in
// spirit (though not in table name) with the already-existing meritokratische `Abstimmung`
// domain (06-abstimmung.kuml.kts). To avoid confusion this aggregate is named with the German
// nominalization `Konsensierung` — matching the single-German-noun convention of `Wahl`/
// `Abstimmung` — mapping SK-Abstimmung -> Konsensierung/konsensierung, SK-Option ->
// KonsensierungOption/konsensierung_option, SK-Widerstandswert -> KonsensierungWiderstand/
// konsensierung_widerstand (the per-ballot-per-option resistance row itself lives on
// konsensierung_widerstand, one row per (stimmzettel, option) pair — see that entity below).
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified
// against both the real Flyway-migrated H2 schema and the generated Exposed Table objects
// (network.lapis.cloud.server.db.generated.Konsensierung*Table.kt) by
// KonsensierungSchemaDriftTest. Per ADR-0016's designModelStrategy option B, this is a
// verification-only artifact for now — see docs/architecture/domain-model.adoc and CLAUDE.md's
// kUML-Repo-Konventionen (vault) for the full rationale.
//
// Cross-domain stubs: minimal id-only Member (foundation-owned), Antrag/Sitzung/Gremium/Beschluss
// (governance-owned) stubs, same pattern as 06-abstimmung.kuml.kts/07-wahl.kuml.kts's own stubs
// — purely so UmlToErmTransformer can resolve this domain's real FK associations within this
// single-file evaluation. Gremium is unused today (kept for documentation/consistency, mirroring
// 07-wahl.kuml.kts's own unused-today Gremium stub) — Konsensierung has no zielGremium concept,
// it never seats anyone.
//
// konsensierung.beschluss_id -> beschluss (id), nullable: association-derived default
// ("beschluss_id") matches the real column name exactly — modelled as a real UML association,
// the clean forward direction of the konsensierung<->beschluss cycle (Beschluss already exists
// here as a stub, so no cycle problem in this direction). 05-governance.kuml.kts's own beschluss
// entity declares beschluss.konsensierung_id as a plain nullable UUID «Column» attribute instead
// of the (circular, at that point) association — exactly the same two-domain-cycle pattern
// already used for abstimmung/wahl (see that file's header comment).
//
// konsensierung.winner_option_id: plain nullable UUID «Column» attribute with NO FK constraint —
// konsensierung_option itself FK-references konsensierung, so a real FK the other way would be
// circular. Same workaround already used for abstimmung.winner_option_id/
// document.current_version_id.
//
// FK-column-naming-mismatch fallbacks (same gap class already discovered in every prior domain —
// association-derived default name snake_case(singular(targetClass))+"_id" doesn't match the
// real column name), modelled as plain «Column» UUID attributes instead of UML associations:
// - konsensierung.opened_by -> member (id), NOT NULL: default would be "member_id".
// - konsensierung_option.created_by -> member (id), NOT NULL: default would be "member_id".
// - konsensierung_widerstand.stimmzettel_id -> konsensierung_stimmzettel (id), NOT NULL: default
//   would be "konsensierung_stimmzettel_id".
// - konsensierung_widerstand.option_id -> konsensierung_option (id), NOT NULL: default would be
//   "konsensierung_option_id".
// Their real FK existence/target/nullability is still independently pinned via
// KonsensierungSchemaDriftTest's information_schema introspection against the real migrated
// schema.
//
// FKs that DO match the association-derived default and are modelled as real UML associations:
// konsensierung.antrag_id/sitzung_id/beschluss_id, konsensierung_option.konsensierung_id,
// konsensierung_stimmberechtigt.konsensierung_id/member_id,
// konsensierung_teilnahme.konsensierung_id/member_id,
// konsensierung_stimmzettel.konsensierung_id/member_id. None of these entities has more than one
// competing member-FK, so the first-declared-association-claims-the-bare-default mechanism never
// causes a collision problem here (same as 06-abstimmung.kuml.kts/07-wahl.kuml.kts).
//
// Ballot secrecy (privacy rationale — do NOT "fix" konsensierung_stimmzettel.member_id's
// nullability, a naive schema reader might assume it should be NOT NULL like most other
// member_id FKs in this file): konsensierung_stimmzettel.member_id is nullable and always NULL
// for a geheim (secret, the default) Konsensierung — there is no FK-joinable link back to
// konsensierung_teilnahme (the "this member rated" proof) in that case. Exactly the same
// practical DB-level table split already used by wahl_stimmzettel/wahl_teilnahme (no
// cryptography anywhere in this codebase) — see 07-wahl.kuml.kts's own file header for the full
// rationale. One-member-one-vote-per-Bewertungsrunde enforcement:
// - Non-secret (geheim = false): UNIQUE (konsensierung_id, member_id, runde) on
//   konsensierung_stimmzettel is the backstop.
// - Secret (geheim = true): konsensierung_stimmzettel.member_id is always NULL, so that table's
//   own unique constraint is a no-op there. konsensierung_teilnahme's own UNIQUE
//   (konsensierung_id, member_id, runde) is the real DB-level backstop for the secret path
//   instead.
// The `runde` column (present on stimmberechtigt/teilnahme/stimmzettel, absent from wahl's
// equivalent tables) is this domain's one structural addition over the Wahl shape: it lets a
// reopened Bewertungsrunde (Diskussion + Wiederabstimmung) keep prior rounds' rows for DSGVO
// retention while the tally counts only the current runde.
//
// Four enum columns in this domain (KonsensierungStatus/SkAggregation/SkTiebreakRegel/
// SkVerbindlichkeit), modelled with only an «Column».enumType tag and NO «Column».sqlType
// override — post-87563ff convention (see 06-abstimmung.kuml.kts/07-wahl.kuml.kts's own current
// attribute shape, NOT their stale header prose): kUML's enum-to-Enum+CHECK fallback path
// applies, and the generated VARCHAR width is derived automatically from the longest literal.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Konsensierung") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by
    // every prior domain's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the konsensierung.antrag_id association can
    // resolve.
    val antrag = classOf(name = "Antrag") {
        stereotype("Entity") { "tableName" to "antrag"; "kotlinObjectName" to "AntragTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the konsensierung.sitzung_id association can
    // resolve.
    val sitzung = classOf(name = "Sitzung") {
        stereotype("Entity") { "tableName" to "sitzung"; "kotlinObjectName" to "SitzungTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only. Kept for documentation/consistency, mirroring
    // 07-wahl.kuml.kts's own unused-today Gremium stub — Konsensierung has no zielGremium concept.
    val gremium = classOf(name = "Gremium") {
        stereotype("Entity") { "tableName" to "gremium"; "kotlinObjectName" to "GremiumTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Governance-owned stub — id-only, purely so the konsensierung.beschluss_id association can
    // resolve. This is the clean forward direction of the konsensierung<->beschluss cycle — see
    // the file header comment for why 05-governance.kuml.kts declares beschluss.konsensierung_id
    // as a plain column instead of the (circular, at that point) association.
    val beschluss = classOf(name = "Beschluss") {
        stereotype("Entity") { "tableName" to "beschluss"; "kotlinObjectName" to "BeschlussTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val konsensierungStatus = enumOf(name = "KonsensierungStatus") {
        literal(name = "SAMMLUNG")
        literal(name = "BEWERTUNG")
        literal(name = "GESCHLOSSEN")
        literal(name = "AUSGEWERTET")
        literal(name = "ABGEBROCHEN")
    }

    val skAggregation = enumOf(name = "SkAggregation") {
        literal(name = "MITTELWERT")
        literal(name = "SUMME")
    }

    val skTiebreakRegel = enumOf(name = "SkTiebreakRegel") {
        literal(name = "NIEDRIGSTER_MAXWIDERSTAND")
        literal(name = "NIEDRIGSTE_STDABW")
        literal(name = "WIEDERHOLUNG")
    }

    val skVerbindlichkeit = enumOf(name = "SkVerbindlichkeit") {
        literal(name = "SONDIERUNG")
        literal(name = "BESCHLUSS")
    }

    val konsensierung = classOf(name = "Konsensierung") {
        stereotype("Entity") { "tableName" to "konsensierung"; "kotlinObjectName" to "KonsensierungTable" }
        stereotype("Index") { "columns" to listOf("antrag_id"); "name" to "idx_konsensierung_antrag" }
        stereotype("Index") { "columns" to listOf("sitzung_id"); "name" to "idx_konsensierung_sitzung" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_konsensierung_status" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "status", type = konsensierungStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.KonsensierungStatus" }
        }
        attribute(name = "geheim", type = "Boolean") {
            stereotype("Column") { "columnName" to "geheim" }
        }
        attribute(name = "skalaMax", type = "Int") {
            stereotype("Column") { "columnName" to "skala_max" }
        }
        attribute(name = "aggregation", type = skAggregation) {
            stereotype("Column") { "columnName" to "aggregation"; "enumType" to "network.lapis.cloud.shared.domain.SkAggregation" }
        }
        attribute(name = "tiebreakRegel", type = skTiebreakRegel) {
            stereotype("Column") { "columnName" to "tiebreak_regel"; "enumType" to "network.lapis.cloud.shared.domain.SkTiebreakRegel" }
        }
        attribute(name = "gkTragfaehigSchwelle", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "gk_tragfaehig_schwelle"; "sqlType" to "DECIMAL(4,3)" }
        }
        attribute(name = "gkWarnSchwelle", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "gk_warn_schwelle"; "sqlType" to "DECIMAL(4,3)" }
        }
        attribute(name = "passivloesungAuto", type = "Boolean") {
            stereotype("Column") { "columnName" to "passivloesung_auto" }
        }
        attribute(name = "verbindlichkeit", type = skVerbindlichkeit) {
            stereotype("Column") { "columnName" to "verbindlichkeit"; "enumType" to "network.lapis.cloud.shared.domain.SkVerbindlichkeit" }
        }
        attribute(name = "maxRunden", type = "Int") {
            stereotype("Column") { "columnName" to "max_runden" }
        }
        attribute(name = "runde", type = "Int") {
            stereotype("Column") { "columnName" to "runde" }
        }
        // No FK constraint (see file header comment: circular with konsensierung_option, which
        // itself FK-references konsensierung).
        attribute(name = "winnerOptionId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "winner_option_id" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "opened_by".
        attribute(name = "openedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "opened_by"; "fkEntity" to "Member" }
        }
        attribute(name = "openedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "opened_at" }
        }
        attribute(name = "bewertungOpenedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "bewertung_opened_at" }
        }
        attribute(name = "bewertungClosedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "bewertung_closed_at" }
        }
        attribute(name = "tallyRunAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "tally_run_at" }
        }
    }

    // konsensierung.antrag_id -> antrag (id): association-derived default matches.
    association(source = antrag, target = konsensierung, id = "assoc-antrag-konsensierung") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "antragId" }
    }

    // konsensierung.sitzung_id -> sitzung (id): association-derived default matches.
    association(source = sitzung, target = konsensierung, id = "assoc-sitzung-konsensierung") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "sitzungId" }
    }

    // konsensierung.beschluss_id -> beschluss (id): association-derived default matches.
    // Nullable — the clean forward direction of the konsensierung<->beschluss cycle (see file
    // header comment).
    association(source = beschluss, target = konsensierung, id = "assoc-beschluss-konsensierung") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "beschlussId" }
    }

    val konsensierungOption = classOf(name = "KonsensierungOption") {
        stereotype("Entity") { "tableName" to "konsensierung_option"; "kotlinObjectName" to "KonsensierungOptionTable" }
        stereotype("Index") { "columns" to listOf("konsensierung_id"); "name" to "idx_konsensierung_option_konsensierung" }

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
        attribute(name = "isPassivloesung", type = "Boolean") {
            stereotype("Column") { "columnName" to "is_passivloesung" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "created_by".
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
    }

    // konsensierung_option.konsensierung_id -> konsensierung (id): association-derived default
    // matches.
    association(source = konsensierung, target = konsensierungOption, id = "assoc-konsensierung-option") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "konsensierungId" }
    }

    val konsensierungStimmberechtigt = classOf(name = "KonsensierungStimmberechtigt") {
        stereotype("Entity") {
            "tableName" to "konsensierung_stimmberechtigt"
            "kotlinObjectName" to "KonsensierungStimmberechtigtTable"
        }
        stereotype("Index") {
            "columns" to listOf("konsensierung_id", "member_id", "runde")
            "unique" to true
            "name" to "uq_konsensierung_stimmberechtigt_member_runde"
        }
        stereotype("Index") { "columns" to listOf("konsensierung_id"); "name" to "idx_konsensierung_stimmberechtigt_konsensierung" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "runde", type = "Int") {
            stereotype("Column") { "columnName" to "runde" }
        }
    }

    // konsensierung_stimmberechtigt.konsensierung_id -> konsensierung (id): association-derived
    // default matches.
    association(source = konsensierung, target = konsensierungStimmberechtigt, id = "assoc-konsensierung-stimmberechtigt") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "konsensierungId" }
    }

    // konsensierung_stimmberechtigt.member_id -> member (id): association-derived default
    // matches (no competing member-FK on this entity).
    association(source = member, target = konsensierungStimmberechtigt, id = "assoc-member-konsensierung-stimmberechtigt") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val konsensierungTeilnahme = classOf(name = "KonsensierungTeilnahme") {
        stereotype("Entity") { "tableName" to "konsensierung_teilnahme"; "kotlinObjectName" to "KonsensierungTeilnahmeTable" }
        stereotype("Index") {
            "columns" to listOf("konsensierung_id", "member_id", "runde")
            "unique" to true
            "name" to "uq_konsensierung_teilnahme_member_runde"
        }
        stereotype("Index") { "columns" to listOf("konsensierung_id"); "name" to "idx_konsensierung_teilnahme_konsensierung" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "votedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "voted_at" }
        }
        attribute(name = "runde", type = "Int") {
            stereotype("Column") { "columnName" to "runde" }
        }
    }

    // konsensierung_teilnahme.konsensierung_id -> konsensierung (id): association-derived
    // default matches.
    association(source = konsensierung, target = konsensierungTeilnahme, id = "assoc-konsensierung-teilnahme") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "konsensierungId" }
    }

    // konsensierung_teilnahme.member_id -> member (id): association-derived default matches (no
    // competing member-FK on this entity).
    association(source = member, target = konsensierungTeilnahme, id = "assoc-member-konsensierung-teilnahme") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    // Ballot secrecy: memberId is nullable and always NULL for a geheim (secret) Konsensierung —
    // see file header comment for the full privacy rationale. This is a meaningful business rule
    // a naive schema reader could "fix" by mistake (most other member_id FKs in this domain are
    // NOT NULL).
    val konsensierungStimmzettel = classOf(name = "KonsensierungStimmzettel") {
        stereotype("Entity") { "tableName" to "konsensierung_stimmzettel"; "kotlinObjectName" to "KonsensierungStimmzettelTable" }
        stereotype("Index") {
            "columns" to listOf("konsensierung_id", "member_id", "runde")
            "unique" to true
            "name" to "uq_konsensierung_stimmzettel_member_runde"
        }
        stereotype("Index") { "columns" to listOf("konsensierung_id"); "name" to "idx_konsensierung_stimmzettel_konsensierung" }

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
        attribute(name = "runde", type = "Int") {
            stereotype("Column") { "columnName" to "runde" }
        }
    }

    // konsensierung_stimmzettel.konsensierung_id -> konsensierung (id): association-derived
    // default matches.
    association(source = konsensierung, target = konsensierungStimmzettel, id = "assoc-konsensierung-stimmzettel") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "konsensierungId" }
    }

    // konsensierung_stimmzettel.member_id -> member (id), nullable: association-derived default
    // matches (no competing member-FK on this entity). Nullable for ballot secrecy — see file
    // header.
    association(source = member, target = konsensierungStimmzettel, id = "assoc-member-konsensierung-stimmzettel") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val konsensierungWiderstand = classOf(name = "KonsensierungWiderstand") {
        stereotype("Entity") { "tableName" to "konsensierung_widerstand"; "kotlinObjectName" to "KonsensierungWiderstandTable" }
        stereotype("Index") {
            "columns" to listOf("stimmzettel_id")
            "name" to "idx_konsensierung_widerstand_stimmzettel"
        }
        stereotype("Index") { "columns" to listOf("option_id"); "name" to "idx_konsensierung_widerstand_option" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "wert", type = "Int") {
            stereotype("Column") { "columnName" to "wert" }
        }
        // Real FK -> konsensierung_stimmzettel (id), NOT NULL. Plain «Column» UUID attribute —
        // association-to-FK naming would derive "konsensierung_stimmzettel_id", not the real
        // schema's "stimmzettel_id".
        attribute(name = "stimmzettelId", type = "UUID") {
            stereotype("Column") { "columnName" to "stimmzettel_id"; "fkEntity" to "KonsensierungStimmzettel" }
        }
        // Real FK -> konsensierung_option (id), NOT NULL. Plain «Column» UUID attribute —
        // association-to-FK naming would derive "konsensierung_option_id", not the real schema's
        // "option_id".
        attribute(name = "optionId", type = "UUID") {
            stereotype("Column") { "columnName" to "option_id"; "fkEntity" to "KonsensierungOption" }
        }
    }
}
