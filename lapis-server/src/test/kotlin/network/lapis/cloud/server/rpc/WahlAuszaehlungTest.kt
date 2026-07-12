package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import network.lapis.cloud.shared.domain.WahlAntwort
import kotlin.uuid.Uuid

/**
 * Pure property tests of [computeJaNeinErgebnis]/[computePersonenwahlErgebnis] -- the
 * algorithmically outcome-affecting core of Demokratische Wahlen (V0.2.4), same rationale as
 * [AbstimmungVickreyTest] gives for [computeVickreySettlement]. No DB access anywhere in this
 * file.
 */
private fun jaNeinStimmenArb(): Arb<List<WahlAntwort>> =
    arbitrary { rs ->
        val random = rs.random
        val count = random.nextInt(0, 30)
        (0 until count).map { WahlAntwort.entries[random.nextInt(WahlAntwort.entries.size)] }
    }

private data class PersonenwahlScenario(
    val optionIds: List<Uuid>,
    val stimmen: List<WahlStimme>,
    val sitzeCount: Int,
)

/**
 * Random contested-or-uncontested scenario: 2..6 options, `sitzeCount` in `1..options-1` (always
 * a genuinely contested election, never the undersubscribed shortcut), 0..15 ballots each
 * selecting 1..sitzeCount distinct options.
 */
private fun personenwahlScenarioArb(): Arb<PersonenwahlScenario> =
    arbitrary { rs ->
        val random = rs.random
        val optionCount = random.nextInt(2, 7)
        val optionIds = (0 until optionCount).map { Uuid.random() }
        val sitzeCount = random.nextInt(1, optionCount)
        val ballotCount = random.nextInt(0, 16)
        val stimmen =
            (0 until ballotCount).map {
                val selectionCount = random.nextInt(1, sitzeCount + 1)
                WahlStimme(optionIds.shuffled(random).take(selectionCount))
            }
        PersonenwahlScenario(optionIds, stimmen, sitzeCount)
    }

class WahlAuszaehlungTest :
    FunSpec({
        context("computeJaNeinErgebnis") {
            test("ja + nein + enthaltung always equals the ballot count") {
                checkAll(300, jaNeinStimmenArb(), Arb.int(1..100)) { stimmen, majority ->
                    val ergebnis = computeJaNeinErgebnis(stimmen, majority)
                    (ergebnis.ja + ergebnis.nein + ergebnis.enthaltung) shouldBe stimmen.size
                }
            }

            test("tie (including all-abstain/no-ballots) is never reported as majorityMet") {
                checkAll(300, jaNeinStimmenArb(), Arb.int(1..100)) { stimmen, majority ->
                    val ergebnis = computeJaNeinErgebnis(stimmen, majority)
                    if (ergebnis.tie) ergebnis.majorityMet shouldBe false
                }
            }

            test("ja == nein always resolves to tie, regardless of requiredMajorityPercent") {
                checkAll(200, Arb.int(0..20)) { n ->
                    val stimmen = List(n) { WahlAntwort.JA } + List(n) { WahlAntwort.NEIN }
                    val ergebnis = computeJaNeinErgebnis(stimmen, 50)
                    ergebnis.tie shouldBe true
                    ergebnis.majorityMet shouldBe false
                }
            }

            test("all-abstain / zero ballots cast is a tie, not a majority") {
                val ergebnisEmpty = computeJaNeinErgebnis(emptyList(), 50)
                ergebnisEmpty.tie shouldBe true
                ergebnisEmpty.majorityMet shouldBe false

                val ergebnisAllAbstain = computeJaNeinErgebnis(List(5) { WahlAntwort.ENTHALTUNG }, 50)
                ergebnisAllAbstain.tie shouldBe true
                ergebnisAllAbstain.majorityMet shouldBe false
            }

            test("unanimous JA always meets any requiredMajorityPercent in 1..100") {
                checkAll(200, Arb.int(1..30), Arb.int(1..100)) { n, majority ->
                    val ergebnis = computeJaNeinErgebnis(List(n) { WahlAntwort.JA }, majority)
                    ergebnis.tie shouldBe false
                    ergebnis.majorityMet shouldBe true
                }
            }

            test("exact-boundary majority (e.g. 2/3 requiring 67%, or 3/5 requiring 60%) is met without float rounding error") {
                // 2 JA / 1 NEIN = 66.66..% decisive share; a naive floating-point compare against
                // 67% could go either way depending on rounding -- the exact-integer-arithmetic
                // contract must reject this one cleanly (66 < 67).
                val notQuiteTwoThirds = computeJaNeinErgebnis(listOf(WahlAntwort.JA, WahlAntwort.JA, WahlAntwort.NEIN), 67)
                notQuiteTwoThirds.majorityMet shouldBe false

                // 3 JA / 2 NEIN = exactly 60% decisive share, requiring exactly 60% -> met.
                val exactSixty =
                    computeJaNeinErgebnis(
                        List(3) { WahlAntwort.JA } + List(2) { WahlAntwort.NEIN },
                        60,
                    )
                exactSixty.majorityMet shouldBe true
            }

            test("ENTHALTUNG ballots are excluded from the decisive-vote denominator") {
                // 1 JA / 0 NEIN / 98 ENTHALTUNG: decisive vote is 1-0, a unanimous (if tiny) majority.
                val stimmen = listOf(WahlAntwort.JA) + List(98) { WahlAntwort.ENTHALTUNG }
                val ergebnis = computeJaNeinErgebnis(stimmen, 50)
                ergebnis.tie shouldBe false
                ergebnis.majorityMet shouldBe true
            }

            test("rejects an out-of-range requiredMajorityPercent") {
                val thrownTooLow = runCatching { computeJaNeinErgebnis(listOf(WahlAntwort.JA), 0) }.exceptionOrNull()
                (thrownTooLow is IllegalArgumentException) shouldBe true
                val thrownTooHigh = runCatching { computeJaNeinErgebnis(listOf(WahlAntwort.JA), 101) }.exceptionOrNull()
                (thrownTooHigh is IllegalArgumentException) shouldBe true
            }

            test("determinism: identical input produces identical output") {
                checkAll(200, jaNeinStimmenArb(), Arb.int(1..100)) { stimmen, majority ->
                    computeJaNeinErgebnis(stimmen, majority) shouldBe computeJaNeinErgebnis(stimmen, majority)
                }
            }
        }

        context("computePersonenwahlErgebnis") {
            test("undersubscribed election (options.size <= sitzeCount) elects every candidate regardless of votes") {
                checkAll(200, Arb.int(1..8)) { sitzeCount ->
                    val optionIds = (0 until sitzeCount).map { Uuid.random() }
                    val ergebnis = computePersonenwahlErgebnis(emptyList(), optionIds, sitzeCount)
                    ergebnis.tie shouldBe false
                    ergebnis.winnerOptionIds.toSet() shouldBe optionIds.toSet()
                }
            }

            test("winnerOptionIds is always a subset of optionIds, and empty exactly when tie is true") {
                checkAll(300, personenwahlScenarioArb()) { scenario ->
                    val ergebnis = computePersonenwahlErgebnis(scenario.stimmen, scenario.optionIds, scenario.sitzeCount)
                    ergebnis.winnerOptionIds.all { it in scenario.optionIds } shouldBe true
                    (ergebnis.tie == ergebnis.winnerOptionIds.isEmpty()) shouldBe true
                }
            }

            test("winnerOptionIds size is exactly sitzeCount whenever there is no seat-cutoff tie (contested election)") {
                checkAll(300, personenwahlScenarioArb()) { scenario ->
                    val ergebnis = computePersonenwahlErgebnis(scenario.stimmen, scenario.optionIds, scenario.sitzeCount)
                    if (!ergebnis.tie) ergebnis.winnerOptionIds.size shouldBe scenario.sitzeCount
                }
            }

            test("voteCounts sums to the total number of selections cast across all ballots") {
                checkAll(300, personenwahlScenarioArb()) { scenario ->
                    val ergebnis = computePersonenwahlErgebnis(scenario.stimmen, scenario.optionIds, scenario.sitzeCount)
                    ergebnis.voteCounts.values.sum() shouldBe scenario.stimmen.sumOf { it.optionIds.size }
                }
            }

            test("a landslide winner (all ballots for one option) always wins uncontested of the tie boundary") {
                val optionIds = (0 until 4).map { Uuid.random() }
                val landslideOption = optionIds.first()
                val stimmen = List(20) { WahlStimme(listOf(landslideOption)) }
                val ergebnis = computePersonenwahlErgebnis(stimmen, optionIds, 1)
                ergebnis.tie shouldBe false
                ergebnis.winnerOptionIds shouldBe listOf(landslideOption)
            }

            test("exact seat-cutoff tie (sitzeCount-th and (sitzeCount+1)-th candidate tied) resolves to no winners at all") {
                val optionA = Uuid.random()
                val optionB = Uuid.random()
                val optionC = Uuid.random()
                // A: 2 votes, B: 1 vote, C: 1 vote -- sitzeCount=1, B and C tie for the cutoff.
                val stimmen =
                    List(2) { WahlStimme(listOf(optionA)) } + listOf(WahlStimme(listOf(optionB)), WahlStimme(listOf(optionC)))
                val ergebnis = computePersonenwahlErgebnis(stimmen, listOf(optionA, optionB, optionC), 2)
                ergebnis.tie shouldBe true
                ergebnis.winnerOptionIds shouldBe emptyList()
            }

            test("rejects sitzeCount < 1, an empty option list, duplicate optionIds, and a ballot selecting an unknown option") {
                val optionA = Uuid.random()
                val optionB = Uuid.random()

                (
                    runCatching {
                        computePersonenwahlErgebnis(
                            emptyList(),
                            listOf(optionA),
                            0,
                        )
                    }.exceptionOrNull() is IllegalArgumentException
                ) shouldBe
                    true
                (
                    runCatching {
                        computePersonenwahlErgebnis(
                            emptyList(),
                            emptyList(),
                            1,
                        )
                    }.exceptionOrNull() is IllegalArgumentException
                ) shouldBe
                    true
                (
                    runCatching {
                        computePersonenwahlErgebnis(emptyList(), listOf(optionA, optionA), 1)
                    }.exceptionOrNull() is IllegalArgumentException
                ) shouldBe true
                (
                    runCatching {
                        computePersonenwahlErgebnis(listOf(WahlStimme(listOf(optionB))), listOf(optionA), 1)
                    }.exceptionOrNull() is IllegalArgumentException
                ) shouldBe true
            }

            test("rejects a ballot selecting the same option twice") {
                val optionA = Uuid.random()
                val optionB = Uuid.random()
                val thrown =
                    runCatching {
                        computePersonenwahlErgebnis(listOf(WahlStimme(listOf(optionA, optionA))), listOf(optionA, optionB), 1)
                    }.exceptionOrNull()
                (thrown is IllegalArgumentException) shouldBe true
            }

            test("determinism: identical input produces identical output") {
                checkAll(200, personenwahlScenarioArb()) { scenario ->
                    val first = computePersonenwahlErgebnis(scenario.stimmen, scenario.optionIds, scenario.sitzeCount)
                    val second = computePersonenwahlErgebnis(scenario.stimmen, scenario.optionIds, scenario.sitzeCount)
                    first shouldBe second
                }
            }
        }
    })
