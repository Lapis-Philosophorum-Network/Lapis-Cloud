package network.lapis.cloud.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication

class ApplicationTest :
    FunSpec({
        test("ping route responds with greeting") {
            testApplication {
                application { module() }

                val response = client.get("/api/ping")

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "Hello from Lapis Cloud"
            }
        }

        // V0.7.3 Basis-Mehrseiten-UI: "/" now serves the KVision/Kotlin-JS client bundle via
        // staticFiles(), not the greeting -- see Application.kt "clientDistRoot" KDoc. In this test
        // environment no client build has run, so the directory is empty and "/" 404s; that is the
        // expected, harmless behavior (see staticFiles KDoc comment), not a regression.
        test("root route 404s when no client build is present") {
            testApplication {
                application { module() }

                val response = client.get("/")

                response.status shouldBe HttpStatusCode.NotFound
            }
        }
    })
