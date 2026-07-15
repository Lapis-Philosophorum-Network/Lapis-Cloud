// Governance domain — gremium/gremium_mitgliedschaft/sitzung/tagesordnungspunkt/anwesenheit/
// beschluss/antrag (V6__governance.sql + V7__antragsverwaltung.sql; the beschluss.resolution_mode/
// abstimmung_id/wahl_id columns added by V8__meritokratische_abstimmungen.sql /
// V9__demokratische_wahlen.sql are also modelled here since they live on this domain's own
// `beschluss` table — see the beschluss entity's own comments below for why abstimmung_id/
// wahl_id are plain columns, not associations).
//
// abstimmung/abstimmung_option/abstimmung_stimme (V8) and wahl/* (V9) are OUT of scope for this
// file — they are later waves (06-abstimmung.kuml.kts / 07-wahl.kuml.kts per the retrofit plan's
// per-domain file layout) even though they happen to live in the same hand-written
// GovernanceTables.kt file today (option B means the hand-written .kt file's own internal
// organisation is decoupled from this retrofit's per-domain .kuml.kts split).
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified
// against both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.tables.GovernanceTables.kt) by GovernanceSchemaDriftTest. Per
// ADR-0016's designModelStrategy option B, this is a verification-only artifact for now: the
// hand-written Table objects remain the actually-compiled/actually-imported-by-N-files source.
// See docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the
// full rationale (enum-to-VARCHAR type-fidelity gap, Kotlin-object-naming-override gap).
//
// Cross-domain stubs: minimal id-only Member (Foundation-owned) and Document (Document-domain-
// owned) stubs, same pattern as prior domains' Member stubs — purely so UmlToErmTransformer can
// resolve this domain's real FK columns within this single-file evaluation. protocol_document_id
// (name mismatch vs. the association-derived default) is pinned via «Column».fkEntity against
// this Document stub rather than a UML association — see sitzung's own comment below.
//
// N-way multi-role-FK-collision finding (this is the domain the retrofit plan's risk note was
// written for): sitzung has FOUR independent FKs to member (called_by/chair_member_id/
// minute_taker_member_id/presenter_member_id — the last one is actually on tagesordnungspunkt,
// not sitzung, but is the same collision family). Empirically verified with a standalone
// reproduction script (four associations from one class to Member, each with a distinct role)
// against this exact kUML version before writing this file: UmlToErmTransformer's collision
// mechanism (`fkEntity.hasAttributeNamed(defaultBaseName)` in addForeignKey) does scale past
// N=2 — the 2nd/3rd/4th associations processed DO correctly disambiguate via their own role names
// once the plain "member_id" default already exists as an attribute. BUT the FIRST association
// processed for a given (fkClass, refClass) pair never collides (nothing named "member_id" exists
// yet at that point) and therefore always claims the bare "member_id" default regardless of its
// own role — confirmed empirically: with roles calledBy/chairMember/minuteTakerMember/
// presenterMember declared in that order, the resulting FK columns were
// ["member_id", "chair_member_id", "minute_taker_member_id", "presenter_member_id"], not
// ["called_by_id", ...]. Since none of the four real column names in this domain happen to equal
// the bare "member_id" default, every single one of them (not just 3 of 4) is modelled as a plain
// «Column» UUID attribute rather than a UML association, per the retrofit plan's own risk-note
// fallback strategy. Their real FK existence/target/nullability is still independently pinned via
// GovernanceSchemaDriftTest's information_schema introspection against the real migrated schema.
//
// Several further FK columns across this domain also fail to match the association-derived
// default name (same naming-gap class already discovered in document/communication/dsgvo) and are
// likewise modelled as plain «Column» UUID attributes: anwesenheit.represented_by_member_id,
// beschluss.recorded_by, antrag.target_gremium_id (default would be "gremium_id", not
// "target_gremium_id"), antrag.submitter_member_id, antrag.reviewed_by.
//
// FKs that DO match the association-derived default and are modelled as real UML associations:
// gremium_mitgliedschaft.gremium_id/member_id, sitzung.gremium_id, tagesordnungspunkt.sitzung_id,
// anwesenheit.sitzung_id/member_id (the first-declared member_id association — see anwesenheit's
// own comment for why this one is safe despite the multi-FK-to-member pattern), beschluss.
// sitzung_id, antrag.sitzung_id/tagesordnungspunkt_id/beschluss_id.
//
// beschluss.abstimmung_id / beschluss.wahl_id / beschluss.konsensierung_id (added by V8/V9/V0.2.5
// respectively): modelled as plain nullable UUID «Column» attributes, NOT UML associations,
// because Abstimmung/Wahl/Konsensierung entities don't exist in this domain's own script —
// exactly the same forward-reference-breaks-the-cycle workaround already used for
// document.current_version_id in the document wave. The abstimmung/wahl/konsensierung domains'
// OWN scripts (06-abstimmung.kuml.kts / 07-wahl.kuml.kts / 09-konsensierung.kuml.kts, later
// waves) declare the real «FK» association from their side instead (Abstimmung.beschlussId ->
// Beschluss, Wahl.beschlussId -> Beschluss, Konsensierung.beschlussId -> Beschluss), which is a
// clean forward reference with no cycle problem in that direction since Beschluss already exists
// (as a stub) by then. Left as plain columns rather than pinned via «Column».fkEntity for the
// same reason as document.current_version_id: the real risk is genuine bidirectional Kotlin
// `object`-initializer circularity at the Exposed layer (beschluss <-> abstimmung, beschluss <->
// wahl and beschluss <-> konsensierung are all truly bidirectional), which «Column».fkEntity's
// later-pass resolution sidesteps at the script-evaluation level but not at the generated-Kotlin
// level. Consequence: the generated SQL/Flyway baseline also lacks these three FK constraints
// (present in the pre-swap hand-written V6/V8/V9 migrations, for the first two) — a deliberate,
// pre-existing trade-off, not a new regression.
//
// Eight enum columns in this domain, all modelled with only an «Column».enumType tag and no
// «Column».sqlType override (post-87563ff convention — see 06-abstimmung.kuml.kts/
// 07-wahl.kuml.kts's own current attribute shape): gremium.type, gremium_mitgliedschaft.rolle,
// sitzung.format, sitzung.status, anwesenheit.status, beschluss.status, beschluss.resolution_mode
// (V0.2.5 adds the ResolutionMode.SYSTEMISCHER_KONSENS literal, widening the generated VARCHAR
// column to fit its 20 characters — the widening is automatic, derived from the longest literal,
// no manual override needed), antrag.status.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Governance") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by
    // contribution's/document's/communication's own Member stub.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Document-domain-owned stub — id-only, mirrors the Member stub above. Only exists here so
    // UmlToErmTransformer can resolve sitzung.protocol_document_id's «Column».fkEntity target.
    val document = classOf(name = "Document") {
        stereotype("Entity") { "tableName" to "document"; "kotlinObjectName" to "DocumentTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val gremiumType = enumOf(name = "GremiumType") {
        literal(name = "VORSTAND")
        literal(name = "ARBEITSKREIS")
        literal(name = "AUSSCHUSS")
        literal(name = "SONSTIGES")
        literal(name = "MITGLIEDERVERSAMMLUNG")
    }

    val gremiumRolle = enumOf(name = "GremiumRolle") {
        literal(name = "VORSITZ")
        literal(name = "STELLV_VORSITZ")
        literal(name = "SCHRIFTFUEHRUNG")
        literal(name = "MITGLIED")
        literal(name = "BEISITZ")
    }

    val sitzungsFormat = enumOf(name = "SitzungsFormat") {
        literal(name = "PRAESENZ")
        literal(name = "ONLINE")
        literal(name = "HYBRID")
    }

    val sitzungsStatus = enumOf(name = "SitzungsStatus") {
        literal(name = "GEPLANT")
        literal(name = "DURCHGEFUEHRT")
        literal(name = "ABGESAGT")
    }

    val anwesenheitStatus = enumOf(name = "AnwesenheitStatus") {
        literal(name = "ANWESEND")
        literal(name = "ENTSCHULDIGT")
        literal(name = "UNENTSCHULDIGT")
        literal(name = "VERTRETEN")
    }

    val beschlussStatus = enumOf(name = "BeschlussStatus") {
        literal(name = "ANGENOMMEN")
        literal(name = "ABGELEHNT")
        literal(name = "VERTAGT")
    }

    val resolutionMode = enumOf(name = "ResolutionMode") {
        literal(name = "GREMIUM_QUORUM")
        literal(name = "MERITOKRATISCH")
        literal(name = "DEMOKRATISCH")
        literal(name = "SYSTEMISCHER_KONSENS")
    }

    val antragStatus = enumOf(name = "AntragStatus") {
        literal(name = "EINGEREICHT")
        literal(name = "GEPRUEFT")
        literal(name = "ABGELEHNT_VORPRUEFUNG")
        literal(name = "TERMINIERT")
        literal(name = "BESCHLOSSEN")
        literal(name = "ABGELEHNT")
        literal(name = "VERTAGT")
        literal(name = "ZURUECKGEZOGEN")
    }

    val gremium = classOf(name = "Gremium") {
        stereotype("Entity") { "tableName" to "gremium"; "kotlinObjectName" to "GremiumTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "type", type = gremiumType) {
            stereotype("Column") { "columnName" to "type"; "enumType" to "network.lapis.cloud.shared.domain.GremiumType" }
        }
        attribute(name = "description", type = "String") {
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "active", type = "Boolean") {
            defaultValue = "TRUE"
            stereotype("Column") { "columnName" to "active" }
        }
        attribute(name = "quorumPercent", type = "Int") {
            defaultValue = "50"
            stereotype("Column") { "columnName" to "quorum_percent" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    val gremiumMitgliedschaft = classOf(name = "GremiumMitgliedschaft") {
        stereotype("Entity") { "tableName" to "gremium_mitgliedschaft"; "kotlinObjectName" to "GremiumMitgliedschaftTable" }
        stereotype("Index") { "columns" to listOf("gremium_id"); "name" to "idx_gremium_mitgliedschaft_gremium" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_gremium_mitgliedschaft_member" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "rolle", type = gremiumRolle) {
            stereotype("Column") { "columnName" to "rolle"; "enumType" to "network.lapis.cloud.shared.domain.GremiumRolle" }
        }
        attribute(name = "since", type = "LocalDate") {
            stereotype("Column") { "columnName" to "since" }
        }
        attribute(name = "until", type = "LocalDate") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "until" }
        }
    }

    // gremium_mitgliedschaft.gremium_id -> gremium (id): association-derived default matches.
    association(source = gremium, target = gremiumMitgliedschaft, id = "assoc-gremium-mitgliedschaft") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "gremiumId" }
    }

    // gremium_mitgliedschaft.member_id -> member (id): association-derived default matches.
    association(source = member, target = gremiumMitgliedschaft, id = "assoc-member-gremium-mitgliedschaft") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val sitzung = classOf(name = "Sitzung") {
        stereotype("Entity") { "tableName" to "sitzung"; "kotlinObjectName" to "SitzungTable" }
        stereotype("Index") { "columns" to listOf("gremium_id"); "name" to "idx_sitzung_gremium" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "scheduledAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "scheduled_at" }
        }
        attribute(name = "location", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "location"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "format", type = sitzungsFormat) {
            stereotype("Column") { "columnName" to "format"; "enumType" to "network.lapis.cloud.shared.domain.SitzungsFormat" }
        }
        attribute(name = "status", type = sitzungsStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.SitzungsStatus" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — see the file header
        // comment (N=4 multi-role-FK-collision case; the FIRST association processed for a given
        // (fkClass, refClass) pair always claims the bare "member_id" default regardless of its
        // own role, which does not match the real "called_by" column).
        attribute(name = "calledBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "called_by"; "fkEntity" to "Member" }
        }
        attribute(name = "calledAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "called_at" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — same N=4 collision
        // family as calledBy above.
        attribute(name = "chairMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "chair_member_id"; "fkEntity" to "Member" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — same N=4 collision
        // family as calledBy above.
        attribute(name = "minuteTakerMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "minute_taker_member_id"; "fkEntity" to "Member" }
        }
        // Real FK -> document (id), nullable. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "document_id", not the real schema's "protocol_document_id" (same
        // naming-gap class as document/communication/dsgvo's own mismatched FK columns).
        attribute(name = "protocolDocumentId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "protocol_document_id"; "fkEntity" to "Document" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }

    // sitzung.gremium_id -> gremium (id): association-derived default matches.
    association(source = gremium, target = sitzung, id = "assoc-gremium-sitzung") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "gremiumId" }
    }

    val tagesordnungspunkt = classOf(name = "Tagesordnungspunkt") {
        stereotype("Entity") { "tableName" to "tagesordnungspunkt"; "kotlinObjectName" to "TagesordnungspunktTable" }
        stereotype("Index") {
            "columns" to listOf("sitzung_id", "position")
            "unique" to true
            "name" to "uq_tagesordnungspunkt_position"
        }
        stereotype("Index") { "columns" to listOf("sitzung_id"); "name" to "idx_tagesordnungspunkt_sitzung" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "position", type = "Int") {
            stereotype("Column") { "columnName" to "position" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        // Widened from VARCHAR(1000) to VARCHAR(4000) by V7__antragsverwaltung.sql.
        attribute(name = "description", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(4000)" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — same naming-gap class
        // as sitzung's member-referencing columns (default would be "member_id", not
        // "presenter_member_id").
        attribute(name = "presenterMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "presenter_member_id"; "fkEntity" to "Member" }
        }
    }

    // tagesordnungspunkt.sitzung_id -> sitzung (id): association-derived default matches.
    association(source = sitzung, target = tagesordnungspunkt, id = "assoc-sitzung-tagesordnungspunkt") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "sitzungId" }
    }

    val anwesenheit = classOf(name = "Anwesenheit") {
        stereotype("Entity") { "tableName" to "anwesenheit"; "kotlinObjectName" to "AnwesenheitTable" }
        stereotype("Index") {
            "columns" to listOf("sitzung_id", "member_id")
            "unique" to true
            "name" to "uq_anwesenheit_member"
        }
        stereotype("Index") { "columns" to listOf("sitzung_id"); "name" to "idx_anwesenheit_sitzung" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "status", type = anwesenheitStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.AnwesenheitStatus" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id" (already claimed by the memberId association below),
        // not the real schema's "represented_by_member_id".
        attribute(name = "representedByMemberId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "represented_by_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "note", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "note"; "sqlType" to "VARCHAR(500)" }
        }
        attribute(name = "recordedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "recorded_at" }
        }
    }

    // anwesenheit.sitzung_id -> sitzung (id): association-derived default matches.
    association(source = sitzung, target = anwesenheit, id = "assoc-sitzung-anwesenheit") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "sitzungId" }
    }

    // anwesenheit.member_id -> member (id): association-derived default matches. Safe despite
    // this entity ALSO having represented_by_member_id, because that second FK is modelled as a
    // plain «Column» attribute (see above), never as a competing association — so there is no
    // ordering-dependent collision to worry about here, unlike sitzung's four-way case.
    association(source = member, target = anwesenheit, id = "assoc-member-anwesenheit") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val beschluss = classOf(name = "Beschluss") {
        stereotype("Entity") { "tableName" to "beschluss"; "kotlinObjectName" to "BeschlussTable" }
        stereotype("Index") { "columns" to listOf("sitzung_id"); "name" to "idx_beschluss_sitzung" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "number", type = "String") {
            stereotype("Column") { "columnName" to "number"; "sqlType" to "VARCHAR(50)" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "text", type = "String") {
            stereotype("Column") { "columnName" to "text"; "sqlType" to "VARCHAR(4000)" }
        }
        attribute(name = "votesYes", type = "Int") {
            stereotype("Column") { "columnName" to "votes_yes" }
        }
        attribute(name = "votesNo", type = "Int") {
            stereotype("Column") { "columnName" to "votes_no" }
        }
        attribute(name = "votesAbstain", type = "Int") {
            stereotype("Column") { "columnName" to "votes_abstain" }
        }
        attribute(name = "quorumMet", type = "Boolean") {
            stereotype("Column") { "columnName" to "quorum_met" }
        }
        attribute(name = "status", type = beschlussStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.BeschlussStatus" }
        }
        attribute(name = "decidedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "decided_at" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "recorded_by".
        attribute(name = "recordedBy", type = "UUID") {
            stereotype("Column") { "columnName" to "recorded_by"; "fkEntity" to "Member" }
        }
        attribute(name = "resolutionMode", type = resolutionMode) {
            defaultValue = "GREMIUM_QUORUM"
            stereotype("Column") { "columnName" to "resolution_mode"; "enumType" to "network.lapis.cloud.shared.domain.ResolutionMode" }
        }
        // Forward reference into the (not-yet-modelled-in-this-file) abstimmung domain — plain
        // nullable UUID «Column» attribute, NOT a UML association, exactly like
        // document.current_version_id's circular-reference workaround in the document wave. The
        // abstimmung domain's own script declares the real «FK» association from its side
        // instead (Abstimmung.beschlussId -> Beschluss), a clean forward reference since Beschluss
        // already exists (as a stub) by then. See the file header comment.
        attribute(name = "abstimmungId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "abstimmung_id" }
        }
        // Same forward-reference workaround as abstimmungId above, but into the wahl domain.
        attribute(name = "wahlId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "wahl_id" }
        }
        // Same forward-reference workaround as abstimmungId/wahlId above, but into the
        // konsensierung domain (V0.2.5, 09-konsensierung.kuml.kts).
        attribute(name = "konsensierungId", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "konsensierung_id" }
        }
    }

    // beschluss.sitzung_id -> sitzung (id): association-derived default matches.
    association(source = sitzung, target = beschluss, id = "assoc-sitzung-beschluss") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "sitzungId" }
    }

    // beschluss.tagesordnungspunkt_id -> tagesordnungspunkt (id): association-derived default
    // matches. Nullable on the beschluss side (0..1 target multiplicity in
    // UmlToErmTransformer's role-based FK-nullability derivation).
    association(source = tagesordnungspunkt, target = beschluss, id = "assoc-tagesordnungspunkt-beschluss") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "tagesordnungspunktId" }
    }

    val antrag = classOf(name = "Antrag") {
        stereotype("Entity") { "tableName" to "antrag"; "kotlinObjectName" to "AntragTable" }
        stereotype("Index") { "columns" to listOf("target_gremium_id"); "name" to "idx_antrag_target_gremium" }
        stereotype("Index") { "columns" to listOf("status"); "name" to "idx_antrag_status" }
        stereotype("Index") { "columns" to listOf("submitter_member_id"); "name" to "idx_antrag_submitter" }
        stereotype("Index") { "columns" to listOf("sitzung_id"); "name" to "idx_antrag_sitzung" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        // Real FK -> gremium (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "gremium_id", not the real schema's "target_gremium_id".
        attribute(name = "targetGremiumId", type = "UUID") {
            stereotype("Column") { "columnName" to "target_gremium_id"; "fkEntity" to "Gremium" }
        }
        attribute(name = "title", type = "String") {
            stereotype("Column") { "columnName" to "title"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "begruendung", type = "String") {
            stereotype("Column") { "columnName" to "begruendung"; "sqlType" to "VARCHAR(4000)" }
        }
        attribute(name = "text", type = "String") {
            stereotype("Column") { "columnName" to "text"; "sqlType" to "VARCHAR(4000)" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "submitter_member_id".
        attribute(name = "submitterMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "submitter_member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "status", type = antragStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.AntragStatus" }
        }
        attribute(name = "submittedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "submitted_at" }
        }
        // Real FK -> member (id), nullable. Plain «Column» UUID attribute — association-to-FK
        // naming would derive "member_id", not the real schema's "reviewed_by".
        attribute(name = "reviewedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reviewed_by"; "fkEntity" to "Member" }
        }
        attribute(name = "reviewedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "reviewed_at" }
        }
        attribute(name = "reviewNote", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "review_note"; "sqlType" to "VARCHAR(1000)" }
        }
        attribute(name = "withdrawnAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "withdrawn_at" }
        }
    }

    // antrag.sitzung_id -> sitzung (id): association-derived default matches. Nullable.
    association(source = sitzung, target = antrag, id = "assoc-sitzung-antrag") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "sitzungId" }
    }

    // antrag.tagesordnungspunkt_id -> tagesordnungspunkt (id): association-derived default
    // matches. Nullable.
    association(source = tagesordnungspunkt, target = antrag, id = "assoc-tagesordnungspunkt-antrag") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "tagesordnungspunktId" }
    }

    // antrag.beschluss_id -> beschluss (id): association-derived default matches. Nullable.
    association(source = beschluss, target = antrag, id = "assoc-beschluss-antrag") {
        source { multiplicity("0..1") }
        target { multiplicity("0..*"); role = "beschlussId" }
    }
}
