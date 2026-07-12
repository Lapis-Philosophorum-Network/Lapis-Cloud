package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
enum class GremiumType { VORSTAND, ARBEITSKREIS, AUSSCHUSS, SONSTIGES }

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
