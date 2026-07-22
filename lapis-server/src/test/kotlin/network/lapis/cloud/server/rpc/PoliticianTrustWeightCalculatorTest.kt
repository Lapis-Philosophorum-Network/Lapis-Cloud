package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.PoliticianReactionValue
import java.math.BigDecimal
import kotlin.uuid.Uuid

private val ZERO_2DP: BigDecimal = BigDecimal.ZERO.setScale(2)

/**
 * Pure unit tests for [PoliticianTrustWeightCalculator] -- fed synthetic korb/balance maps
 * directly, no database, same style [LargestRemainderApportionmentTest] already establishes for
 * its own extraction target. See that calculator's KDoc for the "single shared pool, split by
 * ratio" algorithm this exercises.
 */
class PoliticianTrustWeightCalculatorTest :
    FunSpec({
        test("two politicians, unequal baskets -- pool splits proportionally, sum equals pool exactly") {
            val politicianA = Uuid.random()
            val politicianB = Uuid.random()
            val rater1 = Uuid.random()
            val rater2 = Uuid.random()
            val rater3 = Uuid.random()

            // A: 2 likes (rater1, rater2) -> korb 2. B: 1 like (rater3) -> korb 1. Total korb 3.
            val reactionsByProfile =
                mapOf(
                    politicianA to
                        listOf(
                            rater1 to PoliticianReactionValue.LIKE,
                            rater2 to PoliticianReactionValue.LIKE,
                        ),
                    politicianB to listOf(rater3 to PoliticianReactionValue.LIKE),
                )
            val raterBalances =
                mapOf(
                    rater1 to BigDecimal("30.00"),
                    rater2 to BigDecimal("30.00"),
                    rater3 to BigDecimal("30.00"),
                )
            // pool = 90.00, korb ratio 2:1 -> A gets 60.00, B gets 30.00
            val result = PoliticianTrustWeightCalculator.computeMemberTrustWeights(reactionsByProfile, raterBalances)

            result.getValue(politicianA).memberTrustWeight.compareTo(BigDecimal("60.00")) shouldBe 0
            result.getValue(politicianB).memberTrustWeight.compareTo(BigDecimal("30.00")) shouldBe 0
            val sum = result.values.fold(ZERO_2DP) { acc, r -> acc + r.memberTrustWeight }
            sum.compareTo(BigDecimal("90.00")) shouldBe 0
        }

        test("a rater who rated multiple politicians contributes their balance to the pool only once") {
            val politicianA = Uuid.random()
            val politicianB = Uuid.random()
            val sharedRater = Uuid.random()

            val reactionsByProfile =
                mapOf(
                    politicianA to listOf(sharedRater to PoliticianReactionValue.LIKE),
                    politicianB to listOf(sharedRater to PoliticianReactionValue.LIKE),
                )
            val raterBalances = mapOf(sharedRater to BigDecimal("50.00"))

            val result = PoliticianTrustWeightCalculator.computeMemberTrustWeights(reactionsByProfile, raterBalances)

            // pool == 50.00 (counted once, not 100.00), split evenly 25.00/25.00 across equal korb 1/1.
            val sum = result.values.fold(ZERO_2DP) { acc, r -> acc + r.memberTrustWeight }
            sum.compareTo(BigDecimal("50.00")) shouldBe 0
        }

        test("korb is floored at zero -- more dislikes than likes never produces a negative basket or negative weight") {
            val politician = Uuid.random()
            val rater1 = Uuid.random()
            val rater2 = Uuid.random()
            val rater3 = Uuid.random()

            val reactionsByProfile =
                mapOf(
                    politician to
                        listOf(
                            rater1 to PoliticianReactionValue.LIKE,
                            rater2 to PoliticianReactionValue.DISLIKE,
                            rater3 to PoliticianReactionValue.DISLIKE,
                        ),
                )
            val raterBalances =
                mapOf(
                    rater1 to BigDecimal("10.00"),
                    rater2 to BigDecimal("10.00"),
                    rater3 to BigDecimal("10.00"),
                )

            val result = PoliticianTrustWeightCalculator.computeMemberTrustWeights(reactionsByProfile, raterBalances)

            result.getValue(politician).memberLikeCount shouldBe 1
            result.getValue(politician).memberDislikeCount shouldBe 2
            result.getValue(politician).memberTrustWeight.compareTo(ZERO_2DP) shouldBe 0
        }

        test("a politician with an empty reaction list is still represented, with zero weight (not omitted)") {
            val politicianWithVotes = Uuid.random()
            val politicianWithNone = Uuid.random()
            val rater = Uuid.random()

            val reactionsByProfile =
                mapOf(
                    politicianWithVotes to listOf(rater to PoliticianReactionValue.LIKE),
                    politicianWithNone to emptyList(),
                )
            val raterBalances = mapOf(rater to BigDecimal("100.00"))

            val result = PoliticianTrustWeightCalculator.computeMemberTrustWeights(reactionsByProfile, raterBalances)

            result.keys shouldBe setOf(politicianWithVotes, politicianWithNone)
            result.getValue(politicianWithNone).memberTrustWeight.compareTo(ZERO_2DP) shouldBe 0
            result.getValue(politicianWithVotes).memberTrustWeight.compareTo(BigDecimal("100.00")) shouldBe 0
        }

        test("empty input map returns an empty result") {
            PoliticianTrustWeightCalculator.computeMemberTrustWeights(emptyMap(), emptyMap()) shouldBe emptyMap()
        }

        test("a rater missing from raterBalances is treated as zero balance, defensively") {
            val politician = Uuid.random()
            val rater = Uuid.random()
            val reactionsByProfile = mapOf(politician to listOf(rater to PoliticianReactionValue.LIKE))

            val result = PoliticianTrustWeightCalculator.computeMemberTrustWeights(reactionsByProfile, emptyMap())

            result.getValue(politician).memberTrustWeight.compareTo(ZERO_2DP) shouldBe 0
        }
    })
