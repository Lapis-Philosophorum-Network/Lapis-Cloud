// Communication domain — mailing_list/mailing_list_subscription/mailing_message/
// mailing_delivery_log/direct_message (V4__communication.sql).
//
// This is the versioned source-of-truth *model* for the schema shape (ADR-0016), verified
// against both the real Flyway-migrated H2 schema and the hand-written Exposed Table objects
// (network.lapis.cloud.server.db.tables.CommunicationTables.kt) by SchemaDriftTest. Per
// ADR-0016's designModelStrategy option B, this is a verification-only artifact for now: the
// hand-written Table objects remain the actually-compiled/actually-imported-by-N-files source.
// See docs/architecture/domain-model.adoc and CLAUDE.md's kUML-Repo-Konventionen (vault) for the
// full rationale (enum-to-VARCHAR type-fidelity gap, Kotlin-object-naming-override gap).
//
// Cross-domain stub: a minimal id-only Member stub (Foundation-owned), same pattern as
// contribution's/document's own Member stub, purely so UmlToErmTransformer can resolve this
// domain's several member_id-shaped FK associations (mailing_list.created_by,
// mailing_list_subscription.member_id, mailing_message.sent_by, mailing_delivery_log.member_id,
// direct_message.sender_id/recipient_id) within this single-file evaluation.
//
// Two independent naming situations for this domain's Member-referencing FKs:
//
//  1. mailing_list.created_by and mailing_message.sent_by have real column names that do NOT
//     match UmlToErmTransformer's association-derived default ("member_id") — same naming-gap
//     already discovered in the document wave (folder_id/created_by/uploaded_by). No DSL-level
//     way to override the derived default name without an actual attribute-name collision (which
//     would itself leave a spurious duplicate column). So these two are modelled as plain
//     «Column» UUID attributes, not UML associations. Their real FK existence/target/nullability
//     is still independently pinned via CommunicationSchemaDriftTest's information_schema
//     introspection.
//
//  2. mailing_list_subscription.member_id and mailing_delivery_log.member_id DO match the
//     derived default exactly ("member_id") — these are modelled as real UML associations.
//
//  3. direct_message.sender_id / direct_message.recipient_id: this is the "two associations from
//     the same class to the same target" collision case flagged explicitly in the retrofit plan
//     and documented in UmlToErmTransformer.addForeignKey's own KDoc (fkEntity.hasAttributeNamed
//     check -> falls back to the association-end role name if set, else the collision surfaces as
//     an ErmConstraintChecker ERROR). Verified with a standalone reproduction against
//     UmlToErmAssociationTest's own "two FK associations from the same class to the same target
//     disambiguate via role names" fixture: the FIRST declared association of the pair always
//     claims the plain class-derived default name ("member_id") regardless of whether a role is
//     set on it — the role-based fallback only triggers for the SECOND (or later) association,
//     because `fkEntity.hasAttributeNamed(defaultBaseName)` only sees a collision once the first
//     association's column already exists. So a real UML association pair here would resolve to
//     "member_id" / "recipient_id" (or "member_id" / "sender_id", depending on declaration
//     order) — never "sender_id" AND "recipient_id" together, since neither of the real schema's
//     two column names is the bare "member_id" default. Confirmed empirically against this exact
//     script during implementation (see CommunicationSchemaDriftTest's git history / PR
//     discussion for the failing-first-attempt output). Per the retrofit plan's own risk-note
//     fallback strategy (originally written for the governance domain's N>2 multi-role-FK case,
//     but applicable here at N=2 too since neither real column name equals the bare default):
//     BOTH direct_message Member-referencing FKs are modelled as plain «Column» UUID attributes
//     rather than UML associations, sidestepping the ordering-dependent default/role-fallback
//     mechanics entirely. Their real FK existence/target/nullability is still independently
//     pinned via CommunicationSchemaDriftTest's information_schema introspection.
//
// MailingMessageStatus (mailing_message.status) and DeliveryStatus
// (mailing_delivery_log.delivery_status) are this domain's two enum columns — modelled with
// explicit «Column».sqlType overrides (VARCHAR(20) / VARCHAR(30) respectively), same
// mechanism/rationale as member.status / account.role / document.access_level in the prior
// domains (real V4__communication.sql has plain VARCHAR columns, no CHECK constraints).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Communication") {
    applyProfile(ermMappingProfile)

    // Foundation-owned stub — id-only, mirrors the cross-domain-stub pattern established by
    // contribution's/document's own Member stub. Only exists here so UmlToErmTransformer can
    // resolve this domain's Member-targeting associations within this single-file evaluation.
    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val mailingMessageStatus = enumOf(name = "MailingMessageStatus") {
        literal(name = "DRAFT")
        literal(name = "QUEUED")
        literal(name = "SENT")
        literal(name = "FAILED")
    }

    val deliveryStatus = enumOf(name = "DeliveryStatus") {
        literal(name = "SENT")
        literal(name = "BOUNCED")
        literal(name = "SKIPPED_UNSUBSCRIBED")
    }

    val mailingList = classOf(name = "MailingList") {
        stereotype("Entity") { "tableName" to "mailing_list"; "kotlinObjectName" to "MailingListTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "name", type = "String") {
            stereotype("Column") { "columnName" to "name"; "sqlType" to "VARCHAR(200)" }
        }
        attribute(name = "description", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "description"; "sqlType" to "VARCHAR(1000)" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — see the file header
        // comment (association-to-FK naming would derive "member_id", not the real schema's
        // "created_by").
        attribute(name = "createdBy", type = "UUID") {
            stereotype("Column") { "columnName" to "created_by"; "fkEntity" to "Member" }
        }
    }

    val mailingListSubscription = classOf(name = "MailingListSubscription") {
        stereotype("Entity") { "tableName" to "mailing_list_subscription"; "kotlinObjectName" to "MailingListSubscriptionTable" }
        stereotype("Index") {
            "columns" to listOf("mailing_list_id", "member_id")
            "unique" to true
            "name" to "uq_mailing_subscription_list_member"
        }
        stereotype("Index") { "columns" to listOf("mailing_list_id"); "name" to "idx_mailing_subscription_list" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "subscribedAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "subscribed_at" }
        }
        attribute(name = "unsubscribedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "unsubscribed_at" }
        }
    }

    // mailing_list_subscription.mailing_list_id -> mailing_list (id): the association-derived
    // default name "mailing_list_id" (snake_case(singular("MailingList")) + "_id") matches the
    // real schema exactly.
    association(source = mailingList, target = mailingListSubscription, id = "assoc-mailing-list-subscription") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "mailingListId" }
    }

    // mailing_list_subscription.member_id -> member (id): the association-derived default name
    // "member_id" matches the real schema exactly, unlike mailing_list.created_by above.
    association(source = member, target = mailingListSubscription, id = "assoc-member-mailing-list-subscription") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val mailingMessage = classOf(name = "MailingMessage") {
        stereotype("Entity") { "tableName" to "mailing_message"; "kotlinObjectName" to "MailingMessageTable" }
        stereotype("Index") { "columns" to listOf("mailing_list_id"); "name" to "idx_mailing_message_list" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "subject", type = "String") {
            stereotype("Column") { "columnName" to "subject"; "sqlType" to "VARCHAR(300)" }
        }
        attribute(name = "bodyText", type = "String") {
            stereotype("Column") { "columnName" to "body_text"; "sqlType" to "VARCHAR(20000)" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — see the file header
        // comment (association-to-FK naming would derive "member_id", not the real schema's
        // "sent_by").
        attribute(name = "sentBy", type = "UUID") {
            stereotype("Column") { "columnName" to "sent_by"; "fkEntity" to "Member" }
        }
        attribute(name = "sentAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "sent_at" }
        }
        attribute(name = "status", type = mailingMessageStatus) {
            stereotype("Column") { "columnName" to "status"; "enumType" to "network.lapis.cloud.shared.domain.MailingMessageStatus" }
        }
    }

    // mailing_message.mailing_list_id -> mailing_list (id): the association-derived default name
    // matches the real schema exactly.
    association(source = mailingList, target = mailingMessage, id = "assoc-mailing-list-message") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "mailingListId" }
    }

    val mailingDeliveryLog = classOf(name = "MailingDeliveryLog") {
        stereotype("Entity") { "tableName" to "mailing_delivery_log"; "kotlinObjectName" to "MailingDeliveryLogTable" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "deliveredAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "delivered_at" }
        }
        attribute(name = "deliveryStatus", type = deliveryStatus) {
            stereotype("Column") { "columnName" to "delivery_status"; "enumType" to "network.lapis.cloud.shared.domain.DeliveryStatus" }
        }
    }

    // mailing_delivery_log.mailing_message_id -> mailing_message (id): the association-derived
    // default name matches the real schema exactly.
    association(source = mailingMessage, target = mailingDeliveryLog, id = "assoc-mailing-message-delivery-log") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "mailingMessageId" }
    }

    // mailing_delivery_log.member_id -> member (id): the association-derived default name
    // matches the real schema exactly.
    association(source = member, target = mailingDeliveryLog, id = "assoc-member-mailing-delivery-log") {
        source { multiplicity("1") }
        target { multiplicity("0..*"); role = "memberId" }
    }

    val directMessage = classOf(name = "DirectMessage") {
        stereotype("Entity") { "tableName" to "direct_message"; "kotlinObjectName" to "DirectMessageTable" }
        stereotype("Index") { "columns" to listOf("recipient_id"); "name" to "idx_direct_message_recipient" }
        stereotype("Index") { "columns" to listOf("sender_id"); "name" to "idx_direct_message_sender" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "body", type = "String") {
            stereotype("Column") { "columnName" to "body"; "sqlType" to "VARCHAR(10000)" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute rather than a UML
        // association — see the file header comment (point 3): the two-associations-to-the-
        // same-target collision case, where a real association pair would resolve to
        // "member_id"/"<role>_id" rather than "sender_id"/"recipient_id" together.
        attribute(name = "senderId", type = "UUID") {
            stereotype("Column") { "columnName" to "sender_id"; "fkEntity" to "Member" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute — same rationale as
        // senderId above.
        attribute(name = "recipientId", type = "UUID") {
            stereotype("Column") { "columnName" to "recipient_id"; "fkEntity" to "Member" }
        }
        attribute(name = "sentAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "sent_at" }
        }
        attribute(name = "readAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "read_at" }
        }
    }
}
