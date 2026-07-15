package network.lapis.cloud.server.security

import kotlin.uuid.Uuid

/**
 * Authorization helpers for Systemisches Konsensieren (V0.2.5). [canManageKonsensierung] reuses
 * [canRecordForSitzung]'s Gremium-leadership-or-privileged rule verbatim -- managing a
 * Konsensierung's lifecycle (opening it, freezing options, closing/reopening Bewertung,
 * evaluating, aborting) is the same kind of "who runs this Gremium's business" decision
 * [canRecordForSitzung] already governs for Sitzungen/Beschluesse/Abstimmungen/Wahlen. `gremiumId`
 * here is always the hosting Antrag's own target Gremium, mirroring
 * [network.lapis.cloud.server.security.canManageWahl]'s convention. Participation checks
 * (`addOption`/`castWiderstand`) are done directly against the live/frozen eligibility set in
 * `KonsensierungService` itself -- same house style as `WahlService.castStimme`'s inline
 * `WahlWahlberechtigtTable` check -- rather than as a separate extension function here, since
 * there is no standalone "is eligible" predicate reused outside that one call site.
 */
fun CurrentMember.canManageKonsensierung(gremiumId: Uuid): Boolean = canRecordForSitzung(gremiumId)
