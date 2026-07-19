package network.lapis.cloud.server.postal

/**
 * Abstraction over a physical-letter dispatch provider (V0.4.2 Letterxpress postal-mail dispatch).
 * See [network.lapis.cloud.server.rpc.PostalMailService] KDoc for the caller-side gating
 * (`OrganizationSettingsDto.postalMailEnabled`, role checks, bounded recipient lists) and
 * [LetterxpressPostalMailProvider] for the concrete (unverified wire-format) implementation.
 *
 * Deliberately takes plain scalar parameters rather than a data class wrapping [pdfBytes] --
 * avoids the well-known Kotlin data-class-with-`ByteArray` `equals`/`hashCode` pitfall (a data
 * class's generated `equals`/`hashCode` compares array *references*, not contents, silently
 * breaking value semantics), and there is no need anywhere in this codebase to compare two
 * dispatch requests for equality.
 */
interface PostalMailProvider {
    /**
     * Attempts to dispatch [pdfBytes] as a physical letter to the given recipient address.
     * Synchronous: returns once the provider's API call has completed (or failed) -- there is no
     * async/webhook-based delivery-status callback in this wave (see
     * [network.lapis.cloud.server.rpc.PostalMailService] KDoc "out of scope").
     */
    suspend fun dispatchLetter(
        pdfBytes: ByteArray,
        recipientName: String,
        recipientStreet: String,
        recipientPostalCode: String,
        recipientCity: String,
        recipientCountry: String,
    ): PostalDispatchOutcome
}

/** Result of one [PostalMailProvider.dispatchLetter] call. */
sealed interface PostalDispatchOutcome {
    /** [providerReference] is the provider's own tracking/job id, if it returned one. */
    data class Dispatched(
        val providerReference: String?,
    ) : PostalDispatchOutcome

    /**
     * [sanitizedErrorMessage] is always a short, templated/truncated description (HTTP status
     * code, provider-reported status) -- **never** the raw HTTP response body or a raw exception
     * message verbatim. See [LetterxpressPostalMailProvider] KDoc for why this discipline matters
     * even though credentials travel in the request body, not a log-visible URL.
     */
    data class Failed(
        val sanitizedErrorMessage: String,
    ) : PostalDispatchOutcome
}
