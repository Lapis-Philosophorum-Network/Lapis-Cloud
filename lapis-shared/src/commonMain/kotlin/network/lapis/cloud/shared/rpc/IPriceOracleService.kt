package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.DonationConversionInput
import network.lapis.cloud.shared.domain.OraclePriceStatusDto
import network.lapis.cloud.shared.domain.PriceOracleConfigDto
import network.lapis.cloud.shared.domain.PriceOracleConfigInput
import network.lapis.cloud.shared.domain.PriceOracleConversionDto

/**
 * V0.6.5 Price-Oracle fuer die Anker-Bindung -- see `19-price-oracle.kuml.kts` file header for the
 * full fachlich model. Makes the anchor-asset peg load-bearing by adding the first real
 * money -> LTR conversion boundary this codebase has ever had ([convertDonationToLtr]).
 *
 * **Scope-cut (halt-queue)**: the concept document's persistent halt-queue with deferred/
 * retroactive-vs-forward pricing is NOT built. HALT means [convertDonationToLtr] REJECTS the
 * request (throws, mints nothing) instead of queueing it for later re-pricing -- fully satisfying
 * "conversions must halt rather than silently use a stale/unreliable price" without a
 * queueing/retro-booking mechanism. `PriceStatus.DEFERRED` is reserved-but-unused for a later wave.
 *
 * **Scope-cut (anchor coverage)**: only `AnchorAsset.BITCOIN_BTC` has real, wired price sources.
 * [updateOracleConfig] rejects any other `anchorAsset` with a clear "not yet implemented" error --
 * see `network.lapis.cloud.server.economy.oracle.PriceOracleSource` KDoc for the full reasoning.
 *
 * **Scope-cut (payment intake)**: [convertDonationToLtr] is an operator-triggered booking of an
 * already-received donation (same tier as [ILtrLedgerService.mintLtr]), not a PSP-webhook intake
 * -- no automatic payment-gateway integration exists or is planned this wave.
 */
@RpcService
interface IPriceOracleService {
    /** Role: TREASURER/BOARD/ADMIN. Reads the single seeded oracle-policy row. */
    suspend fun getOracleConfig(): PriceOracleConfigDto

    /**
     * Role: ADMIN only (same tier as [IOrganizationSettingsService.updateOrganizationSettings]).
     * Replaces every field wholesale. Rejects an unsupported [DonationConversionInput]-unrelated
     * `anchorAsset` (only `BITCOIN_BTC` is wired -- see interface KDoc "Scope-cut"), an invalid
     * `donationCurrency`, `minQuorum < 2`, a non-positive `cacheTtlSeconds`/`anchorUnitsPerLtr`, an
     * out-of-range `outlierThresholdBps`, or a `maxSpreadBps` below `outlierThresholdBps`.
     */
    suspend fun updateOracleConfig(input: PriceOracleConfigInput): PriceOracleConfigDto

    /**
     * Role: TREASURER/BOARD/ADMIN. Diagnostic read of the oracle's CURRENT price quote (or halt
     * status) -- lets an operator check oracle health WITHOUT minting anything. Never writes.
     */
    suspend fun previewCurrentPrice(): OraclePriceStatusDto

    /**
     * Role: TREASURER/BOARD/ADMIN. The load-bearing conversion path: fetches a current oracle
     * quote for the active anchor, and if (and only if) it is NOT halted, MINTs the computed LTR
     * amount into [DonationConversionInput.memberId]'s ledger (a real `MINT` `ltr_ledger_entry`
     * row) and writes a permanent `price_oracle_conversion` provenance row in the SAME
     * transaction. If the oracle quote is halted, THROWS -- no ledger row, no provenance row, no
     * partial state -- see interface KDoc "Scope-cut (halt-queue)".
     */
    suspend fun convertDonationToLtr(input: DonationConversionInput): PriceOracleConversionDto
}
