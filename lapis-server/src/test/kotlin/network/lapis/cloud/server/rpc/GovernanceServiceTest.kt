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
import network.lapis.cloud.server.db.tables.AccountTable
import network.lapis.cloud.server.db.tables.AnwesenheitTable
import network.lapis.cloud.server.db.tables.BeschlussTable
import network.lapis.cloud.server.db.tables.GremiumMitgliedschaftTable
import network.lapis.cloud.server.db.tables.GremiumTable
import network.lapis.cloud.server.db.tables.MemberTable
import network.lapis.cloud.server.db.tables.SitzungTable
import network.lapis.cloud.server.db.tables.TagesordnungspunktTable
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.UnauthenticatedException
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AnwesenheitInput
import network.lapis.cloud.shared.domain.AnwesenheitStatus
import network.lapis.cloud.shared.domain.BeschlussInput
import network.lapis.cloud.shared.domain.BeschlussStatus
import network.lapis.cloud.shared.domain.GremiumInput
import network.lapis.cloud.shared.domain.GremiumMitgliedschaftInput
import network.lapis.cloud.shared.domain.GremiumRolle
import network.lapis.cloud.shared.domain.GremiumType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SitzungInput
import network.lapis.cloud.shared.domain.SitzungsFormat
import network.lapis.cloud.shared.domain.SitzungsStatus
import network.lapis.cloud.shared.domain.TagesordnungspunktInput
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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

        fun createTestMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Governance Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
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
 * Hard-deletes every row this Spec created, child-before-parent, so no state leaks into other
 * Spec classes sharing the same H2 in-memory database — same discipline as
 * `DsgvoServiceTest.cleanUpDsgvoTestData`.
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
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
