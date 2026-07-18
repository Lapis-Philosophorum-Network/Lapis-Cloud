package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.UnauthenticatedException
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Exercises [AccountingService] end to end -- same "throwaway routes calling the service class
 * directly" house style as [ElectionServiceTest]/[SystemicConsensusServiceTest]. Uses its own
 * freshly created members/accounts throughout (never [DevSeedData]'s shared demo fixtures), for
 * the same order-independence reasoning those files document. [afterSpec] hard-deletes every row
 * this file created.
 */
class AccountingServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpAccountingTestData(createdMemberIds, createdLedgerAccountIds) }

        fun createTestMember(
            email: String,
            role: AccountRole,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Accounting Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createLedgerAccount(
            number: String,
            type: LedgerAccountType,
            accountClass: Int = 0,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Testkonto $number"
                    it[LedgerAccountTable.accountClass] = accountClass
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun postingsParam(postings: List<PostingInput>): String =
            postings.joinToString(",") { "${it.ledgerAccountId}:${it.side}:${it.amount}" }

        fun entryParams(
            date: LocalDate,
            description: String,
            postings: List<PostingInput>,
            voucher: String? = null,
        ): String =
            buildString {
                append("date=$date&description=$description&postings=${postingsParam(postings)}")
                if (voucher != null) append("&voucher=$voucher")
            }

        test("treasurer can create a LedgerAccount; a plain member is forbidden") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-create@example.org", AccountRole.TREASURER)
                val plainMember = createTestMember("acct-plain-create@example.org", AccountRole.MEMBER)

                val created =
                    client
                        .post("/test/create-ledger-account?number=0930&name=Sparkonto&class=0&type=ASSET") {
                            header("X-Member-Id", treasurer.toString())
                        }.bodyAsText()
                created.split(":")[1] shouldBe "0930"
                createdLedgerAccountIds += Uuid.parse(created.split(":")[0])

                val forbidden =
                    client.post("/test/create-ledger-account?number=0931&name=X&class=0&type=ASSET") {
                        header("X-Member-Id", plainMember.toString())
                    }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val unauthenticated = client.post("/test/create-ledger-account?number=0932&name=X&class=0&type=ASSET")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("duplicate accountNumber is rejected with Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-dup@example.org", AccountRole.TREASURER)

                val first =
                    client.post("/test/create-ledger-account?number=0940&name=Erstkonto&class=0&type=ASSET") {
                        header("X-Member-Id", treasurer.toString())
                    }
                first.status shouldBe HttpStatusCode.OK
                createdLedgerAccountIds += Uuid.parse(first.bodyAsText().split(":")[0])

                val duplicate =
                    client.post("/test/create-ledger-account?number=0940&name=Zweitkonto&class=0&type=ASSET") {
                        header("X-Member-Id", treasurer.toString())
                    }
                duplicate.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("postJournalEntry with a balanced entry succeeds and writes POSTED with N postings") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-post@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0921", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("2111", LedgerAccountType.INCOME, accountClass = 2)

                val postings =
                    listOf(
                        PostingInput(kasse.toString(), PostingSide.DEBIT, BigDecimal("50.00")),
                        PostingInput(beitraege.toString(), PostingSide.CREDIT, BigDecimal("50.00")),
                    )
                val response =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 3, 1), "Mitgliedsbeitrag", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText().split(":")
                body[1] shouldBe "POSTED"
                body[2] shouldBe "2"
            }
        }

        test("postJournalEntry with an unbalanced entry is rejected and nothing is persisted") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-unbalanced@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0922", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("2112", LedgerAccountType.INCOME, accountClass = 2)

                val countBefore = transaction { JournalEntryTable.selectAll().count() }

                val postings =
                    listOf(
                        PostingInput(kasse.toString(), PostingSide.DEBIT, BigDecimal("100.00")),
                        PostingInput(beitraege.toString(), PostingSide.CREDIT, BigDecimal("90.00")),
                    )
                val response =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 3, 1), "Unbalanced", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict

                val countAfter = transaction { JournalEntryTable.selectAll().count() }
                countAfter shouldBe countBefore
            }
        }

        test("posting to an inactive or nonexistent LedgerAccount is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-inactive@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0923", LedgerAccountType.ASSET)
                val inactive = createLedgerAccount("0924", LedgerAccountType.ASSET)
                transaction { LedgerAccountTable.update({ LedgerAccountTable.id eq inactive }) { it[active] = false } }

                val postings =
                    listOf(
                        PostingInput(kasse.toString(), PostingSide.DEBIT, BigDecimal("10.00")),
                        PostingInput(inactive.toString(), PostingSide.CREDIT, BigDecimal("10.00")),
                    )
                val response =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 3, 1), "Inaktiv", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                response.status shouldBe HttpStatusCode.Conflict

                val nonexistentPostings =
                    listOf(
                        PostingInput(kasse.toString(), PostingSide.DEBIT, BigDecimal("10.00")),
                        PostingInput(Uuid.random().toString(), PostingSide.CREDIT, BigDecimal("10.00")),
                    )
                val notFoundResponse =
                    client.post("/test/post-entry?${entryParams(LocalDate(2026, 3, 1), "Unbekannt", nonexistentPostings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                notFoundResponse.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("saveDraftEntry allows an unbalanced entry; postDraftEntry rejects until it balances, then succeeds") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-draft@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0925", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("2113", LedgerAccountType.INCOME, accountClass = 2)

                val unbalancedPostings = listOf(PostingInput(kasse.toString(), PostingSide.DEBIT, BigDecimal("75.00")))
                val draftResponse =
                    client.post("/test/save-draft?${entryParams(LocalDate(2026, 4, 1), "Entwurf", unbalancedPostings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                draftResponse.status shouldBe HttpStatusCode.OK
                val draft = draftResponse.bodyAsText().split(":")
                draft[1] shouldBe "DRAFT"
                val entryId = draft[0]

                // Still unbalanced -- postDraftEntry must reject.
                val rejectedPost = client.post("/test/post-draft/$entryId") { header("X-Member-Id", treasurer.toString()) }
                rejectedPost.status shouldBe HttpStatusCode.Conflict

                // Balance it by inserting the missing credit line directly (simulating a later edit).
                transaction {
                    PostingTable.insert {
                        it[id] = Uuid.random()
                        it[journalEntryId] = Uuid.parse(entryId)
                        it[ledgerAccountId] = beitraege
                        it[side] = PostingSide.CREDIT
                        it[amount] = BigDecimal("75.00")
                    }
                }
                val postedResponse = client.post("/test/post-draft/$entryId") { header("X-Member-Id", treasurer.toString()) }
                postedResponse.status shouldBe HttpStatusCode.OK
                postedResponse.bodyAsText().split(":")[1] shouldBe "POSTED"

                // Once POSTED, a further postDraftEntry attempt (immutability) is rejected.
                val alreadyPosted = client.post("/test/post-draft/$entryId") { header("X-Member-Id", treasurer.toString()) }
                alreadyPosted.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("listJournal is chronological and filters by date range and status") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-journal@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0926", LedgerAccountType.ASSET)
                val beitraege = createLedgerAccount("2114", LedgerAccountType.INCOME, accountClass = 2)

                suspend fun postBalanced(date: LocalDate) {
                    val postings =
                        listOf(
                            PostingInput(kasse.toString(), PostingSide.DEBIT, BigDecimal("10.00")),
                            PostingInput(beitraege.toString(), PostingSide.CREDIT, BigDecimal("10.00")),
                        )
                    client.post("/test/post-entry?${entryParams(date, "Buchung-$date", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                }
                postBalanced(LocalDate(2026, 5, 3))
                postBalanced(LocalDate(2026, 5, 1))
                postBalanced(LocalDate(2026, 5, 2))

                val all =
                    client
                        .get("/test/list-journal?from=2026-05-01&to=2026-05-03") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                val dates = all.split(";").filter { it.isNotBlank() }.map { it.split(":")[1] }
                dates shouldBe listOf("2026-05-01", "2026-05-02", "2026-05-03")

                val narrowed =
                    client
                        .get("/test/list-journal?from=2026-05-02&to=2026-05-02") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                narrowed.split(";").filter { it.isNotBlank() }.size shouldBe 1

                val postedOnly =
                    client
                        .get("/test/list-journal?status=POSTED") { header("X-Member-Id", treasurer.toString()) }
                        .bodyAsText()
                postedOnly.split(";").all { it.isBlank() || it.contains("POSTED") } shouldBe true
            }
        }

        test("getGeneralLedgerAccount computes correct running balances for both normal-balance sides") {
            testApplication {
                application {
                    install(StatusPages) { installAccountingExceptionHandlers() }
                    routing { registerAccountingTestRoutes() }
                }
                val treasurer = createTestMember("acct-treasurer-ledger@example.org", AccountRole.TREASURER)
                val kasse = createLedgerAccount("0927", LedgerAccountType.ASSET) // debit-normal
                val beitraege = createLedgerAccount("2115", LedgerAccountType.INCOME, accountClass = 2) // credit-normal

                suspend fun post(
                    date: LocalDate,
                    kasseSide: PostingSide,
                    amount: String,
                ) {
                    val beitraegeSide = if (kasseSide == PostingSide.DEBIT) PostingSide.CREDIT else PostingSide.DEBIT
                    val postings =
                        listOf(
                            PostingInput(kasse.toString(), kasseSide, BigDecimal(amount)),
                            PostingInput(beitraege.toString(), beitraegeSide, BigDecimal(amount)),
                        )
                    client.post("/test/post-entry?${entryParams(date, "GL-$date", postings)}") {
                        header("X-Member-Id", treasurer.toString())
                    }
                }
                post(LocalDate(2026, 6, 1), PostingSide.DEBIT, "100.00")
                post(LocalDate(2026, 6, 2), PostingSide.DEBIT, "20.00")

                val kasseLedger =
                    client.get("/test/general-ledger/$kasse") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                // ASSET is debit-normal -- two DEBIT postings of 100 and 20 both increase the balance.
                kasseLedger.split(":")[2] shouldBe "120.00"

                val beitraegeLedger =
                    client.get("/test/general-ledger/$beitraege") { header("X-Member-Id", treasurer.toString()) }.bodyAsText()
                // INCOME is credit-normal -- two CREDIT postings of 100 and 20 both increase the balance.
                beitraegeLedger.split(":")[2] shouldBe "120.00"
            }
        }
    })

private fun StatusPagesConfig.installAccountingExceptionHandlers() {
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

/** Shared throwaway routes for [AccountingServiceTest] -- string encodings kept deliberately simple/parseable. */
private fun Route.registerAccountingTestRoutes() {
    post("/test/create-ledger-account") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val dto =
            service.createLedgerAccount(
                LedgerAccountInput(
                    accountNumber = q["number"]!!,
                    name = q["name"]!!,
                    accountClass = q["class"]!!.toInt(),
                    type = LedgerAccountType.valueOf(q["type"]!!),
                ),
            )
        call.respondText("${dto.id}:${dto.accountNumber}:${dto.type}:${dto.active}")
    }
    get("/test/list-ledger-accounts") {
        val service = AccountingService(call)
        val activeOnly = call.request.queryParameters["activeOnly"]?.toBoolean() ?: true
        val list = service.listLedgerAccounts(activeOnly)
        call.respondText(list.joinToString(";") { "${it.id}:${it.accountNumber}:${it.active}" })
    }
    post("/test/save-draft") {
        val service = AccountingService(call)
        val dto = service.saveDraftEntry(readJournalEntryInput(call))
        call.respondText("${dto.id}:${dto.status}:${dto.postings.size}")
    }
    post("/test/post-entry") {
        val service = AccountingService(call)
        val dto = service.postJournalEntry(readJournalEntryInput(call))
        call.respondText("${dto.id}:${dto.status}:${dto.postings.size}")
    }
    post("/test/post-draft/{id}") {
        val service = AccountingService(call)
        val dto = service.postDraftEntry(call.parameters["id"]!!)
        call.respondText("${dto.id}:${dto.status}:${dto.postings.size}")
    }
    get("/test/get-entry/{id}") {
        val service = AccountingService(call)
        val dto = service.getJournalEntry(call.parameters["id"]!!)
        call.respondText("${dto.id}:${dto.status}:${dto.postings.size}")
    }
    get("/test/list-journal") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val list =
            service.listJournal(
                from = q["from"]?.let { LocalDate.parse(it) },
                to = q["to"]?.let { LocalDate.parse(it) },
                status = q["status"]?.let { JournalEntryStatus.valueOf(it) },
            )
        call.respondText(list.joinToString(";") { "${it.id}:${it.entryDate}:${it.status}" })
    }
    get("/test/general-ledger/{ledgerAccountId}") {
        val service = AccountingService(call)
        val dto = service.getGeneralLedgerAccount(call.parameters["ledgerAccountId"]!!)
        call.respondText("${dto.ledgerAccountId}:${dto.openingBalance}:${dto.closingBalance}:${dto.lines.size}")
    }
}

private suspend fun readJournalEntryInput(call: ApplicationCall): JournalEntryInput {
    val q = call.request.queryParameters
    val postings =
        (q["postings"] ?: "")
            .split(",")
            .filter { it.isNotBlank() }
            .map { entry ->
                val parts = entry.split(":")
                PostingInput(ledgerAccountId = parts[0], side = PostingSide.valueOf(parts[1]), amount = BigDecimal(parts[2]))
            }
    return JournalEntryInput(
        entryDate = LocalDate.parse(q["date"]!!),
        description = q["description"]!!,
        voucherReference = q["voucher"],
        postings = postings,
    )
}

/**
 * Hard-deletes every row this Spec created, child-before-parent -- same discipline as
 * [cleanUpElectionTestData]/[cleanUpSystemicConsensusTestData].
 */
private fun cleanUpAccountingTestData(
    memberIds: List<Uuid>,
    ledgerAccountIds: List<Uuid>,
) {
    if (memberIds.isEmpty() && ledgerAccountIds.isEmpty()) return
    transaction {
        val journalEntryIds =
            if (memberIds.isNotEmpty()) {
                JournalEntryTable.selectAll().where { JournalEntryTable.createdBy inList memberIds }.map { it[JournalEntryTable.id] }
            } else {
                emptyList()
            }
        if (journalEntryIds.isNotEmpty()) {
            PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
        }
        if (ledgerAccountIds.isNotEmpty()) {
            PostingTable.deleteWhere { PostingTable.ledgerAccountId inList ledgerAccountIds }
        }
        if (journalEntryIds.isNotEmpty()) {
            JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
        }
        if (ledgerAccountIds.isNotEmpty()) {
            LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList ledgerAccountIds }
        }
        if (memberIds.isNotEmpty()) {
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
