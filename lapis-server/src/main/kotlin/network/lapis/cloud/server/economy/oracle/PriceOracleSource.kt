package network.lapis.cloud.server.economy.oracle

import network.lapis.cloud.shared.domain.AnchorAsset
import java.math.BigDecimal
import kotlin.time.Instant

/**
 * One data point a [PriceOracleSource] returned -- the price of one unit of [PriceOracleSource.anchor]
 * denominated in the requested donation currency, plus when the source itself says the price was
 * observed (used as the [network.lapis.cloud.server.economy.oracle.PriceQuote.priceTimestamp] on a
 * successful quote).
 */
data class SourcePriceResult(
    val sourceId: String,
    val price: BigDecimal,
    val observedAt: Instant,
)

/**
 * The pluggable price-source boundary for [PriceOracleOrchestrator] -- this wave's analogue of
 * [network.lapis.cloud.server.economy.LtrBalanceProvider]/
 * [network.lapis.cloud.server.postal.PostalMailProvider]. Every implementation must be an
 * independent, no-authentication, publicly reachable price feed -- see
 * `network.lapis.cloud.server.economy.oracle.defaultBitcoinOracleSources` for the three concrete
 * `AnchorAsset.BITCOIN_BTC` implementations this wave wires (Coinbase/Kraken/Bitstamp).
 *
 * **`fetchPrice` must NEVER throw.** Every failure path (network error, timeout, non-2xx status,
 * an unparseable/missing-field response body, an oversized response, a non-allowlisted/non-HTTPS
 * URL) is caught inside the implementation and mapped to `null` -- so one misbehaving source can
 * never abort [PriceOracleOrchestrator.currentQuote]'s fan-out across every other source. Same
 * "every failure path maps to a sentinel result, never propagates" discipline
 * [network.lapis.cloud.server.postal.LetterxpressPostalMailProvider] already establishes for its
 * own outbound HTTP call.
 */
interface PriceOracleSource {
    /** Stable audit id (e.g. `"coinbase"`) -- persisted verbatim into `price_oracle_conversion.sources_used`, never a display name. */
    val id: String

    val anchor: AnchorAsset

    /**
     * The current price of one unit of [anchor] in [donationCurrency] (an ISO-4217 code, e.g.
     * `"EUR"`), or `null` on any failure -- see interface KDoc "must NEVER throw".
     */
    suspend fun fetchPrice(donationCurrency: String): SourcePriceResult?
}
