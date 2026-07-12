package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.tables.AbstimmungOptionTable
import network.lapis.cloud.server.db.tables.AbstimmungStimmeTable
import network.lapis.cloud.server.db.tables.AbstimmungTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — abstimmung domain.
 *
 * Verifies that `lapis-server/src/main/kuml/06-abstimmung.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`abstimmung`/`abstimmung_option`/`abstimmung_stimme` —
 * V8__meritokratische_abstimmungen.sql), and (b) the hand-written `AbstimmungTable`/
 * `AbstimmungOptionTable`/`AbstimmungStimmeTable` Exposed objects (defined in the same
 * `GovernanceTables.kt` file as the governance domain's own tables — see
 * `06-abstimmung.kuml.kts`'s file header comment for why this is nonetheless a separate
 * generation unit/domain wave).
 *
 * Mirrors [SchemaDriftTest] (foundation domain), [ContributionSchemaDriftTest] (contribution
 * domain), [DocumentSchemaDriftTest] (document domain), [CommunicationSchemaDriftTest]
 * (communication domain), [DsgvoSchemaDriftTest] (dsgvo domain) and [GovernanceSchemaDriftTest]
 * (governance domain) — see [SchemaDriftTest]'s KDoc for the full designModelStrategy option B
 * rationale (verification-only artifact; hand-written `Table` objects remain the
 * actually-compiled/actually-imported-by-N-files runtime artifact).
 */
class AbstimmungSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "06-abstimmung.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the three abstimmung entities plus the Member/Antrag/Sitzung/Beschluss stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "antrag",
                    "sitzung",
                    "beschluss",
                    "abstimmung",
                    "abstimmung_option",
                    "abstimmung_stimme",
                )
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("abstimmung table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "abstimmung" }
            val real = transaction { introspectAbstimmungTable("abstimmung") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["antrag_id"] shouldBe "antrag"
            real.foreignKeys["sitzung_id"] shouldBe "sitzung"
            real.foreignKeys["beschluss_id"] shouldBe "beschluss"
            model.entityNameOf(entity.attributeByName("antrag_id")?.foreignKey?.targetEntityId ?: "") shouldBe "antrag"
            model.entityNameOf(entity.attributeByName("sitzung_id")?.foreignKey?.targetEntityId ?: "") shouldBe "sitzung"
            model.entityNameOf(entity.attributeByName("beschluss_id")?.foreignKey?.targetEntityId ?: "") shouldBe "beschluss"

            // opened_by -> member, NOT NULL: same naming-gap class as document/communication/
            // dsgvo/governance's own mismatched member-FK columns (default would be "member_id").
            real.foreignKeys["opened_by"] shouldBe "member"
            entity.attributeByName("opened_by")?.foreignKey shouldBe null

            // winner_option_id: real schema has no FK constraint on this column at all (circular
            // with abstimmung_option, which itself FK-references abstimmung) — pinned explicitly.
            real.foreignKeys.containsKey("winner_option_id") shouldBe false
            entity.attributeByName("winner_option_id")?.foreignKey shouldBe null
        }

        test("abstimmung_option table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "abstimmung_option" }
            val real = transaction { introspectAbstimmungTable("abstimmung_option") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["abstimmung_id"] shouldBe "abstimmung"
            model.entityNameOf(entity.attributeByName("abstimmung_id")?.foreignKey?.targetEntityId ?: "") shouldBe "abstimmung"
        }

        test("abstimmung_stimme table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "abstimmung_stimme" }
            val real = transaction { introspectAbstimmungTable("abstimmung_stimme") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["abstimmung_id"] shouldBe "abstimmung"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("abstimmung_id")?.foreignKey?.targetEntityId ?: "") shouldBe "abstimmung"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // option_id -> abstimmung_option, NOT NULL: association-derived default would be
            // "abstimmung_option_id", not the real schema's "option_id" — plain «Column» attribute.
            real.foreignKeys["option_id"] shouldBe "abstimmung_option"
            entity.attributeByName("option_id")?.foreignKey shouldBe null
        }

        test("abstimmung_stimme's composite UNIQUE constraint has no kUML ERM equivalent (accepted gap, pinned)") {
            // uq_abstimmung_stimme_member UNIQUE (abstimmung_id, member_id) — same accepted gap as
            // contribution's/document's/communication's/governance's own composite UNIQUE
            // constraints. ErmProfileNames' «Column».unique tag is single-column only.
            val entity = model.entities.single { it.name == "abstimmung_stimme" }
            val real = transaction { introspectAbstimmungTable("abstimmung_stimme") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("abstimmung_id", "member_id"))
            entity.attributes.none { it.unique } shouldBe true
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("abstimmung entity column-name set matches the hand-written AbstimmungTable 1:1") {
            model.entities
                .single { it.name == "abstimmung" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AbstimmungTable.columns.map { it.name }
        }

        test("abstimmung_option entity column-name set matches the hand-written AbstimmungOptionTable 1:1") {
            model.entities
                .single { it.name == "abstimmung_option" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AbstimmungOptionTable.columns.map { it.name }
        }

        test("abstimmung_stimme entity column-name set matches the hand-written AbstimmungStimmeTable 1:1") {
            model.entities
                .single { it.name == "abstimmung_stimme" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AbstimmungStimmeTable.columns.map { it.name }
        }

        test("abstimmung.status is modelled with explicit sqlType, matching the real schema (enum-fidelity gap, documented)") {
            // Same accepted gap as MemberStatus/AccountRole/.../ResolutionMode in the prior
            // domains — explicit «Column».sqlType overrides take precedence over kUML's
            // enum-to-VARCHAR+CHECK fallback path, matching the real
            // V8__meritokratische_abstimmungen.sql's plain VARCHAR(30) column with no CHECK
            // constraint.
            val status = model.entities.single { it.name == "abstimmung" }.attributeByName("status")
            status?.type shouldBe ErmDataType.Custom("VARCHAR(30)")
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedAbstimmungTable(
    val columns: Map<String, IntrospectedAbstimmungColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedAbstimmungColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors [GovernanceSchemaDriftTest]'s (private,
 * governance-domain-scoped) `introspectGovernanceTable`.
 */
private fun JdbcTransaction.introspectAbstimmungTable(tableName: String): IntrospectedAbstimmungTable {
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
            IntrospectedAbstimmungColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedAbstimmungTable(
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
