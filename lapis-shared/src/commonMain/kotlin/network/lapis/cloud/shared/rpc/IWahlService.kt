package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.KandidaturDto
import network.lapis.cloud.shared.domain.KandidaturInput
import network.lapis.cloud.shared.domain.ReceiptVerificationDto
import network.lapis.cloud.shared.domain.StimmzettelCastResultDto
import network.lapis.cloud.shared.domain.StimmzettelDto
import network.lapis.cloud.shared.domain.StimmzettelInput
import network.lapis.cloud.shared.domain.WahlDto
import network.lapis.cloud.shared.domain.WahlErgebnisDto
import network.lapis.cloud.shared.domain.WahlOpenInput
import network.lapis.cloud.shared.domain.WahlStatus
import network.lapis.cloud.shared.domain.WahlvorstandDto

/**
 * Demokratische Wahlen (V0.2.4): one-person-one-vote elections, structurally distinct from
 * [IGovernanceService]'s Meritokratische-Abstimmungen path (LTR-weighted eBay/Vickrey basket
 * auction). Kept as its own `@RpcService` interface rather than folded into [IGovernanceService]
 * because a Wahl's lifecycle (Wahlvorstand appointment, Kandidatenliste, secret-ballot casting,
 * Vier-Augen-gated Auszaehlung, receipt verification) is a materially different shape than
 * Sitzung/Antrag/Abstimmung's -- see `network.lapis.cloud.server.rpc.WahlService` for the full
 * lifecycle and `03 Bereiche/Lapis Cloud/Demokratische Wahlen.md` for the concept document this
 * implements.
 *
 * A Wahl opens on an [network.lapis.cloud.shared.domain.AntragStatus.TERMINIERT] Antrag exactly
 * like [IGovernanceService.openAbstimmung] does, and its tally is written into the *same*
 * Beschlussbuch [IGovernanceService.recordBeschluss]/[IGovernanceService.resolveAntrag]/
 * [IGovernanceService.closeAbstimmung] use, tagged
 * [network.lapis.cloud.shared.domain.ResolutionMode.DEMOKRATISCH].
 *
 * Explicitly out of scope for this wave (see [network.lapis.cloud.shared.domain.WahlTyp] KDoc):
 * [network.lapis.cloud.shared.domain.WahlTyp.LISTENWAHL]/
 * [network.lapis.cloud.shared.domain.WahlTyp.RANGLISTENWAHL] (rejected by [openWahl] with a
 * `network.lapis.cloud.server.rpc.ConflictException`), cryptographic ballot secrecy (a practical
 * DB-level table-split is used instead -- see `network.lapis.cloud.server.db.tables.WahlTables`
 * KDoc), and threshold-signature Vier-Augen (modeled as a plain N-of-M approval count instead).
 */
@RpcService
interface IWahlService {
    /**
     * Role: target Gremium leadership (of the Antrag's own target Gremium, not necessarily
     * [network.lapis.cloud.shared.domain.WahlOpenInput.zielGremiumId]) or BOARD/ADMIN. Requires
     * [network.lapis.cloud.shared.domain.AntragStatus.TERMINIERT] and no already-open/-resolved
     * Wahl for this Antrag.
     */
    suspend fun openWahl(input: WahlOpenInput): WahlDto

    /**
     * Role: target Gremium leadership or BOARD/ADMIN. Requires
     * [WahlStatus.VORBEREITUNG]. Replaces any prior appointment wholesale (idempotent
     * re-appointment before voting starts). At least 3, at most 25 distinct members. Rejects any
     * member currently an active member of [network.lapis.cloud.shared.domain.WahlDto
     * .zielGremiumId] when that Gremium's [network.lapis.cloud.shared.domain.GremiumType] is
     * [network.lapis.cloud.shared.domain.GremiumType.VORSTAND] (Wahlvorstand/Vorstand separation).
     */
    suspend fun appointWahlvorstand(
        wahlId: String,
        memberIds: List<String>,
    ): List<WahlvorstandDto>

    suspend fun listWahlvorstand(wahlId: String): List<WahlvorstandDto>

    /**
     * Role: self, [network.lapis.cloud.shared.domain.MemberStatus.AKTIV] (self-nomination only,
     * no third-party nomination in this wave). Requires [WahlStatus.VORBEREITUNG] and a
     * [network.lapis.cloud.shared.domain.WahlTyp.EINZELWAHL]/
     * [network.lapis.cloud.shared.domain.WahlTyp.MEHRFACHWAHL] Wahl.
     */
    suspend fun submitKandidatur(
        wahlId: String,
        input: KandidaturInput,
    ): KandidaturDto

    /**
     * Role: the candidate themself while [WahlStatus.VORBEREITUNG], or that Wahl's target Gremium
     * leadership/BOARD/ADMIN at any status -- mirrors
     * [IGovernanceService.withdrawAntrag]'s asymmetric rule.
     */
    suspend fun withdrawKandidatur(id: String): KandidaturDto

    suspend fun listKandidaturen(wahlId: String): List<KandidaturDto>

    /**
     * Role: target Gremium leadership or BOARD/ADMIN. Requires [WahlStatus.VORBEREITUNG] and at
     * least one non-withdrawn Kandidatur. Freezes the candidate list into
     * [network.lapis.cloud.shared.domain.WahlOptionDto] rows.
     */
    suspend fun freigebenKandidatenliste(wahlId: String): WahlDto

    /**
     * Role: Wahlvorstand member or BOARD/ADMIN. Snapshots eligibility (frozen at this moment, not
     * re-evaluated per ballot) into the Wahl's electorate and opens voting.
     */
    suspend fun openVoting(wahlId: String): WahlDto

    /**
     * Role: any member in the eligibility snapshot taken at [openVoting]. Requires
     * [WahlStatus.OFFEN]. Exactly one ballot per member -- a second attempt is rejected, not an
     * upsert (unlike [IGovernanceService.castStimme]), because a secret ballot cannot distinguish
     * "correcting my own vote" from "someone else voting again" once identity is decoupled from
     * ballot content. Enforced at the DB level, not just the application-level pre-check -- see
     * `network.lapis.cloud.server.db.tables.WahlTables` KDoc.
     */
    suspend fun castStimme(input: StimmzettelInput): StimmzettelCastResultDto

    /** Role: Wahlvorstand member or BOARD/ADMIN. Requires [WahlStatus.OFFEN]. */
    suspend fun closeVoting(wahlId: String): WahlDto

    /**
     * Role: an actual [network.lapis.cloud.shared.domain.WahlvorstandDto] member of *this* Wahl
     * specifically -- deliberately does not accept a BOARD/ADMIN privileged bypass here, unlike
     * every other role check in this interface, because the point of the N-of-M
     * Vier-Augen-Prinzip count is that it reflects genuinely distinct named Wahlvorstand
     * approvals. Requires [WahlStatus.GESCHLOSSEN] and no prior approval by the same member.
     */
    suspend fun freigebenAuszaehlung(wahlId: String): WahlDto

    /**
     * Role: Wahlvorstand member or BOARD/ADMIN. Requires [WahlStatus.GESCHLOSSEN] and at least
     * [network.lapis.cloud.shared.domain.WahlDto.tallyThreshold] [freigebenAuszaehlung] approvals.
     * Runs the pure tally, writes the resulting Beschluss, transitions the Antrag, and -- for a
     * decisive personnel result -- seats the winners into
     * [network.lapis.cloud.shared.domain.WahlDto.zielGremiumId].
     */
    suspend fun auszaehlen(wahlId: String): WahlErgebnisDto

    /** Role: target Gremium leadership or BOARD/ADMIN. Requires the Wahl not already [WahlStatus.AUSGEZAEHLT]/[WahlStatus.ABGEBROCHEN]. */
    suspend fun abortWahl(wahlId: String): WahlDto

    suspend fun getWahl(wahlId: String): WahlDto

    suspend fun listWahlen(
        antragId: String? = null,
        status: WahlStatus? = null,
    ): List<WahlDto>

    /**
     * Transparency read of every ballot cast so far. For a [network.lapis.cloud.shared.domain
     * .WahlDto.geheim] Wahl, `memberId`/`memberDisplayName` are always `null` in the returned
     * [StimmzettelDto], and `selectedOptionLabels` is empty until the Wahl reaches
     * [WahlStatus.AUSGEZAEHLT] -- see that DTO's KDoc.
     */
    suspend fun listStimmzettel(wahlId: String): List<StimmzettelDto>

    /**
     * Role: any authenticated member -- the receipt code itself is the real access gate (matches
     * the Helios-style "anyone holding the code may check" model), not the caller's identity.
     */
    suspend fun verifyReceipt(
        wahlId: String,
        receiptCode: String,
    ): ReceiptVerificationDto
}
