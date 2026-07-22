package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/** Persisted lifecycle state of a `politician_profile` row -- flipped only by an explicit BOARD/ADMIN [network.lapis.cloud.shared.rpc.IPoliticianService.grantPoliticianStatus]/[network.lapis.cloud.shared.rpc.IPoliticianService.revokePoliticianStatus] call, never automatically. */
@Serializable
enum class PoliticianProfileStatus { ACTIVE, FORMER }

/** One member's Like/Dislike on one politician's profile -- the "Korb" input the shared LTR-weight pool is split by. See `20-politician.kuml.kts` file header. */
@Serializable
enum class PoliticianReactionValue { LIKE, DISLIKE }

/**
 * A politician's profile. [memberTrustWeight]/[memberLikeCount]/[memberDislikeCount] are always
 * computed fresh on read from the current LTR ledger and [PoliticianReactionDto] rows (see
 * `network.lapis.cloud.server.rpc.PoliticianTrustWeightCalculator`) -- never a cached/persisted
 * snapshot; a manually-triggered historical trace of them is available separately via
 * [network.lapis.cloud.shared.rpc.IPoliticianService.getWeightHistory]
 * ([PoliticianWeightSnapshotDto]).
 *
 * **Member-only weight, deliberately no `guestTrustWeight`/`combinedTrustWeight` field this wave**
 * -- see `20-politician.kuml.kts` file header "Scope-cut: member-only rating, no Gast basket".
 * [memberTrustWeight] IS the full weight today; a future wave adds the guest-basket fields
 * additively once an operational Gast identity exists, rather than this field being renamed.
 */
@Serializable
data class PoliticianProfileDto(
    val id: String,
    val memberId: String,
    val displayName: String,
    val status: PoliticianProfileStatus,
    val mandateText: String?,
    val grantedAt: LocalDateTime,
    val grantedByDisplayName: String,
    val revokedAt: LocalDateTime?,
    val revokedByDisplayName: String?,
    val memberTrustWeight: Decimal,
    val memberLikeCount: Int,
    val memberDislikeCount: Int,
)

@Serializable
data class PoliticianReactionDto(
    val id: String,
    val politicianMemberId: String,
    val value: PoliticianReactionValue,
    val castAt: LocalDateTime,
)

/** One manually-triggered monthly snapshot of a politician's computed weight -- see `20-politician.kuml.kts` file header "politician_weight_snapshot is a manually-triggered historical record". */
@Serializable
data class PoliticianWeightSnapshotDto(
    val id: String,
    val politicianMemberId: String,
    val periodMonth: LocalDate,
    val memberTrustWeight: Decimal,
    val memberLikeCount: Int,
    val memberDislikeCount: Int,
    val computedAt: LocalDateTime,
)
