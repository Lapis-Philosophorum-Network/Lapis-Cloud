package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SitzungTable
import network.lapis.cloud.server.db.generated.WahlFreigabeTable
import network.lapis.cloud.server.db.generated.WahlKandidaturTable
import network.lapis.cloud.server.db.generated.WahlOptionTable
import network.lapis.cloud.server.db.generated.WahlStimmzettelAuswahlTable
import network.lapis.cloud.server.db.generated.WahlStimmzettelTable
import network.lapis.cloud.server.db.generated.WahlTable
import network.lapis.cloud.server.db.generated.WahlTeilnahmeTable
import network.lapis.cloud.server.db.generated.WahlWahlberechtigtTable
import network.lapis.cloud.server.db.generated.WahlWahlvorstandTable
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.UnauthenticatedException
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AntragStatus
import network.lapis.cloud.shared.domain.BeschlussStatus
import network.lapis.cloud.shared.domain.GremiumRolle
import network.lapis.cloud.shared.domain.GremiumType
import network.lapis.cloud.shared.domain.KandidaturInput
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.SitzungsFormat
import network.lapis.cloud.shared.domain.SitzungsStatus
import network.lapis.cloud.shared.domain.StimmzettelInput
import network.lapis.cloud.shared.domain.WahlAntwort
import network.lapis.cloud.shared.domain.WahlOpenInput
import network.lapis.cloud.shared.domain.WahlStatus
import network.lapis.cloud.shared.domain.WahlTyp
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
 * Exercises [WahlService] end to end -- same "throwaway routes calling the service class
 * directly" house style as [GovernanceServiceTest]. Uses its own freshly created Gremien/Sitzungen/
 * Antraege/members throughout (never [DevSeedData]'s shared demo fixtures as Gremium members), for
 * the same order-independence reasoning [GovernanceServiceTest] documents. [afterSpec] hard-deletes
 * every row this file created.
 *
 * Gremium/Sitzung/Antrag test fixtures are seeded via direct table inserts (bypassing
 * [GovernanceService]'s own authorization/validation) -- this file's own routes/assertions only
 * need a Antrag already in [AntragStatus.TERMINIERT] with a scheduled Sitzung, not a full
 * `submitAntrag -> reviewAntrag -> scheduleAntrag` walk (that walk is [GovernanceServiceTest]'s
 * concern, exercised there).
 */
class WahlServiceTest :
    FunSpec({
        val createdGremiumIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpWahlTestData(createdGremiumIds, createdMemberIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Wahl Testmitglied"
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
            "JA_NEIN full lifecycle: open -> appoint Wahlvorstand -> open voting -> non-secret ballots -> " +
                "close -> Vier-Augen freigeben -> auszaehlen writes a DEMOKRATISCH Beschluss and resolves the Antrag",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installWahlExceptionHandlers() }
                    routing { registerWahlTestRoutes() }
                }

                val gremiumId = createTestGremium("JaNein Vorstand")
                val chair = createTestMember("wahl-janein-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val voter1 = createTestMember("wahl-janein-v1@example.org")
                val voter2 = createTestMember("wahl-janein-v2@example.org")
                addMitglied(gremiumId, voter1, GremiumRolle.MITGLIED)
                addMitglied(gremiumId, voter2, GremiumRolle.MITGLIED)
                val wahlvorstand = (1..3).map { createTestMember("wahl-janein-wv$it@example.org") }

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 3, 1, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)

                val opened =
                    client
                        .post(
                            "/test/open-wahl/$antragId/JA_NEIN?geheim=false",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val wahlId = opened.substringBefore(":")
                opened.substringAfter(":") shouldBe WahlStatus.VORBEREITUNG.name

                val appointed =
                    client
                        .post(
                            "/test/appoint-wahlvorstand/$wahlId?memberIds=${wahlvorstand.joinToString(",")}",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                appointed shouldBe "3"

                val openVoting =
                    client.post("/test/open-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }.bodyAsText()
                openVoting shouldBe WahlStatus.OFFEN.name

                client.post(
                    "/test/cast-stimme/$wahlId?antwort=JA",
                ) { header("X-Member-Id", chair.toString()) }
                client.post(
                    "/test/cast-stimme/$wahlId?antwort=JA",
                ) { header("X-Member-Id", voter1.toString()) }
                client.post(
                    "/test/cast-stimme/$wahlId?antwort=NEIN",
                ) { header("X-Member-Id", voter2.toString()) }

                val closeVoting =
                    client.post("/test/close-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }.bodyAsText()
                closeVoting shouldBe WahlStatus.GESCHLOSSEN.name

                // Vier-Augen: default tallyThreshold is 2 -- one approval must not be enough.
                client.post("/test/freigeben-auszaehlung/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }
                val tooEarly = client.post("/test/auszaehlen/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }
                tooEarly.status shouldBe HttpStatusCode.Conflict

                client.post("/test/freigeben-auszaehlung/$wahlId") { header("X-Member-Id", wahlvorstand[1].toString()) }
                val ergebnis =
                    client.post("/test/auszaehlen/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }.bodyAsText()
                // 2 JA vs 1 NEIN -> majority met, JA option wins, no tie.
                val parts = ergebnis.split(":")
                parts[1] shouldBe "false" // tie
                parts[2] shouldBe "true" // majorityMet

                val finalWahl = client.get("/test/get-wahl/$wahlId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                finalWahl.substringBefore(":") shouldBe WahlStatus.AUSGEZAEHLT.name

                val antragStatus =
                    transaction { AntragTable.selectAll().where { AntragTable.id eq antragId }.single()[AntragTable.status] }
                antragStatus shouldBe AntragStatus.BESCHLOSSEN

                val beschluss =
                    transaction {
                        BeschlussTable.selectAll().where { BeschlussTable.wahlId eq Uuid.parse(wahlId) }.single()
                    }
                transaction { beschluss[BeschlussTable.resolutionMode] } shouldBe ResolutionMode.DEMOKRATISCH
                transaction { beschluss[BeschlussTable.status] } shouldBe BeschlussStatus.ANGENOMMEN
            }
        }

        test(
            "EINZELWAHL personnel election: Kandidatur submit/withdraw, freigebenKandidatenliste freezes options, " +
                "geheim ballots hide memberId and hand back a receiptCode, winner is seated into zielGremium",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installWahlExceptionHandlers() }
                    routing { registerWahlTestRoutes() }
                }

                val hostGremium = createTestGremium("Mitgliederversammlung Einzelwahl", GremiumType.MITGLIEDERVERSAMMLUNG)
                val zielGremium = createTestGremium("Vorstand Ziel")
                val chair = createTestMember("wahl-einzel-chair@example.org")
                addMitglied(hostGremium, chair, GremiumRolle.VORSITZ)

                val candidateA = createTestMember("wahl-einzel-candA@example.org")
                val candidateB = createTestMember("wahl-einzel-candB@example.org")
                val withdrawingCandidate = createTestMember("wahl-einzel-candC@example.org")
                val voter = createTestMember("wahl-einzel-voter@example.org")
                val wahlvorstand = (1..3).map { createTestMember("wahl-einzel-wv$it@example.org") }

                val sitzungId = createTestSitzung(hostGremium, LocalDateTime(2026, 4, 1, 18, 0))
                val antragId = createTerminierterAntrag(hostGremium, sitzungId, chair)

                val opened =
                    client
                        .post(
                            "/test/open-wahl/$antragId/EINZELWAHL?zielGremiumId=$zielGremium&sitzeCount=1",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val wahlId = opened.substringBefore(":")

                client.post("/test/appoint-wahlvorstand/$wahlId?memberIds=${wahlvorstand.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }

                val kandidaturA =
                    client.post("/test/submit-kandidatur/$wahlId") { header("X-Member-Id", candidateA.toString()) }.bodyAsText()
                client.post("/test/submit-kandidatur/$wahlId") { header("X-Member-Id", candidateB.toString()) }
                val kandidaturC =
                    client.post("/test/submit-kandidatur/$wahlId") { header("X-Member-Id", withdrawingCandidate.toString()) }.bodyAsText()

                val withdrawn =
                    client
                        .post(
                            "/test/withdraw-kandidatur/$kandidaturC",
                        ) { header("X-Member-Id", withdrawingCandidate.toString()) }
                        .bodyAsText()
                withdrawn shouldBe "true"

                val freigegeben =
                    client
                        .post("/test/freigeben-kandidatenliste/$wahlId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                // Only 2 non-withdrawn candidacies (A, B) become options.
                freigegeben shouldBe "${WahlStatus.KANDIDATENLISTE_FREIGEGEBEN}:2"

                client.post("/test/open-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }

                val optionAId =
                    transaction {
                        WahlOptionTable
                            .selectAll()
                            .where {
                                (WahlOptionTable.wahlId eq Uuid.parse(wahlId)) and
                                    (WahlOptionTable.kandidaturId eq Uuid.parse(kandidaturA))
                            }.single()[WahlOptionTable.id]
                    }

                val castResult =
                    client
                        .post("/test/cast-stimme/$wahlId?selectedOptionIds=$optionAId") { header("X-Member-Id", voter.toString()) }
                        .bodyAsText()
                val receiptCode = castResult.substringAfter(":")
                receiptCode.isBlank() shouldBe false

                // Ballot secrecy: the stored stimmzettel row has no member_id for a geheim Wahl.
                val storedMemberId =
                    transaction {
                        WahlStimmzettelTable.selectAll().where { WahlStimmzettelTable.wahlId eq Uuid.parse(wahlId) }.single()[
                            WahlStimmzettelTable.memberId,
                        ]
                    }
                storedMemberId shouldBe null

                // Before AUSGEZAEHLT, verifyReceipt confirms existence but never the option.
                val beforeTally =
                    client
                        .get(
                            "/test/verify-receipt/$wahlId?receiptCode=$receiptCode",
                        ) { header("X-Member-Id", voter.toString()) }
                        .bodyAsText()
                beforeTally shouldBe "true:"

                client.post("/test/close-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }
                client.post("/test/freigeben-auszaehlung/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }
                client.post("/test/freigeben-auszaehlung/$wahlId") { header("X-Member-Id", wahlvorstand[1].toString()) }
                client.post("/test/auszaehlen/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }

                // After AUSGEZAEHLT, the receipt reveals the chosen option's label (the candidate's display name).
                val afterTally =
                    client
                        .get(
                            "/test/verify-receipt/$wahlId?receiptCode=$receiptCode",
                        ) { header("X-Member-Id", voter.toString()) }
                        .bodyAsText()
                afterTally shouldBe "true:Wahl Testmitglied"

                // Winner (candidateA, only ballot cast) is seated into zielGremium.
                val seated =
                    transaction {
                        GremiumMitgliedschaftTable
                            .selectAll()
                            .where {
                                (GremiumMitgliedschaftTable.gremiumId eq zielGremium) and
                                    (GremiumMitgliedschaftTable.memberId eq candidateA)
                            }.count()
                    }
                seated shouldBe 1L
            }
        }

        test("one-member-one-vote: a second castStimme attempt for the same member is rejected on both the geheim and non-geheim path") {
            testApplication {
                application {
                    install(StatusPages) { installWahlExceptionHandlers() }
                    routing { registerWahlTestRoutes() }
                }

                val gremiumId = createTestGremium("Doppelabstimmung Vorstand")
                val chair = createTestMember("wahl-doppel-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val voter = createTestMember("wahl-doppel-voter@example.org")
                addMitglied(gremiumId, voter, GremiumRolle.MITGLIED)
                val wahlvorstand = (1..3).map { createTestMember("wahl-doppel-wv$it@example.org") }

                listOf(true, false).forEach { geheim ->
                    val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 5, 1, 18, 0))
                    val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)
                    val wahlId =
                        client
                            .post("/test/open-wahl/$antragId/JA_NEIN?geheim=$geheim") { header("X-Member-Id", chair.toString()) }
                            .bodyAsText()
                            .substringBefore(":")
                    client.post("/test/appoint-wahlvorstand/$wahlId?memberIds=${wahlvorstand.joinToString(",")}") {
                        header("X-Member-Id", chair.toString())
                    }
                    client.post("/test/open-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }

                    val first = client.post("/test/cast-stimme/$wahlId?antwort=JA") { header("X-Member-Id", voter.toString()) }
                    first.status shouldBe HttpStatusCode.OK
                    val second = client.post("/test/cast-stimme/$wahlId?antwort=NEIN") { header("X-Member-Id", voter.toString()) }
                    second.status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        test("concurrency: N simultaneous castStimme calls for the same member on a geheim Wahl -- exactly one succeeds") {
            testApplication {
                application {
                    install(StatusPages) { installWahlExceptionHandlers() }
                    routing { registerWahlTestRoutes() }
                }

                val gremiumId = createTestGremium("Concurrency Vorstand")
                val chair = createTestMember("wahl-concurrency-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val voter = createTestMember("wahl-concurrency-voter@example.org")
                addMitglied(gremiumId, voter, GremiumRolle.MITGLIED)
                val wahlvorstand = (1..3).map { createTestMember("wahl-concurrency-wv$it@example.org") }

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 6, 1, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)
                val wahlId =
                    client
                        .post("/test/open-wahl/$antragId/JA_NEIN") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/appoint-wahlvorstand/$wahlId?memberIds=${wahlvorstand.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }
                client.post("/test/open-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }

                val attempts = 8
                val results =
                    coroutineScope {
                        (1..attempts)
                            .map {
                                async {
                                    client.post("/test/cast-stimme/$wahlId?antwort=JA") { header("X-Member-Id", voter.toString()) }.status
                                }
                            }.map { it.await() }
                    }
                results.count { it == HttpStatusCode.OK } shouldBe 1
                results.count { it == HttpStatusCode.Conflict } shouldBe attempts - 1

                val teilnahmeCount =
                    transaction {
                        WahlTeilnahmeTable
                            .selectAll()
                            .where { (WahlTeilnahmeTable.wahlId eq Uuid.parse(wahlId)) and (WahlTeilnahmeTable.memberId eq voter) }
                            .count()
                    }
                teilnahmeCount shouldBe 1L
            }
        }

        test("appointWahlvorstand rejects a member currently active in a VORSTAND-typed zielGremium (Wahlvorstand/Vorstand separation)") {
            testApplication {
                application {
                    install(StatusPages) { installWahlExceptionHandlers() }
                    routing { registerWahlTestRoutes() }
                }

                val hostGremium = createTestGremium("MV Separation", GremiumType.MITGLIEDERVERSAMMLUNG)
                val zielVorstand = createTestGremium("Vorstand Separation")
                val chair = createTestMember("wahl-sep-chair@example.org")
                addMitglied(hostGremium, chair, GremiumRolle.VORSITZ)
                val vorstandMember = createTestMember("wahl-sep-vorstand@example.org")
                addMitglied(zielVorstand, vorstandMember, GremiumRolle.MITGLIED)
                val others = (1..3).map { createTestMember("wahl-sep-other$it@example.org") }

                val sitzungId = createTestSitzung(hostGremium, LocalDateTime(2026, 7, 1, 18, 0))
                val antragId = createTerminierterAntrag(hostGremium, sitzungId, chair)
                val wahlId =
                    client
                        .post("/test/open-wahl/$antragId/EINZELWAHL?zielGremiumId=$zielVorstand&sitzeCount=1") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")

                val memberIds = (others + vorstandMember).joinToString(",")
                val rejected =
                    client.post("/test/appoint-wahlvorstand/$wahlId?memberIds=$memberIds") { header("X-Member-Id", chair.toString()) }
                rejected.status shouldBe HttpStatusCode.Conflict

                val allowed =
                    client
                        .post("/test/appoint-wahlvorstand/$wahlId?memberIds=${others.joinToString(",")}") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                allowed shouldBe "3"
            }
        }

        test("authorization: non-Gremium-leadership member cannot openWahl; ineligible member cannot castStimme") {
            testApplication {
                application {
                    install(StatusPages) { installWahlExceptionHandlers() }
                    routing { registerWahlTestRoutes() }
                }

                val gremiumId = createTestGremium("Auth Vorstand")
                val chair = createTestMember("wahl-auth-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val plainMember = createTestMember("wahl-auth-plain@example.org")
                addMitglied(gremiumId, plainMember, GremiumRolle.MITGLIED)
                val outsider = createTestMember("wahl-auth-outsider@example.org")
                val wahlvorstand = (1..3).map { createTestMember("wahl-auth-wv$it@example.org") }

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 8, 1, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)

                val forbiddenOpen =
                    client.post("/test/open-wahl/$antragId/JA_NEIN") { header("X-Member-Id", plainMember.toString()) }
                forbiddenOpen.status shouldBe HttpStatusCode.Forbidden

                val wahlId =
                    client
                        .post("/test/open-wahl/$antragId/JA_NEIN") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/appoint-wahlvorstand/$wahlId?memberIds=${wahlvorstand.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }
                client.post("/test/open-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }

                // outsider is not in the eligibility snapshot (not a Gremium member).
                val forbiddenVote = client.post("/test/cast-stimme/$wahlId?antwort=JA") { header("X-Member-Id", outsider.toString()) }
                forbiddenVote.status shouldBe HttpStatusCode.Forbidden

                val unauthenticated = client.post("/test/cast-stimme/$wahlId?antwort=JA")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "geheim ballot secrecy: wahl_stimmzettel.cast_at is decoupled from wahl_teilnahme.voted_at, " +
                "not a bit-identical join key back to the voter",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installWahlExceptionHandlers() }
                    routing { registerWahlTestRoutes() }
                }

                val gremiumId = createTestGremium("Geheim Timestamp Vorstand")
                val chair = createTestMember("wahl-ts-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val voter = createTestMember("wahl-ts-voter@example.org")
                addMitglied(gremiumId, voter, GremiumRolle.MITGLIED)
                val wahlvorstand = (1..3).map { createTestMember("wahl-ts-wv$it@example.org") }

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 9, 1, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)
                val wahlId =
                    client
                        .post("/test/open-wahl/$antragId/JA_NEIN?geheim=true") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/appoint-wahlvorstand/$wahlId?memberIds=${wahlvorstand.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }
                client.post("/test/open-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }
                client.post("/test/cast-stimme/$wahlId?antwort=JA") { header("X-Member-Id", voter.toString()) }

                val votedAt =
                    transaction {
                        WahlTeilnahmeTable
                            .selectAll()
                            .where { (WahlTeilnahmeTable.wahlId eq Uuid.parse(wahlId)) and (WahlTeilnahmeTable.memberId eq voter) }
                            .single()[WahlTeilnahmeTable.votedAt]
                    }
                val castAt =
                    transaction {
                        WahlStimmzettelTable
                            .selectAll()
                            .where { WahlStimmzettelTable.wahlId eq Uuid.parse(wahlId) }
                            .single()[WahlStimmzettelTable.castAt]
                    }
                // The bug: both columns were written from the same `now` value, so a trivial
                // `voted_at = cast_at` join re-linked every secret ballot to its voter. The fix:
                // cast_at is coarsened to the calendar date (time-of-day zeroed) for a geheim
                // Wahl, so it is never bit-identical to voted_at's full-precision timestamp.
                castAt shouldNotBe votedAt
                castAt.hour shouldBe 0
                castAt.minute shouldBe 0
                castAt.second shouldBe 0
                castAt.date shouldBe votedAt.date
            }
        }

        test("listStimmzettel hides selectedOptionLabels for a geheim Wahl until AUSGEZAEHLT (no mid-vote running-tally leak)") {
            testApplication {
                application {
                    install(StatusPages) { installWahlExceptionHandlers() }
                    routing { registerWahlTestRoutes() }
                }

                val gremiumId = createTestGremium("Geheim Transparenz Vorstand")
                val chair = createTestMember("wahl-transparenz-chair@example.org")
                addMitglied(gremiumId, chair, GremiumRolle.VORSITZ)
                val voter1 = createTestMember("wahl-transparenz-v1@example.org")
                val voter2 = createTestMember("wahl-transparenz-v2@example.org")
                addMitglied(gremiumId, voter1, GremiumRolle.MITGLIED)
                addMitglied(gremiumId, voter2, GremiumRolle.MITGLIED)
                val wahlvorstand = (1..3).map { createTestMember("wahl-transparenz-wv$it@example.org") }

                val sitzungId = createTestSitzung(gremiumId, LocalDateTime(2026, 9, 15, 18, 0))
                val antragId = createTerminierterAntrag(gremiumId, sitzungId, chair)
                val wahlId =
                    client
                        .post("/test/open-wahl/$antragId/JA_NEIN?geheim=true") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/appoint-wahlvorstand/$wahlId?memberIds=${wahlvorstand.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }
                client.post("/test/open-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }
                client.post("/test/cast-stimme/$wahlId?antwort=JA") { header("X-Member-Id", voter1.toString()) }
                client.post("/test/cast-stimme/$wahlId?antwort=NEIN") { header("X-Member-Id", voter2.toString()) }
                client.post("/test/close-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }

                // OFFEN/GESCHLOSSEN, pre-tally: any authenticated member must not be able to read
                // the plaintext choices and compute a running tally themselves.
                val beforeTally =
                    client
                        .get("/test/list-stimmzettel/$wahlId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                beforeTally.split(";").forEach { entry -> entry.substringAfter(":") shouldBe "" }

                client.post("/test/freigeben-auszaehlung/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }
                client.post("/test/freigeben-auszaehlung/$wahlId") { header("X-Member-Id", wahlvorstand[1].toString()) }
                client.post("/test/auszaehlen/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }

                // AUSGEZAEHLT: labels are now revealed.
                val afterTally =
                    client
                        .get("/test/list-stimmzettel/$wahlId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                afterTally.split(";").any { entry -> entry.substringAfter(":").isNotBlank() } shouldBe true
            }
        }

        test(
            "EINZELWAHL requires an absolute majority, not mere plurality: a sub-majority plurality winner " +
                "resolves to VERTAGT (Stichwahl signal) and nobody is seated",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installWahlExceptionHandlers() }
                    routing { registerWahlTestRoutes() }
                }

                val hostGremium = createTestGremium("MV Einzelwahl Mehrheit", GremiumType.MITGLIEDERVERSAMMLUNG)
                val zielGremium = createTestGremium("Vorstand Mehrheit Ziel")
                val chair = createTestMember("wahl-mehrheit-chair@example.org")
                addMitglied(hostGremium, chair, GremiumRolle.VORSITZ)

                val candidateA = createTestMember("wahl-mehrheit-candA@example.org")
                val candidateB = createTestMember("wahl-mehrheit-candB@example.org")
                val candidateC = createTestMember("wahl-mehrheit-candC@example.org")
                val voters = (1..7).map { createTestMember("wahl-mehrheit-voter$it@example.org") }
                val wahlvorstand = (1..3).map { createTestMember("wahl-mehrheit-wv$it@example.org") }

                val sitzungId = createTestSitzung(hostGremium, LocalDateTime(2026, 10, 1, 18, 0))
                val antragId = createTerminierterAntrag(hostGremium, sitzungId, chair)

                val wahlId =
                    client
                        .post("/test/open-wahl/$antragId/EINZELWAHL?zielGremiumId=$zielGremium&sitzeCount=1&geheim=false") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")

                client.post("/test/appoint-wahlvorstand/$wahlId?memberIds=${wahlvorstand.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }

                val kandidaturA =
                    client.post("/test/submit-kandidatur/$wahlId") { header("X-Member-Id", candidateA.toString()) }.bodyAsText()
                val kandidaturB =
                    client.post("/test/submit-kandidatur/$wahlId") { header("X-Member-Id", candidateB.toString()) }.bodyAsText()
                val kandidaturC =
                    client.post("/test/submit-kandidatur/$wahlId") { header("X-Member-Id", candidateC.toString()) }.bodyAsText()

                client.post("/test/freigeben-kandidatenliste/$wahlId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/open-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }

                fun optionIdFor(kandidaturId: String): String =
                    transaction {
                        WahlOptionTable
                            .selectAll()
                            .where {
                                (WahlOptionTable.wahlId eq Uuid.parse(wahlId)) and
                                    (WahlOptionTable.kandidaturId eq Uuid.parse(kandidaturId))
                            }.single()[WahlOptionTable.id]
                    }.toString()
                val optionAId = optionIdFor(kandidaturA)
                val optionBId = optionIdFor(kandidaturB)
                val optionCId = optionIdFor(kandidaturC)

                // 3 votes for A, 2 for B, 2 for C: A is the plurality winner (3/7 ~= 42.9%) but
                // fails the default 50% absolute-majority requirement -- must not be seated.
                voters.take(3).forEach { v ->
                    client.post("/test/cast-stimme/$wahlId?selectedOptionIds=$optionAId") { header("X-Member-Id", v.toString()) }
                }
                voters.drop(3).take(2).forEach { v ->
                    client.post("/test/cast-stimme/$wahlId?selectedOptionIds=$optionBId") { header("X-Member-Id", v.toString()) }
                }
                voters.drop(5).take(2).forEach { v ->
                    client.post("/test/cast-stimme/$wahlId?selectedOptionIds=$optionCId") { header("X-Member-Id", v.toString()) }
                }

                client.post("/test/close-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }
                client.post("/test/freigeben-auszaehlung/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }
                client.post("/test/freigeben-auszaehlung/$wahlId") { header("X-Member-Id", wahlvorstand[1].toString()) }
                val ergebnis =
                    client.post("/test/auszaehlen/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }.bodyAsText()
                // winnerOptionIds empty, tie=true (majority-not-met is reported as a tie), majorityMet blank (personnel Wahl).
                ergebnis shouldBe ":true:"

                val antragStatus =
                    transaction { AntragTable.selectAll().where { AntragTable.id eq antragId }.single()[AntragTable.status] }
                antragStatus shouldBe AntragStatus.VERTAGT

                val seated =
                    transaction {
                        GremiumMitgliedschaftTable
                            .selectAll()
                            .where {
                                (GremiumMitgliedschaftTable.gremiumId eq zielGremium) and
                                    (GremiumMitgliedschaftTable.memberId eq candidateA)
                            }.count()
                    }
                seated shouldBe 0L
            }
        }

        test(
            "auszaehlen seating an incumbent (already an active member of zielGremium) closes the " +
                "existing active membership instead of leaving two concurrent active rows",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installWahlExceptionHandlers() }
                    routing { registerWahlTestRoutes() }
                }

                val hostGremium = createTestGremium("MV Einzelwahl Wiederwahl", GremiumType.MITGLIEDERVERSAMMLUNG)
                val zielGremium = createTestGremium("Vorstand Wiederwahl Ziel")
                val chair = createTestMember("wahl-wiederwahl-chair@example.org")
                addMitglied(hostGremium, chair, GremiumRolle.VORSITZ)

                // Incumbent: already an active MITGLIED of zielGremium before the Wahl even opens.
                val incumbent = createTestMember("wahl-wiederwahl-incumbent@example.org")
                addMitglied(zielGremium, incumbent, GremiumRolle.MITGLIED)
                val voter = createTestMember("wahl-wiederwahl-voter@example.org")
                val wahlvorstand = (1..3).map { createTestMember("wahl-wiederwahl-wv$it@example.org") }

                val sitzungId = createTestSitzung(hostGremium, LocalDateTime(2026, 11, 1, 18, 0))
                val antragId = createTerminierterAntrag(hostGremium, sitzungId, chair)

                // Re-elected into a different Rolle (VORSITZ) on the same Gremium -- the "sitting
                // MITGLIED elected to VORSITZ" case from the review finding, not just plain re-election.
                val opened =
                    client
                        .post(
                            "/test/open-wahl/$antragId/EINZELWAHL?zielGremiumId=$zielGremium&sitzeCount=1&zielRolle=VORSITZ&geheim=false",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val wahlId = opened.substringBefore(":")

                client.post("/test/appoint-wahlvorstand/$wahlId?memberIds=${wahlvorstand.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }

                val kandidaturIncumbent =
                    client.post("/test/submit-kandidatur/$wahlId") { header("X-Member-Id", incumbent.toString()) }.bodyAsText()
                client.post("/test/freigeben-kandidatenliste/$wahlId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/open-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }

                val optionId =
                    transaction {
                        WahlOptionTable
                            .selectAll()
                            .where {
                                (WahlOptionTable.wahlId eq Uuid.parse(wahlId)) and
                                    (WahlOptionTable.kandidaturId eq Uuid.parse(kandidaturIncumbent))
                            }.single()[WahlOptionTable.id]
                    }.toString()
                client.post("/test/cast-stimme/$wahlId?selectedOptionIds=$optionId") { header("X-Member-Id", voter.toString()) }

                client.post("/test/close-voting/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }
                client.post("/test/freigeben-auszaehlung/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }
                client.post("/test/freigeben-auszaehlung/$wahlId") { header("X-Member-Id", wahlvorstand[1].toString()) }
                client.post("/test/auszaehlen/$wahlId") { header("X-Member-Id", wahlvorstand[0].toString()) }

                val rows =
                    transaction {
                        GremiumMitgliedschaftTable
                            .selectAll()
                            .where {
                                (GremiumMitgliedschaftTable.gremiumId eq zielGremium) and
                                    (GremiumMitgliedschaftTable.memberId eq incumbent)
                            }.map { it[GremiumMitgliedschaftTable.rolle] to (it[GremiumMitgliedschaftTable.until] == null) }
                    }
                // Exactly one row (the pre-existing MITGLIED membership) is closed, and exactly one
                // (the freshly seated VORSITZ membership) is active -- never two concurrent active rows.
                rows.size shouldBe 2
                rows.count { (_, active) -> active } shouldBe 1
                val activeRolle = rows.single { (_, active) -> active }.first
                activeRolle shouldBe GremiumRolle.VORSITZ
            }
        }
    })

private fun StatusPagesConfig.installWahlExceptionHandlers() {
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
 * Shared throwaway routes for [WahlServiceTest] -- string encodings are kept deliberately
 * simple/parseable, same house style as [GovernanceServiceTest]'s own test routes.
 */
private fun Route.registerWahlTestRoutes() {
    post("/test/open-wahl/{antragId}/{wahlTyp}") {
        val service = WahlService(call)
        val q = call.request.queryParameters
        val w =
            service.openWahl(
                WahlOpenInput(
                    antragId = call.parameters["antragId"]!!,
                    wahlTyp = WahlTyp.valueOf(call.parameters["wahlTyp"]!!),
                    geheim = q["geheim"]?.toBoolean() ?: true,
                    sitzeCount = q["sitzeCount"]?.toInt() ?: 1,
                    zielGremiumId = q["zielGremiumId"],
                    zielRolle = q["zielRolle"]?.let { GremiumRolle.valueOf(it) },
                    requiredMajorityPercent = q["requiredMajorityPercent"]?.toInt() ?: 50,
                    tallyThreshold = q["tallyThreshold"]?.toInt() ?: 2,
                ),
            )
        call.respondText("${w.id}:${w.status}")
    }
    post("/test/appoint-wahlvorstand/{wahlId}") {
        val service = WahlService(call)
        val memberIds = call.request.queryParameters["memberIds"]!!.split(",")
        val list = service.appointWahlvorstand(call.parameters["wahlId"]!!, memberIds)
        call.respondText(list.size.toString())
    }
    post("/test/submit-kandidatur/{wahlId}") {
        val service = WahlService(call)
        val k = service.submitKandidatur(call.parameters["wahlId"]!!, KandidaturInput(motivationText = "Motivation"))
        call.respondText(k.id)
    }
    post("/test/withdraw-kandidatur/{id}") {
        val service = WahlService(call)
        val k = service.withdrawKandidatur(call.parameters["id"]!!)
        call.respondText((k.withdrawnAt != null).toString())
    }
    post("/test/freigeben-kandidatenliste/{wahlId}") {
        val service = WahlService(call)
        val w = service.freigebenKandidatenliste(call.parameters["wahlId"]!!)
        call.respondText("${w.status}:${w.options.size}")
    }
    post("/test/open-voting/{wahlId}") {
        val service = WahlService(call)
        val w = service.openVoting(call.parameters["wahlId"]!!)
        call.respondText(w.status.name)
    }
    post("/test/cast-stimme/{wahlId}") {
        val service = WahlService(call)
        val q = call.request.queryParameters
        val antwort = q["antwort"]?.let { WahlAntwort.valueOf(it) }
        val selectedOptionIds = q["selectedOptionIds"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val result =
            service.castStimme(
                StimmzettelInput(
                    wahlId = call.parameters["wahlId"]!!,
                    antwort = antwort,
                    selectedOptionIds = selectedOptionIds,
                ),
            )
        call.respondText("${result.id}:${result.receiptCode ?: ""}")
    }
    post("/test/close-voting/{wahlId}") {
        val service = WahlService(call)
        val w = service.closeVoting(call.parameters["wahlId"]!!)
        call.respondText(w.status.name)
    }
    post("/test/freigeben-auszaehlung/{wahlId}") {
        val service = WahlService(call)
        service.freigebenAuszaehlung(call.parameters["wahlId"]!!)
        call.respondText("ok")
    }
    post("/test/auszaehlen/{wahlId}") {
        val service = WahlService(call)
        val e = service.auszaehlen(call.parameters["wahlId"]!!)
        call.respondText("${e.winnerOptionIds.joinToString(",")}:${e.tie}:${e.majorityMet ?: ""}")
    }
    post("/test/abort-wahl/{wahlId}") {
        val service = WahlService(call)
        val w = service.abortWahl(call.parameters["wahlId"]!!)
        call.respondText(w.status.name)
    }
    get("/test/get-wahl/{wahlId}") {
        val service = WahlService(call)
        val w = service.getWahl(call.parameters["wahlId"]!!)
        call.respondText("${w.status}:${w.options.size}:${w.beschlussId ?: ""}")
    }
    get("/test/verify-receipt/{wahlId}") {
        val service = WahlService(call)
        val receiptCode = call.request.queryParameters["receiptCode"]!!
        val r = service.verifyReceipt(call.parameters["wahlId"]!!, receiptCode)
        call.respondText("${r.found}:${r.optionLabel ?: ""}")
    }
    get("/test/list-stimmzettel/{wahlId}") {
        val service = WahlService(call)
        val list = service.listStimmzettel(call.parameters["wahlId"]!!)
        call.respondText(list.joinToString(";") { "${it.id}:${it.selectedOptionLabels.joinToString("|")}" })
    }
}

/**
 * Hard-deletes every row this Spec created, child-before-parent -- same discipline as
 * [cleanUpGovernanceTestData]. Wahl child tables are deleted before [WahlTable] itself; [WahlTable]
 * and [BeschlussTable] are mutually FK-linked (`wahl.beschluss_id` -> `beschluss.id`,
 * `beschluss.wahl_id` -> `wahl.id`), so both FKs are nulled out before either table's rows are
 * deleted, mirroring [cleanUpGovernanceTestData]'s `abstimmung`/`beschluss` cycle-breaking.
 */
private fun cleanUpWahlTestData(
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

        val wahlCondition =
            when {
                sitzungIds.isNotEmpty() && memberIds.isNotEmpty() ->
                    (WahlTable.sitzungId inList sitzungIds) or (WahlTable.openedBy inList memberIds)
                sitzungIds.isNotEmpty() -> WahlTable.sitzungId inList sitzungIds
                memberIds.isNotEmpty() -> WahlTable.openedBy inList memberIds
                else -> null
            }
        val wahlIds =
            if (wahlCondition != null) {
                WahlTable.selectAll().where { wahlCondition }.map { it[WahlTable.id] }
            } else {
                emptyList()
            }
        if (wahlIds.isNotEmpty()) {
            WahlTable.update({ WahlTable.id inList wahlIds }) { it[beschlussId] = null }
            BeschlussTable.update({ BeschlussTable.wahlId inList wahlIds }) { it[BeschlussTable.wahlId] = null }
            val stimmzettelIds =
                WahlStimmzettelTable.selectAll().where { WahlStimmzettelTable.wahlId inList wahlIds }.map { it[WahlStimmzettelTable.id] }
            if (stimmzettelIds.isNotEmpty()) {
                WahlStimmzettelAuswahlTable.deleteWhere { WahlStimmzettelAuswahlTable.stimmzettelId inList stimmzettelIds }
            }
            WahlStimmzettelTable.deleteWhere { WahlStimmzettelTable.wahlId inList wahlIds }
            WahlTeilnahmeTable.deleteWhere { WahlTeilnahmeTable.wahlId inList wahlIds }
            WahlFreigabeTable.deleteWhere { WahlFreigabeTable.wahlId inList wahlIds }
            WahlWahlberechtigtTable.deleteWhere { WahlWahlberechtigtTable.wahlId inList wahlIds }
            WahlWahlvorstandTable.deleteWhere { WahlWahlvorstandTable.wahlId inList wahlIds }
            WahlOptionTable.deleteWhere { WahlOptionTable.wahlId inList wahlIds }
            WahlKandidaturTable.deleteWhere { WahlKandidaturTable.wahlId inList wahlIds }
            WahlTable.deleteWhere { WahlTable.id inList wahlIds }
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
