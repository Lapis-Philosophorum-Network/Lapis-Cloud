package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AntragTable
import network.lapis.cloud.server.db.generated.BeschlussTable
import network.lapis.cloud.server.db.generated.GremiumMitgliedschaftTable
import network.lapis.cloud.server.db.generated.GremiumTable
import network.lapis.cloud.server.db.generated.KonsensierungOptionTable
import network.lapis.cloud.server.db.generated.KonsensierungStimmberechtigtTable
import network.lapis.cloud.server.db.generated.KonsensierungStimmzettelTable
import network.lapis.cloud.server.db.generated.KonsensierungTable
import network.lapis.cloud.server.db.generated.KonsensierungTeilnahmeTable
import network.lapis.cloud.server.db.generated.KonsensierungWiderstandTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SitzungTable
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.UnauthenticatedException
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AntragStatus
import network.lapis.cloud.shared.domain.BeschlussStatus
import network.lapis.cloud.shared.domain.GremiumRolle
import network.lapis.cloud.shared.domain.GremiumType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.SitzungsFormat
import network.lapis.cloud.shared.domain.SitzungsStatus
import network.lapis.cloud.shared.domain.SkOpenInput
import network.lapis.cloud.shared.domain.SkOptionInput
import network.lapis.cloud.shared.domain.SkStimmzettelInput
import network.lapis.cloud.shared.domain.SkVerbindlichkeit
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Exercises [KonsensierungService] end to end -- same "throwaway routes calling the service class
 * directly" house style as [WahlServiceTest]/[GovernanceServiceTest]. Uses its own freshly created
 * Gremien/Sitzungen/Antraege/members throughout. [afterSpec] hard-deletes every row this file
 * created.
 */
class KonsensierungServiceTest :
    FunSpec({
        val createdGremiumIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpKonsensierungTestData(createdGremiumIds, createdMemberIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Konsensierung Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun createTestGremium(
            name: String,
            type: GremiumType = GremiumType.VORSTAND,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                GremiumTable.insert {
                    it[GremiumTable.id] = id
                    it[GremiumTable.name] = name
                    it[GremiumTable.type] = type
                    it[description] = "Testgremium"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdGremiumIds += id
            return id
        }

        fun addMitglied(
            gremiumId: Uuid,
            memberId: Uuid,
            rolle: GremiumRolle,
        ) {
            transaction {
                GremiumMitgliedschaftTable.insert {
                    it[id] = Uuid.random()
                    it[GremiumMitgliedschaftTable.gremiumId] = gremiumId
                    it[GremiumMitgliedschaftTable.memberId] = memberId
                    it[GremiumMitgliedschaftTable.rolle] = rolle
                    it[since] = LocalDate(2020, 1, 1)
                    it[until] = null
                }
            }
        }

        fun createTestSitzung(
            gremiumId: Uuid,
            scheduledAt: LocalDateTime,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                SitzungTable.insert {
                    it[SitzungTable.id] = id
                    it[SitzungTable.gremiumId] = gremiumId
                    it[title] = "Testsitzung"
                    it[SitzungTable.scheduledAt] = scheduledAt
                    it[location] = "Vereinsheim"
                    it[format] = SitzungsFormat.PRAESENZ
                    it[status] = SitzungsStatus.GEPLANT
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = scheduledAt
                }
            }
            return id
        }

        /** Directly seeds an already-[AntragStatus.TERMINIERT] Antrag -- see class KDoc. */
        fun createTerminierterAntrag(
            gremiumId: Uuid,
            sitzungId: Uuid,
            submitterId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                AntragTable.insert {
                    it[AntragTable.id] = id
                    it[targetGremiumId] = gremiumId
                    it[title] = "Testantrag"
                    it[begruendung] = "Begruendung"
                    it[text] = "Antragstext"
                    it[submitterMemberId] = submitterId
                    it[status] = AntragStatus.TERMINIERT
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = submitterId
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[AntragTable.sitzungId] = sitzungId
                    it[tagesordnungspunktId] = null
                    it[beschlussId] = null
                    it[withdrawnAt] = null
                }
            }
            return id
        }

        test(
            "full lifecycle: open (geheim, Passivloesung auto-add) -> participants add options -> freeze -> " +
                "castWiderstand -> close -> auswerten (SONDIERUNG writes no Beschluss)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installKonsensierungExceptionHandlers() }
                    routing { registerKonsensierungTestRoutes() }
                }

                val gremiumId = createTestGremium("SK Vorstand")
                val chair = createTestMember("sk-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val voter1 = createTestMember("sk-v1@example.org")
                val voter2 = createTestMember("sk-v2@example.org")
                addMitglied(gremiumId, voter1, GremiumRolle.MITGLIED)
                addMitglied(gremiumId, voter2, GremiumRolle.MITGLIED)

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 3, 1, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)

                val opened =
                    client
                        .post("/test/open-konsensierung/$antragId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val parts = opened.split(":")
                val konsensierungId = parts[0]
                parts[1] shouldBe "SAMMLUNG"
                parts[2] shouldBe "1" // Passivloesung auto-added

                val optionAId =
                    client
                        .post("/test/add-option/$konsensierungId?label=Option+A") { header("X-Member-Id", voter1.toString()) }
                        .bodyAsText()
                val optionBId =
                    client
                        .post("/test/add-option/$konsensierungId?label=Option+B") { header("X-Member-Id", voter2.toString()) }
                        .bodyAsText()

                val listed = client.get("/test/list-options/$konsensierungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                listed.split(";").size shouldBe 3

                val frozen =
                    client.post("/test/freeze-optionen/$konsensierungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                frozen shouldBe "BEWERTUNG:3"

                val passivloesungId =
                    transaction {
                        KonsensierungOptionTable
                            .selectAll()
                            .where {
                                (KonsensierungOptionTable.konsensierungId eq Uuid.parse(konsensierungId)) and
                                    (KonsensierungOptionTable.isPassivloesung eq true)
                            }.single()[KonsensierungOptionTable.id]
                    }.toString()

                fun widerstaendeParam(
                    passiv: Int,
                    a: Int,
                    b: Int,
                ) = "$passivloesungId:$passiv,$optionAId:$a,$optionBId:$b"

                client.post("/test/cast-widerstand/$konsensierungId?widerstaende=${widerstaendeParam(8, 2, 5)}") {
                    header("X-Member-Id", chair.toString())
                }
                client.post("/test/cast-widerstand/$konsensierungId?widerstaende=${widerstaendeParam(9, 1, 6)}") {
                    header("X-Member-Id", voter1.toString())
                }
                client.post("/test/cast-widerstand/$konsensierungId?widerstaende=${widerstaendeParam(7, 3, 4)}") {
                    header("X-Member-Id", voter2.toString())
                }

                val closed = client.post("/test/close-bewertung/$konsensierungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                closed shouldBe "GESCHLOSSEN"

                val ergebnis = client.post("/test/auswerten/$konsensierungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                // Option A has the lowest cumulative resistance (2+1+3=6) of the three.
                ergebnis shouldBe "$optionAId:false:false"

                val final =
                    client.get("/test/get-konsensierung/$konsensierungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val finalParts = final.split(":")
                finalParts[0] shouldBe "AUSGEWERTET"
                finalParts[2] shouldBe "" // SONDIERUNG (default verbindlichkeit) writes no Beschluss
            }
        }

        test("verbindlichkeit=BESCHLUSS writes a Beschluss tagged SYSTEMISCHER_KONSENS and transitions the Antrag") {
            testApplication {
                application {
                    install(StatusPages) { installKonsensierungExceptionHandlers() }
                    routing { registerKonsensierungTestRoutes() }
                }

                val gremiumId = createTestGremium("SK Beschluss Vorstand")
                val chair = createTestMember("sk-beschluss-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val voter = createTestMember("sk-beschluss-voter@example.org")
                addMitglied(gremiumId, voter, GremiumRolle.MITGLIED)

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 3, 15, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)

                val konsensierungId =
                    client
                        .post("/test/open-konsensierung/$antragId?verbindlichkeit=BESCHLUSS&passivloesungAuto=false") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")

                val optionId =
                    client
                        .post("/test/add-option/$konsensierungId?label=Einzige+Option") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                client.post("/test/freeze-optionen/$konsensierungId") { header("X-Member-Id", chair.toString()) }

                client.post("/test/cast-widerstand/$konsensierungId?widerstaende=$optionId:2") { header("X-Member-Id", chair.toString()) }
                client.post("/test/cast-widerstand/$konsensierungId?widerstaende=$optionId:4") { header("X-Member-Id", voter.toString()) }

                client.post("/test/close-bewertung/$konsensierungId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/auswerten/$konsensierungId") { header("X-Member-Id", chair.toString()) }

                val final =
                    client.get("/test/get-konsensierung/$konsensierungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val beschlussId = final.split(":")[2]
                beschlussId.isBlank() shouldBe false

                val beschluss = transaction { BeschlussTable.selectAll().where { BeschlussTable.id eq Uuid.parse(beschlussId) }.single() }
                transaction { beschluss[BeschlussTable.resolutionMode] } shouldBe ResolutionMode.SYSTEMISCHER_KONSENS
                transaction { beschluss[BeschlussTable.status] } shouldBe BeschlussStatus.ANGENOMMEN
                transaction { beschluss[BeschlussTable.konsensierungId]?.toString() } shouldBe konsensierungId

                val antragStatus = transaction { AntragTable.selectAll().where { AntragTable.id eq antragId }.single()[AntragTable.status] }
                antragStatus shouldBe AntragStatus.BESCHLOSSEN
            }
        }

        test("reopenBewertung is rejected once a binding Beschluss has been recorded (verbindlichkeit=BESCHLUSS)") {
            testApplication {
                application {
                    install(StatusPages) { installKonsensierungExceptionHandlers() }
                    routing { registerKonsensierungTestRoutes() }
                }

                val gremiumId = createTestGremium("SK Reopen-Beschluss Vorstand")
                val chair = createTestMember("sk-reopen-beschluss-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val voter = createTestMember("sk-reopen-beschluss-voter@example.org")
                addMitglied(gremiumId, voter, GremiumRolle.MITGLIED)

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 3, 20, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)

                val konsensierungId =
                    client
                        .post("/test/open-konsensierung/$antragId?verbindlichkeit=BESCHLUSS&passivloesungAuto=false&maxRunden=3") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")

                val optionId =
                    client
                        .post("/test/add-option/$konsensierungId?label=Einzige+Option") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                client.post("/test/freeze-optionen/$konsensierungId") { header("X-Member-Id", chair.toString()) }

                client.post("/test/cast-widerstand/$konsensierungId?widerstaende=$optionId:2") { header("X-Member-Id", chair.toString()) }
                client.post("/test/cast-widerstand/$konsensierungId?widerstaende=$optionId:4") { header("X-Member-Id", voter.toString()) }

                client.post("/test/close-bewertung/$konsensierungId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/auswerten/$konsensierungId") { header("X-Member-Id", chair.toString()) }

                val beforeReopen =
                    client.get("/test/get-konsensierung/$konsensierungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val beschlussIdBefore = beforeReopen.split(":")[2]
                beschlussIdBefore.isBlank() shouldBe false

                // The binding Beschluss is already recorded -- reopening must be rejected so the
                // Beschlussbuch record is never orphaned/duplicated.
                val reopenAttempt =
                    client.post("/test/reopen-bewertung/$konsensierungId") { header("X-Member-Id", chair.toString()) }
                reopenAttempt.status shouldBe HttpStatusCode.Conflict

                // Konsensierung is unchanged: still AUSGEWERTET, still round 1, same Beschluss.
                val afterReopen =
                    client.get("/test/get-konsensierung/$konsensierungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val afterParts = afterReopen.split(":")
                afterParts[0] shouldBe "AUSGEWERTET"
                afterParts[2] shouldBe beschlussIdBefore
                afterParts[3] shouldBe "1"

                val beschlussCount =
                    transaction {
                        BeschlussTable
                            .selectAll()
                            .where { BeschlussTable.konsensierungId eq Uuid.parse(konsensierungId) }
                            .count()
                    }
                beschlussCount shouldBe 1L
            }
        }

        test("one-rating-per-member-per-round: a second castWiderstand attempt is rejected on both the geheim and non-geheim path") {
            testApplication {
                application {
                    install(StatusPages) { installKonsensierungExceptionHandlers() }
                    routing { registerKonsensierungTestRoutes() }
                }

                val gremiumId = createTestGremium("SK Doppelbewertung Vorstand")
                val chair = createTestMember("sk-doppel-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val voter = createTestMember("sk-doppel-voter@example.org")
                addMitglied(gremiumId, voter, GremiumRolle.MITGLIED)

                listOf(true, false).forEach { geheim ->
                    val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 5, 1, 18, 0))
                    val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)
                    val konsensierungId =
                        client
                            .post("/test/open-konsensierung/$antragId?geheim=$geheim&passivloesungAuto=false") {
                                header("X-Member-Id", chair.toString())
                            }.bodyAsText()
                            .substringBefore(":")
                    val optionId =
                        client
                            .post("/test/add-option/$konsensierungId?label=Option") { header("X-Member-Id", chair.toString()) }
                            .bodyAsText()
                    client.post("/test/freeze-optionen/$konsensierungId") { header("X-Member-Id", chair.toString()) }

                    val first =
                        client.post(
                            "/test/cast-widerstand/$konsensierungId?widerstaende=$optionId:3",
                        ) { header("X-Member-Id", voter.toString()) }
                    first.status shouldBe HttpStatusCode.OK
                    val second =
                        client.post(
                            "/test/cast-widerstand/$konsensierungId?widerstaende=$optionId:7",
                        ) { header("X-Member-Id", voter.toString()) }
                    second.status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        test("concurrency: N simultaneous castWiderstand calls for the same member -- exactly one succeeds") {
            testApplication {
                application {
                    install(StatusPages) { installKonsensierungExceptionHandlers() }
                    routing { registerKonsensierungTestRoutes() }
                }

                val gremiumId = createTestGremium("SK Concurrency Vorstand")
                val chair = createTestMember("sk-concurrency-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val voter = createTestMember("sk-concurrency-voter@example.org")
                addMitglied(gremiumId, voter, GremiumRolle.MITGLIED)

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 6, 1, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)
                val konsensierungId =
                    client
                        .post("/test/open-konsensierung/$antragId?passivloesungAuto=false") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                val optionId =
                    client.post("/test/add-option/$konsensierungId?label=Option") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                client.post("/test/freeze-optionen/$konsensierungId") { header("X-Member-Id", chair.toString()) }

                val attempts = 8
                val results =
                    coroutineScope {
                        (1..attempts)
                            .map {
                                async {
                                    client
                                        .post("/test/cast-widerstand/$konsensierungId?widerstaende=$optionId:5") {
                                            header("X-Member-Id", voter.toString())
                                        }.status
                                }
                            }.map { it.await() }
                    }
                results.count { it == HttpStatusCode.OK } shouldBe 1
                results.count { it == HttpStatusCode.Conflict } shouldBe attempts - 1

                val teilnahmeCount =
                    transaction {
                        KonsensierungTeilnahmeTable
                            .selectAll()
                            .where {
                                (KonsensierungTeilnahmeTable.konsensierungId eq Uuid.parse(konsensierungId)) and
                                    (KonsensierungTeilnahmeTable.memberId eq voter)
                            }.count()
                    }
                teilnahmeCount shouldBe 1L
            }
        }

        test("listWiderstaende hides values for a geheim Konsensierung until AUSGEWERTET (no mid-Bewertung running-tally leak)") {
            testApplication {
                application {
                    install(StatusPages) { installKonsensierungExceptionHandlers() }
                    routing { registerKonsensierungTestRoutes() }
                }

                val gremiumId = createTestGremium("SK Transparenz Vorstand")
                val chair = createTestMember("sk-transparenz-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val voter = createTestMember("sk-transparenz-voter@example.org")
                addMitglied(gremiumId, voter, GremiumRolle.MITGLIED)

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 6, 15, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)
                val konsensierungId =
                    client
                        .post("/test/open-konsensierung/$antragId?geheim=true&passivloesungAuto=false") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                val optionId =
                    client.post("/test/add-option/$konsensierungId?label=Option") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                client.post("/test/freeze-optionen/$konsensierungId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/cast-widerstand/$konsensierungId?widerstaende=$optionId:6") { header("X-Member-Id", voter.toString()) }

                val beforeTally =
                    client.get("/test/list-widerstaende/$konsensierungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                beforeTally.split(";").forEach { entry -> entry.substringAfter(":") shouldBe "0" }

                client.post("/test/close-bewertung/$konsensierungId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/auswerten/$konsensierungId") { header("X-Member-Id", chair.toString()) }

                val afterTally =
                    client.get("/test/list-widerstaende/$konsensierungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                afterTally.split(";").any { entry -> entry.substringAfter(":") == "1" } shouldBe true
            }
        }

        test("reopenBewertung increments runde and retains prior-round ballots; rejected once maxRunden is reached") {
            testApplication {
                application {
                    install(StatusPages) { installKonsensierungExceptionHandlers() }
                    routing { registerKonsensierungTestRoutes() }
                }

                val gremiumId = createTestGremium("SK Wiederholung Vorstand")
                val chair = createTestMember("sk-wiederholung-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val voter = createTestMember("sk-wiederholung-voter@example.org")
                addMitglied(gremiumId, voter, GremiumRolle.MITGLIED)

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 7, 1, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)
                val konsensierungId =
                    client
                        .post("/test/open-konsensierung/$antragId?passivloesungAuto=false&maxRunden=2") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                val optionId =
                    client.post("/test/add-option/$konsensierungId?label=Option") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                client.post("/test/freeze-optionen/$konsensierungId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/cast-widerstand/$konsensierungId?widerstaende=$optionId:8") { header("X-Member-Id", voter.toString()) }
                client.post("/test/close-bewertung/$konsensierungId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/auswerten/$konsensierungId") { header("X-Member-Id", chair.toString()) }

                val reopened =
                    client.post("/test/reopen-bewertung/$konsensierungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                reopened shouldBe "BEWERTUNG:2"

                // Prior round's ballot is retained (DSGVO retention), not deleted.
                val round1Count =
                    transaction {
                        KonsensierungStimmzettelTable
                            .selectAll()
                            .where {
                                (KonsensierungStimmzettelTable.konsensierungId eq Uuid.parse(konsensierungId)) and
                                    (KonsensierungStimmzettelTable.runde eq 1)
                            }.count()
                    }
                round1Count shouldBe 1L

                // The same member may rate again in round 2 (fresh eligibility snapshot).
                client.post("/test/cast-widerstand/$konsensierungId?widerstaende=$optionId:2") { header("X-Member-Id", voter.toString()) }
                client.post("/test/close-bewertung/$konsensierungId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/auswerten/$konsensierungId") { header("X-Member-Id", chair.toString()) }

                // maxRunden=2 already reached (runde=2) -- a further reopen must be rejected.
                val cappedReopen =
                    client.post("/test/reopen-bewertung/$konsensierungId") { header("X-Member-Id", chair.toString()) }
                cappedReopen.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("status guards: castWiderstand while SAMMLUNG and freezeOptionen with zero options are both rejected") {
            testApplication {
                application {
                    install(StatusPages) { installKonsensierungExceptionHandlers() }
                    routing { registerKonsensierungTestRoutes() }
                }

                val gremiumId = createTestGremium("SK Statusguard Vorstand")
                val chair = createTestMember("sk-statusguard-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 8, 1, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)
                val konsensierungId =
                    client
                        .post("/test/open-konsensierung/$antragId?passivloesungAuto=false") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                val prematureCast =
                    client.post("/test/cast-widerstand/$konsensierungId?widerstaende=") { header("X-Member-Id", chair.toString()) }
                prematureCast.status shouldBe HttpStatusCode.Conflict

                val emptyFreeze =
                    client.post("/test/freeze-optionen/$konsensierungId") { header("X-Member-Id", chair.toString()) }
                emptyFreeze.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("authorization: non-Gremium-leadership member cannot openKonsensierung; ineligible member cannot castWiderstand") {
            testApplication {
                application {
                    install(StatusPages) { installKonsensierungExceptionHandlers() }
                    routing { registerKonsensierungTestRoutes() }
                }

                val gremiumId = createTestGremium("SK Auth Vorstand")
                val chair = createTestMember("sk-auth-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val plainMember = createTestMember("sk-auth-plain@example.org")
                addMitglied(gremiumId, plainMember, GremiumRolle.MITGLIED)
                val outsider = createTestMember("sk-auth-outsider@example.org")

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 9, 1, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)

                val forbiddenOpen =
                    client.post("/test/open-konsensierung/$antragId") { header("X-Member-Id", plainMember.toString()) }
                forbiddenOpen.status shouldBe HttpStatusCode.Forbidden

                val konsensierungId =
                    client
                        .post("/test/open-konsensierung/$antragId?passivloesungAuto=false") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                val optionId =
                    client.post("/test/add-option/$konsensierungId?label=Option") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                client.post("/test/freeze-optionen/$konsensierungId") { header("X-Member-Id", chair.toString()) }

                // outsider is not in the eligibility snapshot (not a Gremium member).
                val forbiddenCast =
                    client.post(
                        "/test/cast-widerstand/$konsensierungId?widerstaende=$optionId:5",
                    ) { header("X-Member-Id", outsider.toString()) }
                forbiddenCast.status shouldBe HttpStatusCode.Forbidden

                val unauthenticated = client.post("/test/cast-widerstand/$konsensierungId?widerstaende=$optionId:5")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("removeOption never removes the Passivloesung option, and only the proposer or leadership may remove a regular option") {
            testApplication {
                application {
                    install(StatusPages) { installKonsensierungExceptionHandlers() }
                    routing { registerKonsensierungTestRoutes() }
                }

                val gremiumId = createTestGremium("SK RemoveOption Vorstand")
                val chair = createTestMember("sk-removeoption-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val proposer = createTestMember("sk-removeoption-proposer@example.org")
                addMitglied(gremiumId, proposer, GremiumRolle.MITGLIED)
                val otherMember = createTestMember("sk-removeoption-other@example.org")
                addMitglied(gremiumId, otherMember, GremiumRolle.MITGLIED)

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 9, 15, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)
                val konsensierungId =
                    client
                        .post("/test/open-konsensierung/$antragId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                val passivloesungId =
                    transaction {
                        KonsensierungOptionTable
                            .selectAll()
                            .where {
                                (KonsensierungOptionTable.konsensierungId eq Uuid.parse(konsensierungId)) and
                                    (KonsensierungOptionTable.isPassivloesung eq true)
                            }.single()[KonsensierungOptionTable.id]
                    }.toString()
                val optionId =
                    client
                        .post("/test/add-option/$konsensierungId?label=Vorschlag") { header("X-Member-Id", proposer.toString()) }
                        .bodyAsText()

                val passivloesungRemoval =
                    client.post("/test/remove-option/$passivloesungId") { header("X-Member-Id", chair.toString()) }
                passivloesungRemoval.status shouldBe HttpStatusCode.Conflict

                val forbiddenRemoval =
                    client.post("/test/remove-option/$optionId") { header("X-Member-Id", otherMember.toString()) }
                forbiddenRemoval.status shouldBe HttpStatusCode.Forbidden

                val allowedRemoval =
                    client.post("/test/remove-option/$optionId") { header("X-Member-Id", proposer.toString()) }.bodyAsText()
                allowedRemoval shouldBe "SAMMLUNG:1"
            }
        }
    })

private fun StatusPagesConfig.installKonsensierungExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
}

/**
 * Shared throwaway routes for [KonsensierungServiceTest] -- string encodings are kept
 * deliberately simple/parseable, same house style as [WahlServiceTest]'s own test routes.
 */
private fun Route.registerKonsensierungTestRoutes() {
    post("/test/open-konsensierung/{antragId}") {
        val service = KonsensierungService(call)
        val q = call.request.queryParameters
        val k =
            service.openKonsensierung(
                SkOpenInput(
                    antragId = call.parameters["antragId"]!!,
                    geheim = q["geheim"]?.toBoolean() ?: true,
                    skalaMax = q["skalaMax"]?.toInt() ?: 10,
                    passivloesungAuto = q["passivloesungAuto"]?.toBoolean() ?: true,
                    verbindlichkeit = q["verbindlichkeit"]?.let { SkVerbindlichkeit.valueOf(it) } ?: SkVerbindlichkeit.SONDIERUNG,
                    maxRunden = q["maxRunden"]?.toInt() ?: 3,
                ),
            )
        call.respondText("${k.id}:${k.status}:${k.options.size}")
    }
    post("/test/add-option/{konsensierungId}") {
        val service = KonsensierungService(call)
        val label = call.request.queryParameters["label"]!!
        val o = service.addOption(call.parameters["konsensierungId"]!!, SkOptionInput(label = label))
        call.respondText(o.id)
    }
    post("/test/remove-option/{optionId}") {
        val service = KonsensierungService(call)
        val k = service.removeOption(call.parameters["optionId"]!!)
        call.respondText("${k.status}:${k.options.size}")
    }
    get("/test/list-options/{konsensierungId}") {
        val service = KonsensierungService(call)
        val list = service.listOptions(call.parameters["konsensierungId"]!!)
        call.respondText(list.joinToString(";") { "${it.id}:${it.label}:${it.isPassivloesung}" })
    }
    post("/test/freeze-optionen/{konsensierungId}") {
        val service = KonsensierungService(call)
        val k = service.freezeOptionen(call.parameters["konsensierungId"]!!)
        call.respondText("${k.status}:${k.options.size}")
    }
    post("/test/cast-widerstand/{konsensierungId}") {
        val service = KonsensierungService(call)
        val param = call.request.queryParameters["widerstaende"] ?: ""
        val widerstaende =
            param
                .split(",")
                .filter { it.isNotBlank() }
                .associate { pair ->
                    val (optId, wert) = pair.split(":")
                    optId to wert.toInt()
                }
        val result =
            service.castWiderstand(
                SkStimmzettelInput(konsensierungId = call.parameters["konsensierungId"]!!, widerstaende = widerstaende),
            )
        call.respondText("${result.id}:${result.receiptCode ?: ""}")
    }
    post("/test/close-bewertung/{konsensierungId}") {
        val service = KonsensierungService(call)
        val k = service.closeBewertung(call.parameters["konsensierungId"]!!)
        call.respondText(k.status.name)
    }
    post("/test/auswerten/{konsensierungId}") {
        val service = KonsensierungService(call)
        val e = service.auswerten(call.parameters["konsensierungId"]!!)
        call.respondText("${e.gewinnerOptionId ?: ""}:${e.tie}:${e.keineBewertungen}")
    }
    post("/test/reopen-bewertung/{konsensierungId}") {
        val service = KonsensierungService(call)
        val k = service.reopenBewertung(call.parameters["konsensierungId"]!!)
        call.respondText("${k.status}:${k.runde}")
    }
    post("/test/abort-konsensierung/{konsensierungId}") {
        val service = KonsensierungService(call)
        val k = service.abortKonsensierung(call.parameters["konsensierungId"]!!)
        call.respondText(k.status.name)
    }
    get("/test/get-konsensierung/{konsensierungId}") {
        val service = KonsensierungService(call)
        val k = service.getKonsensierung(call.parameters["konsensierungId"]!!)
        call.respondText("${k.status}:${k.options.size}:${k.beschlussId ?: ""}:${k.runde}")
    }
    get("/test/list-widerstaende/{konsensierungId}") {
        val service = KonsensierungService(call)
        val list = service.listWiderstaende(call.parameters["konsensierungId"]!!)
        call.respondText(list.joinToString(";") { "${it.id}:${it.widerstaende.size}" })
    }
}

/**
 * Hard-deletes every row this Spec created, child-before-parent -- same discipline as
 * [cleanUpWahlTestData]. [KonsensierungTable] and [BeschlussTable] are mutually FK-linked
 * (`konsensierung.beschluss_id` -> `beschluss.id`, `beschluss.konsensierung_id` ->
 * `konsensierung.id`), so both FKs are nulled out before either table's rows are deleted.
 */
private fun cleanUpKonsensierungTestData(
    gremiumIds: List<Uuid>,
    memberIds: List<Uuid>,
) {
    if (gremiumIds.isEmpty() && memberIds.isEmpty()) return
    transaction {
        val sitzungIds =
            if (gremiumIds.isEmpty()) {
                emptyList()
            } else {
                SitzungTable.selectAll().where { SitzungTable.gremiumId inList gremiumIds }.map { it[SitzungTable.id] }
            }

        val konsensierungCondition =
            when {
                sitzungIds.isNotEmpty() && memberIds.isNotEmpty() ->
                    (KonsensierungTable.sitzungId inList sitzungIds) or (KonsensierungTable.openedBy inList memberIds)
                sitzungIds.isNotEmpty() -> KonsensierungTable.sitzungId inList sitzungIds
                memberIds.isNotEmpty() -> KonsensierungTable.openedBy inList memberIds
                else -> null
            }
        val konsensierungIds =
            if (konsensierungCondition != null) {
                KonsensierungTable.selectAll().where { konsensierungCondition }.map { it[KonsensierungTable.id] }
            } else {
                emptyList()
            }
        if (konsensierungIds.isNotEmpty()) {
            KonsensierungTable.update({ KonsensierungTable.id inList konsensierungIds }) { it[beschlussId] = null }
            BeschlussTable.update({ BeschlussTable.konsensierungId inList konsensierungIds }) { it[BeschlussTable.konsensierungId] = null }
            val stimmzettelIds =
                KonsensierungStimmzettelTable
                    .selectAll()
                    .where { KonsensierungStimmzettelTable.konsensierungId inList konsensierungIds }
                    .map { it[KonsensierungStimmzettelTable.id] }
            if (stimmzettelIds.isNotEmpty()) {
                KonsensierungWiderstandTable.deleteWhere { KonsensierungWiderstandTable.stimmzettelId inList stimmzettelIds }
            }
            KonsensierungStimmzettelTable.deleteWhere { KonsensierungStimmzettelTable.konsensierungId inList konsensierungIds }
            KonsensierungTeilnahmeTable.deleteWhere { KonsensierungTeilnahmeTable.konsensierungId inList konsensierungIds }
            KonsensierungStimmberechtigtTable.deleteWhere { KonsensierungStimmberechtigtTable.konsensierungId inList konsensierungIds }
            KonsensierungOptionTable.deleteWhere { KonsensierungOptionTable.konsensierungId inList konsensierungIds }
            KonsensierungTable.deleteWhere { KonsensierungTable.id inList konsensierungIds }
        }

        if (gremiumIds.isNotEmpty() || memberIds.isNotEmpty()) {
            val antragCondition =
                when {
                    gremiumIds.isNotEmpty() && memberIds.isNotEmpty() ->
                        (AntragTable.targetGremiumId inList gremiumIds) or
                            (AntragTable.submitterMemberId inList memberIds) or
                            (AntragTable.reviewedBy inList memberIds)
                    gremiumIds.isNotEmpty() -> AntragTable.targetGremiumId inList gremiumIds
                    else -> (AntragTable.submitterMemberId inList memberIds) or (AntragTable.reviewedBy inList memberIds)
                }
            AntragTable.deleteWhere { antragCondition }
        }
        if (sitzungIds.isNotEmpty()) {
            BeschlussTable.deleteWhere { BeschlussTable.sitzungId inList sitzungIds }
            SitzungTable.deleteWhere { SitzungTable.id inList sitzungIds }
        }
        if (gremiumIds.isNotEmpty()) {
            GremiumMitgliedschaftTable.deleteWhere { GremiumMitgliedschaftTable.gremiumId inList gremiumIds }
            GremiumTable.deleteWhere { GremiumTable.id inList gremiumIds }
        }
        if (memberIds.isNotEmpty()) {
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
