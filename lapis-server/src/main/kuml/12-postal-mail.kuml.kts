// PostalMail domain (V0.4.2, Letterxpress postal-mail dispatch) -- postal_delivery_log.
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified against
// both the real Flyway-migrated H2 schema and the hand-written PostalDeliveryLogTable Exposed
// object by PostalMailSchemaDriftTest. Per ADR-0016's designModelStrategy option B, this is a
// verification-only artifact for now: the hand-written Table object remains the actually-
// compiled/actually-imported-by-N-files source. See docs/architecture/domain-model.adoc and
// CLAUDE.md's kUML-Repo-Konventionen (vault) for the full rationale.
//
// Cross-domain stub: a minimal id-only Member stub (Foundation-owned), same pattern as
// contribution's/document's/communication's own Member stub, purely so UmlToErmTransformer can
// resolve this domain's recipient_member_id-shaped FK association within this single-file
// evaluation.
//
// Naming-gap workaround: postal_delivery_log.recipient_member_id does NOT match
// UmlToErmTransformer's association-derived default ("member_id") -- same naming-gap already
// discovered in the document wave (folder_id/created_by/uploaded_by) and the communication wave
// (mailing_list.created_by/mailing_message.sent_by). No DSL-level way to override the derived
// default name without an actual attribute-name collision (which would itself leave a spurious
// duplicate column). So it is modelled as a plain «Column» UUID attribute, not a UML association.
// Its real FK existence/target/nullability is still independently pinned via
// PostalMailSchemaDriftTest's information_schema introspection, exactly like mailing_list.created_by.
//
// PostalDeliveryStatus (postal_delivery_log.status) is this domain's one enum column -- longest
// literal ("QUEUED"/"FAILED") is 6 characters, so the generator emits
// enumerationByName("status", 6) / SQL VARCHAR(6) -- same max-literal-length-derived sizing as
// e.g. mailing_delivery_log.delivery_status -> VARCHAR(20) (longest DeliveryStatus literal,
// "SKIPPED_UNSUBSCRIBED", is 20 characters) in 03-communication.kuml.kts.
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "PostalMail") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub -- id-only, mirrors the cross-domain-stub pattern established by
    // contribution's/document's/communication's own Member stub. Only exists here so
    // UmlToErmTransformer can resolve this domain's Member-targeting association within this
    // single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val postalDeliveryStatus = enumOf(name = "PostalDeliveryStatus") {
        literal(name = "QUEUED")
        literal(name = "SENT")
        literal(name = "FAILED")
    }

    val postalDeliveryLog = classOf(name = "PostalDeliveryLog") {
        stereotype("Entity") { "tableName" to "postal_delivery_log"; "kotlinObjectName" to "PostalDeliveryLogTable" }
        stereotype("Index") { "columns" to listOf("recipient_member_id"); "name" to "idx_postal_delivery_log_recipient" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "documentReference", type = "String") {
            stereotype("Column") { "columnName" to "document_reference"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "dispatchedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "dispatched_at" }
        }
        attribute(name = "status", type = postalDeliveryStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.PostalDeliveryStatus" }
        }
        attribute(name = "providerReference", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "provider_reference"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "errorMessage", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "error_message"; "sqlType" to "VARCHAR(1000)" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // comment (association-to-FK naming would derive "member_id", not the real schema's
        // "recipient_member_id").
        attribute(name = "recipientMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "recipient_member_id"; "fkEntity" to "Member" }
        }
    }
}
