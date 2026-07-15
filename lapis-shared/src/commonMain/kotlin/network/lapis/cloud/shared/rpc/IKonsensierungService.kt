package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.KonsensierungDto
import network.lapis.cloud.shared.domain.KonsensierungOptionDto
import network.lapis.cloud.shared.domain.KonsensierungStatus
import network.lapis.cloud.shared.domain.SkErgebnisDto
import network.lapis.cloud.shared.domain.SkOpenInput
import network.lapis.cloud.shared.domain.SkOptionInput
import network.lapis.cloud.shared.domain.SkStimmzettelCastResultDto
import network.lapis.cloud.shared.domain.SkStimmzettelDto
import network.lapis.cloud.shared.domain.SkStimmzettelInput

/**
 * Systemisches Konsensieren (V0.2.5): lowest-cumulative-resistance consensus tool, structurally
 * distinct from [IGovernanceService]'s Meritokratische-Abstimmungen path and [IWahlService]'s
 * one-person-one-vote path -- see `network.lapis.cloud.server.rpc.KonsensierungService` for the
 * full lifecycle (`openKonsensierung` -> `addOption`/`removeOption` -> `freezeOptionen` ->
 * `castWiderstand` -> `closeBewertung` -> `auswerten`, with `reopenBewertung` as the
 * Diskussion-und-Wiederabstimmung loop back to `castWiderstand`) and
 * `03 Bereiche/Lapis Cloud/Systemisches Konsensieren.md` for the concept document this implements.
 *
 * Lighter-weight than [IWahlService]: no Wahlvorstand, no Vier-Augen-Prinzip on the tally -- a
 * Konsensierung is run directly by the hosting Antrag's target Gremium leadership (or BOARD/
 * ADMIN), since it is a consensus-finding tool, not a formal ballot. A Konsensierung opens on an
 * [network.lapis.cloud.shared.domain.AntragStatus.TERMINIERT] Antrag exactly like
 * [IWahlService.openWahl] does, and -- only when
 * [network.lapis.cloud.shared.domain.SkVerbindlichkeit.BESCHLUSS] -- its tally is written into
 * the same Beschlussbuch [IGovernanceService.recordBeschluss]/[IGovernanceService.resolveAntrag]/
 * [IGovernanceService.closeAbstimmung]/[IWahlService.auszaehlen] use, tagged
 * [network.lapis.cloud.shared.domain.ResolutionMode.SYSTEMISCHER_KONSENS]. A
 * [network.lapis.cloud.shared.domain.SkVerbindlichkeit.SONDIERUNG] Konsensierung (the default)
 * never writes a Beschluss -- purely advisory.
 *
 * Anonymity is a practical DB-level table-split, not cryptography -- the identical mechanism
 * [IWahlService] already uses (see `network.lapis.cloud.server.rpc.KonsensierungService` KDoc).
 */
@RpcService
interface IKonsensierungService {
    /**
     * Role: target Gremium leadership (of the Antrag's own target Gremium) or BOARD/ADMIN.
     * Requires [network.lapis.cloud.shared.domain.AntragStatus.TERMINIERT] and no already-open/
     * -resolved Konsensierung for this Antrag. Transitions the new Konsensierung to
     * [KonsensierungStatus.SAMMLUNG]. If [SkOpenInput.passivloesungAuto], auto-inserts a
     * `KonsensierungOptionDto.isPassivloesung` status-quo option.
     */
    suspend fun openKonsensierung(input: SkOpenInput): KonsensierungDto

    /**
     * Role: any member eligible to participate in this Konsensierung (mirrors the concept
     * document's "Teilnehmer bringen Optionen ein" collection phase). Requires
     * [KonsensierungStatus.SAMMLUNG]. Rejected once
     * `KonsensierungService.MAX_OPTIONEN_HARD` options already exist.
     */
    suspend fun addOption(
        konsensierungId: String,
        input: SkOptionInput,
    ): KonsensierungOptionDto

    /**
     * Role: the option's own proposer, or target Gremium leadership/BOARD/ADMIN. Requires
     * [KonsensierungStatus.SAMMLUNG]. Never removes the auto-inserted Passivloesung option.
     */
    suspend fun removeOption(optionId: String): KonsensierungDto

    suspend fun listOptions(konsensierungId: String): List<KonsensierungOptionDto>

    /**
     * Role: target Gremium leadership or BOARD/ADMIN. Requires [KonsensierungStatus.SAMMLUNG].
     * Snapshots eligibility (frozen at this moment, mirrors [IWahlService.openVoting]) into
     * `konsensierung_stimmberechtigt` and transitions to [KonsensierungStatus.BEWERTUNG].
     */
    suspend fun freezeOptionen(konsensierungId: String): KonsensierungDto

    /**
     * Role: any member in the eligibility snapshot taken at [freezeOptionen] for the current
     * [KonsensierungDto.runde]. Requires [KonsensierungStatus.BEWERTUNG]. The ballot must rate
     * every frozen option exactly once -- see [SkStimmzettelInput] KDoc. Exactly one ballot per
     * member per Bewertungsrunde -- a second attempt is rejected, not an upsert, same rationale as
     * [IWahlService.castStimme]. Enforced at the DB level, not just the application-level
     * pre-check.
     */
    suspend fun castWiderstand(input: SkStimmzettelInput): SkStimmzettelCastResultDto

    /** Role: target Gremium leadership or BOARD/ADMIN. Requires [KonsensierungStatus.BEWERTUNG]. */
    suspend fun closeBewertung(konsensierungId: String): KonsensierungDto

    /**
     * Role: target Gremium leadership or BOARD/ADMIN. Requires [KonsensierungStatus.GESCHLOSSEN].
     * Runs [network.lapis.cloud.server.rpc.computeKonsensierungErgebnis], transitions to
     * [KonsensierungStatus.AUSGEWERTET], and -- only when
     * [network.lapis.cloud.shared.domain.SkVerbindlichkeit.BESCHLUSS] and the result is resolved
     * (not [network.lapis.cloud.shared.domain.SkTiebreakRegel.WIEDERHOLUNG]-tied) -- writes the
     * resulting Beschluss and transitions the Antrag.
     */
    suspend fun auswerten(konsensierungId: String): SkErgebnisDto

    /**
     * Role: target Gremium leadership or BOARD/ADMIN. Requires
     * [KonsensierungStatus.GESCHLOSSEN]/[KonsensierungStatus.AUSGEWERTET] and
     * [network.lapis.cloud.shared.domain.KonsensierungDto.runde] `<`
     * [network.lapis.cloud.shared.domain.KonsensierungDto.maxRunden]. Transitions back to
     * [KonsensierungStatus.BEWERTUNG] with `runde` incremented by one -- prior rounds' ballots are
     * retained (DSGVO retention), only the new `runde`'s ballots count toward the next tally.
     */
    suspend fun reopenBewertung(konsensierungId: String): KonsensierungDto

    /** Role: target Gremium leadership or BOARD/ADMIN. Requires the Konsensierung not already [KonsensierungStatus.AUSGEWERTET]/[KonsensierungStatus.ABGEBROCHEN]. */
    suspend fun abortKonsensierung(konsensierungId: String): KonsensierungDto

    suspend fun getKonsensierung(konsensierungId: String): KonsensierungDto

    suspend fun listKonsensierungen(
        antragId: String? = null,
        status: KonsensierungStatus? = null,
    ): List<KonsensierungDto>

    /**
     * Transparency read of every ballot cast so far in the *current* [KonsensierungDto.runde].
     * For a [KonsensierungDto.geheim] Konsensierung, `memberId`/`memberDisplayName` are always
     * `null`, and `widerstaende` is empty until [KonsensierungStatus.AUSGEWERTET] -- see
     * [SkStimmzettelDto] KDoc.
     */
    suspend fun listWiderstaende(konsensierungId: String): List<SkStimmzettelDto>
}
