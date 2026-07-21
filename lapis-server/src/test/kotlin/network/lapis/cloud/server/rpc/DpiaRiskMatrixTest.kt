package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.DpiaRiskBand
import network.lapis.cloud.shared.domain.RiskLevel

/**
 * Pure unit tests for [DpiaRiskMatrix] -- no database needed. See that object's KDoc: this is a
 * visualization aid only, never the Art. 35 DSGVO DSFA-necessity determination.
 */
class DpiaRiskMatrixTest :
    FunSpec({
        test("band() is null whenever either input is null") {
            DpiaRiskMatrix.band(null, RiskLevel.HIGH) shouldBe null
            DpiaRiskMatrix.band(RiskLevel.HIGH, null) shouldBe null
            DpiaRiskMatrix.band(null, null) shouldBe null
        }

        test("band() covers all nine likelihood x severity combinations with the expected band") {
            DpiaRiskMatrix.band(RiskLevel.LOW, RiskLevel.LOW) shouldBe DpiaRiskBand.LOW
            DpiaRiskMatrix.band(RiskLevel.LOW, RiskLevel.MEDIUM) shouldBe DpiaRiskBand.LOW
            DpiaRiskMatrix.band(RiskLevel.MEDIUM, RiskLevel.LOW) shouldBe DpiaRiskBand.LOW
            DpiaRiskMatrix.band(RiskLevel.LOW, RiskLevel.HIGH) shouldBe DpiaRiskBand.MEDIUM
            DpiaRiskMatrix.band(RiskLevel.MEDIUM, RiskLevel.MEDIUM) shouldBe DpiaRiskBand.MEDIUM
            DpiaRiskMatrix.band(RiskLevel.HIGH, RiskLevel.LOW) shouldBe DpiaRiskBand.MEDIUM
            DpiaRiskMatrix.band(RiskLevel.MEDIUM, RiskLevel.HIGH) shouldBe DpiaRiskBand.HIGH
            DpiaRiskMatrix.band(RiskLevel.HIGH, RiskLevel.MEDIUM) shouldBe DpiaRiskBand.HIGH
            DpiaRiskMatrix.band(RiskLevel.HIGH, RiskLevel.HIGH) shouldBe DpiaRiskBand.CRITICAL
        }

        test("band() is monotonic -- increasing either input never decreases the resulting band") {
            val order = listOf(DpiaRiskBand.LOW, DpiaRiskBand.MEDIUM, DpiaRiskBand.HIGH, DpiaRiskBand.CRITICAL)
            val levels = listOf(RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH)
            for (severity in levels) {
                val bands = levels.map { likelihood -> DpiaRiskMatrix.band(likelihood, severity)!! }
                val ranks = bands.map { order.indexOf(it) }
                (ranks == ranks.sorted()) shouldBe true
            }
            for (likelihood in levels) {
                val bands = levels.map { severity -> DpiaRiskMatrix.band(likelihood, severity)!! }
                val ranks = bands.map { order.indexOf(it) }
                (ranks == ranks.sorted()) shouldBe true
            }
        }

        test("band() is symmetric -- likelihood and severity are interchangeable") {
            val levels = listOf(RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH)
            for (a in levels) {
                for (b in levels) {
                    DpiaRiskMatrix.band(a, b) shouldBe DpiaRiskMatrix.band(b, a)
                }
            }
        }
    })
