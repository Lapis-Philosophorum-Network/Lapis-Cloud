package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
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
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AbstimmungOptionTable
import network.lapis.cloud.server.db.generated.AbstimmungStimmeTable
import network.lapis.cloud.server.db.generated.AbstimmungTable
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AntragTable
import network.lapis.cloud.server.db.generated.AnwesenheitTable
import network.lapis.cloud.server.db.generated.BeschlussTable
import network.lapis.cloud.server.db.generated.GremiumMitgliedschaftTable
import network.lapis.cloud.server.db.generated.GremiumTable
import network.lapis.cloud.server.db.generated.LtrBalanceTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SitzungTable
import network.lapis.cloud.server.db.generated.TagesordnungspunktTable
import network.lapis.cloud.server.dsgvo.GovernancePersonalData
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.UnauthenticatedException
import network.lapis.cloud.shared.domain.AbstimmungOpenInput
import network.lapis.cloud.shared.domain.AbstimmungStatus
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AntragInput
import network.lapis.cloud.shared.domain.AntragPruefungsEntscheidung
import network.lapis.cloud.shared.domain.AntragResolutionInput
import network.lapis.cloud.shared.domain.AntragStatus
import network.lapis.cloud.shared.domain.AnwesenheitInput
import network.lapis.cloud.shared.domain.AnwesenheitStatus
import network.lapis.cloud.shared.domain.BeschlussInput
import network.lapis.cloud.shared.domain.BeschlussStatus
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.GremiumInput
import network.lapis.cloud.shared.domain.GremiumMitgliedschaftInput
import network.lapis.cloud.shared.domain.GremiumRolle
import network.lapis.cloud.shared.domain.GremiumType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.SitzungInput
import network.lapis.cloud.shared.domain.SitzungsFormat
import network.lapis.cloud.shared.domain.SitzungsStatus
import network.lapis.cloud.shared.domain.StimmeInput
import network.lapis.cloud.shared.domain.TagesordnungspunktInput
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Exercises [GovernanceService] end to end, mirroring [ServiceIntegrationTest]'s/
 * [DsgvoServiceTest]'s house style (throwaway routes calling the service class directly, no wire
 * format to reverse-engineer). Uses its own freshly created Gremien and members throughout — never
 * [DevSeedData]'s four fixed demo members as Gremium members/Sitzung participants — for the same
 * reason [DsgvoServiceTest] documents for its own fixtures: other Spec classes running in the same
 * H2-in-memory JVM assert exact counts against the shared demo fixtures, and this file's own
 * assertions (e.g. exact `eligibleMemberCount`) would themselves become order-dependent if a
 * shared member acquired an extra Gremium role. [afterSpec] hard-deletes every row this file
 * created, same discipline as `cleanUpDsgvoTestData`.
 *
 * DevSeedData's ADMIN/BOARD accounts are still used as the *actors* performing privileged actions
 * (creating Gremien, adding members) — only the Gremien/Sitzungen/members created *as test data*
 * are fresh.
 */
class GovernanceServiceTest :
    FunSpec({
        val createdGremiumIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpGovernanceTestData(createdGremiumIds, createdMemberIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Governance Testmitglied"
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

        /**
         * Meritokratische Abstimmungen (V0.2.3) tests seed [LtrBalanceTable] directly rather than
         * going through a real ledger (V0.6 doesn't exist yet) — mirrors how these tests already
         * seed Gremium/Sitzung/Member rows directly instead of using a UI flow.
         */
        fun seedLtrBalance(
            memberId: Uuid,
            balance: BigDecimal,
        ) {
            transaction {
                LtrBalanceTable.insert {
                    it[LtrBalanceTable.memberId] = memberId
                    it[balanceLtr] = balance
                    it[updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
        }

        test("createGremium requires BOARD/ADMIN; reads require authentication but no elevated role") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing {
                        post("/test/create-gremium/{name}/{type}/{quorumPercent}") {
                            val service = GovernanceService(call)
                            val p = call.parameters
                            val g =
                                service.createGremium(
                                    GremiumInput(
                                        name = p["name"]!!,
                                        type = GremiumType.valueOf(p["type"]!!),
                                        description = "Testgremium",
                                        quorumPercent = p["quorumPercent"]!!.toInt(),
                                    ),
                                )
                            call.respondText(g.id)
                        }
                        get("/test/list-gremien") {
                            val service = GovernanceService(call)
                            call.respondText(service.listGremien().joinToString(",") { it.id })
                        }
                    }
                }

                val forbidden =
                    client.post("/test/create-gremium/Testverein/VORSTAND/50") { header("X-Member-Id", MEMBER_ID) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val created =
                    client
                        .post("/test/create-gremium/Vorstand%20Test/VORSTAND/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                created.isBlank() shouldBe false
                createdGremiumIds += Uuid.parse(created)

                val unauthenticated = client.get("/test/list-gremien")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized

                val listedByMember = client.get("/test/list-gremien") { header("X-Member-Id", MEMBER_ID) }.bodyAsText()
                (created in listedByMember.split(",")) shouldBe true
            }
        }

        test(
            "Gremium leadership authorization: plain member without a Gremium role cannot create a " +
                "Sitzung, VORSITZ member can",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                        exception<NotFoundException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.NotFound)
                        }
                    }
                    routing {
                        post("/test/create-gremium2/{quorumPercent}") {
                            val service = GovernanceService(call)
                            val g =
                                service.createGremium(
                                    GremiumInput(
                                        name = "Arbeitskreis IT",
                                        type = GremiumType.ARBEITSKREIS,
                                        description = "Testgremium 2",
                                        quorumPercent = call.parameters["quorumPercent"]!!.toInt(),
                                    ),
                                )
                            call.respondText(g.id)
                        }
                        post("/test/add-mitglied/{gremiumId}/{memberId}/{rolle}") {
                            val service = GovernanceService(call)
                            val q = call.request.queryParameters
                            val since =
                                LocalDate(
                                    q["sinceYear"]?.toInt() ?: 2020,
                                    q["sinceMonth"]?.toInt() ?: 1,
                                    q["sinceDay"]?.toInt() ?: 1,
                                )
                            val m =
                                service.addGremiumMitglied(
                                    call.parameters["gremiumId"]!!,
                                    GremiumMitgliedschaftInput(
                                        memberId = call.parameters["memberId"]!!,
                                        rolle = GremiumRolle.valueOf(call.parameters["rolle"]!!),
                                        since = since,
                                    ),
                                )
                            call.respondText(m.id)
                        }
                        post("/test/create-sitzung/{gremiumId}/{year}/{month}/{day}/{hour}") {
                            val service = GovernanceService(call)
                            val p = call.parameters
                            val scheduledAt =
                                LocalDateTime(p["year"]!!.toInt(), p["month"]!!.toInt(), p["day"]!!.toInt(), p["hour"]!!.toInt(), 0)
                            val s =
                                service.createSitzung(
                                    SitzungInput(
                                        gremiumId = p["gremiumId"]!!,
                                        title = "Testsitzung",
                                        scheduledAt = scheduledAt,
                                        location = "Vereinsheim",
                                        format = SitzungsFormat.PRAESENZ,
                                    ),
                                )
                            call.respondText("${s.id}:${s.status}")
                        }
                    }
                }

                val gremiumId = client.post("/test/create-gremium2/50") { header("X-Member-Id", BOARD_ID) }.bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumId)

                val vorsitzMember = createTestMember("gov-vorsitz@example.org")
                val plainMember = createTestMember("gov-plain@example.org")

                client.post(
                    "/test/add-mitglied/$gremiumId/$vorsitzMember/VORSITZ",
                ) { header("X-Member-Id", BOARD_ID) }

                val forbidden =
                    client.post(
                        "/test/create-sitzung/$gremiumId/2026/4/1/18",
                    ) { header("X-Member-Id", plainMember.toString()) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val allowed =
                    client
                        .post(
                            "/test/create-sitzung/$gremiumId/2026/4/1/18",
                        ) { header("X-Member-Id", vorsitzMember.toString()) }
                        .bodyAsText()
                allowed.substringBefore(":").isBlank() shouldBe false
                allowed.substringAfter(":") shouldBe SitzungsStatus.GEPLANT.name
            }
        }

        test(
            "full meeting lifecycle: agenda, mixed attendance, quorum reflects eligible-as-of-scheduledAt, " +
                "Beschluss snapshots quorumMet, status update, protocol draft, guest does not count toward quorum",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                        exception<NotFoundException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.NotFound)
                        }
                    }
                    routing { registerGovernanceTestRoutes() }
                }

                val gremiumId =
                    client
                        .post("/test/create-gremium/Vorstand%20Lifecycle/VORSTAND/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumId)

                val m1 = createTestMember("gov-m1@example.org")
                val m2 = createTestMember("gov-m2@example.org")
                val m3 = createTestMember("gov-m3@example.org")
                val m4 = createTestMember("gov-m4@example.org")
                val guest = createTestMember("gov-guest@example.org")

                client.post("/test/add-mitglied/$gremiumId/$m1/VORSITZ") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$m2/MITGLIED") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$m3/MITGLIED") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$m4/MITGLIED") { header("X-Member-Id", BOARD_ID) }
                // guest is deliberately never added to the Gremium.

                val sitzungResponse =
                    client
                        .post("/test/create-sitzung/$gremiumId/2026/3/10/18") { header("X-Member-Id", m1.toString()) }
                        .bodyAsText()
                val sitzungId = sitzungResponse.substringBefore(":")
                sitzungResponse.substringAfter(":") shouldBe SitzungsStatus.GEPLANT.name

                client.post("/test/add-top/$sitzungId/1") { header("X-Member-Id", m1.toString()) }

                client.post("/test/record-attendance/$sitzungId/$m1/ANWESEND") { header("X-Member-Id", m1.toString()) }
                client.post("/test/record-attendance/$sitzungId/$m2/ENTSCHULDIGT") { header("X-Member-Id", m1.toString()) }
                client.post(
                    "/test/record-attendance/$sitzungId/$m3/VERTRETEN?representedBy=$m1",
                ) { header("X-Member-Id", m1.toString()) }
                // m4 is intentionally never recorded (absent without excuse, no row at all).
                client.post("/test/record-attendance/$sitzungId/$guest/ANWESEND") { header("X-Member-Id", m1.toString()) }

                val quorum = client.get("/test/check-quorum/$sitzungId") { header("X-Member-Id", m1.toString()) }.bodyAsText()
                // Eligible: m1..m4 (4) -- guest never counts. Present (ANWESEND/VERTRETEN, eligible only):
                // m1 + m3 = 2. Required = ceil(4 * 50 / 100) = 2. met = true.
                quorum shouldBe "4:2:2:true"

                val beschluss =
                    client
                        .post("/test/record-beschluss/$sitzungId") { header("X-Member-Id", m1.toString()) }
                        .bodyAsText()
                val parts = beschluss.split(":")
                parts[0] shouldBe "VORSTAND-2026-01"
                parts[1] shouldBe "true"
                parts[2] shouldBe BeschlussStatus.ANGENOMMEN.name

                val statusUpdate =
                    client
                        .post(
                            "/test/update-status/$sitzungId/${SitzungsStatus.DURCHGEFUEHRT}",
                        ) { header("X-Member-Id", m1.toString()) }
                        .bodyAsText()
                statusUpdate shouldBe SitzungsStatus.DURCHGEFUEHRT.name

                // 1 Tagesordnungspunkt, 4 recorded Anwesenheit rows (m1, m2, m3, guest -- not m4), 1 Beschluss.
                val protocol =
                    client.get("/test/protocol-draft/$sitzungId") { header("X-Member-Id", m1.toString()) }.bodyAsText()
                protocol shouldBe "1:4:1"
            }
        }

        test("quorum: single-member committee at the exact boundary, zero attendees is not met") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                        exception<NotFoundException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.NotFound)
                        }
                    }
                    routing { registerGovernanceTestRoutes() }
                }

                val gremiumId =
                    client
                        .post("/test/create-gremium/Einzelgremium/AUSSCHUSS/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumId)

                val soleMember = createTestMember("gov-sole@example.org")
                client.post("/test/add-mitglied/$gremiumId/$soleMember/VORSITZ") { header("X-Member-Id", BOARD_ID) }

                val sitzungId =
                    client
                        .post("/test/create-sitzung/$gremiumId/2026/5/1/10") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .substringBefore(":")

                // Zero attendees recorded yet: eligible=1, present=0, required=ceil(1*0.5)=1, not met.
                val beforeAttendance =
                    client.get("/test/check-quorum/$sitzungId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                beforeAttendance shouldBe "1:0:1:false"

                client.post(
                    "/test/record-attendance/$sitzungId/$soleMember/ANWESEND",
                ) { header("X-Member-Id", soleMember.toString()) }

                // Exact boundary: present=1 == required=1 -> met.
                val afterAttendance =
                    client.get("/test/check-quorum/$sitzungId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                afterAttendance shouldBe "1:1:1:true"
            }
        }

        test(
            "quorum: eligible-as-of-scheduledAt excludes memberships that end before, or start after, the meeting date",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                        exception<NotFoundException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.NotFound)
                        }
                    }
                    routing { registerGovernanceTestRoutes() }
                }

                val gremiumId =
                    client
                        .post("/test/create-gremium/Zeitgremium/SONSTIGES/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumId)

                val exMember = createTestMember("gov-ex@example.org")
                val futureMember = createTestMember("gov-future@example.org")

                // Membership ended well before the Sitzung's scheduled date.
                client.post(
                    "/test/add-mitglied/$gremiumId/$exMember/MITGLIED?sinceYear=2020&sinceMonth=1&sinceDay=1",
                ) { header("X-Member-Id", BOARD_ID) }
                transaction {
                    GremiumMitgliedschaftTable.update({
                        (GremiumMitgliedschaftTable.gremiumId eq Uuid.parse(gremiumId)) and
                            (GremiumMitgliedschaftTable.memberId eq exMember)
                    }) {
                        it[until] = LocalDate(2026, 1, 1)
                    }
                }

                // Membership starts well after the Sitzung's scheduled date.
                client.post(
                    "/test/add-mitglied/$gremiumId/$futureMember/MITGLIED?sinceYear=2026&sinceMonth=6&sinceDay=1",
                ) { header("X-Member-Id", BOARD_ID) }

                val sitzungId =
                    client
                        .post("/test/create-sitzung/$gremiumId/2026/3/1/10") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .substringBefore(":")

                // Neither membership is active as of 2026-03-01 -- eligible=0, vacuously met (0 required).
                val quorum = client.get("/test/check-quorum/$sitzungId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                quorum shouldBe "0:0:0:true"
            }
        }

        test(
            "submitAntrag: Mitgliederversammlung target requires MemberStatus.AKTIV; Gremium target requires " +
                "any-role membership, not just leadership; unauthenticated is rejected",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerAntragTestRoutes()
                    }
                }

                val mvGremiumId =
                    client
                        .post("/test/create-gremium/Mitgliederversammlung/MITGLIEDERVERSAMMLUNG/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(mvGremiumId)

                val aktivMember = createTestMember("antrag-mv-aktiv@example.org")
                val antragStatusMember = createTestMember("antrag-mv-antragstatus@example.org", MemberStatus.ANTRAG)

                val allowed =
                    client.post("/test/submit-antrag/$mvGremiumId") { header("X-Member-Id", aktivMember.toString()) }.bodyAsText()
                allowed.substringAfter(":") shouldBe AntragStatus.EINGEREICHT.name

                val forbiddenNonAktiv =
                    client.post("/test/submit-antrag/$mvGremiumId") { header("X-Member-Id", antragStatusMember.toString()) }
                forbiddenNonAktiv.status shouldBe HttpStatusCode.Forbidden

                val unauthenticated = client.post("/test/submit-antrag/$mvGremiumId")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized

                val gremiumId =
                    client
                        .post("/test/create-gremium/AK%20Antrag/ARBEITSKREIS/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumId)

                val plainGremiumMember = createTestMember("antrag-plain-member@example.org")
                val nonMember = createTestMember("antrag-nonmember@example.org")
                client.post(
                    "/test/add-mitglied/$gremiumId/$plainGremiumMember/MITGLIED",
                ) { header("X-Member-Id", BOARD_ID) }

                val gremiumAllowed =
                    client
                        .post("/test/submit-antrag/$gremiumId") { header("X-Member-Id", plainGremiumMember.toString()) }
                        .bodyAsText()
                gremiumAllowed.substringAfter(":") shouldBe AntragStatus.EINGEREICHT.name

                val gremiumForbidden =
                    client.post("/test/submit-antrag/$gremiumId") { header("X-Member-Id", nonMember.toString()) }
                gremiumForbidden.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "Antrag lifecycle: EINGEREICHT -> GEPRUEFT -> TERMINIERT -> resolveAntrag creates a matching " +
                "Beschluss; VERTAGT -> reschedule -> re-resolve produces a second Beschluss and updates beschlussId",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerAntragTestRoutes()
                    }
                }

                val gremiumId =
                    client
                        .post("/test/create-gremium/Vorstand%20Antrag/VORSTAND/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumId)

                val chair = createTestMember("antrag-lifecycle-chair@example.org")
                val submitter = createTestMember("antrag-lifecycle-submitter@example.org")
                client.post("/test/add-mitglied/$gremiumId/$chair/VORSITZ") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$submitter/MITGLIED") { header("X-Member-Id", BOARD_ID) }

                val antragId =
                    client
                        .post("/test/submit-antrag/$gremiumId") { header("X-Member-Id", submitter.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                val reviewForbidden =
                    client.post("/test/review-antrag/$antragId/ANNEHMEN") { header("X-Member-Id", submitter.toString()) }
                reviewForbidden.status shouldBe HttpStatusCode.Forbidden

                val reviewed =
                    client.post("/test/review-antrag/$antragId/ANNEHMEN") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                reviewed shouldBe AntragStatus.GEPRUEFT.name

                // Re-review is rejected: no longer EINGEREICHT.
                val reReview = client.post("/test/review-antrag/$antragId/ANNEHMEN") { header("X-Member-Id", chair.toString()) }
                reReview.status shouldBe HttpStatusCode.Conflict

                val sitzungId1 =
                    client
                        .post("/test/create-sitzung/$gremiumId/2026/6/1/18") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                val scheduled1 =
                    client
                        .post("/test/schedule-antrag/$antragId/$sitzungId1/1") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                scheduled1.substringBefore(":") shouldBe AntragStatus.TERMINIERT.name

                client.post(
                    "/test/record-attendance/$sitzungId1/$chair/ANWESEND",
                ) { header("X-Member-Id", chair.toString()) }

                val vertagt =
                    client
                        .post("/test/resolve-antrag/$antragId/VERTAGT") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                vertagt.substringBefore(":") shouldBe AntragStatus.VERTAGT.name
                val firstBeschlussId = vertagt.substringAfter(":")

                // Reschedule onto a second Sitzung (allowed again from VERTAGT) and resolve again.
                val sitzungId2 =
                    client
                        .post("/test/create-sitzung/$gremiumId/2026/7/1/18") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                val scheduled2 =
                    client
                        .post("/test/schedule-antrag/$antragId/$sitzungId2/1") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                scheduled2.substringBefore(":") shouldBe AntragStatus.TERMINIERT.name

                client.post(
                    "/test/record-attendance/$sitzungId2/$chair/ANWESEND",
                ) { header("X-Member-Id", chair.toString()) }

                val angenommen =
                    client
                        .post("/test/resolve-antrag/$antragId/ANGENOMMEN") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                angenommen.substringBefore(":") shouldBe AntragStatus.BESCHLOSSEN.name
                val secondBeschlussId = angenommen.substringAfter(":")
                (secondBeschlussId != firstBeschlussId) shouldBe true

                val finalAntrag =
                    client.get("/test/get-antrag/$antragId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                finalAntrag shouldBe "${AntragStatus.BESCHLOSSEN.name}:$secondBeschlussId"
            }
        }

        test(
            "scheduleAntrag: rejects wrong starting status, cross-Gremium Sitzung, non-GEPLANT Sitzung, and " +
                "position collision; the widened tagesordnungspunkt.description column holds the full begruendung",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerAntragTestRoutes()
                    }
                }

                val gremiumA =
                    client
                        .post("/test/create-gremium/Vorstand%20Schedule/VORSTAND/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumA)
                val gremiumB =
                    client
                        .post("/test/create-gremium/AK%20Schedule/ARBEITSKREIS/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumB)

                val chairA = createTestMember("antrag-schedule-chairA@example.org")
                val chairB = createTestMember("antrag-schedule-chairB@example.org")
                client.post("/test/add-mitglied/$gremiumA/$chairA/VORSITZ") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumB/$chairB/VORSITZ") { header("X-Member-Id", BOARD_ID) }

                val antragId =
                    client
                        .post(
                            "/test/submit-antrag/$gremiumA?begruendungLen=4000",
                        ) { header("X-Member-Id", chairA.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                val sitzungA1 =
                    client
                        .post("/test/create-sitzung/$gremiumA/2026/8/1/18") { header("X-Member-Id", chairA.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                // Wrong starting status: still EINGEREICHT, not GEPRUEFT/VERTAGT.
                val wrongStatus =
                    client.post("/test/schedule-antrag/$antragId/$sitzungA1/1") { header("X-Member-Id", chairA.toString()) }
                wrongStatus.status shouldBe HttpStatusCode.Conflict

                client.post("/test/review-antrag/$antragId/ANNEHMEN") { header("X-Member-Id", chairA.toString()) }

                // Cross-Gremium: a Sitzung of gremiumB cannot host an Antrag targeting gremiumA.
                val sitzungB =
                    client
                        .post("/test/create-sitzung/$gremiumB/2026/8/1/18") { header("X-Member-Id", chairB.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                val crossGremium =
                    client.post("/test/schedule-antrag/$antragId/$sitzungB/1") { header("X-Member-Id", chairA.toString()) }
                crossGremium.status shouldBe HttpStatusCode.Conflict

                // Non-GEPLANT: mark sitzungA1 DURCHGEFUEHRT, scheduling must be rejected.
                client.post(
                    "/test/update-status/$sitzungA1/${SitzungsStatus.DURCHGEFUEHRT}",
                ) { header("X-Member-Id", chairA.toString()) }
                val nonGeplant =
                    client.post("/test/schedule-antrag/$antragId/$sitzungA1/1") { header("X-Member-Id", chairA.toString()) }
                nonGeplant.status shouldBe HttpStatusCode.Conflict

                // Fresh GEPLANT Sitzung under the correct Gremium: position collision, then success.
                val sitzungA2 =
                    client
                        .post("/test/create-sitzung/$gremiumA/2026/9/1/18") { header("X-Member-Id", chairA.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/add-top/$sitzungA2/1") { header("X-Member-Id", chairA.toString()) }
                val collision =
                    client.post("/test/schedule-antrag/$antragId/$sitzungA2/1") { header("X-Member-Id", chairA.toString()) }
                collision.status shouldBe HttpStatusCode.Conflict

                val scheduled =
                    client
                        .post("/test/schedule-antrag/$antragId/$sitzungA2/2") { header("X-Member-Id", chairA.toString()) }
                        .bodyAsText()
                scheduled.substringBefore(":") shouldBe AntragStatus.TERMINIERT.name

                val topDescriptionLength =
                    client
                        .get(
                            "/test/top-description-length/$sitzungA2/2",
                        ) { header("X-Member-Id", chairA.toString()) }
                        .bodyAsText()
                topDescriptionLength shouldBe "4000"
            }
        }

        test(
            "withdrawAntrag: submitter can withdraw own EINGEREICHT Antrag; cannot withdraw someone else's or " +
                "their own past EINGEREICHT; leadership can withdraw at any status but not twice",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerAntragTestRoutes()
                    }
                }

                val gremiumId =
                    client
                        .post("/test/create-gremium/Vorstand%20Withdraw/VORSTAND/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumId)

                val chair = createTestMember("antrag-withdraw-chair@example.org")
                val submitter = createTestMember("antrag-withdraw-submitter@example.org")
                val other = createTestMember("antrag-withdraw-other@example.org")
                client.post("/test/add-mitglied/$gremiumId/$chair/VORSITZ") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$submitter/MITGLIED") { header("X-Member-Id", BOARD_ID) }

                val antragId1 =
                    client
                        .post("/test/submit-antrag/$gremiumId") { header("X-Member-Id", submitter.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                val otherForbidden =
                    client.post("/test/withdraw-antrag/$antragId1") { header("X-Member-Id", other.toString()) }
                otherForbidden.status shouldBe HttpStatusCode.Forbidden

                val withdrawn =
                    client.post("/test/withdraw-antrag/$antragId1") { header("X-Member-Id", submitter.toString()) }.bodyAsText()
                withdrawn shouldBe AntragStatus.ZURUECKGEZOGEN.name

                // Submitter cannot withdraw their own Antrag once it is past EINGEREICHT.
                val antragId2 =
                    client
                        .post("/test/submit-antrag/$gremiumId") { header("X-Member-Id", submitter.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/review-antrag/$antragId2/ANNEHMEN") { header("X-Member-Id", chair.toString()) }
                val submitterLateWithdraw =
                    client.post("/test/withdraw-antrag/$antragId2") { header("X-Member-Id", submitter.toString()) }
                submitterLateWithdraw.status shouldBe HttpStatusCode.Forbidden

                // Leadership can withdraw at any status -- and a second withdraw is a Conflict.
                val chairWithdraw =
                    client.post("/test/withdraw-antrag/$antragId2") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                chairWithdraw shouldBe AntragStatus.ZURUECKGEZOGEN.name
                val chairDoubleWithdraw =
                    client.post("/test/withdraw-antrag/$antragId2") { header("X-Member-Id", chair.toString()) }
                chairDoubleWithdraw.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "Mitgliederversammlung quorum eligibility counts only MemberStatus.AKTIV members directly from " +
                "MemberTable, unaffected by GremiumMitgliedschaftTable rows",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing { registerGovernanceTestRoutes() }
                }

                val mvGremiumId =
                    client
                        .post("/test/create-gremium/MV%20Quorum/MITGLIEDERVERSAMMLUNG/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(mvGremiumId)

                val baselineSitzungId =
                    client
                        .post("/test/create-sitzung/$mvGremiumId/2026/10/1/18") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .substringBefore(":")
                val baselineEligible =
                    client
                        .get("/test/check-quorum/$baselineSitzungId") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .substringBefore(":")
                        .toInt()

                createTestMember("mv-quorum-aktiv-1@example.org")
                createTestMember("mv-quorum-aktiv-2@example.org")
                createTestMember("mv-quorum-antragstatus@example.org", MemberStatus.ANTRAG)
                // Deliberately never added to GremiumMitgliedschaftTable for mvGremiumId.

                val afterSitzungId =
                    client
                        .post("/test/create-sitzung/$mvGremiumId/2026/10/2/18") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .substringBefore(":")
                val afterEligible =
                    client
                        .get("/test/check-quorum/$afterSitzungId") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                        .substringBefore(":")
                        .toInt()

                (afterEligible - baselineEligible) shouldBe 2
            }
        }

        test(
            "DSGVO: GovernancePersonalData export/erase covers antrag rows for both submitter and reviewer, " +
                "review_note retained verbatim (retain-with-reason, no field nulled)",
        ) {
            val gremiumId = Uuid.random()
            val antragId = Uuid.random()
            val submitter = createTestMember("dsgvo-antrag-submitter@example.org")
            val reviewer = createTestMember("dsgvo-antrag-reviewer@example.org")
            transaction {
                GremiumTable.insert {
                    it[id] = gremiumId
                    it[name] = "DSGVO-Antrag-Testgremium"
                    it[type] = GremiumType.SONSTIGES
                    it[description] = "Nur fuer DSGVO-Antrag-Test"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                AntragTable.insert {
                    it[id] = antragId
                    it[targetGremiumId] = gremiumId
                    it[title] = "DSGVO-Testantrag"
                    it[begruendung] = "Testbegruendung"
                    it[text] = "Antragstext"
                    it[submitterMemberId] = submitter
                    it[status] = AntragStatus.GEPRUEFT
                    it[submittedAt] = LocalDateTime(2026, 1, 2, 0, 0)
                    it[reviewedBy] = reviewer
                    it[reviewedAt] = LocalDateTime(2026, 1, 3, 0, 0)
                    it[reviewNote] = "Vertrauliche Pruefungsnotiz"
                    it[sitzungId] = null
                    it[tagesordnungspunktId] = null
                    it[beschlussId] = null
                    it[withdrawnAt] = null
                }
            }
            createdGremiumIds += gremiumId

            val exportSubmitter = transaction { GovernancePersonalData.export(submitter) }.toString()
            exportSubmitter shouldContain "DSGVO-Testantrag"

            val exportReviewer = transaction { GovernancePersonalData.export(reviewer) }.toString()
            exportReviewer shouldContain "DSGVO-Testantrag"

            val outcomes = transaction { GovernancePersonalData.erase(submitter, ErasureMode.ANONYMIZE) }
            val antragOutcome = outcomes.single { it.table == "antrag" }
            antragOutcome.rowsRetained shouldBe 1

            transaction {
                val note = AntragTable.selectAll().where { AntragTable.id eq antragId }.single()[AntragTable.reviewNote]
                note shouldBe "Vertrauliche Pruefungsnotiz"
            }
        }

        test(
            "Meritokratische Abstimmung happy path: open -> cast contested JA/NEIN baskets -> close computes " +
                "the Vickrey settlement exactly, creates a MERITOKRATISCH Beschluss linked to the Abstimmung, " +
                "and transitions the Antrag to BESCHLOSSEN",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerAntragTestRoutes()
                        registerAbstimmungTestRoutes()
                    }
                }

                val gremiumId =
                    client
                        .post("/test/create-gremium/Vorstand%20Abstimmung/VORSTAND/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumId)

                val chair = createTestMember("abst-happy-chair@example.org")
                val m2 = createTestMember("abst-happy-m2@example.org")
                val m3 = createTestMember("abst-happy-m3@example.org")
                val m4 = createTestMember("abst-happy-m4@example.org")
                client.post("/test/add-mitglied/$gremiumId/$chair/VORSITZ") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$m2/MITGLIED") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$m3/MITGLIED") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$m4/MITGLIED") { header("X-Member-Id", BOARD_ID) }

                seedLtrBalance(m2, BigDecimal("100.00"))
                seedLtrBalance(m3, BigDecimal("100.00"))
                seedLtrBalance(m4, BigDecimal("100.00"))

                val (antragId, sitzungId) = client.createTerminierterAntrag(gremiumId, chair, m2, 2026, 11, 1)

                val opened =
                    client.post("/test/open-abstimmung/$antragId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val openedParts = opened.split(":", limit = 3)
                val abstimmungId = openedParts[0]
                openedParts[1] shouldBe AbstimmungStatus.OFFEN.name
                val optionIdByLabel =
                    openedParts[2].split(";").associate { entry ->
                        val (optId, label) = entry.split("=")
                        label to optId
                    }
                val jaOptionId = optionIdByLabel.getValue("JA")
                val neinOptionId = optionIdByLabel.getValue("NEIN")

                // Contested: JA basket = 60 (m2) + 30 (m3) = 90, NEIN basket = 50 (m4).
                client.post("/test/cast-stimme/$abstimmungId/$jaOptionId/60.00") { header("X-Member-Id", m2.toString()) }
                client.post("/test/cast-stimme/$abstimmungId/$jaOptionId/30.00") { header("X-Member-Id", m3.toString()) }
                client.post("/test/cast-stimme/$abstimmungId/$neinOptionId/50.00") { header("X-Member-Id", m4.toString()) }

                val closed =
                    client
                        .post("/test/close-abstimmung/$abstimmungId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val closedParts = closed.split(":")
                closedParts[0] shouldBe AbstimmungStatus.GESCHLOSSEN.name
                closedParts[1] shouldBe jaOptionId
                // secondPrice = the losing NEIN basket's total = 50.00 (winners collectively pay this much).
                closedParts[2] shouldBe "50.00"
                val beschlussId = closedParts[3]
                beschlussId.isBlank() shouldBe false

                // Vickrey proportional split of 50.00 between m2 (60/90 share) and m3 (30/90 share), largest-
                // remainder rounded to the cent: m2 = 33.33, m3 = 16.67, sum = 50.00 exactly. m4 (loser) = 0.00.
                val stimmenStr =
                    client.get("/test/list-stimmen/$abstimmungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val settledByMember =
                    stimmenStr.split(";").associate { entry ->
                        val parts = entry.split(":")
                        parts[0] to parts[2]
                    }
                settledByMember.getValue(m2.toString()) shouldBe "33.33"
                settledByMember.getValue(m3.toString()) shouldBe "16.67"
                settledByMember.getValue(m4.toString()) shouldBe "0.00"

                val beschlussInfo =
                    client
                        .get("/test/beschluss-for-sitzung/$sitzungId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val beschlussParts = beschlussInfo.split(":")
                beschlussParts[0] shouldBe ResolutionMode.MERITOKRATISCH.name
                beschlussParts[1] shouldBe abstimmungId
                beschlussParts[2] shouldBe BeschlussStatus.ANGENOMMEN.name

                val antragInfo =
                    client.get("/test/get-antrag/$antragId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                antragInfo shouldBe "${AntragStatus.BESCHLOSSEN.name}:$beschlussId"
            }
        }

        test(
            "Meritokratische Abstimmung authz: non-leadership cannot open/close; only eligible (Gremium-member) " +
                "callers can castStimme",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerAntragTestRoutes()
                        registerAbstimmungTestRoutes()
                    }
                }

                val gremiumId =
                    client
                        .post("/test/create-gremium/Vorstand%20Abst%20Authz/VORSTAND/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumId)

                val chair = createTestMember("abst-authz-chair@example.org")
                val member = createTestMember("abst-authz-member@example.org")
                val outsider = createTestMember("abst-authz-outsider@example.org")
                client.post("/test/add-mitglied/$gremiumId/$chair/VORSITZ") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$member/MITGLIED") { header("X-Member-Id", BOARD_ID) }
                // outsider is deliberately never added to the Gremium.

                seedLtrBalance(member, BigDecimal("50.00"))
                seedLtrBalance(outsider, BigDecimal("50.00"))

                val (antragId, _) = client.createTerminierterAntrag(gremiumId, chair, member, 2026, 11, 2)

                val nonLeadershipOpen =
                    client.post("/test/open-abstimmung/$antragId") { header("X-Member-Id", member.toString()) }
                nonLeadershipOpen.status shouldBe HttpStatusCode.Forbidden

                val opened =
                    client.post("/test/open-abstimmung/$antragId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val abstimmungId = opened.substringBefore(":")
                val jaOptionId =
                    opened
                        .split(":", limit = 3)[2]
                        .split(";")
                        .first { it.endsWith("=JA") }
                        .substringBefore("=")

                val outsiderCast =
                    client.post("/test/cast-stimme/$abstimmungId/$jaOptionId/10.00") { header("X-Member-Id", outsider.toString()) }
                outsiderCast.status shouldBe HttpStatusCode.Forbidden

                val memberCast =
                    client.post("/test/cast-stimme/$abstimmungId/$jaOptionId/10.00") { header("X-Member-Id", member.toString()) }
                memberCast.status shouldBe HttpStatusCode.OK

                val nonLeadershipClose =
                    client.post("/test/close-abstimmung/$abstimmungId") { header("X-Member-Id", member.toString()) }
                nonLeadershipClose.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "Meritokratische Abstimmung state guards: openAbstimmung requires TERMINIERT and rejects a second " +
                "Abstimmung on the same Antrag; castStimme/closeAbstimmung reject once GESCHLOSSEN",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerAntragTestRoutes()
                        registerAbstimmungTestRoutes()
                    }
                }

                val gremiumId =
                    client
                        .post("/test/create-gremium/Vorstand%20Abst%20Guards/VORSTAND/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumId)

                val chair = createTestMember("abst-guards-chair@example.org")
                val member = createTestMember("abst-guards-member@example.org")
                client.post("/test/add-mitglied/$gremiumId/$chair/VORSITZ") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$member/MITGLIED") { header("X-Member-Id", BOARD_ID) }
                seedLtrBalance(member, BigDecimal("50.00"))

                val antragId =
                    client
                        .post("/test/submit-antrag/$gremiumId") { header("X-Member-Id", member.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/review-antrag/$antragId/ANNEHMEN") { header("X-Member-Id", chair.toString()) }

                // Still GEPRUEFT, not TERMINIERT yet: openAbstimmung must reject.
                val wrongStatus = client.post("/test/open-abstimmung/$antragId") { header("X-Member-Id", chair.toString()) }
                wrongStatus.status shouldBe HttpStatusCode.Conflict

                val sitzungId =
                    client
                        .post("/test/create-sitzung/$gremiumId/2026/11/3/18") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/schedule-antrag/$antragId/$sitzungId/1") { header("X-Member-Id", chair.toString()) }

                val opened =
                    client.post("/test/open-abstimmung/$antragId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val abstimmungId = opened.substringBefore(":")
                val jaOptionId =
                    opened
                        .split(":", limit = 3)[2]
                        .split(";")
                        .first { it.endsWith("=JA") }
                        .substringBefore("=")

                val duplicateOpen = client.post("/test/open-abstimmung/$antragId") { header("X-Member-Id", chair.toString()) }
                duplicateOpen.status shouldBe HttpStatusCode.Conflict

                client.post("/test/cast-stimme/$abstimmungId/$jaOptionId/5.00") { header("X-Member-Id", member.toString()) }
                client.post("/test/close-abstimmung/$abstimmungId") { header("X-Member-Id", chair.toString()) }

                val castAfterClose =
                    client.post("/test/cast-stimme/$abstimmungId/$jaOptionId/5.00") { header("X-Member-Id", member.toString()) }
                castAfterClose.status shouldBe HttpStatusCode.Conflict

                val doubleClose = client.post("/test/close-abstimmung/$abstimmungId") { header("X-Member-Id", chair.toString()) }
                doubleClose.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "Meritokratische Abstimmung validation: stake below the 0.01 LTR floor and stake exceeding the " +
                "member's free LTR balance are both rejected server-side, never trusting the client amount",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerAntragTestRoutes()
                        registerAbstimmungTestRoutes()
                    }
                }

                val gremiumId =
                    client
                        .post("/test/create-gremium/Vorstand%20Abst%20Val/VORSTAND/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumId)

                val chair = createTestMember("abst-val-chair@example.org")
                val member = createTestMember("abst-val-member@example.org")
                client.post("/test/add-mitglied/$gremiumId/$chair/VORSITZ") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$member/MITGLIED") { header("X-Member-Id", BOARD_ID) }
                seedLtrBalance(member, BigDecimal("10.00"))

                val (antragId, _) = client.createTerminierterAntrag(gremiumId, chair, member, 2026, 11, 4)
                val opened =
                    client.post("/test/open-abstimmung/$antragId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val abstimmungId = opened.substringBefore(":")
                val jaOptionId =
                    opened
                        .split(":", limit = 3)[2]
                        .split(";")
                        .first { it.endsWith("=JA") }
                        .substringBefore("=")

                val belowFloor =
                    client.post("/test/cast-stimme/$abstimmungId/$jaOptionId/0.00") { header("X-Member-Id", member.toString()) }
                belowFloor.status shouldBe HttpStatusCode.Conflict

                val exceedsBalance =
                    client.post("/test/cast-stimme/$abstimmungId/$jaOptionId/15.00") { header("X-Member-Id", member.toString()) }
                exceedsBalance.status shouldBe HttpStatusCode.Conflict

                val valid =
                    client.post("/test/cast-stimme/$abstimmungId/$jaOptionId/5.00") { header("X-Member-Id", member.toString()) }
                valid.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "Meritokratische Abstimmung: exact tie between the top two baskets closes with no winner, zero " +
                "settlement for everyone, and resolves the Antrag to VERTAGT",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installGovernanceExceptionHandlers() }
                    routing {
                        registerGovernanceTestRoutes()
                        registerAntragTestRoutes()
                        registerAbstimmungTestRoutes()
                    }
                }

                val gremiumId =
                    client
                        .post("/test/create-gremium/Vorstand%20Abst%20Tie/VORSTAND/50") { header("X-Member-Id", BOARD_ID) }
                        .bodyAsText()
                createdGremiumIds += Uuid.parse(gremiumId)

                val chair = createTestMember("abst-tie-chair@example.org")
                val m2 = createTestMember("abst-tie-m2@example.org")
                val m3 = createTestMember("abst-tie-m3@example.org")
                client.post("/test/add-mitglied/$gremiumId/$chair/VORSITZ") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$m2/MITGLIED") { header("X-Member-Id", BOARD_ID) }
                client.post("/test/add-mitglied/$gremiumId/$m3/MITGLIED") { header("X-Member-Id", BOARD_ID) }
                seedLtrBalance(m2, BigDecimal("50.00"))
                seedLtrBalance(m3, BigDecimal("50.00"))

                val (antragId, sitzungId) = client.createTerminierterAntrag(gremiumId, chair, m2, 2026, 11, 5)
                val opened =
                    client.post("/test/open-abstimmung/$antragId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val abstimmungId = opened.substringBefore(":")
                val optionIdByLabel =
                    opened.split(":", limit = 3)[2].split(";").associate { entry ->
                        val (optId, label) = entry.split("=")
                        label to optId
                    }
                val jaOptionId = optionIdByLabel.getValue("JA")
                val neinOptionId = optionIdByLabel.getValue("NEIN")

                client.post("/test/cast-stimme/$abstimmungId/$jaOptionId/25.00") { header("X-Member-Id", m2.toString()) }
                client.post("/test/cast-stimme/$abstimmungId/$neinOptionId/25.00") { header("X-Member-Id", m3.toString()) }

                val closed =
                    client
                        .post("/test/close-abstimmung/$abstimmungId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val closedParts = closed.split(":")
                closedParts[0] shouldBe AbstimmungStatus.GESCHLOSSEN.name
                closedParts[1] shouldBe "" // no winner
                closedParts[2] shouldBe "0.00" // no settlement

                val stimmenStr =
                    client.get("/test/list-stimmen/$abstimmungId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                stimmenStr.split(";").forEach { entry ->
                    entry.split(":")[2] shouldBe "0.00"
                }

                val beschlussInfo =
                    client
                        .get("/test/beschluss-for-sitzung/$sitzungId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                beschlussInfo.split(":")[2] shouldBe BeschlussStatus.VERTAGT.name

                val antragInfo =
                    client.get("/test/get-antrag/$antragId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                antragInfo.substringBefore(":") shouldBe AntragStatus.VERTAGT.name
            }
        }

        test(
            "DSGVO: GovernancePersonalData export/erase covers abstimmung (opened_by) and abstimmung_stimme " +
                "(member_id) rows; staked LTR retained verbatim (retain-with-reason, property record)",
        ) {
            val gremiumId = Uuid.random()
            val sitzungId = Uuid.random()
            val antragId = Uuid.random()
            val abstimmungId = Uuid.random()
            val optionId = Uuid.random()
            val opener = createTestMember("dsgvo-abst-opener@example.org")
            val voter = createTestMember("dsgvo-abst-voter@example.org")
            transaction {
                GremiumTable.insert {
                    it[id] = gremiumId
                    it[name] = "DSGVO-Abstimmung-Testgremium"
                    it[type] = GremiumType.SONSTIGES
                    it[description] = "Nur fuer DSGVO-Abstimmung-Test"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                SitzungTable.insert {
                    it[id] = sitzungId
                    it[SitzungTable.gremiumId] = gremiumId
                    it[title] = "DSGVO-Abstimmung-Testsitzung"
                    it[scheduledAt] = LocalDateTime(2026, 1, 4, 18, 0)
                    it[location] = null
                    it[format] = SitzungsFormat.ONLINE
                    it[status] = SitzungsStatus.GEPLANT
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                AntragTable.insert {
                    it[id] = antragId
                    it[targetGremiumId] = gremiumId
                    it[title] = "DSGVO-Testabstimmung"
                    it[begruendung] = "Testbegruendung"
                    it[text] = "Antragstext"
                    it[submitterMemberId] = opener
                    it[status] = AntragStatus.TERMINIERT
                    it[submittedAt] = LocalDateTime(2026, 1, 2, 0, 0)
                    it[reviewedBy] = null
                    it[reviewedAt] = null
                    it[reviewNote] = null
                    it[AntragTable.sitzungId] = sitzungId
                    it[tagesordnungspunktId] = null
                    it[beschlussId] = null
                    it[withdrawnAt] = null
                }
                AbstimmungTable.insert {
                    it[id] = abstimmungId
                    it[AbstimmungTable.antragId] = antragId
                    it[AbstimmungTable.sitzungId] = sitzungId
                    it[title] = "DSGVO-Testabstimmung"
                    it[status] = AbstimmungStatus.OFFEN
                    it[openedBy] = opener
                    it[openedAt] = LocalDateTime(2026, 1, 4, 0, 0)
                    it[closedAt] = null
                    it[winnerOptionId] = null
                    it[secondPriceLtr] = null
                    it[beschlussId] = null
                }
                AbstimmungOptionTable.insert {
                    it[id] = optionId
                    it[AbstimmungOptionTable.abstimmungId] = abstimmungId
                    it[label] = "JA"
                    it[position] = 0
                }
                AbstimmungStimmeTable.insert {
                    it[id] = Uuid.random()
                    it[AbstimmungStimmeTable.abstimmungId] = abstimmungId
                    it[AbstimmungStimmeTable.optionId] = optionId
                    it[AbstimmungStimmeTable.memberId] = voter
                    it[stakeLtr] = BigDecimal("12.34")
                    it[settledLtr] = null
                    it[castAt] = LocalDateTime(2026, 1, 5, 0, 0)
                }
            }
            createdGremiumIds += gremiumId

            val exportOpener = transaction { GovernancePersonalData.export(opener) }.toString()
            exportOpener shouldContain "DSGVO-Testabstimmung"

            val exportVoter = transaction { GovernancePersonalData.export(voter) }.toString()
            exportVoter shouldContain "12.34"

            val outcomes = transaction { GovernancePersonalData.erase(voter, ErasureMode.ANONYMIZE) }
            val stimmeOutcome = outcomes.single { it.table == "abstimmung_stimme" }
            stimmeOutcome.rowsRetained shouldBe 1

            transaction {
                val stake =
                    AbstimmungStimmeTable
                        .selectAll()
                        .where { AbstimmungStimmeTable.memberId eq voter }
                        .single()[AbstimmungStimmeTable.stakeLtr]
                stake shouldBe BigDecimal("12.34")
            }
        }
    })

/**
 * Shared throwaway routes for the multi-step tests (lifecycle, quorum edge cases) — pulled out
 * once because those tests all need the same nine service call sites. The single-route tests
 * above (Gremium creation authz, leadership authz) keep their own smaller inline route sets since
 * they don't need every route.
 */
private fun Route.registerGovernanceTestRoutes() {
    post("/test/create-gremium/{name}/{type}/{quorumPercent}") {
        val service = GovernanceService(call)
        val p = call.parameters
        val g =
            service.createGremium(
                GremiumInput(
                    name = p["name"]!!,
                    type = GremiumType.valueOf(p["type"]!!),
                    description = "Testgremium",
                    quorumPercent = p["quorumPercent"]!!.toInt(),
                ),
            )
        call.respondText(g.id)
    }
    post("/test/add-mitglied/{gremiumId}/{memberId}/{rolle}") {
        val service = GovernanceService(call)
        val q = call.request.queryParameters
        val since =
            LocalDate(
                q["sinceYear"]?.toInt() ?: 2020,
                q["sinceMonth"]?.toInt() ?: 1,
                q["sinceDay"]?.toInt() ?: 1,
            )
        val m =
            service.addGremiumMitglied(
                call.parameters["gremiumId"]!!,
                GremiumMitgliedschaftInput(
                    memberId = call.parameters["memberId"]!!,
                    rolle = GremiumRolle.valueOf(call.parameters["rolle"]!!),
                    since = since,
                ),
            )
        call.respondText(m.id)
    }
    post("/test/create-sitzung/{gremiumId}/{year}/{month}/{day}/{hour}") {
        val service = GovernanceService(call)
        val p = call.parameters
        val scheduledAt = LocalDateTime(p["year"]!!.toInt(), p["month"]!!.toInt(), p["day"]!!.toInt(), p["hour"]!!.toInt(), 0)
        val s =
            service.createSitzung(
                SitzungInput(
                    gremiumId = p["gremiumId"]!!,
                    title = "Testsitzung",
                    scheduledAt = scheduledAt,
                    location = "Vereinsheim",
                    format = SitzungsFormat.PRAESENZ,
                ),
            )
        call.respondText("${s.id}:${s.status}")
    }
    post("/test/add-top/{sitzungId}/{position}") {
        val service = GovernanceService(call)
        val top =
            service.addTagesordnungspunkt(
                call.parameters["sitzungId"]!!,
                TagesordnungspunktInput(
                    position = call.parameters["position"]!!.toInt(),
                    title = "TOP ${call.parameters["position"]}",
                ),
            )
        call.respondText(top.id)
    }
    post("/test/record-attendance/{sitzungId}/{memberId}/{status}") {
        val service = GovernanceService(call)
        val representedBy = call.request.queryParameters["representedBy"]
        val a =
            service.recordAttendance(
                call.parameters["sitzungId"]!!,
                AnwesenheitInput(
                    memberId = call.parameters["memberId"]!!,
                    status = AnwesenheitStatus.valueOf(call.parameters["status"]!!),
                    representedByMemberId = representedBy,
                ),
            )
        call.respondText(a.id)
    }
    get("/test/check-quorum/{sitzungId}") {
        val service = GovernanceService(call)
        val q = service.checkQuorum(call.parameters["sitzungId"]!!)
        call.respondText("${q.eligibleMemberCount}:${q.presentCount}:${q.requiredCount}:${q.met}")
    }
    post("/test/record-beschluss/{sitzungId}") {
        val service = GovernanceService(call)
        val b =
            service.recordBeschluss(
                call.parameters["sitzungId"]!!,
                BeschlussInput(
                    title = "Testbeschluss",
                    text = "Beschlusstext",
                    votesYes = 3,
                    votesNo = 1,
                    votesAbstain = 0,
                    status = BeschlussStatus.ANGENOMMEN,
                ),
            )
        call.respondText("${b.number}:${b.quorumMet}:${b.status}")
    }
    post("/test/update-status/{sitzungId}/{status}") {
        val service = GovernanceService(call)
        val s = service.updateSitzungStatus(call.parameters["sitzungId"]!!, SitzungsStatus.valueOf(call.parameters["status"]!!))
        call.respondText(s.status.name)
    }
    get("/test/protocol-draft/{sitzungId}") {
        val service = GovernanceService(call)
        val draft = service.generateProtocolDraft(call.parameters["sitzungId"]!!)
        call.respondText("${draft.tagesordnung.size}:${draft.anwesenheit.size}:${draft.beschluesse.size}")
    }
}

/**
 * Shared [StatusPages] wiring for the Antrag (V0.2.2) tests -- registers all four exception types
 * that surface across the Antrag lifecycle (`UnauthenticatedException`/`ForbiddenException`/
 * `NotFoundException`/`ConflictException`), unlike the older single-route tests above which only
 * register the subset they individually need.
 */
private fun StatusPagesConfig.installGovernanceExceptionHandlers() {
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

/** Shared throwaway routes for the Antrag (V0.2.2) lifecycle tests -- mirrors [registerGovernanceTestRoutes]'s style. */
private fun Route.registerAntragTestRoutes() {
    post("/test/submit-antrag/{targetGremiumId}") {
        val service = GovernanceService(call)
        val q = call.request.queryParameters
        val begruendung = q["begruendungLen"]?.toInt()?.let { "A".repeat(it) } ?: (q["begruendung"] ?: "Testbegruendung")
        val a =
            service.submitAntrag(
                AntragInput(
                    targetGremiumId = call.parameters["targetGremiumId"]!!,
                    title = q["title"] ?: "Testantrag",
                    begruendung = begruendung,
                    text = q["text"] ?: "Antragstext",
                ),
            )
        call.respondText("${a.id}:${a.status}")
    }
    get("/test/get-antrag/{id}") {
        val service = GovernanceService(call)
        val a = service.getAntrag(call.parameters["id"]!!)
        call.respondText("${a.status}:${a.beschlussId ?: ""}")
    }
    post("/test/review-antrag/{id}/{decision}") {
        val service = GovernanceService(call)
        val a =
            service.reviewAntrag(
                call.parameters["id"]!!,
                AntragPruefungsEntscheidung.valueOf(call.parameters["decision"]!!),
                call.request.queryParameters["note"],
            )
        call.respondText(a.status.name)
    }
    post("/test/schedule-antrag/{id}/{sitzungId}/{position}") {
        val service = GovernanceService(call)
        val a =
            service.scheduleAntrag(
                call.parameters["id"]!!,
                call.parameters["sitzungId"]!!,
                call.parameters["position"]!!.toInt(),
            )
        call.respondText("${a.status}:${a.tagesordnungspunktId}")
    }
    post("/test/resolve-antrag/{id}/{status}") {
        val service = GovernanceService(call)
        val a =
            service.resolveAntrag(
                call.parameters["id"]!!,
                AntragResolutionInput(
                    votesYes = 3,
                    votesNo = 1,
                    votesAbstain = 0,
                    status = BeschlussStatus.valueOf(call.parameters["status"]!!),
                ),
            )
        call.respondText("${a.status}:${a.beschlussId}")
    }
    post("/test/withdraw-antrag/{id}") {
        val service = GovernanceService(call)
        val a = service.withdrawAntrag(call.parameters["id"]!!)
        call.respondText(a.status.name)
    }
    get("/test/top-description-length/{sitzungId}/{position}") {
        val service = GovernanceService(call)
        val detail = service.getSitzungDetail(call.parameters["sitzungId"]!!)
        val top = detail.tagesordnung.first { it.position == call.parameters["position"]!!.toInt() }
        call.respondText((top.description?.length ?: 0).toString())
    }
}

/**
 * Drives an Antrag from EINGEREICHT to TERMINIERT the same way the "Antrag lifecycle" test above
 * does by hand — pulled into a shared helper for the Meritokratische Abstimmung (V0.2.3) tests,
 * which all need exactly this precondition before `openAbstimmung` becomes reachable. [chairId]
 * both reviews and creates/schedules the Sitzung (leadership actions); [submitterId] only needs
 * to be entitled to submit (any Gremium role, or the chair themself). Requires
 * [registerGovernanceTestRoutes] and [registerAntragTestRoutes] to be installed on the same
 * [Route]. Returns `(antragId, sitzungId)`.
 */
private suspend fun HttpClient.createTerminierterAntrag(
    gremiumId: String,
    chairId: Uuid,
    submitterId: Uuid,
    year: Int,
    month: Int,
    day: Int,
): Pair<String, String> {
    val antragId =
        post("/test/submit-antrag/$gremiumId") { header("X-Member-Id", submitterId.toString()) }
            .bodyAsText()
            .substringBefore(":")
    post("/test/review-antrag/$antragId/ANNEHMEN") { header("X-Member-Id", chairId.toString()) }
    val sitzungId =
        post("/test/create-sitzung/$gremiumId/$year/$month/$day/18") { header("X-Member-Id", chairId.toString()) }
            .bodyAsText()
            .substringBefore(":")
    post("/test/schedule-antrag/$antragId/$sitzungId/1") { header("X-Member-Id", chairId.toString()) }
    return antragId to sitzungId
}

/**
 * Shared throwaway routes for the Meritokratische Abstimmung (V0.2.3) tests. String encodings are
 * kept deliberately simple/parseable (`;`-separated entries, `=`/`:`-separated fields) — this is
 * the same "throwaway route calling the service class directly, no wire format to reverse-
 * engineer" house style [registerGovernanceTestRoutes]/[registerAntragTestRoutes] already use.
 */
private fun Route.registerAbstimmungTestRoutes() {
    post("/test/open-abstimmung/{antragId}") {
        val service = GovernanceService(call)
        val labels = call.request.queryParameters["labels"]?.split(",") ?: listOf("JA", "NEIN")
        val a =
            service.openAbstimmung(
                AbstimmungOpenInput(antragId = call.parameters["antragId"]!!, optionLabels = labels),
            )
        val optionsStr = a.options.joinToString(";") { "${it.id}=${it.label}" }
        call.respondText("${a.id}:${a.status}:$optionsStr")
    }
    get("/test/get-abstimmung/{id}") {
        val service = GovernanceService(call)
        val a = service.getAbstimmung(call.parameters["id"]!!)
        val optionsStr = a.options.joinToString(";") { "${it.id}=${it.label}:${it.basketTotalLtr}" }
        call.respondText("${a.status}:${a.winnerOptionId ?: ""}:${a.secondPriceLtr ?: ""}:${a.beschlussId ?: ""}:$optionsStr")
    }
    post("/test/cast-stimme/{abstimmungId}/{optionId}/{stake}") {
        val service = GovernanceService(call)
        val s =
            service.castStimme(
                StimmeInput(
                    abstimmungId = call.parameters["abstimmungId"]!!,
                    optionId = call.parameters["optionId"]!!,
                    stakeLtr = BigDecimal(call.parameters["stake"]!!),
                ),
            )
        call.respondText("${s.id}:${s.stakeLtr}")
    }
    post("/test/close-abstimmung/{id}") {
        val service = GovernanceService(call)
        val a = service.closeAbstimmung(call.parameters["id"]!!)
        call.respondText("${a.status}:${a.winnerOptionId ?: ""}:${a.secondPriceLtr ?: ""}:${a.beschlussId ?: ""}")
    }
    post("/test/abort-abstimmung/{id}") {
        val service = GovernanceService(call)
        val a = service.abortAbstimmung(call.parameters["id"]!!)
        call.respondText(a.status.name)
    }
    get("/test/list-stimmen/{abstimmungId}") {
        val service = GovernanceService(call)
        val stimmen = service.listStimmen(call.parameters["abstimmungId"]!!)
        call.respondText(stimmen.joinToString(";") { "${it.memberId}:${it.stakeLtr}:${it.settledLtr ?: ""}" })
    }
    get("/test/beschluss-for-sitzung/{sitzungId}") {
        val service = GovernanceService(call)
        val b = service.listBeschluesse(sitzungId = call.parameters["sitzungId"]!!).single()
        call.respondText("${b.resolutionMode}:${b.abstimmungId ?: ""}:${b.status}")
    }
}

/**
 * Hard-deletes every row this Spec created, child-before-parent, so no state leaks into other
 * Spec classes sharing the same H2 in-memory database — same discipline as
 * `DsgvoServiceTest.cleanUpDsgvoTestData`. [AntragTable] is deleted first since it references
 * gremium/member/sitzung/tagesordnungspunkt/beschluss, all of which are deleted further down.
 *
 * Meritokratische Abstimmungen (V0.2.3): [AbstimmungTable]/[BeschlussTable] form a cycle
 * (`beschluss.abstimmung_id` -> `abstimmung.id`, `abstimmung.beschluss_id` -> `beschluss.id`) --
 * both FKs are nulled out before either table's rows are deleted, same reasoning as the compile-
 * time circular column reference in `GovernanceTables.kt` (breaking the cycle explicitly rather
 * than relying on delete order alone). [LtrBalanceTable] rows are matched by `memberIds` only
 * (never scoped by Gremium) since a balance is a property of the member, not of any Gremium.
 */
private fun cleanUpGovernanceTestData(
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

        val abstimmungCondition =
            when {
                sitzungIds.isNotEmpty() && memberIds.isNotEmpty() ->
                    (AbstimmungTable.sitzungId inList sitzungIds) or (AbstimmungTable.openedBy inList memberIds)
                sitzungIds.isNotEmpty() -> AbstimmungTable.sitzungId inList sitzungIds
                memberIds.isNotEmpty() -> AbstimmungTable.openedBy inList memberIds
                else -> null
            }
        val abstimmungIds =
            if (abstimmungCondition != null) {
                AbstimmungTable.selectAll().where { abstimmungCondition }.map { it[AbstimmungTable.id] }
            } else {
                emptyList()
            }
        if (abstimmungIds.isNotEmpty()) {
            AbstimmungTable.update({ AbstimmungTable.id inList abstimmungIds }) { it[beschlussId] = null }
            BeschlussTable.update({ BeschlussTable.abstimmungId inList abstimmungIds }) { it[BeschlussTable.abstimmungId] = null }
            AbstimmungStimmeTable.deleteWhere { AbstimmungStimmeTable.abstimmungId inList abstimmungIds }
            AbstimmungOptionTable.deleteWhere { AbstimmungOptionTable.abstimmungId inList abstimmungIds }
            AbstimmungTable.deleteWhere { AbstimmungTable.id inList abstimmungIds }
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
            AnwesenheitTable.deleteWhere { AnwesenheitTable.sitzungId inList sitzungIds }
            TagesordnungspunktTable.deleteWhere { TagesordnungspunktTable.sitzungId inList sitzungIds }
            SitzungTable.deleteWhere { SitzungTable.id inList sitzungIds }
        }
        if (gremiumIds.isNotEmpty()) {
            GremiumMitgliedschaftTable.deleteWhere { GremiumMitgliedschaftTable.gremiumId inList gremiumIds }
            GremiumTable.deleteWhere { GremiumTable.id inList gremiumIds }
        }
        if (memberIds.isNotEmpty()) {
            LtrBalanceTable.deleteWhere { LtrBalanceTable.memberId inList memberIds }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
