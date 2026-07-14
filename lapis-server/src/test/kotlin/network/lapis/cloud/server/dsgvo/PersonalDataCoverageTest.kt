package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The actual enforcement mechanism referenced throughout the `dsgvo` package's KDoc (see
 * [PersonalDataRegistry]): walks the `information_schema` for every foreign key that targets
 * `member(id)` and asserts the referencing table is either covered by some
 * [PersonalDataContributor] in [PersonalDataRegistry.contributors] or explicitly listed in
 * [PersonalDataRegistry.noPersonalDataAllowlist] with a written reason. A future wave that adds
 * e.g. `event_registration.member_id` without registering a contributor (or allowlisting it) goes
 * red here — `./gradlew clean check` catches the rot a hand-maintained list alone could not.
 *
 * Runs against the H2 in-memory test database (house rule: tests never touch a real deployment).
 * The ANSI `information_schema` views queried here (`table_constraints`, `key_column_usage`,
 * `referential_constraints`) resolve the same way on H2 (`MODE=PostgreSQL`) as on real Postgres,
 * so this walk reflects what a Postgres deployment's schema would show too.
 */
class PersonalDataCoverageTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        test("every table with a FK to member(id) is covered by a contributor or allowlisted") {
            val uncovered =
                transaction {
                    tablesReferencingMember().filterNot { tableName ->
                        tableName in PersonalDataRegistry.coveredTableNames() ||
                            tableName in PersonalDataRegistry.noPersonalDataAllowlist.keys
                    }
                }
            uncovered.shouldBeEmpty()
        }

        test("member itself is covered even though it is the PK side, not the FK side, of every relationship") {
            ("member" in PersonalDataRegistry.coveredTableNames()) shouldBe true
        }

        test("no table is covered by more than one contributor (regression guard for PersonalDataRegistry's init check)") {
            val allCoveredTables = PersonalDataRegistry.contributors.flatMap { it.coveredTables.map { table -> table.tableName } }
            allCoveredTables.size shouldBe allCoveredTables.toSet().size
        }

        test("every noPersonalDataAllowlist entry carries a non-blank written reason") {
            PersonalDataRegistry.noPersonalDataAllowlist.values.all { it.isNotBlank() } shouldBe true
        }

        test("dsgvo_audit_log rows never carry payload -- only the columns declared on DsgvoAuditLogTable exist") {
            // Structural guard, not a runtime-content check (that is exercised end-to-end in
            // DsgvoServiceTest): the table's column set itself has no free-text payload column
            // (email/message body/file name) to begin with, only counters/UUIDs/enums.
            val columnNames =
                transaction {
                    network.lapis.cloud.server.db.generated.DsgvoAuditLogTable.columns
                        .map { it.name }
                        .toSet()
                }
            val allowedColumns =
                setOf(
                    "id",
                    "occurred_at",
                    "actor_member_id",
                    "actor_role",
                    "action",
                    "subject_member_id",
                    "request_id",
                    "outcome_summary",
                    "legal_basis",
                )
            columnNames shouldBe allowedColumns
        }
    })

/**
 * ANSI `information_schema` walk (see class KDoc) — table names of every FK column whose target
 * table is `member`. Deliberately does not rely on constraint-naming conventions: joins
 * `table_constraints` (the FK side) through `referential_constraints` to the target
 * `table_constraints` row (the unique/PK constraint on the referenced table) instead, which is
 * portable across H2 and real Postgres.
 */
private fun JdbcTransaction.tablesReferencingMember(): Set<String> {
    val tables = mutableSetOf<String>()
    exec(
        """
        SELECT tc.table_name AS fk_table, tc2.table_name AS ref_table
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        JOIN information_schema.referential_constraints rc
            ON tc.constraint_name = rc.constraint_name
            AND tc.constraint_schema = rc.constraint_schema
        JOIN information_schema.table_constraints tc2
            ON rc.unique_constraint_name = tc2.constraint_name
            AND rc.unique_constraint_schema = tc2.table_schema
        WHERE tc.constraint_type = 'FOREIGN KEY'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            if (rs.getString("ref_table") == "member") {
                tables += rs.getString("fk_table")
            }
        }
    }
    return tables
}
