package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AbstimmungDto
import network.lapis.cloud.shared.domain.AbstimmungOpenInput
import network.lapis.cloud.shared.domain.AntragDto
import network.lapis.cloud.shared.domain.AntragInput
import network.lapis.cloud.shared.domain.AntragPruefungsEntscheidung
import network.lapis.cloud.shared.domain.AntragResolutionInput
import network.lapis.cloud.shared.domain.AntragStatus
import network.lapis.cloud.shared.domain.AnwesenheitDto
import network.lapis.cloud.shared.domain.AnwesenheitInput
import network.lapis.cloud.shared.domain.BeschlussDto
import network.lapis.cloud.shared.domain.BeschlussInput
import network.lapis.cloud.shared.domain.GremiumDto
import network.lapis.cloud.shared.domain.GremiumInput
import network.lapis.cloud.shared.domain.GremiumMitgliedschaftDto
import network.lapis.cloud.shared.domain.GremiumMitgliedschaftInput
import network.lapis.cloud.shared.domain.ProtocolDraftDto
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.SitzungDetailDto
import network.lapis.cloud.shared.domain.SitzungDto
import network.lapis.cloud.shared.domain.SitzungInput
import network.lapis.cloud.shared.domain.SitzungsStatus
import network.lapis.cloud.shared.domain.StimmeDto
import network.lapis.cloud.shared.domain.StimmeInput
import network.lapis.cloud.shared.domain.TagesordnungspunktDto
import network.lapis.cloud.shared.domain.TagesordnungspunktInput

/**
 * Gremien- und Sitzungsverwaltung (V0.2.1): Gremien/Arbeitskreise, Mitgliedschaften darin,
 * Sitzungen mit Tagesordnung/Anwesenheit/Beschlussfaehigkeit sowie ein Beschlussbuch. Reads
 * (listGremien/getSitzungDetail/listBeschluesse/etc.) are open to any authenticated member —
 * a deliberate simplification versus [network.lapis.cloud.shared.domain.DocumentAccessLevel]'s
 * three-tier model, to keep this wave's scope bounded; worth revisiting if some Gremien need
 * confidentiality. Write operations that manage a specific Gremium's meetings/agenda/attendance/
 * resolutions require that Gremium's leadership role (VORSITZ/STELLV_VORSITZ/SCHRIFTFUEHRUNG) or
 * global BOARD/ADMIN — see `network.lapis.cloud.server.security.GovernanceAuthorization`.
 *
 * Antragsverwaltung (V0.2.2) extends this same interface rather than fragmenting into a parallel
 * `IAntragService` — an Antrag's lifecycle is tightly coupled to Sitzung/Tagesordnungspunkt/
 * Beschluss, which this interface already spans. See [AntragDto] KDoc for the full lifecycle and
 * `GovernanceAuthorization.canSubmitAntrag` for submission rules (broad participation right for
 * the Mitgliederversammlung, any-role Gremium membership for a specific Gremium).
 *
 * Meritokratische Abstimmungen (V0.2.3) extends this same interface once more: an eBay/Vickrey
 * basket auction opened on a [AntragStatus.TERMINIERT] Antrag (`openAbstimmung`), per-member LTR
 * staking into one of the auction's baskets (`castStimme`), and a settlement close
 * (`closeAbstimmung`) that runs the Vickrey settlement and writes into the *same* Beschlussbuch as
 * [recordBeschluss]/[resolveAntrag] via [network.lapis.cloud.shared.domain.ResolutionMode
 * .MERITOKRATISCH] — see [network.lapis.cloud.shared.domain.AbstimmungDto] KDoc for the full
 * lifecycle and `network.lapis.cloud.server.rpc.AbstimmungSettlement` for the settlement algorithm
 * itself. This is a parallel *resolution* path for an already-[AntragStatus.TERMINIERT] Antrag,
 * not a parallel submission/review/scheduling pipeline — those steps are unchanged.
 *
 * Explicitly out of scope for this wave (see roadmap's separate bullets): Demokratische Wahlen,
 * Systemisches Konsensieren, floor amendments to an Antrag's text. The [recordBeschluss]/
 * [resolveAntrag] Gremium-Quorum path remains a straightforward decision log with a
 * Ja/Nein/Enthaltung tally for every Antrag that does not go through a meritocratic Abstimmung.
 */
@RpcService
interface IGovernanceService {
    suspend fun listGremien(activeOnly: Boolean = true): List<GremiumDto>

    /** Role: BOARD/ADMIN — committee structure itself is an org-wide governance decision. */
    suspend fun createGremium(input: GremiumInput): GremiumDto

    /** Role: BOARD/ADMIN. */
    suspend fun updateGremium(
        id: String,
        input: GremiumInput,
    ): GremiumDto

    suspend fun listGremiumMitglieder(
        gremiumId: String,
        activeOnly: Boolean = true,
    ): List<GremiumMitgliedschaftDto>

    /** Role: BOARD/ADMIN. */
    suspend fun addGremiumMitglied(
        gremiumId: String,
        input: GremiumMitgliedschaftInput,
    ): GremiumMitgliedschaftDto

    /** Role: BOARD/ADMIN. */
    suspend fun endGremiumMitgliedschaft(
        mitgliedschaftId: String,
        until: LocalDate,
    ): GremiumMitgliedschaftDto

    suspend fun listSitzungen(
        gremiumId: String? = null,
        status: SitzungsStatus? = null,
    ): List<SitzungDto>

    suspend fun getSitzungDetail(sitzungId: String): SitzungDetailDto

    /** Role: Gremium-Vorsitz/-Stellv./Schriftfuehrung/Board/Admin (see class KDoc). */
    suspend fun createSitzung(input: SitzungInput): SitzungDto

    /** Role: Gremium-Vorsitz/-Stellv./Schriftfuehrung/Board/Admin. */
    suspend fun updateSitzungStatus(
        sitzungId: String,
        status: SitzungsStatus,
    ): SitzungDto

    /** Role: Gremium-Vorsitz/-Stellv./Schriftfuehrung/Board/Admin. */
    suspend fun addTagesordnungspunkt(
        sitzungId: String,
        input: TagesordnungspunktInput,
    ): TagesordnungspunktDto

    /** Role: Gremium-Vorsitz/-Stellv./Schriftfuehrung/Board/Admin. */
    suspend fun removeTagesordnungspunkt(id: String)

    /** Role: Gremium-Vorsitz/-Stellv./Schriftfuehrung/Board/Admin. */
    suspend fun recordAttendance(
        sitzungId: String,
        input: AnwesenheitInput,
    ): AnwesenheitDto

    suspend fun getAttendance(sitzungId: String): List<AnwesenheitDto>

    suspend fun checkQuorum(sitzungId: String): QuorumResultDto

    /** Role: Gremium-Vorsitz/-Stellv./Schriftfuehrung/Board/Admin. */
    suspend fun recordBeschluss(
        sitzungId: String,
        input: BeschlussInput,
    ): BeschlussDto

    suspend fun listBeschluesse(
        gremiumId: String? = null,
        sitzungId: String? = null,
    ): List<BeschlussDto>

    suspend fun generateProtocolDraft(sitzungId: String): ProtocolDraftDto

    /**
     * Role: any member with [network.lapis.cloud.shared.domain.MemberStatus.AKTIV] when the
     * target is the Mitgliederversammlung; any active [GremiumMitgliedschaftDto] (any
     * [network.lapis.cloud.shared.domain.GremiumRolle]) of the target Gremium otherwise; or
     * BOARD/ADMIN. See `GovernanceAuthorization.canSubmitAntrag`.
     */
    suspend fun submitAntrag(input: AntragInput): AntragDto

    suspend fun listAntraege(
        targetGremiumId: String? = null,
        status: AntragStatus? = null,
    ): List<AntragDto>

    suspend fun getAntrag(id: String): AntragDto

    /**
     * Role: the submitter themself while [AntragStatus.EINGEREICHT], or that Gremium's leadership/
     * BOARD/ADMIN at any status.
     */
    suspend fun withdrawAntrag(id: String): AntragDto

    /** Role: target Gremium leadership (VORSITZ/STELLV_VORSITZ/SCHRIFTFUEHRUNG) or BOARD/ADMIN. */
    suspend fun reviewAntrag(
        id: String,
        decision: AntragPruefungsEntscheidung,
        note: String? = null,
    ): AntragDto

    /** Role: target Gremium leadership or BOARD/ADMIN. Requires [AntragStatus.GEPRUEFT] or [AntragStatus.VERTAGT]. */
    suspend fun scheduleAntrag(
        id: String,
        sitzungId: String,
        position: Int,
    ): AntragDto

    /** Role: target Gremium leadership or BOARD/ADMIN. Requires [AntragStatus.TERMINIERT]. */
    suspend fun resolveAntrag(
        id: String,
        input: AntragResolutionInput,
    ): AntragDto

    /**
     * Role: target Gremium leadership or BOARD/ADMIN. Requires [AntragStatus.TERMINIERT] and no
     * already-open/-closed Abstimmung for this Antrag.
     */
    suspend fun openAbstimmung(input: AbstimmungOpenInput): AbstimmungDto

    /**
     * Role: any member eligible for the Abstimmung's underlying Sitzung/Gremium (same
     * eligibility set `checkQuorum` uses for that Sitzung) — see
     * `network.lapis.cloud.server.rpc.GovernanceService.castStimme` KDoc. Requires
     * [network.lapis.cloud.shared.domain.AbstimmungStatus.OFFEN]. Upserts one ballot per member;
     * a second call overwrites the member's own prior stake/option, it does not add a second
     * ballot.
     */
    suspend fun castStimme(input: StimmeInput): StimmeDto

    /**
     * Role: target Gremium leadership or BOARD/ADMIN. Requires
     * [network.lapis.cloud.shared.domain.AbstimmungStatus.OFFEN]; runs the Vickrey settlement,
     * writes the resulting Beschluss, and transitions the underlying Antrag.
     */
    suspend fun closeAbstimmung(abstimmungId: String): AbstimmungDto

    /**
     * Role: target Gremium leadership or BOARD/ADMIN. Requires
     * [network.lapis.cloud.shared.domain.AbstimmungStatus.OFFEN]; no settlement runs, no Beschluss
     * is created, the underlying Antrag stays [AntragStatus.TERMINIERT].
     */
    suspend fun abortAbstimmung(abstimmungId: String): AbstimmungDto

    suspend fun getAbstimmung(abstimmungId: String): AbstimmungDto

    /**
     * Transparency read of every ballot cast so far, including staked/settled amounts — open to
     * any authenticated member, not just the Abstimmung's own participants. Pseudonymization of
     * ballots is future scope (see the implementation plan's open decision points); `memberId`/
     * `memberDisplayName` are exposed like every other DTO in this interface for now.
     */
    suspend fun listStimmen(abstimmungId: String): List<StimmeDto>
}
