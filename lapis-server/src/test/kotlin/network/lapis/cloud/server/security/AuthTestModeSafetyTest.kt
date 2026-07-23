package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Exercises [AuthTestMode.evaluate] -- the pure core of [AuthTestMode.trustedHeaderAuthEnabled] --
 * across all four combinations of its two independent locks, WITHOUT touching the real, JVM-wide
 * `lapis.test.mode` system property (see [AuthTestMode] KDoc "Pure, side-effect-free core"). This
 * is the structural guard behind [resolveCurrentMember]'s legacy `X-Member-Id` trusted-header
 * fallback -- referenced by name from [AuthTestMode]'s own class KDoc.
 */
class AuthTestModeSafetyTest :
    FunSpec({
        test("both locks held -- trusted-header fallback is enabled") {
            AuthTestMode.evaluate(testModeProperty = "true", isH2InMemory = true) shouldBe true
        }

        test("system property missing (null) -- fallback is disabled even against H2") {
            AuthTestMode.evaluate(testModeProperty = null, isH2InMemory = true) shouldBe false
        }

        test("system property set to something other than the literal string 'true' -- fallback is disabled") {
            AuthTestMode.evaluate(testModeProperty = "false", isH2InMemory = true) shouldBe false
            AuthTestMode.evaluate(testModeProperty = "True", isH2InMemory = true) shouldBe false
            AuthTestMode.evaluate(testModeProperty = "1", isH2InMemory = true) shouldBe false
            AuthTestMode.evaluate(testModeProperty = "", isH2InMemory = true) shouldBe false
        }

        test("real (non-H2) database -- fallback is disabled even with the system property set") {
            AuthTestMode.evaluate(testModeProperty = "true", isH2InMemory = false) shouldBe false
        }

        test("neither lock held -- fallback is disabled") {
            AuthTestMode.evaluate(testModeProperty = null, isH2InMemory = false) shouldBe false
        }

        test(
            "trustedHeaderAuthEnabled is actually enabled in this test JVM (lapis.test.mode=true set by build.gradle.kts, H2 default in effect)",
        ) {
            // Confirms the wiring end to end: the Gradle `test` task's systemProperty(...) block
            // actually reaches this JVM, and DeploymentMode.isH2InMemory() actually holds under the
            // default (LAPIS_DB_URL unset) test configuration -- the exact precondition every one
            // of this codebase's ~700 existing tests relies on for their `header("X-Member-Id",
            // ...)` calls to keep working.
            AuthTestMode.trustedHeaderAuthEnabled shouldBe true
        }
    })
