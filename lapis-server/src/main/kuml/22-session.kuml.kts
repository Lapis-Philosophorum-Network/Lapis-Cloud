// Server-side, DB-persisted, revocable session domain (V0.7.1 Authentifizierung) -- see
// network.lapis.cloud.server.security.SessionStore KDoc and IAuthService KDoc for the full
// fachlich model. Replaces the X-Member-Id stand-in (see 00-foundation.kuml.kts / RequestContext
// KDoc) with real password-login sessions -- see network.lapis.cloud.server.security.
// RequestContext.resolveCurrentMember for the ONE switch point this feeds.
//
// **Session vs. stateless JWT**: chosen deliberately server-side/DB-persisted/revocable, NOT a
// stateless JWT -- this codebase makes auditability and revocability first-class (V0.5.3 GoBD
// audit-log chain, V0.5.4 backup); logout and password-change must invalidate a token server-side
// immediately, which a stateless JWT cannot do without its own revocation list (which reintroduces
// the DB round-trip a JWT was meant to avoid in the first place). See SessionStore KDoc.
//
// **Only a hash of the token is ever stored** -- token_hash is SHA-256(rawToken), never the raw,
// bearer-usable token itself. A leaked/backed-up database row can never be replayed as a session
// (see SessionStore.createSession/resolve KDoc, and the codebase's standing security checklist
// "Kryptografie").
//
// **FK-naming choice for `member_id`**: modelled as a plain «Column» UUID attribute with
// «Column».fkEntity="Member", NOT a UML association -- same idiom 18-peer-transfer.kuml.kts's own
// file header documents at length ("an association's class-derived default column name could only
// ever match ONE FK, and this codebase has never actually exercised the role-override-renames-the-
// column mechanism"). Exactly one FK here, but the same plain-«Column» idiom is used for
// consistency with every other domain file that needs this pattern, and because a session's
// member_id is looked up by an unauthenticated caller's token (never resolved via
// resolveCurrentMember first) -- same "first ungated client input reaching a FK-target lookup"
// shape peer_transfer's recipient_member_id already established.
//
// This file, symmetrically, carries a minimal id-only Member stub (owned by Foundation) purely so
// UmlToErmTransformer can resolve the «Column».fkEntity override within this single-file
// evaluation -- same cross-domain-stub pattern every other domain in this codebase already
// establishes (see 18-peer-transfer.kuml.kts's own file header).
//
// token_hash is VARCHAR(64) (SHA-256 hex digest length) and unique -- same plain-«Column».unique
// idiom member.email already uses in 00-foundation.kuml.kts (UmlToErmTransformer.addForeignKey
// always synthesizes association-derived FK columns with unique=false, but a plain, non-FK
// «Column» attribute CAN carry «Column».unique directly).
//
// last_used_at/revoked_at are both nullable -- last_used_at is null until the first resolve()
// touches the row, revoked_at is null for a still-live session and set exactly once by
// SessionStore.revoke/revokeAllForMember (logout, or a password change invalidating every OTHER
// session of that member). No separate "status" enum column -- exactly the same "compute liveness
// from nullable timestamp columns, no separate status flag" idiom this codebase already prefers
// elsewhere (e.g. erasure_request/crowdfunding_project use dedicated status enums where the
// concept genuinely has more than two states; a session's liveness is fully determined by
// revoked_at IS NULL AND expires_at > now(), so a redundant status enum would just be another
// place the two could silently drift apart).
import dev.kuml.profile.erm.ermMappingProfile
import dev.kuml.uml.Multiplicity
import dev.kuml.uml.dsl.applyProfile
import dev.kuml.uml.dsl.stereotype

classDiagram(name = "Session") {
    applyProfile(ermMappingProfile)

    val member = classOf(name = "Member") {
        stereotype("Entity") { "tableName" to "member"; "kotlinObjectName" to "MemberTable" }
        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
    }

    val session = classOf(name = "Session") {
        stereotype("Entity") { "tableName" to "session"; "kotlinObjectName" to "SessionTable" }
        stereotype("Index") { "columns" to listOf("member_id"); "name" to "idx_session_member_id" }
        stereotype("Index") { "columns" to listOf("expires_at"); "name" to "idx_session_expires_at" }

        attribute(name = "id", type = "UUID") {
            stereotype("Id")
            stereotype("Column") { "columnName" to "id" }
        }
        attribute(name = "tokenHash", type = "String") {
            stereotype("Column") { "columnName" to "token_hash"; "sqlType" to "VARCHAR(64)"; "unique" to true }
        }
        // Real FK -> member (id), NOT NULL. Plain «Column» UUID attribute -- see file header
        // "FK-naming choice".
        attribute(name = "memberId", type = "UUID") {
            stereotype("Column") { "columnName" to "member_id"; "fkEntity" to "Member" }
        }
        attribute(name = "createdAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "created_at" }
        }
        attribute(name = "expiresAt", type = "LocalDateTime") {
            stereotype("Column") { "columnName" to "expires_at" }
        }
        attribute(name = "lastUsedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "last_used_at" }
        }
        attribute(name = "revokedAt", type = "LocalDateTime") {
            multiplicity = Multiplicity(0, 1)
            stereotype("Column") { "columnName" to "revoked_at" }
        }
    }
}
