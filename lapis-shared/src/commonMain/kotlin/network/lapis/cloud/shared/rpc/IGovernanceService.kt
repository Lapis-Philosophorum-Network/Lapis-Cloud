package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
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
 * Explicitly out of scope for this wave (see roadmap's separate bullets): Antragsverwaltung
 * (pre-meeting motion submission), Meritokratische Abstimmungen, Demokratische Wahlen,
 * Systemisches Konsensieren. This wave's Beschlussbuch is a straightforward decision log with a
 * Ja/Nein/Enthaltung tally — not the meritocratic weighting algorithm.
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
}
