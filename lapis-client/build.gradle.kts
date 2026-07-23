plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kvision)
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "main.bundle.js"
            }
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        named("jsMain") {
            dependencies {
                implementation(project(":lapis-shared"))
                implementation(libs.kvision.core)
                implementation(libs.kvision.bootstrap)
                // V0.7.3 Basis-Mehrseiten-UI: hash-based multi-screen routing (login/register/
                // dashboard/members/contributions/documents/communication) -- see
                // io.kvision.routing.Routing KDoc.
                implementation(libs.kvision.routing.navigo.ng)
            }
        }
        // V0.7.3 Basis-Mehrseiten-UI: this module had no jsTest source set at all before this wave
        // (only build/tmp artifacts existed) -- see CHANGELOG V0.7.3 entry "Testing approach" for
        // what is and isn't covered. Runs under the Karma+ChromeHeadless testTask already
        // configured above; kotlin.test is the only new test dependency.
        named("jsTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
