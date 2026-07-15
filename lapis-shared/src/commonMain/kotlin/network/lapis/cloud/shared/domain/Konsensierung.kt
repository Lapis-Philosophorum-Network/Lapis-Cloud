package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Systemisches Konsensieren (V0.2.5): a third, orthogonal counting logic hung off the same
 * Antrag/Sitzung/Beschlussbuch governance spine as [AbstimmungDto] (LTR-weighted eBay/Vickrey
 * basket auction) and [WahlDto] (one-person-one-vote elections) -- here every participant rates
 * *every* option with a resistance value (Widerstand) on a `0..skalaMax` scale, and the option
 * with the LOWEST cumulative resistance (Kumulierter Widerstand / KW) wins. See
 * `network.lapis.cloud.server.rpc.KonsensierungService` KDoc for the full lifecycle and
 * `03 Bereiche/Lapis Cloud/Systemisches Konsensieren.md` for the concept document this implements.
 *
 * [SAMMLUNG] (options collected) -> [BEWERTUNG] (participants rate every frozen option) ->
 * [GESCHLOSSEN] -> [AUSGEWERTET], or [ABGEBROCHEN] from any non-terminal state. A
 * [GESCHLOSSEN]/[AUSGEWERTET] Konsensierung may return to [BEWERTUNG] via `reopenBewertung`
 * (Diskussion + Wiederabstimmung), incrementing [KonsensierungDto.runde] up to
 * [KonsensierungDto.maxRunden] times.
 */
@Serializable
enum class KonsensierungStatus { SAMMLUNG, BEWERTUNG, GESCHLOSSEN, AUSGEWERTET, ABGEBROCHEN }

/**
 * Display/cross-Konsensierung-comparability knob only -- within one Konsensierung, [MITTELWERT]
 * (mean resistance, KW/n) and [SUMME] (raw KW) always agree on the winner, because every option
 * is rated by the same `n` voters (mean is a monotone transform of the sum). See
 * `network.lapis.cloud.server.rpc.computeKonsensierungErgebnis` KDoc.
 */
@Serializable
enum class SkAggregation { MITTELWERT, SUMME }

/**
 * How a Kumulierter-Widerstand (KW) tie between two or more options is broken.
 * [NIEDRIGSTER_MAXWIDERSTAND] (lowest per-option maximum single resistance rating wins -- the
 * strongest minority-protection rule, the concept document's recommended default) ->
 * [NIEDRIGSTE_STDABW] (lowest standard deviation of resistance ratings wins) -> [WIEDERHOLUNG]
 * (no tiebreak at all -- the Konsensierung resolves to a tie with no winner, signalling a repeat
 * round, analogous to [WahlStatus] having no direct VERTAGT state but the same "tie is the safe,
 * non-manipulable default" philosophy as `WahlAuszaehlung`).
 */
@Serializable
enum class SkTiebreakRegel { NIEDRIGSTER_MAXWIDERSTAND, NIEDRIGSTE_STDABW, WIEDERHOLUNG }

/**
 * [SONDIERUNG] (advisory only -- `auswerten` never writes a [BeschlussDto]) vs. [BESCHLUSS]
 * (`auswerten` writes a [BeschlussDto] tagged [ResolutionMode.SYSTEMISCHER_KONSENS] and
 * transitions the hosting Antrag, exactly like [WahlDto]/[AbstimmungDto] do). [SONDIERUNG] is the
 * safe default -- see `03 Bereiche/Lapis Cloud/Systemisches Konsensieren.md`.
 */
@Serializable
enum class SkVerbindlichkeit { SONDIERUNG, BESCHLUSS }

/**
 * A ratable option. [isPassivloesung] marks the (optionally auto-inserted, see
 * [SkOpenInput.passivloesungAuto]) status-quo/"do nothing" option every participant can rate
 * alongside every real proposal -- a low Passivloesung resistance is a strong signal the group
 * would rather change nothing than accept any of the alternatives on offer.
 */
@Serializable
data class KonsensierungOptionDto(
    val id: String,
    val konsensierungId: String,
    val label: String,
    val position: Int,
    val isPassivloesung: Boolean,
    val createdById: String,
    val createdByDisplayName: String,
)

@Serializable
data class SkOptionInput(
    val label: String,
)

@Serializable
data class KonsensierungDto(
    val id: String,
    val antragId: String,
    val sitzungId: String,
    val title: String,
    val status: KonsensierungStatus,
    val geheim: Boolean,
    val skalaMax: Int,
    val aggregation: SkAggregation,
    val tiebreakRegel: SkTiebreakRegel,
    val gkTragfaehigSchwelle: Decimal,
    val gkWarnSchwelle: Decimal,
    val passivloesungAuto: Boolean,
    val verbindlichkeit: SkVerbindlichkeit,
    val maxRunden: Int,
    val runde: Int,
    val winnerOptionId: String?,
    val openedById: String,
    val openedByDisplayName: String,
    val openedAt: LocalDateTime,
    val bewertungOpenedAt: LocalDateTime?,
    val bewertungClosedAt: LocalDateTime?,
    val tallyRunAt: LocalDateTime?,
    val beschlussId: String?,
    val options: List<KonsensierungOptionDto>,
    /** `true` once [KonsensierungOptionDto] count exceeds the server-side soft cap -- see `KonsensierungService.MAX_OPTIONEN_SOFT`. */
    val zuVieleOptionenWarnung: Boolean,
)

/**
 * [geheim] defaults to `true` per the concept document's recommendation (see
 * `network.lapis.cloud.server.rpc.KonsensierungService` KDoc for the anonymity mechanism --
 * identical DB-level table-split already used by [WahlDto], no cryptography). [verbindlichkeit]
 * defaults to [SkVerbindlichkeit.SONDIERUNG], the safe advisory default.
 */
@Serializable
data class SkOpenInput(
    val antragId: String,
    val geheim: Boolean = true,
    val skalaMax: Int = 10,
    val aggregation: SkAggregation = SkAggregation.MITTELWERT,
    val tiebreakRegel: SkTiebreakRegel = SkTiebreakRegel.NIEDRIGSTER_MAXWIDERSTAND,
    val gkTragfaehigSchwelle: Decimal = 0.2.toDecimal(),
    val gkWarnSchwelle: Decimal = 0.5.toDecimal(),
    val passivloesungAuto: Boolean = true,
    val verbindlichkeit: SkVerbindlichkeit = SkVerbindlichkeit.SONDIERUNG,
    val maxRunden: Int = 3,
)

/**
 * The ballot itself -- [widerstaende] MUST rate every option frozen at `freezeOptionen` exactly
 * once, key = option id, value = resistance in `0..konsensierung.skalaMax`.
 * `KonsensierungService.castWiderstand` rejects any other shape.
 */
@Serializable
data class SkStimmzettelInput(
    val konsensierungId: String,
    val widerstaende: Map<String, Int>,
)

/**
 * [receiptCode] is present only when the Konsensierung is [KonsensierungDto.geheim] -- the one
 * time it is ever returned to a caller, mirroring [StimmzettelCastResultDto].
 */
@Serializable
data class SkStimmzettelCastResultDto(
    val id: String,
    val castAt: LocalDateTime,
    val receiptCode: String?,
)

/**
 * Transparency read of ballots cast so far. For a [KonsensierungDto.geheim] Konsensierung,
 * [memberId]/[memberDisplayName] are always `null` (mirrors [StimmzettelDto]), and
 * [widerstaende] is `emptyMap()` until [KonsensierungStatus.AUSGEWERTET] -- the same pre-tally
 * secrecy gate [StimmzettelDto.selectedOptionLabels] documents. Non-secret Konsensierungen always
 * reveal the resistance values.
 */
@Serializable
data class SkStimmzettelDto(
    val id: String,
    val konsensierungId: String,
    val memberId: String?,
    val memberDisplayName: String?,
    /** option id -> resistance value. */
    val widerstaende: Map<String, Int>,
    val castAt: LocalDateTime,
    val runde: Int,
)

/**
 * Per-option aggregate. [kumulierterWiderstand] is the classic KW (sum of every rated
 * resistance); [mittlererWiderstand] = KW/n; [konsensIndex] is the Gruppenkonflikt-Index (G-K) =
 * KW / (n * skalaMax), in `[0, 1]` -- 0 means unanimous full acceptance, 1 means unanimous
 * maximum resistance. [verteilung] maps each distinct resistance value cast to how many voters
 * cast it (a rating histogram for this option).
 */
@Serializable
data class SkOptionErgebnisDto(
    val optionId: String,
    val kumulierterWiderstand: Int,
    val mittlererWiderstand: Double,
    val maxWiderstand: Int,
    val standardabweichung: Double,
    val konsensIndex: Double,
    val verteilung: Map<Int, Int>,
)

/**
 * [gewinnerOptionId] is `null` iff [tie] is `true` and [SkTiebreakRegel.WIEDERHOLUNG] applies (or
 * [keineBewertungen] is `true`) -- otherwise a deterministic fallback always yields a concrete
 * winner even under an unbroken KW tie. [tiebreakAngewendet] names the rule that actually decided
 * a KW tie (`null` when the raw KW comparison alone already gave a clear winner).
 * [konsensTragfaehig] is `true` when the winner's G-K is below
 * [KonsensierungDto.gkTragfaehigSchwelle]; [gruppenkonfliktWarnung] is `true` when it exceeds
 * [KonsensierungDto.gkWarnSchwelle].
 */
@Serializable
data class SkErgebnisDto(
    val konsensierungId: String,
    val optionErgebnisse: List<SkOptionErgebnisDto>,
    val gewinnerOptionId: String?,
    val tie: Boolean,
    val tiebreakAngewendet: SkTiebreakRegel?,
    val konsensTragfaehig: Boolean,
    val gruppenkonfliktWarnung: Boolean,
    val keineBewertungen: Boolean,
)
