package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.tables.DocumentFolderTable
import network.lapis.cloud.server.db.tables.DocumentTable
import network.lapis.cloud.server.db.tables.DocumentVersionTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — document domain.
 *
 * Verifies that `lapis-server/src/main/kuml/02-document.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`document_folder`/`document`/`document_version`), and
 * (b) the hand-written `DocumentFolderTable`/`DocumentTable`/`DocumentVersionTable` Exposed
 * objects.
 *
 * Mirrors [SchemaDriftTest] (foundation domain) and [ContributionSchemaDriftTest] (contribution
 * domain) — see [SchemaDriftTest]'s KDoc for the full designModelStrategy option B rationale
 * (verification-only artifact; hand-written `Table` objects remain the
 * actually-compiled/actually-imported-by-N-files runtime artifact).
 */
class DocumentSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "02-document.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        test("model declares exactly document_folder, document, document_version") {
            // Unlike foundation/contribution, no cross-domain Member stub is needed here: both
            // Member-referencing FKs (document.created_by, document_version.uploaded_by) are
            // modelled as plain «Column» UUID attributes, not UML associations (see the
            // .kuml.kts file header comment for the naming-gap rationale), so there is no
            // association target to resolve.
            model.entities.map { it.name }.toSet() shouldBe
                setOf("document_folder", "document", "document_version")
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("document_folder table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "document_folder" }
            val real = transaction { introspectDocumentTable("document_folder") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // parent_folder_id is a genuinely self-referential FK in the real schema (REFERENCES
            // document_folder (id)) — UmlToErmTransformer skips self-referential UML associations,
            // so the model has no FK representation for it at all (plain «Column» attribute, same
            // as the hand-written DocumentFolderTable, which also declares no .references() on
            // this column). Pinned explicitly rather than silently allowed to drift.
            real.foreignKeys["parent_folder_id"] shouldBe "document_folder"
            model.entities
                .single { it.name == "document_folder" }
                .attributeByName("parent_folder_id")
                ?.foreignKey shouldBe null
        }

        test("document table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "document" }
            val real = transaction { introspectDocumentTable("document") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // folder_id and created_by both have real FKs in the migrated schema, but neither is
            // modelled as a UML association here (see the .kuml.kts file header comment): the
            // association-to-FK column-naming default ("document_folder_id" / "member_id") does
            // not match the real schema's actual column names ("folder_id" / "created_by"), and
            // there is no DSL-level way to override the derived name without an actual naming
            // collision (which would leave a spurious duplicate column). Pinned explicitly here
            // rather than silently allowed to drift.
            real.foreignKeys["folder_id"] shouldBe "document_folder"
            real.foreignKeys["created_by"] shouldBe "member"
            model.entities
                .single { it.name == "document" }
                .attributeByName("folder_id")
                ?.foreignKey shouldBe null
            model.entities
                .single { it.name == "document" }
                .attributeByName("created_by")
                ?.foreignKey shouldBe null
            // current_version_id has a real FK in the migrated schema (added via a second ALTER
            // TABLE after both tables exist, fk_document_current_version) but deliberately no FK
            // at the Exposed layer (avoids a document <-> document_version circular reference at
            // DSL-declaration time) — modelled here as a plain «Column» attribute, matching the
            // hand-written DocumentTable. Pinned explicitly rather than silently allowed to drift.
            real.foreignKeys["current_version_id"] shouldBe "document_version"
            model.entities
                .single { it.name == "document" }
                .attributeByName("current_version_id")
                ?.foreignKey shouldBe null
        }

        test("document_version table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "document_version" }
            val real = transaction { introspectDocumentTable("document_version") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys

            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["document_id"] shouldBe "document"
            // uploaded_by has a real FK in the migrated schema, but (same rationale as
            // document.folder_id/created_by above) is modelled as a plain «Column» attribute, not
            // a UML association — the derived default name would be "member_id", not the real
            // schema's "uploaded_by".
            real.foreignKeys["uploaded_by"] shouldBe "member"
            model.entities
                .single { it.name == "document_version" }
                .attributeByName("uploaded_by")
                ?.foreignKey shouldBe null
        }

        test("document_version's composite UNIQUE constraint has no kUML ERM equivalent (accepted gap, pinned)") {
            // uq_document_version_number UNIQUE (document_id, version_number) in
            // V3__documents.sql. Same accepted gap as contribution's
            // uq_contribution_member_tier_period (see ContributionSchemaDriftTest) —
            // ErmProfileNames' «Column».unique tag is single-column only, no composite-unique-
            // constraint tag exists in the ERM mapping profile today.
            val real = transaction { introspectDocumentTable("document_version") }
            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder
                listOf(setOf("document_id", "version_number"))

            val entity = model.entities.single { it.name == "document_version" }
            entity.attributes.none { it.unique } shouldBe true
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("document_folder entity column-name set matches the hand-written DocumentFolderTable 1:1") {
            model.entities
                .single { it.name == "document_folder" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder DocumentFolderTable.columns.map { it.name }
        }

        test("document entity column-name set matches the hand-written DocumentTable 1:1") {
            model.entities
                .single { it.name == "document" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder DocumentTable.columns.map { it.name }
        }

        test("document_version entity column-name set matches the hand-written DocumentVersionTable 1:1") {
            model.entities
                .single { it.name == "document_version" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder DocumentVersionTable.columns.map { it.name }
        }

        test("document.access_level is modelled as VARCHAR(20), matching the real schema (enum-fidelity gap, documented)") {
            // Same accepted gap as MemberStatus/AccountRole/BillingInterval/ContributionStatus in
            // the prior domains (see SchemaDriftTest's matching test) — explicit
            // «Column».sqlType="VARCHAR(20)" override takes precedence over kUML's
            // enum-to-VARCHAR+CHECK fallback path, matching the real V3__documents.sql's plain
            // `VARCHAR(20)` with no CHECK constraint.
            val accessLevel = model.entities.single { it.name == "document" }.attributeByName("access_level")
            accessLevel?.type shouldBe ErmDataType.Custom("VARCHAR(20)")
        }

        test("document_version.document_id has NO_ACTION referential action, matching the real schema") {
            // Associations cannot carry stereotype() calls from .kuml.kts script code today
            // (AssociationBuilder does not implement UmlElementScope — confirmed during the
            // foundation/contribution waves), so no explicit «FK».onDelete tag is set here.
            // UmlToErmTransformer's parseReferentialAction falls back to
            // ReferentialAction.NO_ACTION whenever no tag is present, which happens to already
            // match the real schema's non-default NO_ACTION override for this specific column —
            // pinned explicitly so a future default change in either the transformer or the real
            // schema is caught.
            val fk =
                model.entities
                    .single { it.name == "document_version" }
                    .attributeByName("document_id")
                    ?.foreignKey
            fk?.onDelete?.name shouldBe "NO_ACTION"
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedDocumentTable(
    val columns: Map<String, IntrospectedDocumentColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedDocumentColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors
 * [network.lapis.cloud.server.db.ContributionSchemaDriftTest]'s (private, contribution-domain-
 * scoped) `introspectContributionTable`.
 */
private fun JdbcTransaction.introspectDocumentTable(tableName: String): IntrospectedDocumentTable {
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
            IntrospectedDocumentColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedDocumentTable(
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
