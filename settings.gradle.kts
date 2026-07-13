pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // TEMPORARY (MDA codegen verification, 2026-07-13): local-only coupling to an
        // unreleased kUML change (the «Entity».kotlinObjectName ERM tag, kUML commit
        // 21f4fb72, published locally as version 0.99.99 — see gradle/libs.versions.toml
        // for why that specific, dash-free version string was chosen). Scoped via
        // exclusiveContent to the "dev.kuml" group only, so it can never shadow a real
        // Maven Central artifact of any other dependency. Remove this block (and revert
        // the kuml version pin in gradle/libs.versions.toml) once kUML publishes a real
        // release with kotlinObjectName support (expected v0.33.0) to Maven Central — do
        // not leave this repo permanently bound to a local snapshot.
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
