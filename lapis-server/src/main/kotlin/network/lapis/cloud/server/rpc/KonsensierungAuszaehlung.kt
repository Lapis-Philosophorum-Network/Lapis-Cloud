package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.SkAggregation
import network.lapis.cloud.shared.domain.SkTiebreakRegel
import kotlin.math.sqrt
import kotlin.uuid.Uuid

/**
 * One voter's complete resistance vector for a single Bewertungsrunde -- the pure-function input
 * shape for [computeKonsensierungErgebnis], deliberately decoupled from
 * [network.lapis.cloud.server.db.generated.KonsensierungWiderstandTable] so this file has zero DB
 * dependency and can be property-tested directly (see `KonsensierungAuszaehlungTest`), same
 * rationale as `WahlAuszaehlung.WahlStimme` gives. [widerstaende] MUST cover every frozen option
 * id exactly once -- [computeKonsensierungErgebnis] rejects any ballot that doesn't.
 */
data class SkStimme(
    val widerstaende: Map<Uuid, Int>,
)

/**
 * Per-option aggregate. [kumulierterWiderstand] is the classic Kumulierter Widerstand (KW) = the
 * sum of every rated resistance value for this option; [mittlererWiderstand] = KW/n;
 * [konsensIndex] is the Gruppenkonflikt-Index (G-K) = KW / (n * skalaMax), always in `[0, 1]`.
 * [verteilung] maps each distinct resistance value cast to how many voters cast it.
 */
data class SkOptionErgebnis(
    val optionId: Uuid,
    val kumulierterWiderstand: Int,
    val mittlererWiderstand: Double,
    val maxWiderstand: Int,
    val standardabweichung: Double,
    val konsensIndex: Double,
    val verteilung: Map<Int, Int>,
)

/**
 * Result of [computeKonsensierungErgebnis]. [optionErgebnisse] is sorted best-first (lowest KW
 * first). [gewinnerOptionId] is `null` iff [tie] is `true` AND the configured
 * [SkTiebreakRegel.WIEDERHOLUNG] rule applies (or there were zero ballots) -- otherwise a
 * deterministic option-id-string-ascending fallback always yields a concrete winner even under an
 * unbroken KW tie, mirroring `computePersonenwahlErgebnis`'s deterministic ordering.
 * [tiebreakAngewendet] names the rule that actually decided a KW tie (`null` when KW alone gave a
 * clear winner). [konsensTragfaehig]/[gruppenkonfliktWarnung] are only meaningful when
 * [gewinnerOptionId] is non-null; both are `false` when [keineBewertungen] is `true`.
 */
data class SkErgebnis(
    val optionErgebnisse: List<SkOptionErgebnis>,
    val gewinnerOptionId: Uuid?,
    val tie: Boolean,
    val tiebreakAngewendet: SkTiebreakRegel?,
    val konsensTragfaehig: Boolean,
    val gruppenkonfliktWarnung: Boolean,
    val keineBewertungen: Boolean,
)

/**
 * Systemisches-Konsensieren tally: the option with the LOWEST cumulative resistance (KW) wins --
 * the exact opposite ranking direction of `computePersonenwahlErgebnis`'s "most votes wins".
 * Pure function, no DB access -- exhaustively property-testable, same rationale
 * `WahlAuszaehlung`'s KDoc gives (a bug here directly changes a governance outcome).
 *
 * [aggregation] is deliberately **not** used to compute the ranking: within one Konsensierung,
 * [SkAggregation.SUMME] (raw KW) and [SkAggregation.MITTELWERT] (KW/n) always agree on the winner,
 * because every option is rated by the same `n` voters -- the mean is a strictly monotone
 * transform of the sum for a fixed `n`. The parameter exists purely so callers can request either
 * figure for display/cross-Konsensierung-comparison purposes ([SkOptionErgebnis] always reports
 * both); do not expect it to change [SkErgebnis.gewinnerOptionId].
 *
 * Tiebreak on equal KW: [SkTiebreakRegel.NIEDRIGSTER_MAXWIDERSTAND] -> lowest per-option
 * [SkOptionErgebnis.maxWiderstand] wins (the strongest minority-protection rule -- no single voter
 * was pushed to their maximum resistance); [SkTiebreakRegel.NIEDRIGSTE_STDABW] -> lowest
 * [SkOptionErgebnis.standardabweichung] wins (the option the group agrees on most uniformly);
 * [SkTiebreakRegel.WIEDERHOLUNG] -> [SkErgebnis.tie] `true`, [SkErgebnis.gewinnerOptionId] `null`
 * (no winner, signals a repeat round). A final deterministic `optionId.toString()` ascending
 * fallback keeps [NIEDRIGSTER_MAXWIDERSTAND]/[NIEDRIGSTE_STDABW] well-defined even when the
 * tiebreak criterion itself is still tied.
 */
fun computeKonsensierungErgebnis(
    stimmen: List<SkStimme>,
    optionIds: List<Uuid>,
    skalaMax: Int = 10,
    aggregation: SkAggregation = SkAggregation.MITTELWERT,
    tiebreak: SkTiebreakRegel = SkTiebreakRegel.NIEDRIGSTER_MAXWIDERSTAND,
    gkTragfaehigSchwelle: Double = 0.2,
    gkWarnSchwelle: Double = 0.5,
): SkErgebnis {
    require(optionIds.isNotEmpty()) { "computeKonsensierungErgebnis requires at least 1 option" }
    require(optionIds.size == optionIds.toSet().size) { "optionIds must not contain duplicates" }
    require(skalaMax >= 1) { "skalaMax must be >= 1, got $skalaMax" }
    require(gkTragfaehigSchwelle in 0.0..1.0) { "gkTragfaehigSchwelle must be in 0.0..1.0, got $gkTragfaehigSchwelle" }
    require(gkWarnSchwelle in 0.0..1.0) { "gkWarnSchwelle must be in 0.0..1.0, got $gkWarnSchwelle" }
    val optionIdSet = optionIds.toSet()
    stimmen.forEach { stimme ->
        require(stimme.widerstaende.keys == optionIdSet) {
            "Every ballot must rate exactly the option set $optionIdSet once each, got ${stimme.widerstaende.keys}"
        }
        stimme.widerstaende.values.forEach { wert ->
            require(wert in 0..skalaMax) { "Resistance value must be in 0..$skalaMax, got $wert" }
        }
    }
    // aggregation is intentionally unused -- see KDoc "is deliberately not used".

    val n = stimmen.size
    val optionErgebnisse =
        optionIds.map { optionId ->
            val werte = stimmen.map { it.widerstaende.getValue(optionId) }
            val kw = werte.sum()
            val mittel = if (n == 0) 0.0 else kw.toDouble() / n
            val maxWert = werte.maxOrNull() ?: 0
            val variance = if (n == 0) 0.0 else werte.sumOf { (it - mittel) * (it - mittel) } / n
            val konsensIndex = if (n == 0) 0.0 else kw.toDouble() / (n.toDouble() * skalaMax)
            SkOptionErgebnis(
                optionId = optionId,
                kumulierterWiderstand = kw,
                mittlererWiderstand = mittel,
                maxWiderstand = maxWert,
                standardabweichung = sqrt(variance),
                konsensIndex = konsensIndex,
                verteilung = werte.groupingBy { it }.eachCount(),
            )
        }

    if (n == 0) {
        return SkErgebnis(
            optionErgebnisse = optionErgebnisse,
            gewinnerOptionId = null,
            tie = true,
            tiebreakAngewendet = null,
            konsensTragfaehig = false,
            gruppenkonfliktWarnung = false,
            keineBewertungen = true,
        )
    }

    val sortedBestFirst =
        optionErgebnisse.sortedWith(compareBy<SkOptionErgebnis> { it.kumulierterWiderstand }.thenBy { it.optionId.toString() })
    val minKw = sortedBestFirst.first().kumulierterWiderstand
    val kwTied = sortedBestFirst.filter { it.kumulierterWiderstand == minKw }

    val tie: Boolean
    val tiebreakAngewendet: SkTiebreakRegel?
    val gewinner: SkOptionErgebnis?

    if (kwTied.size == 1) {
        tie = false
        tiebreakAngewendet = null
        gewinner = kwTied.single()
    } else {
        // A genuine KW-level tie exists -- [tie] stays true regardless of whether a tiebreak
        // rule below still produces a concrete winner (see SkErgebnis KDoc: tie=true simply
        // means "the raw KW comparison alone did not distinguish a winner", not "no winner was
        // determined at all").
        tie = true
        when (tiebreak) {
            SkTiebreakRegel.NIEDRIGSTER_MAXWIDERSTAND -> {
                val minMax = kwTied.minOf { it.maxWiderstand }
                tiebreakAngewendet = SkTiebreakRegel.NIEDRIGSTER_MAXWIDERSTAND
                gewinner = kwTied.filter { it.maxWiderstand == minMax }.minBy { it.optionId.toString() }
            }
            SkTiebreakRegel.NIEDRIGSTE_STDABW -> {
                val minStdabw = kwTied.minOf { it.standardabweichung }
                tiebreakAngewendet = SkTiebreakRegel.NIEDRIGSTE_STDABW
                gewinner = kwTied.filter { it.standardabweichung == minStdabw }.minBy { it.optionId.toString() }
            }
            SkTiebreakRegel.WIEDERHOLUNG -> {
                tiebreakAngewendet = null
                gewinner = null
            }
        }
    }

    val gewinnerKonsensIndex = gewinner?.konsensIndex
    return SkErgebnis(
        optionErgebnisse = sortedBestFirst,
        gewinnerOptionId = gewinner?.optionId,
        tie = tie,
        tiebreakAngewendet = tiebreakAngewendet,
        konsensTragfaehig = gewinnerKonsensIndex != null && gewinnerKonsensIndex < gkTragfaehigSchwelle,
        gruppenkonfliktWarnung = gewinnerKonsensIndex != null && gewinnerKonsensIndex > gkWarnSchwelle,
        keineBewertungen = false,
    )
}
