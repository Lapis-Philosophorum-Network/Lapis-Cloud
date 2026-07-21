// Direkte LTR-Peer-to-Peer-Uebertragung domain (V0.6.3) -- see the concept document
// ("03 Bereiche/Lapis Cloud/Meritokratisches System und Libertaler.md", "Direkte LTR-Übertragung
// (Peer-to-Peer-Transfer)" section, vault) for the full fachlich specification this implements.
//
// **Split from ltr_ledger_entry, not a widening of it**: see 08-ltr-balance.kuml.kts's own header
// for why this wave's stored self-reported legal characterization
// (Schenkung/Honorar/Privatverkauf/Sonstiges) plus optional purpose text live here, in a dedicated
// business table, rather than as new nullable columns on the generic, polymorphic
// `ltr_ledger_entry`. Every transfer writes exactly ONE `peer_transfer` row plus TWO
// `ltr_ledger_entry` rows (entryType=PEER_TRANSFER_OUT at the sender, entryType=PEER_TRANSFER_IN
// at the recipient, both with referenceType=PEER_TRANSFER/referenceId=this row's id) -- same
// "business table carries fachlich detail, ledger carries only the generic signed booking effect"
// split `crowdfunding_project`/`PROJECT_STAKE` already established.
//
// **Scope-cut: member-to-member only.** The concept document allows a Gast recipient (LTR
// ownership is not bound to membership), but this codebase has no Gast/guest identity model yet
// (V0.7-dependent, not built). `recipient_member_id` is therefore a real, NOT NULL FK -> member,
// never a nullable/polymorphic pointer -- a Gast recipient is a later, V0.7-dependent extension,
// not simulated here. See network.lapis.cloud.shared.rpc.IPeerTransferService KDoc.
//
// **Scope-cut: no public pseudonymous visibility layer.** The concept document envisions full
// public visibility under a pseudonym; this codebase has no pseudonym-display layer at all (only
// real member.display_name everywhere). Read access for `peer_transfer` rows is therefore NOT
// modelled via any dedicated read path here -- PEER_TRANSFER_OUT/IN ltr_ledger_entry rows already
// surface in the existing ILtrLedgerService.listMyEntries/listMemberEntries history (own entries
// for every member, all entries for TREASURER/BOARD/ADMIN), reused as-is rather than duplicating a
// second, parallel read stream. See network.lapis.cloud.shared.rpc.IPeerTransferService KDoc.
//
// **No storno/revert column or table anywhere**: a completed transfer is final by design (see
// concept document "Unwiderruflichkeit"). Correction happens exclusively through a second,
// REGULAR transfer in the opposite direction, executed by a privileged Finanzvorstand/Schiedsstelle
// role via the same `peer_transfer` shape -- `initiated_by` (below) distinguishes that privileged,
// third-party-initiated transfer from an ordinary self-initiated one; there is no separate table,
// status column, or "reversed" flag.
//
// **`characterization` is a self-reported Selbstauskunft, not an automatic steuerrechtliche
// Einordnung** -- same "current understanding, not a legal specification, verify before
// production use" disclaimer class network.lapis.cloud.server.rpc.PartyDonationComplianceCalculator
// already carries for its own threshold constants. The platform stores the member's choice
// verbatim and performs no tax classification of its own.
//
// **`initiated_by`** (nullable FK -> member) is null for an ordinary self-initiated transfer
// (`IPeerTransferService.transferLtr`) and set to the acting TREASURER/BOARD/ADMIN's id for a
// privileged arbitration correction (`IPeerTransferService.executeArbitrationTransfer`) -- exactly
// mirrors `ltr_ledger_entry.created_by`'s own null-for-self-service/set-for-privileged-actor
// convention (see 08-ltr-balance.kuml.kts). Both `ltr_ledger_entry` rows this wave's service
// writes carry the SAME `created_by` value as this row's `initiated_by`.
//
// **FK-naming choice for `recipient_member_id`/`sender_member_id`/`initiated_by`**: all three
// modelled as plain «Column» UUID attributes with «Column».fkEntity="Member", NOT UML
// associations -- this entity has THREE separate FKs to `member`, and an association's
// class-derived default column name ("member_id") could only ever match ONE of them; using plain
// «Column» attributes for all three avoids relying on an association `role` override to rename
// the FK column away from that default (a mechanism this codebase has never actually exercised --
// every existing `role = "..."` assignment across every domain file merely restates the already-
// matching class-derived default, never renames away from it). Exactly the same idiom
// `crowdfunding_project.submitter_member_id`/`reviewed_by` and
// `crowdfunding_distribution.triggered_by` already use for the identical "differently-named FK to
// Member, no association" situation (see 17-crowdfunding.kuml.kts).
//
// This file, symmetrically, carries a minimal id-only Member stub (owned by Foundation) purely so
// UmlToErmTransformer can resolve all three «Column».fkEntity overrides within this single-file
// evaluation -- same cross-domain-stub pattern every other domain in this codebase already
// establishes.
//
// amount_ltr uses an explicit «Column».sqlType="DECIMAL(18,2)" override, same precision as
// ltr_ledger_entry.amount_ltr (see that file's own header for why UmlErmTypeMapper's bare
// "decimal" keyword is insufficient here).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "PeerTransfer") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    // Self-reported Selbstauskunft, not an automatic tax classification -- see file header.
    val peerTransferCharacterization = enumOf(name = "PeerTransferCharacterization") {
        literal(name = "SCHENKUNG")
        literal(name = "HONORAR")
        literal(name = "PRIVATVERKAUF")
        literal(name = "SONSTIGES")
    }

    val peerTransfer = classOf(name = "PeerTransfer") {
        stereotype("Entity") { "tableName" to "peer_transfer"; "kotlinObjectName" to "PeerTransferTable" }
        stereotype("Index") { "columns" to listOf("sender_member_id"); "name" to "idx_peer_transfer_sender" }
        stereotype("Index") { "columns" to listOf("recipient_member_id"); "name" to "idx_peer_transfer_recipient" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "amountLtr", type = "BigDecimal") {
            stereotype("Column") { "columnName" to "amount_ltr"; "sqlType" to "DECIMAL(18,2)" }
        }
        attribute(name = "characterization", type = peerTransferCharacterization) {
            stereotype("Column") {
                "columnName" to "characterization"
                "enumType" to "network.lapis.cloud.shared.domain.PeerTransferCharacterization"
            }
        }
        attribute(name = "purpose", type = "String") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "purpose"; "sqlType" to "VARCHAR(500)" }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // "FK-naming choice".
        attribute(name = "senderMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "sender_member_id"; "fkEntity" to "Member" }
        }
        // Real FK -> member (id), NOT NULL. Deliberately NOT nullable/polymorphic -- see file
        // header "Scope-cut: member-to-member only" (no Gast identity model exists yet).
        attribute(name = "recipientMemberId", type = "UUID") {
            stereotype("Column") { "columnName" to "recipient_member_id"; "fkEntity" to "Member" }
        }
        // Real FK -> member (id), nullable -- null for a self-initiated transfer, set to the
        // acting TREASURER/BOARD/ADMIN for a privileged arbitration correction. See file header.
        attribute(name = "initiatedBy", type = "UUID") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "initiated_by"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
    }
}
