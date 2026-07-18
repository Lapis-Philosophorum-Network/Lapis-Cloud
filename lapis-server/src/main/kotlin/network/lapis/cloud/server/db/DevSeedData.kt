package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Foundation stub (see CLAUDE.md "Vorab-Befund"): there is no member onboarding flow yet
 * (V0.1.2-V0.1.4), so without this there would be no way to obtain a member id to put in the
 * `X-Member-Id` header (see [network.lapis.cloud.server.security.resolveCurrentMember]) and
 * exercise anything in this wave. Seeds a fixed, deterministic set of demo members/accounts —
 * one per [AccountRole] — the first time the member table is empty.
 *
 * **Secure by default: opt-IN, not opt-out.** [seedIfEmpty] is a no-op unless
 * `LAPIS_SEED_DEMO_DATA=true` is set explicitly (local/dev convenience only). Even then it
 * hard-refuses to run against anything but the H2 in-memory default — i.e. it never touches a
 * real deployment reachable via `LAPIS_DB_URL`. This matters because the seeded ADMIN account
 * has a fixed, guessable id (`00000000-0000-0000-0000-000000000001`) and a `NULL`
 * `password_hash`: with identity currently resolved from the client-supplied `X-Member-Id`
 * header, that id would otherwise be an unauthenticated full-admin login on a fresh production
 * database whose member table happens to be empty.
 */
object DevSeedData {
    data class SeedMember(
        val id: Uuid,
        val displayName: String,
        val email: String,
        val role: AccountRole,
    )

    val demoMembers =
        listOf(
            SeedMember(
                Uuid.parse("00000000-0000-0000-0000-000000000001"),
                "Amara Admin",
                "amara.admin@example.org",
                AccountRole.ADMIN,
            ),
            SeedMember(
                Uuid.parse("00000000-0000-0000-0000-000000000002"),
                "Boris Board",
                "boris.board@example.org",
                AccountRole.BOARD,
            ),
            SeedMember(
                Uuid.parse("00000000-0000-0000-0000-000000000003"),
                "Theresa Treasurer",
                "theresa.treasurer@example.org",
                AccountRole.TREASURER,
            ),
            SeedMember(
                Uuid.parse("00000000-0000-0000-0000-000000000004"),
                "Max Mitglied",
                "max.mitglied@example.org",
                AccountRole.MEMBER,
            ),
        )

    val standardTierId: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000f1")

    /**
     * A representative SKR49 (DATEV's Kontenrahmen for non-profit associations) chart of
     * accounts, spanning every Kontenklasse this codebase's `10-accounting.kuml.kts` documents
     * (0/1/2/3/4/5/7/9) -- see that file's header for the full class-to-Gemeinnützigkeit-sphere
     * background. This is a *reference-data candidate*: a real deployment would likely want the
     * complete SKR49 (hundreds of accounts) seeded via a dedicated import rather than this
     * hand-picked subset, but this set is enough to exercise `AccountingService` end to end and
     * gives a treasurer a plausible starting point in dev/demo environments.
     */
    data class SeedLedgerAccount(
        val accountNumber: String,
        val name: String,
        val accountClass: Int,
        val type: LedgerAccountType,
    )

    val demoLedgerAccounts =
        listOf(
            // Klasse 0 -- Anlagevermögen + liquid funds (SKR49 idiosyncratically puts Kasse/Bank here).
            SeedLedgerAccount("0400", "Büroausstattung", 0, LedgerAccountType.ASSET),
            SeedLedgerAccount("0920", "Kasse", 0, LedgerAccountType.ASSET),
            SeedLedgerAccount("0945", "Bank", 0, LedgerAccountType.ASSET),
            // Klasse 1 -- Umlaufvermögen/Forderungen/Verbindlichkeiten/USt.
            SeedLedgerAccount("1200", "Forderungen aus Lieferungen und Leistungen", 1, LedgerAccountType.ASSET),
            SeedLedgerAccount("1600", "Verbindlichkeiten aus Lieferungen und Leistungen", 1, LedgerAccountType.LIABILITY),
            SeedLedgerAccount("1776", "Umsatzsteuer", 1, LedgerAccountType.LIABILITY),
            // Klasse 2 -- Ideeller Bereich.
            SeedLedgerAccount("2110", "Mitgliederbeiträge", 2, LedgerAccountType.INCOME),
            SeedLedgerAccount("2300", "Verwaltungsaufwand", 2, LedgerAccountType.EXPENSE),
            // Klasse 3 -- steuerneutrale Vorgänge / durchlaufende Posten (Spenden).
            SeedLedgerAccount("3221", "Geldzuwendungen", 3, LedgerAccountType.INCOME),
            // Klasse 4 -- Vermögensverwaltung.
            SeedLedgerAccount("4150", "Zinserträge", 4, LedgerAccountType.INCOME),
            SeedLedgerAccount("4200", "Aufwendungen Vermögensverwaltung", 4, LedgerAccountType.EXPENSE),
            // Klassen 5-6 -- Zweckbetrieb.
            SeedLedgerAccount("5010", "Eintrittsgelder", 5, LedgerAccountType.INCOME),
            SeedLedgerAccount("5400", "Aufwendungen Zweckbetrieb", 5, LedgerAccountType.EXPENSE),
            // Klassen 7-8 -- Wirtschaftlicher Geschäftsbetrieb.
            SeedLedgerAccount("7000", "Erlöse wirtschaftlicher Geschäftsbetrieb", 7, LedgerAccountType.INCOME),
            SeedLedgerAccount("7600", "Wareneinsatz", 7, LedgerAccountType.EXPENSE),
            // Klasse 9 -- Vortrags-/statistische Konten.
            SeedLedgerAccount("9000", "Eröffnungsbilanzkonto", 9, LedgerAccountType.EQUITY),
            SeedLedgerAccount("9008", "Gewinn-/Verlustvortrag", 9, LedgerAccountType.EQUITY),
        )

    /**
     * Returns `true` only when [network.lapis.cloud.server.db.DatabaseConfig] is (or will be)
     * pointed at the H2 in-memory default — i.e. `LAPIS_DB_URL` is unset or explicitly an
     * `jdbc:h2:mem:` URL. A real deployment always sets `LAPIS_DB_URL` to a `jdbc:postgresql://...`
     * URL, so this is the same signal [DatabaseConfig] itself uses to pick a driver.
     */
    private fun isH2InMemory(): Boolean {
        val jdbcUrl = System.getenv("LAPIS_DB_URL")
        return jdbcUrl == null || jdbcUrl.startsWith("jdbc:h2:mem:")
    }

    /**
     * Seeds the fixed demo members/accounts the first time the member table is empty.
     *
     * @param force Bypasses the `LAPIS_SEED_DEMO_DATA` opt-in gate. Intended for test setup
     *   only (tests always run against the H2 default, never a real deployment) — production
     *   code must never pass `true` here. The H2-in-memory safety check below always applies,
     *   even with `force = true`.
     */
    fun seedIfEmpty(force: Boolean = false) {
        if (!force) {
            val seedRequested = System.getenv("LAPIS_SEED_DEMO_DATA")?.equals("true", ignoreCase = true) == true
            if (!seedRequested) return
        }
        check(isH2InMemory()) {
            "Refusing to seed demo data: LAPIS_DB_URL points at a non-H2-in-memory database. " +
                "Demo seeding (fixed, guessable member ids with a NULL password_hash) must never run " +
                "against a real deployment."
        }
        transaction {
            val alreadySeeded = MemberTable.selectAll().limit(1).any()
            if (alreadySeeded) return@transaction

            MembershipTierTable.insert {
                it[id] = standardTierId
                it[name] = "Standardbeitrag"
                it[description] = "Regulaerer Mitgliedsbeitrag, monatlich."
                it[contributionAmount] = BigDecimal("10.00")
                it[billingInterval] = BillingInterval.MONTHLY
                it[active] = true
            }

            demoMembers.forEach { seed ->
                MemberTable.insert {
                    it[id] = seed.id
                    it[displayName] = seed.displayName
                    it[email] = seed.email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = standardTierId
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = seed.id
                    it[role] = seed.role
                }
            }

            demoLedgerAccounts.forEach { seed ->
                LedgerAccountTable.insert {
                    it[id] = Uuid.random()
                    it[accountNumber] = seed.accountNumber
                    it[name] = seed.name
                    it[accountClass] = seed.accountClass
                    it[type] = seed.type
                    it[active] = true
                }
            }
        }
    }
}
