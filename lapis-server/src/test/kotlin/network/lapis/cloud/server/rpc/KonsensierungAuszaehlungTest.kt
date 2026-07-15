package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import network.lapis.cloud.shared.domain.SkAggregation
import network.lapis.cloud.shared.domain.SkTiebreakRegel
import kotlin.uuid.Uuid

/**
 * Pure property tests of [computeKonsensierungErgebnis] -- the algorithmically outcome-affecting
 * core of Systemisches Konsensieren (V0.2.5), same rationale as [WahlAuszaehlungTest] gives for
 * [computePersonenwahlErgebnis]. No DB access anywhere in this file.
 */
private data class KonsensierungScenario(
    val optionIds: List<Uuid>,
    val stimmen: List<SkStimme>,
    val skalaMax: Int,
)

/** Random scenario: 2..6 options, 0..15 ballots, each rating every option in `0..skalaMax`. */
private fun konsensierungScenarioArb(): Arb<KonsensierungScenario> =
    arbitrary { rs ->
        val random = rs.random
        val optionCount = random.nextInt(2, 7)
        val optionIds = (0 until optionCount).map { Uuid.random() }
        val skalaMax = random.nextInt(1, 11)
        val ballotCount = random.nextInt(0, 16)
        val stimmen =
            (0 until ballotCount).map {
                SkStimme(optionIds.associateWith { random.nextInt(0, skalaMax + 1) })
            }
        KonsensierungScenario(optionIds, stimmen, skalaMax)
    }

class KonsensierungAuszaehlungTest :
    FunSpec({
        test("konsensIndex is always in [0,1]") {
            checkAll(300, konsensierungScenarioArb()) { scenario ->
                val ergebnis = computeKonsensierungErgebnis(scenario.stimmen, scenario.optionIds, scenario.skalaMax)
                ergebnis.optionErgebnisse.forEach { (it.konsensIndex in 0.0..1.0) shouldBe true }
            }
        }

        test("verteilung counts always sum to the ballot count") {
            checkAll(300, konsensierungScenarioArb()) { scenario ->
                val ergebnis = computeKonsensierungErgebnis(scenario.stimmen, scenario.optionIds, scenario.skalaMax)
                ergebnis.optionErgebnisse.forEach { it.verteilung.values.sum() shouldBe scenario.stimmen.size }
            }
        }

        test("kumulierterWiderstand == n * mittlererWiderstand (integer/double consistency)") {
            checkAll(300, konsensierungScenarioArb()) { scenario ->
                val ergebnis = computeKonsensierungErgebnis(scenario.stimmen, scenario.optionIds, scenario.skalaMax)
                val n = scenario.stimmen.size
                ergebnis.optionErgebnisse.forEach {
                    if (n == 0) {
                        it.mittlererWiderstand shouldBe 0.0
                    } else {
                        it.mittlererWiderstand shouldBe (it.kumulierterWiderstand.toDouble() / n plusOrMinus 1e-9)
                    }
                }
            }
        }

        test("single voter: winner is that voter's lowest-resistance option, G-K = wert/skalaMax") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            val optionC = Uuid.random()
            val stimme = SkStimme(mapOf(optionA to 2, optionB to 7, optionC to 9))
            val ergebnis = computeKonsensierungErgebnis(listOf(stimme), listOf(optionA, optionB, optionC), skalaMax = 10)
            ergebnis.gewinnerOptionId shouldBe optionA
            ergebnis.tie shouldBe false
            val optionAErgebnis = ergebnis.optionErgebnisse.single { it.optionId == optionA }
            optionAErgebnis.konsensIndex shouldBe 0.2
        }

        test(
            "unanimous 0 on all options: KW=0/G-K=0/konsensTragfaehig=true for every option, KW tie flagged, a deterministic winner is still picked unless WIEDERHOLUNG",
        ) {
            val optionIds = (0 until 4).map { Uuid.random() }
            val stimmen = List(5) { SkStimme(optionIds.associateWith { 0 }) }

            val niedrigsterMax =
                computeKonsensierungErgebnis(stimmen, optionIds, skalaMax = 10, tiebreak = SkTiebreakRegel.NIEDRIGSTER_MAXWIDERSTAND)
            niedrigsterMax.optionErgebnisse.forEach {
                it.kumulierterWiderstand shouldBe 0
                it.konsensIndex shouldBe 0.0
            }
            niedrigsterMax.tie shouldBe true
            niedrigsterMax.gewinnerOptionId shouldNotBe null
            niedrigsterMax.konsensTragfaehig shouldBe true

            val wiederholung = computeKonsensierungErgebnis(stimmen, optionIds, skalaMax = 10, tiebreak = SkTiebreakRegel.WIEDERHOLUNG)
            wiederholung.tie shouldBe true
            wiederholung.gewinnerOptionId shouldBe null
        }

        test("unanimous max resistance on all options: G-K=1.0 and gruppenkonfliktWarnung=true for the winner") {
            val optionIds = (0 until 3).map { Uuid.random() }
            val stimmen = List(4) { SkStimme(optionIds.associateWith { 10 }) }
            val ergebnis = computeKonsensierungErgebnis(stimmen, optionIds, skalaMax = 10)
            ergebnis.optionErgebnisse.forEach { it.konsensIndex shouldBe 1.0 }
            ergebnis.gruppenkonfliktWarnung shouldBe true
            ergebnis.konsensTragfaehig shouldBe false
        }

        test("KW tie, different maxWiderstand: NIEDRIGSTER_MAXWIDERSTAND picks the option with the lower per-option maximum") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            // Both sum to 6 across 2 ballots -- A: 3+3 (max 3), B: 0+6 (max 6).
            val stimmen =
                listOf(
                    SkStimme(mapOf(optionA to 3, optionB to 0)),
                    SkStimme(mapOf(optionA to 3, optionB to 6)),
                )
            val ergebnis =
                computeKonsensierungErgebnis(
                    stimmen,
                    listOf(optionA, optionB),
                    skalaMax = 10,
                    tiebreak = SkTiebreakRegel.NIEDRIGSTER_MAXWIDERSTAND,
                )
            ergebnis.tie shouldBe true
            ergebnis.tiebreakAngewendet shouldBe SkTiebreakRegel.NIEDRIGSTER_MAXWIDERSTAND
            ergebnis.gewinnerOptionId shouldBe optionA
        }

        test("KW tie, equal maxWiderstand but different stddev: NIEDRIGSTE_STDABW picks the more uniform option") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            // Both sum to 8 across 2 ballots, both max 5 -- A: 3+5 (stddev low-ish), B: 5+3 is
            // the same distribution as A, so use a genuinely different shape: A: 4+4 (stddev 0),
            // B: 0+8 clipped -- use skalaMax 10, A: 4+4 (max 4, sum 8), B: 8+0 (max 8, sum 8).
            // To isolate stddev specifically at EQUAL max, use three ballots each: A: 3,3,2 (sum 8,
            // max 3), B: 4,3,1 (sum 8, max 4) -- still differs in max. Construct equal-max
            // instead: A: 4,4,0 (sum 8, max 4, stddev higher), B: 3,3,2 (sum 8, max... 3). Not
            // equal max either. Use four equal values vs spread with the same max deliberately:
            // A: 4,4,0,0 (sum 8, max 4), B: 4,2,2,0 (sum 8, max 4) -- equal max=4, different stddev.
            val stimmen =
                listOf(
                    SkStimme(mapOf(optionA to 4, optionB to 4)),
                    SkStimme(mapOf(optionA to 4, optionB to 2)),
                    SkStimme(mapOf(optionA to 0, optionB to 2)),
                    SkStimme(mapOf(optionA to 0, optionB to 0)),
                )
            val ergebnis =
                computeKonsensierungErgebnis(stimmen, listOf(optionA, optionB), skalaMax = 10, tiebreak = SkTiebreakRegel.NIEDRIGSTE_STDABW)
            val optionAErgebnis = ergebnis.optionErgebnisse.single { it.optionId == optionA }
            val optionBErgebnis = ergebnis.optionErgebnisse.single { it.optionId == optionB }
            optionAErgebnis.kumulierterWiderstand shouldBe optionBErgebnis.kumulierterWiderstand
            optionAErgebnis.maxWiderstand shouldBe optionBErgebnis.maxWiderstand
            (optionAErgebnis.standardabweichung > optionBErgebnis.standardabweichung) shouldBe true
            ergebnis.tie shouldBe true
            ergebnis.tiebreakAngewendet shouldBe SkTiebreakRegel.NIEDRIGSTE_STDABW
            ergebnis.gewinnerOptionId shouldBe optionB
        }

        test("KW tie under WIEDERHOLUNG: tie=true, gewinnerOptionId=null") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            val stimmen = listOf(SkStimme(mapOf(optionA to 5, optionB to 5)))
            val ergebnis =
                computeKonsensierungErgebnis(stimmen, listOf(optionA, optionB), skalaMax = 10, tiebreak = SkTiebreakRegel.WIEDERHOLUNG)
            ergebnis.tie shouldBe true
            ergebnis.gewinnerOptionId shouldBe null
            ergebnis.tiebreakAngewendet shouldBe null
        }

        test("aggregation invariance: SUMME and MITTELWERT always produce the identical winner for the same input") {
            checkAll(300, konsensierungScenarioArb()) { scenario ->
                val summe =
                    computeKonsensierungErgebnis(scenario.stimmen, scenario.optionIds, scenario.skalaMax, aggregation = SkAggregation.SUMME)
                val mittelwert =
                    computeKonsensierungErgebnis(
                        scenario.stimmen,
                        scenario.optionIds,
                        scenario.skalaMax,
                        aggregation = SkAggregation.MITTELWERT,
                    )
                summe.gewinnerOptionId shouldBe mittelwert.gewinnerOptionId
                summe.tie shouldBe mittelwert.tie
            }
        }

        test("zero ballots: keineBewertungen=true, tie=true, gewinnerOptionId=null, no divide-by-zero") {
            val optionIds = (0 until 3).map { Uuid.random() }
            val ergebnis = computeKonsensierungErgebnis(emptyList(), optionIds, skalaMax = 10)
            ergebnis.keineBewertungen shouldBe true
            ergebnis.tie shouldBe true
            ergebnis.gewinnerOptionId shouldBe null
            ergebnis.konsensTragfaehig shouldBe false
            ergebnis.gruppenkonfliktWarnung shouldBe false
            ergebnis.optionErgebnisse.forEach {
                it.mittlererWiderstand shouldBe 0.0
                it.konsensIndex shouldBe 0.0
                it.standardabweichung shouldBe 0.0
            }
        }

        test("rejects an empty option list, duplicate optionIds, an invalid skalaMax, and out-of-range thresholds") {
            val optionA = Uuid.random()
            (
                runCatching {
                    computeKonsensierungErgebnis(
                        emptyList(),
                        emptyList(),
                        10,
                    )
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe
                true
            (
                runCatching {
                    computeKonsensierungErgebnis(emptyList(), listOf(optionA, optionA), 10)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
            (
                runCatching {
                    computeKonsensierungErgebnis(emptyList(), listOf(optionA), 0)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
            (
                runCatching {
                    computeKonsensierungErgebnis(emptyList(), listOf(optionA), 10, gkTragfaehigSchwelle = 1.5)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
            (
                runCatching {
                    computeKonsensierungErgebnis(emptyList(), listOf(optionA), 10, gkWarnSchwelle = -0.1)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
        }

        test("rejects a ballot that is missing an option or rates an option outside the frozen set") {
            val optionA = Uuid.random()
            val optionB = Uuid.random()
            val missingOption = SkStimme(mapOf(optionA to 3))
            (
                runCatching {
                    computeKonsensierungErgebnis(listOf(missingOption), listOf(optionA, optionB), 10)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true

            val extraOption = SkStimme(mapOf(optionA to 3, optionB to 2, Uuid.random() to 1))
            (
                runCatching {
                    computeKonsensierungErgebnis(listOf(extraOption), listOf(optionA, optionB), 10)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
        }

        test("rejects a resistance value outside 0..skalaMax") {
            val optionA = Uuid.random()
            val tooHigh = SkStimme(mapOf(optionA to 11))
            (
                runCatching {
                    computeKonsensierungErgebnis(listOf(tooHigh), listOf(optionA), skalaMax = 10)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true

            val negative = SkStimme(mapOf(optionA to -1))
            (
                runCatching {
                    computeKonsensierungErgebnis(listOf(negative), listOf(optionA), skalaMax = 10)
                }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
        }

        test("determinism: identical input produces identical output") {
            checkAll(200, konsensierungScenarioArb()) { scenario ->
                val first = computeKonsensierungErgebnis(scenario.stimmen, scenario.optionIds, scenario.skalaMax)
                val second = computeKonsensierungErgebnis(scenario.stimmen, scenario.optionIds, scenario.skalaMax)
                first shouldBe second
            }
        }
    })
