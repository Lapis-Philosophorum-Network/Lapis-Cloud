package network.lapis.cloud.server.audit

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * Pure SHA-256 hash-chain computation for the GoBD audit log (V0.5.3) -- DB-free, no `transaction
 * {}` dependency, testable in isolation. See `network.lapis.cloud.server.audit.AuditLogRecorder`
 * KDoc for how this is wired into the write path and
 * `lapis-server/src/main/kuml/14-audit-log.kuml.kts`'s file header for the chain-integrity
 * rationale.
 *
 * Same "pure Calculator, DB-free" idiom as `network.lapis.cloud.server.rpc.JournalEntryBalance` /
 * `GeneralLedgerCalculator`.
 */
object AuditHashChain {
    /** Placeholder folded into [canonicalPayload] in place of a real [ChainInput.previousEntryHash] for the genesis row. */
    const val GENESIS_MARKER = "GENESIS"

    /**
     * Unicode `U+0001` ("start of heading" control character) used to join [canonicalPayload]'s
     * fields -- cannot appear in any of [ChainInput]'s own string fields under normal application
     * usage (unlike e.g. a comma or pipe), so no field's content can be crafted to shift a later
     * field's boundary and produce a hash collision between two semantically different inputs.
     */
    private const val FIELD_SEPARATOR: Char = '\u0001'

    /** Every field a hash-chained [network.lapis.cloud.server.db.generated.AuditLogEntryTable] row commits to. */
    data class ChainInput(
        val sequenceNumber: Long,
        val occurredAt: LocalDateTime,
        val actorMemberId: Uuid?,
        val actorRole: AccountRole?,
        val entityType: AuditEntityType,
        val entityId: Uuid,
        val action: AuditAction,
        val beforeSnapshot: String?,
        val afterSnapshot: String?,
        val previousEntryHash: String?,
    )

    /**
     * Deterministic, field-separated payload -- deliberately NOT a JSON re-encoding of [input]
     * (that would make the hash depend on kotlinx.serialization's field-ordering stability across
     * library versions, an unnecessary and fragile coupling). See [FIELD_SEPARATOR] for the
     * field-boundary-shift argument. [ChainInput.previousEntryHash] folds to [GENESIS_MARKER] when
     * `null` (the very first/"genesis" row) so that row's hash still depends on its own position in
     * the chain, rather than silently collapsing to the same payload an empty-string previous hash
     * would produce.
     */
    fun canonicalPayload(input: ChainInput): String =
        with(input) {
            listOf(
                sequenceNumber.toString(),
                occurredAt.toString(),
                actorMemberId?.toString().orEmpty(),
                actorRole?.name.orEmpty(),
                entityType.name,
                entityId.toString(),
                action.name,
                beforeSnapshot.orEmpty(),
                afterSnapshot.orEmpty(),
                previousEntryHash ?: GENESIS_MARKER,
            ).joinToString(FIELD_SEPARATOR.toString())
        }

    /**
     * SHA-256 of [canonicalPayload], lowercase hex-encoded (64 chars, matching the
     * `entry_hash`/`previous_entry_hash` `VARCHAR(64)` columns). A fresh [MessageDigest] instance
     * is created on every call -- [MessageDigest] is NOT thread-safe, and this object has no
     * per-instance or shared mutable digest state that could leak between concurrent callers
     * (standard security checklist: Kryptografie).
     */
    fun computeHash(input: ChainInput): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(canonicalPayload(input).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
