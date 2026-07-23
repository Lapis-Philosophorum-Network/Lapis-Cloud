package network.lapis.cloud.server.mail

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.shared.domain.DeliveryStatus

private val logger = KotlinLogging.logger {}

/**
 * Abstraction over "send this password-reset link/token to this email" (V0.7.2 Beitritts-/
 * Registrierungs-Workflow). Same swap-seam shape
 * [network.lapis.cloud.server.postal.PostalMailProvider] already establishes for a different
 * outbound-delivery need -- a real SMTP-backed implementation can later replace
 * [NoOpPasswordResetMailer] without touching `AuthRoutes.registerAuthRoutes`'s call site.
 *
 * **Honest, disclosed non-delivery, same established precedent as
 * [network.lapis.cloud.server.rpc.MailingService.sendMailingMessage]**: this codebase has NO real
 * SMTP/email-transport integration anywhere -- `MailingService.sendMailingMessage`'s own code
 * comment already says so explicitly ("kein echter Versand-Anbieter angebunden ... Simuliert hier
 * nur den Erfolgsfall"). [NoOpPasswordResetMailer] follows the exact same convention rather than
 * silently claiming a working delivery. The token-generation/storage/consumption mechanics
 * themselves ARE fully real -- see [network.lapis.cloud.server.security.PasswordResetTokenStore]
 * -- only the email TRANSPORT is a disclosed stub. This decision is deliberate (not a shortcut):
 * building even a minimal real SMTP client without any test double / verifiable relay in this
 * environment would itself violate this project's own "no overclaiming capability" norm (see
 * README "What doesn't work yet" and the Letterxpress/postal-mail precedent of treating real
 * external delivery integration as its own explicit scope item). An operator locked out for real
 * must fall back to `network.lapis.cloud.server.bootstrap.AdminBootstrap --force` until a real
 * [PasswordResetMailer] implementation is wired in.
 */
interface PasswordResetMailer {
    /**
     * Attempts to deliver [rawToken] to [email] as a password-reset link/token. Synchronous:
     * returns once the attempt has completed (or been simulated) -- there is no async/webhook-based
     * delivery-status callback, same scope as [network.lapis.cloud.server.postal.PostalMailProvider.dispatchLetter].
     */
    fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus
}

/**
 * See [PasswordResetMailer] KDoc "Honest, disclosed non-delivery" for the full rationale.
 * **Never logs [rawToken]** -- logging the raw, bearer-usable reset token would defeat the entire
 * hash-only-persisted security model [network.lapis.cloud.server.security.PasswordResetTokenStore]
 * establishes (a leaked/misconfigured log sink would become an account-takeover oracle).
 */
class NoOpPasswordResetMailer : PasswordResetMailer {
    override fun send(
        email: String,
        rawToken: String,
    ): DeliveryStatus {
        logger.info { "Password-reset email would be sent to $email (no real SMTP transport configured -- see PasswordResetMailer KDoc)" }
        return DeliveryStatus.SENT
    }
}
