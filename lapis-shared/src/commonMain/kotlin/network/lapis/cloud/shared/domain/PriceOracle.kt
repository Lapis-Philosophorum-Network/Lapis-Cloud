package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * The real-world asset a Libertaler (LTR) is pegged to -- see
 * `network.lapis.cloud.server.economy.oracle.PriceOracleSource` KDoc and
 * `lapis-server/src/main/kuml/19-price-oracle.kuml.kts` file header for the full fachlich model.
 * Only [BITCOIN_BTC] has real, wired price sources this wave (V0.6.5) -- three independent, free,
 * no-API-key public endpoints (Coinbase/Kraken/Bitstamp). [GOLD_XAU]/[FIAT] are deliberately
 * unimplemented enum literals: a robust free, no-API-key, >=2-independent-source feed set for spot
 * gold requires paid API keys this codebase has no secret-management story for yet, and fiat
 * cross-rates have essentially one authoritative free source (ECB reference rates) -- a
 * degenerate single-source case that cannot satisfy the >=2-source quorum this design leans on.
 * [network.lapis.cloud.shared.rpc.IPriceOracleService.updateOracleConfig] rejects any non-
 * [BITCOIN_BTC] value with a clear "not yet implemented" error. Additively extensible -- literal
 * order is pinned by `PriceOracleSchemaDriftTest`.
 */
@Serializable
enum class AnchorAsset { BITCOIN_BTC, GOLD_XAU, FIAT }

/**
 * The trustworthiness of a [OraclePriceStatusDto]/[PriceOracleConversionDto] price quote --
 * [LIVE] (every configured source for the active anchor agreed within the outlier threshold),
 * [DEGRADED] (at least [PriceOracleConfigDto.minQuorum] sources survived, but fewer than every
 * configured source), or [CACHED] (live quorum could not be reached, but a still-fresh cached
 * price -- within [PriceOracleConfigDto.cacheTtlSeconds] -- was used instead). There is
 * deliberately no HALT literal here: a halted quote mints nothing and writes no
 * [PriceOracleConversionDto] row at all, so HALT never needs to be a stored value -- see
 * `network.lapis.cloud.server.rpc.PriceOracleService.convertDonationToLtr` KDoc.
 *
 * [DEFERRED] is reserved-but-unused (no code path ever writes it) -- see
 * `19-price-oracle.kuml.kts` file header "Scope-cut: no persistent halt-queue" for why the
 * concept document's persistent halt-queue/deferred-retroactive-pricing mechanism is not built
 * this wave, and why the literal is nonetheless defined now (a later wave can add the queue
 * without an enum-literal-order-breaking re-model). Additively extensible -- literal order is
 * pinned by `PriceOracleSchemaDriftTest`.
 */
@Serializable
enum class PriceStatus { LIVE, DEGRADED, CACHED, DEFERRED }

/**
 * Single-row, ADMIN-tunable oracle policy -- see `19-price-oracle.kuml.kts` file header. No
 * create/delete RPC, only
 * [network.lapis.cloud.shared.rpc.IPriceOracleService.getOracleConfig]/
 * [network.lapis.cloud.shared.rpc.IPriceOracleService.updateOracleConfig], both always targeting
 * the one seeded row. **SSRF invariant**: this DTO exposes no URL/host/source field on purpose --
 * price sources stay a code-fixed, allowlisted set
 * (`network.lapis.cloud.server.economy.oracle.defaultBitcoinOracleSources`); an ADMIN tunes only
 * scalar policy (which anchor/currency, the peg, cache TTL, quorum, outlier/spread thresholds).
 * [anchorUnitsPerLtr] is the peg (how many [anchorAsset] units back exactly one LTR) -- a much
 * higher-precision decimal than every other money field in this codebase, since one LTR is
 * expected to be worth a tiny fraction of one BTC.
 */
@Serializable
data class PriceOracleConfigDto(
    val id: String,
    val anchorAsset: AnchorAsset,
    val donationCurrency: String,
    val anchorUnitsPerLtr: Decimal,
    val cacheTtlSeconds: Int,
    val minQuorum: Int,
    val outlierThresholdBps: Int,
    val maxSpreadBps: Int,
    val updatedAt: LocalDateTime,
)

/** Replaces every field of the single [PriceOracleConfigDto] row wholesale (no partial update). Role: ADMIN only -- see [PriceOracleConfigDto] KDoc "SSRF invariant". */
@Serializable
data class PriceOracleConfigInput(
    val anchorAsset: AnchorAsset,
    val donationCurrency: String,
    val anchorUnitsPerLtr: Decimal,
    val cacheTtlSeconds: Int,
    val minQuorum: Int,
    val outlierThresholdBps: Int,
    val maxSpreadBps: Int,
)

/**
 * A diagnostic read of the oracle's CURRENT price quote -- never mints anything (see
 * [network.lapis.cloud.shared.rpc.IPriceOracleService.previewCurrentPrice]). Exactly one of
 * ([status]/[medianPrice]/[priceTimestamp] non-null, [halted] == false) or ([halted] == true,
 * [haltReason] non-null, the other three null) holds.
 */
@Serializable
data class OraclePriceStatusDto(
    val status: PriceStatus?,
    val halted: Boolean,
    val haltReason: String?,
    val medianPrice: Decimal?,
    val sourceIds: List<String>,
    val priceTimestamp: LocalDateTime?,
)

/**
 * Role: TREASURER/BOARD/ADMIN (same tier as [MintLtrInput] -- a real payment-intake webhook is
 * out of scope this wave, this is the operator-triggered booking of an already-received
 * donation). [donationAmount] must be strictly positive with at most 2 decimal places, denominated
 * in [PriceOracleConfigDto.donationCurrency].
 */
@Serializable
data class DonationConversionInput(
    val memberId: String,
    val donationAmount: Decimal,
    val note: String? = null,
)

/**
 * The permanent provenance record of one donation -> LTR conversion -- see
 * `19-price-oracle.kuml.kts` file header "Trust und Auditierbarkeit" paragraph.
 * [ltrLedgerEntryId] points at the `MINT` [LtrLedgerEntryDto] this conversion caused (written in
 * the SAME transaction). [priceStatus] is never a halt-representing value -- a halted quote mints
 * nothing and this row is never written for it.
 */
@Serializable
data class PriceOracleConversionDto(
    val id: String,
    val memberId: String,
    val donationAmount: Decimal,
    val donationCurrency: String,
    val anchorAsset: AnchorAsset,
    val anchorPrice: Decimal,
    val anchorUnitsPerLtr: Decimal,
    val ltrMinted: Decimal,
    val priceStatus: PriceStatus,
    val sourceCount: Int,
    val sourcesUsed: String,
    val priceTimestamp: LocalDateTime,
    val ltrLedgerEntryId: String,
    val createdById: String?,
    val createdAt: LocalDateTime,
)
