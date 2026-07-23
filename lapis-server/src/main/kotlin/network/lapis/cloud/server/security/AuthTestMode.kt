package network.lapis.cloud.server.security

/**
 * The structural guard behind [resolveCurrentMember]'s trusted-`X-Member-Id`-header fallback (see
 * that function's KDoc "905-Test-Falle"). Exists so this codebase's ~700 existing tests — 905
 * `header("X-Member-Id", ...)` call sites across ~40 `testApplication` blocks — never have to be
 * rewritten to perform a real password-login handshake.
 *
 * **Two independent locks, both must hold**:
 * 1. [System.getProperty] `"lapis.test.mode"` == `"true"` — set ONLY by
 *    `lapis-server/build.gradle.kts`'s `tasks.test { systemProperty(...) }` block. It is NEVER set
 *    by `main()`/`./gradlew run`/`installDist` — a real server process starting outside a Gradle
 *    `test` task JVM simply never has this property.
 * 2. [DeploymentMode.isH2InMemory] — a real deployment always sets `LAPIS_DB_URL` to a
 *    `jdbc:postgresql://...` URL, which this returns `false` for.
 *
 * Either lock alone already makes the fallback unreachable in a real (Postgres) deployment; both
 * together is defense in depth. [trustedHeaderAuthEnabled] is evaluated exactly once (a `val`, not
 * a function re-read on every request) — deliberately NOT a runtime-flippable feature flag, so
 * nothing in a running process can ever toggle it on after the JVM started.
 *
 * A THIRD, independent check lives directly in
 * [network.lapis.cloud.server.security.resolveFromTrustedHeader] itself
 * (`check(DeploymentMode.isH2InMemory())`) — belt-and-braces in case [trustedHeaderAuthEnabled]
 * were ever evaluated in a context where the system property was set (e.g. a misconfigured
 * process) but the database were somehow real; that inner check throws rather than silently
 * granting access.
 */
internal object AuthTestMode {
    val trustedHeaderAuthEnabled: Boolean =
        evaluate(testModeProperty = System.getProperty("lapis.test.mode"), isH2InMemory = DeploymentMode.isH2InMemory())

    /**
     * Pure, side-effect-free core of the decision above — split out purely so
     * `AuthTestModeSafetyTest` can exercise every combination of the two locks directly, without
     * needing to flip the real, JVM-wide `lapis.test.mode` system property (which, once read into
     * [trustedHeaderAuthEnabled] at class-init time, cannot be un-read within the same JVM anyway).
     */
    internal fun evaluate(
        testModeProperty: String?,
        isH2InMemory: Boolean,
    ): Boolean = testModeProperty == "true" && isH2InMemory
}
