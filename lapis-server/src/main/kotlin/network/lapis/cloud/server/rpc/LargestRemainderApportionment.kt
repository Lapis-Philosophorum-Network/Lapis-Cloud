package network.lapis.cloud.server.rpc

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.uuid.Uuid

private val ZERO_2DP: BigDecimal = BigDecimal.ZERO.setScale(2)

/**
 * Largest-remainder ("Hare-Niemeyer") apportionment of a fixed [BigDecimal] pool among a set of
 * non-negative weights, extracted from [VoteSettlement]'s `allocateProportional` (V0.2.3
 * Meritokratische Voteen) so V0.6.1's `CrowdfundingDistributionCalculator` can reuse the exact
 * same rounding-critical logic instead of a parallel re-implementation — see that Kotlin file's
 * own KDoc history for why this extraction, not a duplicate, is the safer path (a second
 * hand-written largest-remainder implementation risks a subtly different tie-break and would be
 * a second place to get the cent-rounding invariant wrong).
 *
 * All monetary/weight amounts here (LTR in [VoteSettlement], EUR in
 * `CrowdfundingDistributionCalculator`) are scale-2 decimals ("whole cents"), so converting to
 * [BigInteger] cent counts is always exact — every subsequent computation
 * (`weightCents * poolCents`, integer-divided by `totalWeightCents`) is exact [BigInteger]
 * arithmetic too. The only place any rounding *decision* is made at all is the final
 * one-cent-at-a-time apportionment of the leftover remainder below.
 */
internal object LargestRemainderApportionment {
    /**
     * Splits [pool] proportionally across [weights] (each entry's share of the total weight),
     * guaranteeing `Σ result.values == pool` **exactly** — no cent is ever created or destroyed
     * by rounding. Returns an empty map for empty [weights]; returns every key mapped to
     * [BigDecimal.ZERO] (scale 2) if [pool] is zero or every weight is zero (nothing to
     * apportion, but every participant is still represented in the result with a zero share).
     *
     * Deterministic: the same [weights]/[pool] input always produces the same output. Ties in
     * the largest-remainder tie-break are broken by ascending key (`Uuid.toString()`) — same
     * "member id ascending" convention [VoteSettlement] already established.
     */
    fun apportion(
        weights: Map<Uuid, BigDecimal>,
        pool: BigDecimal,
    ): Map<Uuid, BigDecimal> {
        if (weights.isEmpty()) return emptyMap()

        val totalWeight = weights.values.fold(BigDecimal.ZERO) { acc, w -> acc + w }
        if (pool.signum() == 0 || totalWeight.signum() == 0) {
            return weights.keys.associateWith { ZERO_2DP }
        }

        val poolCents = pool.movePointRight(2).toBigIntegerExact()
        val totalWeightCents = totalWeight.movePointRight(2).toBigIntegerExact()

        // Deterministic base order: key, ascending. Both the floor pass and the largest-remainder
        // tie-break below iterate in this order so equal inputs always produce identical output.
        val ordered = weights.entries.sortedBy { it.key.toString() }

        data class Share(
            val key: Uuid,
            val floorCents: BigInteger,
            val remainderCents: BigInteger,
        )

        val shares =
            ordered.map { (key, weight) ->
                val weightCents = weight.movePointRight(2).toBigIntegerExact()
                val numerator = weightCents * poolCents
                val floorCents = numerator / totalWeightCents
                val remainderCents = numerator - floorCents * totalWeightCents
                Share(key, floorCents, remainderCents)
            }

        val floorSumCents = shares.fold(BigInteger.ZERO) { acc, s -> acc + s.floorCents }
        var leftoverCents = (poolCents - floorSumCents).toInt()

        val amounts = shares.associateTo(LinkedHashMap()) { it.key to it.floorCents }

        // Distribute the leftover cents one at a time to the largest-remainder shares first (key
        // ascending as the deterministic tie-break for equal remainders); wraps around if
        // leftoverCents ever exceeded the share count (cannot happen mathematically here, since
        // each floor loses strictly less than 1 cent, but the loop stays correct regardless).
        val byRemainderDesc = shares.sortedWith(compareByDescending<Share> { it.remainderCents }.thenBy { it.key.toString() })
        var idx = 0
        while (leftoverCents > 0 && byRemainderDesc.isNotEmpty()) {
            val target = byRemainderDesc[idx % byRemainderDesc.size].key
            amounts[target] = amounts.getValue(target) + BigInteger.ONE
            leftoverCents--
            idx++
        }

        return amounts.mapValues { (_, cents) -> BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY) }
    }
}
