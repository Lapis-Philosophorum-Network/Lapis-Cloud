package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * [MITGLIEDERVERSAMMLUNG] added in V0.2.2 (Antragsverwaltung) to model the general assembly as a
 * singleton [GremiumDto] rather than inventing a second, parallel "target kind" axis alongside
 * Gremium -- see [AntragDto] KDoc and `GovernanceService.computeQuorum`'s branch on this type.
 */
@Serializable
enum class GremiumType { VORSTAND, ARBEITSKREIS, AUSSCHUSS, SONSTIGES, MITGLIEDERVERSAMMLUNG }

/**
 * Role *within* a [GremiumDto] — distinct from [AccountRole], the system-wide login role. A
 * person can be [AccountRole.MEMBER] system-wide but [VORSITZ] of the Arbeitskreis IT.
 */
@Serializable
enum class GremiumRolle { VORSITZ, STELLV_VORSITZ, SCHRIFTFUEHRUNG, MITGLIED, BEISITZ }

@Serializable
enum class SitzungsFormat { PRAESENZ, ONLINE, HYBRID }

@Serializable
enum class SitzungsStatus { GEPLANT, DURCHGEFUEHRT, ABGESAGT }

@Serializable
enum class AnwesenheitStatus { ANWESEND, ENTSCHULDIGT, UNENTSCHULDIGT, VERTRETEN }

@Serializable
enum class BeschlussStatus { ANGENOMMEN, ABGELEHNT, VERTAGT }

/**
 * Meritokratische Abstimmungen (V0.2.3): distinguishes the pre-existing Gremium-Quorum
 * Beschlussbuch (V0.2.1/V0.2.2, headcount-driven Ja/Nein/Enthaltung tally) from the new
 * Vickrey-basket-auction path (LTR-weighted, see [AbstimmungDto]). [GREMIUM_QUORUM] stays the DB
 * default so every pre-existing [BeschlussDto] row and `recordBeschluss`/`resolveAntrag` call site
 * is unaffected by this wave.
 */
@Serializable
enum class ResolutionMode { GREMIUM_QUORUM, MERITOKRATISCH }

@Serializable
data class GremiumDto(
    val id: String,
    val name: String,
    val type: GremiumType,
    val description: String,
    val active: Boolean,
    val quorumPercent: Int,
    val createdAt: LocalDateTime,
)

@Serializable
data class GremiumInput(
    val name: String,
    val type: GremiumType,
    val description: String,
    val quorumPercent: Int = 50,
    val active: Boolean = true,
)

@Serializable
data class GremiumMitgliedschaftDto(
    val id: String,
    val gremiumId: String,
    val memberId: String,
    val memberDisplayName: String,
    val rolle: GremiumRolle,
    val since: LocalDate,
    val until: LocalDate?,
)

@Serializable
data class GremiumMitgliedschaftInput(
    val memberId: String,
    val rolle: GremiumRolle,
    val since: LocalDate,
)

@Serializable
data class SitzungDto(
    val id: String,
    val gremiumId: String,
    val gremiumName: String,
    val title: String,
    val scheduledAt: LocalDateTime,
    val location: String?,
    val format: SitzungsFormat,
    val status: SitzungsStatus,
    val calledById: String?,
    val calledByDisplayName: String?,
    val calledAt: LocalDateTime?,
    val chairMemberId: String?,
    val chairDisplayName: String?,
    val minuteTakerMemberId: String?,
    val minuteTakerDisplayName: String?,
    val protocolDocumentId: String?,
    val createdAt: LocalDateTime,
)

@Serializable
data class SitzungInput(
    val gremiumId: String,
    val title: String,
    val scheduledAt: LocalDateTime,
    val location: String?,
    val format: SitzungsFormat,
    val chairMemberId: String? = null,
    val minuteTakerMemberId: String? = null,
)

@Serializable
data class TagesordnungspunktDto(
    val id: String,
    val sitzungId: String,
    val position: Int,
    val title: String,
    val description: String?,
    val presenterMemberId: String?,
    val presenterDisplayName: String?,
)

@Serializable
data class TagesordnungspunktInput(
    val position: Int,
    val title: String,
    val description: String? = null,
    val presenterMemberId: String? = null,
)

@Serializable
data class AnwesenheitDto(
    val id: String,
    val sitzungId: String,
    val memberId: String,
    val memberDisplayName: String,
    val status: AnwesenheitStatus,
    val representedByMemberId: String?,
    val representedByDisplayName: String?,
    val note: String?,
    val recordedAt: LocalDateTime,
)

@Serializable
data class AnwesenheitInput(
    val memberId: String,
    val status: AnwesenheitStatus,
    val representedByMemberId: String? = null,
    val note: String? = null,
)

@Serializable
data class QuorumResultDto(
    val sitzungId: String,
    val eligibleMemberCount: Int,
    val presentCount: Int,
    val requiredCount: Int,
    val quorumPercent: Int,
    val met: Boolean,
)

@Serializable
data class BeschlussDto(
    val id: String,
    val sitzungId: String,
    val tagesordnungspunktId: String?,
    val number: String,
    val title: String,
    val text: String,
    val votesYes: Int,
    val votesNo: Int,
    val votesAbstain: Int,
    val quorumMet: Boolean,
    val status: BeschlussStatus,
    val decidedAt: LocalDateTime,
    val recordedById: String,
    val recordedByDisplayName: String,
    // Meritokratische Abstimmungen (V0.2.3). Defaults keep every existing call site (that builds
    // a BeschlussDto without naming these two params) source-compatible.
    val resolutionMode: ResolutionMode = ResolutionMode.GREMIUM_QUORUM,
    val abstimmungId: String? = null,
)

@Serializable
data class BeschlussInput(
    val tagesordnungspunktId: String? = null,
    val title: String,
    val text: String,
    val votesYes: Int,
    val votesNo: Int,
    val votesAbstain: Int,
    val status: BeschlussStatus,
)

@Serializable
data class SitzungDetailDto(
    val sitzung: SitzungDto,
    val tagesordnung: List<TagesordnungspunktDto>,
    val anwesenheit: List<AnwesenheitDto>,
    val beschluesse: List<BeschlussDto>,
    val quorum: QuorumResultDto,
)

/**
 * Structured template a client (or a later PDF/Serienbrief engine, see roadmap) renders — no
 * markdown/PDF generation happens in this wave, avoiding duplicate work with the planned
 * Serienbrief-/PDF-Engine (V0.4).
 */
@Serializable
data class ProtocolDraftDto(
    val sitzung: SitzungDto,
    val anwesenheit: List<AnwesenheitDto>,
    val tagesordnung: List<TagesordnungspunktDto>,
    val beschluesse: List<BeschlussDto>,
    val quorum: QuorumResultDto,
    val generatedAt: LocalDateTime,
)

/**
 * Antragsverwaltung (V0.2.2): pre-meeting motion submission targeting either a specific
 * [GremiumDto] or the [GremiumType.MITGLIEDERVERSAMMLUNG] singleton Gremium. Lifecycle:
 * [AntragStatus.EINGEREICHT] -> [AntragStatus.GEPRUEFT] | [AntragStatus.ABGELEHNT_VORPRUEFUNG]
 * (`reviewAntrag`) -> [AntragStatus.TERMINIERT] (`scheduleAntrag`, also reachable again from
 * [AntragStatus.VERTAGT] to support rescheduling) -> [AntragStatus.BESCHLOSSEN] |
 * [AntragStatus.ABGELEHNT] | [AntragStatus.VERTAGT] (`resolveAntrag`, mapped 1:1 from the
 * resulting [BeschlussStatus]) | [AntragStatus.ZURUECKGEZOGEN] (`withdrawAntrag`, only while
 * [AntragStatus.EINGEREICHT] unless performed by Gremium leadership/BOARD/ADMIN).
 */
@Serializable
enum class AntragStatus {
    EINGEREICHT,
    GEPRUEFT,
    ABGELEHNT_VORPRUEFUNG,
    TERMINIERT,
    BESCHLOSSEN,
    ABGELEHNT,
    VERTAGT,
    ZURUECKGEZOGEN,
}

@Serializable
enum class AntragPruefungsEntscheidung { ANNEHMEN, ABLEHNEN }

/**
 * `text` is the motion text itself and becomes [BeschlussDto.text] verbatim at resolution --
 * deliberately no amendment/"Aenderungsantrag" support in this wave (floor amendments are a
 * distinct Robert's-Rules-style feature with real complexity, out of scope here; see roadmap).
 */
@Serializable
data class AntragDto(
    val id: String,
    val targetGremiumId: String,
    val targetGremiumName: String,
    val targetGremiumType: GremiumType,
    val title: String,
    val begruendung: String,
    val text: String,
    val submitterMemberId: String,
    val submitterDisplayName: String,
    val status: AntragStatus,
    val submittedAt: LocalDateTime,
    val reviewedById: String?,
    val reviewedByDisplayName: String?,
    val reviewedAt: LocalDateTime?,
    val reviewNote: String?,
    val sitzungId: String?,
    val tagesordnungspunktId: String?,
    val beschlussId: String?,
)

@Serializable
data class AntragInput(
    val targetGremiumId: String,
    val title: String,
    val begruendung: String,
    val text: String,
)

@Serializable
data class AntragResolutionInput(
    val votesYes: Int,
    val votesNo: Int,
    val votesAbstain: Int,
    val status: BeschlussStatus,
)

/**
 * Meritokratische Abstimmungen (V0.2.3): lifecycle of an eBay/Vickrey basket auction opened on a
 * [AntragStatus.TERMINIERT] Antrag. [OFFEN] -> [GESCHLOSSEN] (`GovernanceService.closeAbstimmung`,
 * runs the Vickrey settlement and creates a [BeschlussDto] with
 * [ResolutionMode.MERITOKRATISCH]) | [ABGEBROCHEN] (`abortAbstimmung`, no settlement, no
 * Beschluss). Exactly one non-[ABGEBROCHEN] Abstimmung may exist per Antrag at a time — see
 * `GovernanceService.openAbstimmung`.
 */
@Serializable
enum class AbstimmungStatus { OFFEN, GESCHLOSSEN, ABGEBROCHEN }

/**
 * A basket (`abstimmung_option`). [basketTotalLtr] is computed server-side from this option's
 * ballots (never stored, never trusted from a client), see
 * `network.lapis.cloud.server.rpc.GovernanceService`.
 */
@Serializable
data class AbstimmungOptionDto(
    val id: String,
    val abstimmungId: String,
    val label: String,
    val position: Int,
    val basketTotalLtr: Decimal,
)

/**
 * The vote itself. `winnerOptionId`/`secondPriceLtr` are null while [status] is [AbstimmungStatus
 * .OFFEN], and both stay null even after close if the top two baskets tied (see
 * `network.lapis.cloud.server.rpc.AbstimmungSettlement` KDoc for the tie rule) — a tied vote
 * produces no winner, no charges, and resolves its Antrag to [AntragStatus.VERTAGT].
 */
@Serializable
data class AbstimmungDto(
    val id: String,
    val antragId: String,
    val sitzungId: String,
    val title: String,
    val status: AbstimmungStatus,
    val options: List<AbstimmungOptionDto>,
    val winnerOptionId: String?,
    val secondPriceLtr: Decimal?,
    val openedById: String,
    val openedByDisplayName: String,
    val openedAt: LocalDateTime,
    val closedAt: LocalDateTime?,
    val beschlussId: String?,
)

/**
 * The per-member ballot (`abstimmung_stimme`). [settledLtr] is null while the Abstimmung is
 * [AbstimmungStatus.OFFEN] and computed once at close — 0 for a losing ballot, never null once
 * settled.
 */
@Serializable
data class StimmeDto(
    val id: String,
    val abstimmungId: String,
    val optionId: String,
    val memberId: String,
    val memberDisplayName: String,
    val stakeLtr: Decimal,
    val settledLtr: Decimal?,
    val castAt: LocalDateTime,
)

/** Two options ("JA"/"NEIN") by default; ordering follows list order, `position` 0-based. */
@Serializable
data class AbstimmungOpenInput(
    val antragId: String,
    val optionLabels: List<String> = listOf("JA", "NEIN"),
)

@Serializable
data class StimmeInput(
    val abstimmungId: String,
    val optionId: String,
    val stakeLtr: Decimal,
)
