package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.DpiaRiskBand
import network.lapis.cloud.shared.domain.RiskLevel

/**
 * Pure display-band helper for a DSFA's risk_likelihood x risk_severity pair, extracted so it is
 * unit-testable without a database -- same "pure logic extracted to a sibling file" idiom as
 * [PartyDonationComplianceCalculator]/[BreachDeadlineCalculator].
 *
 * **This is a visualization aid only, NOT the Art. 35 DSGVO DSFA-necessity determination.** Whether
 * a Datenschutz-Folgenabschaetzung is legally required for a given processing activity is
 * [network.lapis.cloud.shared.domain.DpiaAssessmentDto.dpiaRequired] -- a separate,
 * always-human-entered field this object never reads or influences. [band] only classifies how
 * severe the *documented* likelihood/severity pair looks at a glance (e.g. for a colored badge in a
 * list view); it carries no legal weight and is never persisted (see
 * `16-dsgvo-compliance.kuml.kts` file header).
 */
internal object DpiaRiskMatrix {
    /**
     * `null` iff either input is `null` (an incomplete/draft assessment has no band yet). Otherwise
     * a monotonic 3x3 matrix: LOW/LOW -> LOW, HIGH/HIGH -> CRITICAL, every off-diagonal combination
     * in between ordered so that increasing either input never decreases the resulting band.
     */
    fun band(
        likelihood: RiskLevel?,
        severity: RiskLevel?,
    ): DpiaRiskBand? {
        if (likelihood == null || severity == null) return null
        val score = ordinal(likelihood) + ordinal(severity)
        return when (score) {
            0 -> DpiaRiskBand.LOW // LOW/LOW
            1 -> DpiaRiskBand.LOW // LOW/MEDIUM or MEDIUM/LOW
            2 -> DpiaRiskBand.MEDIUM // LOW/HIGH, MEDIUM/MEDIUM, or HIGH/LOW
            3 -> DpiaRiskBand.HIGH // MEDIUM/HIGH or HIGH/MEDIUM
            else -> DpiaRiskBand.CRITICAL // HIGH/HIGH
        }
    }

    private fun ordinal(level: RiskLevel): Int =
        when (level) {
            RiskLevel.LOW -> 0
            RiskLevel.MEDIUM -> 1
            RiskLevel.HIGH -> 2
        }
}
