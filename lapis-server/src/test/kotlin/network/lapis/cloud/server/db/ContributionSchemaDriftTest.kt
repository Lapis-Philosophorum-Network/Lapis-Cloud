package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.tables.ContributionTable
import network.lapis.cloud.server.db.tables.MembershipTierTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — contribution domain.
 *
 * Verifies that `lapis-server/src/main/kuml/01-contribution.kuml.kts` is a faithful model of
 * both (a) the real, Flyway-migrated H2 schema (`membership_tier`/`contribution`), and (b) the
 * hand-written `MembershipTierTable`/`ContributionTable` Exposed objects.
 *
 * Mirrors [SchemaDriftTest] (foundation domain) — see its KDoc for the full designModelStrategy
 * option B rationale (verification-only artifact; hand-written `Table` objects remain the
 * actually-compiled/actually-imported-by-N-files runtime artifact).
 */
class ContributionSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "01-contribution.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly membership_tier, contribution and the member stub") {
            model.entities.map { it.name }.toSet() shouldBe setOf("membership_tier", "contribution", "member")
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("membership_tier table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "membership_tier" }
            val real = transaction { introspectContributionTable("membership_tier") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
        }

        test("contribution table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "contribution" }
            val real = transaction { introspectContributionTable("contribution") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["member_id"] shouldBe "member"
            real.foreignKeys["membership_tier_id"] shouldBe "membership_tier"
        }

        test("contribution's composite UNIQUE constraint has no kUML ERM equivalent (accepted gap, pinned)") {
            // uq_contribution_member_tier_period UNIQUE (member_id, membership_tier_id,
            // period_start, period_end) in V2__contributions.sql. ErmProfileNames' «Column».unique
            // tag is single-column only — no composite-unique-constraint tag exists in the ERM
            // mapping profile today. Pinned explicitly here (real=composite-unique present, model
            // has no representation of it at all) rather than silently allowing drift, so a future
            // kUML release adding composite-unique support is noticed and the model can be
            // tightened.
            val real = transaction { introspectContributionTable("contribution") }
            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("member_id", "membership_tier_id", "period_start", "period_end"))

            val entity = model.entities.single { it.name == "contribution" }
            entity.attributes.none { it.unique } shouldBe true
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("membership_tier entity column-name set matches the hand-written MembershipTierTable 1:1") {
            model.entities
                .single { it.name == "membership_tier" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder MembershipTierTable.columns.map { it.name }
        }

        test("contribution entity column-name set matches the hand-written ContributionTable 1:1") {
            model.entities
                .single { it.name == "contribution" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder ContributionTable.columns.map { it.name }
        }

        test(
            "membership_tier.billing_interval and contribution.status are modelled as VARCHAR(20), matching the real schema (enum-fidelity gap, documented)",
        ) {
            // Same accepted gap as MemberStatus/AccountRole in the foundation domain (see
            // SchemaDriftTest's matching test) — explicit «Column».sqlType="VARCHAR(20)"
            // overrides take precedence over kUML's enum-to-VARCHAR+CHECK fallback path, matching
            // the real V2__contributions.sql's plain `VARCHAR(20)` with no CHECK constraint.
            val billingInterval = model.entities.single { it.name == "membership_tier" }.attributeByName("billing_interval")
            val status = model.entities.single { it.name == "contribution" }.attributeByName("status")
            billingInterval?.type shouldBe ErmDataType.Custom("VARCHAR(20)")
            status?.type shouldBe ErmDataType.Custom("VARCHAR(20)")
        }

        test("decimal columns are modelled with the real schema's DECIMAL(12,2) precision, not the default DECIMAL(19,2)") {
            // UmlErmTypeMapper's default "bigdecimal" mapping is ErmDataType.Decimal(19, 2) — the
            // real schema uses DECIMAL(12, 2) throughout (contribution_amount, amount_due,
            // paid_amount). An explicit «Column».sqlType="DECIMAL(12,2)" override (rendered as
            // ErmDataType.Custom, same mechanism as the VARCHAR overrides above) pins the correct
            // precision/scale instead of silently drifting to the wider default.
            val tier = model.entities.single { it.name == "membership_tier" }
            val contribution = model.entities.single { it.name == "contribution" }
            tier.attributeByName("contribution_amount")?.type shouldBe ErmDataType.Custom("DECIMAL(12,2)")
            contribution.attributeByName("amount_due")?.type shouldBe ErmDataType.Custom("DECIMAL(12,2)")
            contribution.attributeByName("paid_amount")?.type shouldBe ErmDataType.Custom("DECIMAL(12,2)")
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedContributionTable(
    val columns: Map<String, IntrospectedContributionColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedContributionColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors [network.lapis.cloud.server.db.SchemaDriftTest]'s
 * (private, foundation-domain-scoped) `introspectTable`, extended with composite-unique
 * detection for this domain's `uq_contribution_member_tier_period` constraint.
 */
private fun JdbcTransaction.introspectContributionTable(tableName: String): IntrospectedContributionTable {
    val nullableByColumn = mutableMapOf<String, Boolean>()
    exec(
        """
        SELECT column_name, is_nullable
        FROM information_schema.columns
        WHERE table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            nullableByColumn[rs.getString("column_name")] = rs.getString("is_nullable") == "YES"
        }
    }

    val fkByColumn = mutableMapOf<String, String>()
    exec(
        """
        SELECT kcu.column_name AS fk_column, tc2.table_name AS ref_table
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
        WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            fkByColumn[rs.getString("fk_column")] = rs.getString("ref_table")
        }
    }

    val uniqueColumnsByConstraint = mutableMapOf<String, MutableSet<String>>()
    exec(
        """
        SELECT tc.constraint_name, kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'UNIQUE' AND tc.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            uniqueColumnsByConstraint
                .getOrPut(rs.getString("constraint_name")) { mutableSetOf() }
                .add(rs.getString("column_name"))
        }
    }

    val columns =
        nullableByColumn.mapValues { (_, nullable) ->
            IntrospectedContributionColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedContributionTable(
        columns = columns,
        foreignKeys = fkByColumn,
        compositeUniqueConstraints = compositeUniques,
    )
}

/** Small local stand-in for Kotest's `withClue` to keep imports minimal (mirrors SchemaDriftTest's). */
private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError("$clue: ${e.message}", e)
    }
