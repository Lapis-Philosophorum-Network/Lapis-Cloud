package network.lapis.cloud.server.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val STRONG_PASSWORD = "a-perfectly-strong-bootstrap-password"

/** Exercises [AdminBootstrap] end to end against a real (H2) DB -- no member ever created via DevSeedData needed. */
class AdminBootstrapTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec { cleanUpAdminBootstrapTestData(createdMemberIds) }

        fun createTestMember(
            email: String,
            passwordHash: String? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Bootstrap Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.ADMIN
                    it[AccountTable.passwordHash] = passwordHash
                }
            }
            createdMemberIds += id
            return id
        }

        fun storedPasswordHashOf(memberId: Uuid): String? =
            transaction {
                AccountTable.selectAll().where { AccountTable.memberId eq memberId }.single()[AccountTable.passwordHash]
            }

        test("setInitialAdminPassword: happy path sets the hash for an existing, password-less account") {
            val email = "bootstrap-happy@example.org"
            val memberId = createTestMember(email)

            val result = AdminBootstrap.setInitialAdminPassword(email, STRONG_PASSWORD)

            result shouldBe AdminBootstrap.BootstrapResult.Success(email = email, displayName = "Bootstrap Testmitglied")
            PasswordHasher.verify(STRONG_PASSWORD, storedPasswordHashOf(memberId)) shouldBe true
        }

        test("setInitialAdminPassword: email lookup is case-insensitive, mirroring the login endpoint") {
            val email = "bootstrap-case@example.org"
            val memberId = createTestMember(email)

            val result = AdminBootstrap.setInitialAdminPassword("Bootstrap-Case@Example.ORG", STRONG_PASSWORD)

            result shouldBe AdminBootstrap.BootstrapResult.Success(email = email, displayName = "Bootstrap Testmitglied")
            PasswordHasher.verify(STRONG_PASSWORD, storedPasswordHashOf(memberId)) shouldBe true
        }

        test("setInitialAdminPassword: unknown email is reported, never throws / never touches the DB") {
            val result = AdminBootstrap.setInitialAdminPassword("no-such-bootstrap-account@example.org", STRONG_PASSWORD)
            result shouldBe AdminBootstrap.BootstrapResult.AccountNotFound("no-such-bootstrap-account@example.org")
        }

        test("setInitialAdminPassword: refuses to overwrite an account that already has a password, without force") {
            val email = "bootstrap-already-set@example.org"
            val memberId = createTestMember(email, passwordHash = PasswordHasher.hash("pre-existing-password"))

            val result = AdminBootstrap.setInitialAdminPassword(email, STRONG_PASSWORD)

            result shouldBe AdminBootstrap.BootstrapResult.AlreadyHasPassword(email)
            PasswordHasher.verify("pre-existing-password", storedPasswordHashOf(memberId)) shouldBe true
            PasswordHasher.verify(STRONG_PASSWORD, storedPasswordHashOf(memberId)) shouldBe false
        }

        test("setInitialAdminPassword: force=true deliberately overwrites an already-set password") {
            val email = "bootstrap-force@example.org"
            val memberId = createTestMember(email, passwordHash = PasswordHasher.hash("pre-existing-password"))

            val result = AdminBootstrap.setInitialAdminPassword(email, STRONG_PASSWORD, force = true)

            result shouldBe AdminBootstrap.BootstrapResult.Success(email = email, displayName = "Bootstrap Testmitglied")
            PasswordHasher.verify(STRONG_PASSWORD, storedPasswordHashOf(memberId)) shouldBe true
        }

        test("setInitialAdminPassword: a weak password is rejected, account left untouched") {
            val email = "bootstrap-weak@example.org"
            val memberId = createTestMember(email)

            val result = AdminBootstrap.setInitialAdminPassword(email, "short")

            (result is AdminBootstrap.BootstrapResult.WeakPassword) shouldBe true
            storedPasswordHashOf(memberId) shouldBe null
        }
    })

private fun cleanUpAdminBootstrapTestData(memberIds: List<Uuid>) {
    if (memberIds.isEmpty()) return
    transaction {
        AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
        MemberTable.deleteWhere { MemberTable.id inList memberIds }
    }
}
