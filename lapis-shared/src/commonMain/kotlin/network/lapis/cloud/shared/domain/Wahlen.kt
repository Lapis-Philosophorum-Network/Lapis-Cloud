package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Demokratische Wahlen (V0.2.4): one-person-one-vote elections/ballots, structurally distinct
 * from [AbstimmungDto] (LTR-weighted eBay/Vickrey basket auction, V0.2.3) -- see
 * `network.lapis.cloud.server.rpc.WahlService` KDoc for the full lifecycle and
 * `03 Bereiche/Lapis Cloud/Demokratische Wahlen.md` for the concept document this implements.
 *
 * [LISTENWAHL]/[RANGLISTENWAHL] are reserved for forward compatibility (DTO/DB shape only, so a
 * later wave does not need another migration) but are rejected by
 * `WahlService.openWahl` in this wave with a `ConflictException` -- D'Hondt/Sainte-Laguë and
 * Schulze/Ranked-Pairs/STV are each a real, non-trivial algorithm, explicitly out of scope for
 * the "standard implementation, no novel algorithm" framing of V0.2.4.
 */
@Serializable
enum class WahlTyp { JA_NEIN, EINZELWAHL, MEHRFACHWAHL, LISTENWAHL, RANGLISTENWAHL }

/**
 * [VORBEREITUNG] -> ([KANDIDATENLISTE_FREIGEGEBEN], personnel types only) -> [OFFEN] ->
 * [GESCHLOSSEN] -> [AUSGEZAEHLT], or [ABGEBROCHEN] from any non-terminal state -- see
 * `network.lapis.cloud.server.rpc.WahlService` for the exact transition guards.
 */
@Serializable
enum class WahlStatus { VORBEREITUNG, KANDIDATENLISTE_FREIGEGEBEN, OFFEN, GESCHLOSSEN, AUSGEZAEHLT, ABGEBROCHEN }

/** Ballot answer for [WahlTyp.JA_NEIN] Wahlen only -- personnel-type Wahlen select option ids instead. */
@Serializable
enum class WahlAntwort { JA, NEIN, ENTHALTUNG }

/**
 * A ballot-selectable option: either a fixed JA/NEIN/ENTHALTUNG row ([WahlTyp.JA_NEIN], created
 * automatically by `openWahl`) or a candidate row ([kandidaturId] set, created by
 * `freigebenKandidatenliste` from the approved Kandidaturen). [voteCount] is always `0` while the
 * Wahl has not reached [WahlStatus.AUSGEZAEHLT] -- exposing a live running count while voting is
 * still open would leak a partial tally and undermine ballot secrecy, the same reasoning behind
 * [ReceiptVerificationDto.optionLabel] staying `null` before the tally runs.
 */
@Serializable
data class WahlOptionDto(
    val id: String,
    val wahlId: String,
    val label: String,
    val position: Int,
    val kandidaturId: String?,
    val voteCount: Int,
)

@Serializable
data class WahlDto(
    val id: String,
    val antragId: String,
    val sitzungId: String,
    val title: String,
    val wahlTyp: WahlTyp,
    val geheim: Boolean,
    val sitzeCount: Int,
    val zielGremiumId: String?,
    val zielGremiumName: String?,
    val zielRolle: GremiumRolle?,
    val requiredMajorityPercent: Int,
    val status: WahlStatus,
    val openedById: String,
    val openedByDisplayName: String,
    val openedAt: LocalDateTime,
    val candidateListApprovedAt: LocalDateTime?,
    val votingOpenedAt: LocalDateTime?,
    val votingClosedAt: LocalDateTime?,
    val tallyThreshold: Int,
    val tallyRunAt: LocalDateTime?,
    val beschlussId: String?,
    val options: List<WahlOptionDto>,
)

/**
 * [zielGremiumId] is required (enforced by `WahlService.openWahl`) for personnel [wahlTyp]s
 * ([WahlTyp.EINZELWAHL]/[WahlTyp.MEHRFACHWAHL]) -- it is the Gremium winners join, which may
 * differ from the Antrag's own target Gremium (e.g. a Mitgliederversammlung-hosted Antrag electing
 * the Vorstand: `antrag.targetGremiumId` is the Mitgliederversammlung, `zielGremiumId` is the
 * Vorstand). `null` for [WahlTyp.JA_NEIN], which seats nobody.
 */
@Serializable
data class WahlOpenInput(
    val antragId: String,
    val wahlTyp: WahlTyp,
    val geheim: Boolean = true,
    val sitzeCount: Int = 1,
    val zielGremiumId: String? = null,
    val zielRolle: GremiumRolle? = null,
    val requiredMajorityPercent: Int = 50,
    val tallyThreshold: Int = 2,
)

@Serializable
data class KandidaturDto(
    val id: String,
    val wahlId: String,
    val memberId: String,
    val memberDisplayName: String,
    val motivationText: String?,
    val submittedAt: LocalDateTime,
    val withdrawnAt: LocalDateTime?,
)

@Serializable
data class KandidaturInput(
    val motivationText: String? = null,
)

@Serializable
data class WahlvorstandDto(
    val id: String,
    val wahlId: String,
    val memberId: String,
    val memberDisplayName: String,
    val appointedAt: LocalDateTime,
)

/**
 * The ballot itself. For a [WahlTyp.JA_NEIN] Wahl, set [antwort] and leave [selectedOptionIds]
 * empty. For a personnel Wahl, set [selectedOptionIds] (1..`sitzeCount` distinct option ids) and
 * leave [antwort] `null`. `WahlService.castStimme` rejects any other combination.
 */
@Serializable
data class StimmzettelInput(
    val wahlId: String,
    val antwort: WahlAntwort? = null,
    val selectedOptionIds: List<String> = emptyList(),
)

/**
 * [receiptCode] is present only when the Wahl is [WahlDto.geheim] -- the one time it is ever
 * returned to a caller; from then on only [network.lapis.cloud.shared.rpc.IWahlService
 * .verifyReceipt] can look up its own ballot by that code, and even then only the option label
 * once [WahlStatus.AUSGEZAEHLT], never the fact of who cast it.
 */
@Serializable
data class StimmzettelCastResultDto(
    val id: String,
    val castAt: LocalDateTime,
    val receiptCode: String?,
)

/**
 * Transparency read of ballots cast so far. For a [WahlDto.geheim] Wahl, [memberId]/
 * [memberDisplayName] are always `null` -- there is no member FK on the ballot row to begin with
 * in that case (see `network.lapis.cloud.server.db.tables.WahlTables` KDoc), so this is a direct
 * reflection of the storage shape, not a filtered projection.
 *
 * [selectedOptionLabels] is `emptyList()` for a [WahlDto.geheim] Wahl until it reaches
 * [WahlStatus.AUSGEZAEHLT] -- same pre-tally-secrecy invariant as [WahlOptionDto.voteCount] (held
 * at `0` until the tally runs) and [ReceiptVerificationDto.optionLabel] (`null` until the tally
 * runs). Without this gate, anyone could enumerate every anonymized ballot's plaintext choice
 * while voting is still open and tally a running result themselves. Non-secret Wahlen always
 * reveal the labels, since the ballot's `memberId` is already visible in the clear.
 */
@Serializable
data class StimmzettelDto(
    val id: String,
    val wahlId: String,
    val memberId: String?,
    val memberDisplayName: String?,
    val selectedOptionLabels: List<String>,
    val castAt: LocalDateTime,
)

/**
 * [majorityMet] is only meaningful for [WahlTyp.JA_NEIN] (`null` for personnel Wahlen).
 * [winnerOptionIds] is empty whenever [tie] is `true` -- a tie resolves the whole Wahl to "no
 * winners" (see `network.lapis.cloud.server.rpc.WahlAuszaehlung` KDoc), never a partial result.
 * For [WahlTyp.EINZELWAHL], [tie] is also `true` when the plurality winner fails to reach
 * `WahlDto.requiredMajorityPercent` of the votes cast -- the concept document requires an
 * absolute majority for this Wahltyp ("ggf. Stichwahl"), so a sub-majority plurality result is
 * reported the same way as a genuine seat-cutoff tie: no winner seated, signalling a runoff is
 * needed (see `network.lapis.cloud.server.rpc.WahlService.auszaehlen`).
 */
@Serializable
data class WahlErgebnisDto(
    val wahlId: String,
    val winnerOptionIds: List<String>,
    val tie: Boolean,
    val majorityMet: Boolean?,
    val perOptionVotes: Map<String, Int>,
)

/**
 * [optionLabel] is `null` until the Wahl reaches [WahlStatus.AUSGEZAEHLT], even when [found] is
 * `true` -- returning a partial tally result to a receipt holder while voting is still open (or
 * closed but not yet counted) would leak information no other caller can see, defeating the point
 * of holding vote-counts back until the tally (see [WahlOptionDto.voteCount] KDoc).
 */
@Serializable
data class ReceiptVerificationDto(
    val found: Boolean,
    val optionLabel: String?,
)
