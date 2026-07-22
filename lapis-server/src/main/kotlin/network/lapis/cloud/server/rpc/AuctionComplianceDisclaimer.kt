package network.lapis.cloud.server.rpc

import java.security.MessageDigest

/**
 * The versioned, hashed legal-risk disclaimer an ADMIN must be shown -- and echo back verbatim
 * (via [matches]) -- before [AuctionService.enableAuction] will flip
 * `OrganizationSettings.auctionEnabled` on. See [IAuctionService][network.lapis.cloud.shared.rpc.IAuctionService]
 * KDoc "The disclaimer-acknowledgment mechanism" for the full rationale.
 *
 * **Legal-verification disclaimer, same class as
 * [PartyDonationComplianceCalculator]/[DsgvoComplianceService]'s own KDoc**: [TEXT] below names
 * the risk areas this wave's authors identified as relevant to an LTR marketplace at the time it
 * was written -- Zahlungsdiensteaufsicht (ZAG/MiCAR), Gewerbeordnung, Steuerrecht
 * (UStG/EStG/GewStG), Verbraucherschutz, PartG (fuer Parteiinstanzen), Geldwaeschegesetz (GwG).
 * **This is NOT a reviewed legal conclusion and NOT automated Rechtsberatung** -- exactly the same
 * "Selbstauskunft, keine automatisierte Rechtsberatung" framing
 * `PartyDonationComplianceCalculator`/`DsgvoComplianceService` already establish for their own
 * domains. The platform performs no legal classification of its own; [TEXT] only documents that an
 * ADMIN was shown these named risk areas before opting in. Verify against current law and, ideally,
 * a lawyer before relying on this for a real organization's actual legal posture.
 *
 * [VERSION]/[TEXT]/[SHA256] are all `const`/`val` (immutable at runtime) -- a future wording change
 * requires a NEW [VERSION] string, never an in-place edit of [TEXT] under the same version (that
 * would silently invalidate the audit trail's claim that a given ADMIN saw a given version's exact
 * wording).
 */
object AuctionComplianceDisclaimer {
    const val VERSION: String = "2026-07-22.v1"

    val TEXT: String =
        """
        Rechtshinweis zur LTR-Auktion (Marktplatz fuer LTR-Inhaber)

        Bevor Sie die Auktionsfunktion fuer Ihre Organisation aktivieren, bestaetigen Sie, dass
        folgende Risikobereiche geprueft und ggf. mit eigener rechtlicher Beratung geklaert wurden:

        - Zahlungsdiensteaufsicht (ZAG/MiCAR): ob der Betrieb einer LTR-Auktion eine erlaubnispflichtige
          Zahlungsdienstleistung oder ein Kryptowerte-Geschaeft im Sinne des Zahlungsdiensteaufsichtsgesetzes
          bzw. der MiCA-Verordnung darstellt.
        - Gewerbeordnung (GewO): ob der Betrieb eines Marktplatzes eine gewerberechtliche Erlaubnis
          oder Anzeige erfordert.
        - Steuerrecht (UStG/EStG/GewStG): die umsatz-, einkommen- und gewerbesteuerliche Behandlung
          der Auktionserloese und der Listing-Gebuehr.
        - Verbraucherschutz: ob und wie verbraucherschutzrechtliche Informations- und
          Widerrufspflichten auf diesen Marktplatz anwendbar sind.
        - Parteiengesetz (PartG): bei Nutzung durch eine Parteigliederung, ob Auktionserloese als
          sonstige Einnahme im Sinne des PartG zu behandeln und im Rechenschaftsbericht auszuweisen sind.
        - Geldwaeschegesetz (GwG): ob geldwaescherechtliche Sorgfaltspflichten (Identifizierung,
          Meldewesen) fuer den Auktionsbetrieb gelten.

        Dieser Hinweis stellt KEINE Rechtsberatung dar und ersetzt keine Pruefung durch eine
        Rechtsanwaeltin/einen Rechtsanwalt. Die Plattform selbst nimmt keine rechtliche Einordnung
        vor und trifft keine automatisierte Entscheidung ueber die Zulaessigkeit des Betriebs. Die
        Verantwortung fuer die Einhaltung aller einschlaegigen Vorschriften liegt ausschliesslich
        beim Betreiber der jeweiligen Organisation. Es wird empfohlen, die Auktionsfunktion zunaechst
        nur in einem eng begrenzten Pilotbetrieb mit klarer Wertobergrenze zu nutzen, bis die eigene
        Rechtslage abschliessend geklaert ist.
        """.trimIndent()

    /**
     * `SHA-256` over `"$VERSION\n$TEXT"` -- a fresh [MessageDigest] instance PER CALL (thread-safe,
     * see the codebase's standing security checklist "Kryptografie: MessageDigest neue Instanz pro
     * Aufruf"), computed once at class-init time since [VERSION]/[TEXT] are themselves immutable.
     */
    val SHA256: String = sha256Hex("$VERSION\n$TEXT")

    /**
     * `true` iff [version] equals [VERSION] AND [sha256] equals [SHA256] (case-insensitive hex
     * comparison, constant-time via [MessageDigest.isEqual] so a malformed/tampered hash cannot be
     * distinguished from a correct-length-wrong-value one by timing). A malformed (non-hex, wrong
     * length) [sha256] is treated as a non-match, never thrown.
     */
    fun matches(
        version: String,
        sha256: String,
    ): Boolean {
        if (version != VERSION) return false
        val provided = runCatching { hexToBytes(sha256) }.getOrNull() ?: return false
        val expected = hexToBytes(SHA256)
        return MessageDigest.isEqual(provided, expected)
    }

    private fun sha256Hex(input: String): String {
        val digestBytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digestBytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(hex.length / 2) { i ->
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "invalid hex character in '$hex'" }
            ((high shl 4) + low).toByte()
        }
    }
}
