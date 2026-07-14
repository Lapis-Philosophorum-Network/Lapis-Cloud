pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // TEMPORARY (production codegen swap prep, 2026-07-13): local-only coupling to an
        // unreleased kUML change (the «Column».enumType ERM tag, kUML commit d81633c7,
        // published locally as version 0.99.99 — see gradle/libs.versions.toml for why
        // that specific, dash-free version string was chosen). Scoped via exclusiveContent
        // to the "dev.kuml" group only, so it can never shadow a real Maven Central artifact
        // of any other dependency. Remove this block (and revert the kuml version pin in
        // gradle/libs.versions.toml) once kUML publishes a real release with enumType
        // support to Maven Central — do not leave this repo permanently bound to a local
        // snapshot. Same pattern as the prior kotlinObjectName coupling (removed in 2c36e84
        // once v0.33.0 was released).
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroup("dev.kuml") }
        }
        mavenCentral()
    }
}

rootProject.name = "Lapis-Cloud"

// ── Shared (KMP: jvm + js) ───────────────────────────────────────
include("lapis-shared") // Shared DTOs/domain code used by both server and client

// ── Server (Ktor) ─────────────────────────────────────────────────
include("lapis-server") // Ktor application

// ── Client (KVision, Kotlin/JS) ────────────────────────────────────
include("lapis-client") // KVision UI, Kotlin/JS
