package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.PostalDeliveryLogDto
import network.lapis.cloud.shared.domain.PostalInvitationDispatchInput

/**
 * V0.4.2 Letterxpress postal-mail dispatch -- physically mails an already-generated
 * Beitragsrechnung/Spendenbescheinigung/Einladung to one (or, for Einladung, a small bounded set
 * of) member(s) via [network.lapis.cloud.server.postal.PostalMailProvider]. See
 * `network.lapis.cloud.server.rpc.PostalMailService` KDoc for the full implementation, the
 * `OrganizationSettingsDto.postalMailEnabled` opt-in gate (AVV requirement), and the sandbox/
 * live-mode distinction.
 *
 * Every dispatch method is an **explicit, human-triggered action** -- there is no automatic/
 * silent postal fallback anywhere in this codebase (e.g. when a member has no email on file).
 * Postal dispatch costs real money per letter and transfers PII to a third party, so both cost and
 * data-sharing require a conscious action by a TREASURER/BOARD/ADMIN, never an implicit one.
 */
@RpcService
interface IPostalMailService {
    /**
     * Role: TREASURER/BOARD/ADMIN. Regenerates and re-archives the Beitragsrechnung for
     * [contributionId] (same completeness guards as the free PDF route --
     * [network.lapis.cloud.server.routes.registerMailmergeRoutes] KDoc), then dispatches it by
     * post to the contribution's own member. Single recipient by construction -- no recipient
     * list needed.
     */
    suspend fun dispatchBeitragsrechnungByPost(contributionId: String): PostalDeliveryLogDto

    /**
     * Role: TREASURER/BOARD/ADMIN. Regenerates and re-archives the Spendenbescheinigung for
     * [journalEntryId], then dispatches it by post to the journal entry's donor. Single recipient
     * by construction -- no recipient list needed.
     */
    suspend fun dispatchSpendenbescheinigungByPost(journalEntryId: String): PostalDeliveryLogDto

    /**
     * Role: BOARD/ADMIN. [PostalInvitationDispatchInput.recipientMemberIds] is bounded (see
     * `MAX_POSTAL_INVITATION_RECIPIENTS` in `network.lapis.cloud.server.rpc.PostalMailService`) --
     * exceeding the cap is rejected with a 400 up front, no partial dispatch. Every recipient must
     * have a complete postal address; if even one is incomplete the whole call is rejected
     * (fail-closed), unlike the lenient free-PDF Einladung route. Generates one single-recipient
     * PDF per recipient (never archived -- Einladung stays ephemeral, same as the PDF-only route).
     */
    suspend fun dispatchEinladungByPost(input: PostalInvitationDispatchInput): List<PostalDeliveryLogDto>

    /** Role: TREASURER/BOARD/ADMIN. Full dispatch history, newest first. */
    suspend fun listPostalDeliveryLog(): List<PostalDeliveryLogDto>
}
