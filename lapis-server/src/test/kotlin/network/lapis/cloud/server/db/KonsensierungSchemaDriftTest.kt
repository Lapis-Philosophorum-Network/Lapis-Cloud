package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.KonsensierungOptionTable
import network.lapis.cloud.server.db.generated.KonsensierungStimmberechtigtTable
import network.lapis.cloud.server.db.generated.KonsensierungStimmzettelTable
import network.lapis.cloud.server.db.generated.KonsensierungTable
import network.lapis.cloud.server.db.generated.KonsensierungTeilnahmeTable
import network.lapis.cloud.server.db.generated.KonsensierungWiderstandTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — konsensierung domain (Systemisches Konsensieren, V0.2.5).
 *
 * Verifies that `lapis-server/src/main/kuml/09-konsensierung.kuml.kts` is a faithful model of
 * both (a) the real, Flyway-migrated H2 schema (`konsensierung`/`konsensierung_option`/
 * `konsensierung_stimmberechtigt`/`konsensierung_teilnahme`/`konsensierung_stimmzettel`/
 * `konsensierung_widerstand` — V1__baseline.sql), and (b) the generated `KonsensierungTable`/
 * `KonsensierungOptionTable`/`KonsensierungStimmberechtigtTable`/`KonsensierungTeilnahmeTable`/
 * `KonsensierungStimmzettelTable`/`KonsensierungWiderstandTable` Exposed objects
 * (`network.lapis.cloud.server.db.generated`).
 *
 * Mirrors [WahlSchemaDriftTest] (wahl domain) — see [SchemaDriftTest]'s KDoc for the full
 * designModelStrategy option B rationale.
 */
class KonsensierungSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "09-konsensierung.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the six konsensierung entities plus the Member/Antrag/Sitzung/Gremium/Beschluss stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "antrag",
                    "sitzung",
                    "gremium",
                    "beschluss",
                    "konsensierung",
                    "konsensierung_option",
                    "konsensierung_stimmberechtigt",
                    "konsensierung_teilnahme",
                    "konsensierung_stimmzettel",
                    "konsensierung_widerstand",
                )
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("konsensierung table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "konsensierung" }
            val real = transaction { introspectKonsensierungTable("konsensierung") }

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

            // opened_by -> member, NOT NULL: default would be "member_id" -- pinned via
            // «Column».fkEntity instead (same naming-gap class as every prior domain).
            real.foreignKeys["opened_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("opened_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // winner_option_id: no FK constraint in the real schema -- circular with
            // konsensierung_option, same workaround as abstimmung.winner_option_id.
            real.foreignKeys.containsKey("winner_option_id") shouldBe false
            entity.attributeByName("winner_option_id")?.foreignKey shouldBe null
            entity.attributeByName("winner_option_id")?.nullable shouldBe true
        }

        test("konsensierung_option table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "konsensierung_option" }
            val real = transaction { introspectKonsensierungTable("konsensierung_option") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["konsensierung_id"] shouldBe "konsensierung"
            model.entityNameOf(entity.attributeByName("konsensierung_id")?.foreignKey?.targetEntityId ?: "") shouldBe "konsensierung"

            // created_by -> member, NOT NULL: default would be "member_id" -- pinned via
            // «Column».fkEntity instead.
            real.foreignKeys["created_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("created_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("konsensierung_stimmberechtigt table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "konsensierung_stimmberechtigt" }
            val real = transaction { introspectKonsensierungTable("konsensierung_stimmberechtigt") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["konsensierung_id"] shouldBe "konsensierung"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("konsensierung_id")?.foreignKey?.targetEntityId ?: "") shouldBe "konsensierung"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test(
            "konsensierung_stimmberechtigt's composite UNIQUE constraint is pinned via a class-level «Index», including the runde dimension",
        ) {
            val entity = model.entities.single { it.name == "konsensierung_stimmberechtigt" }
            val real = transaction { introspectKonsensierungTable("konsensierung_stimmberechtigt") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("konsensierung_id", "member_id", "runde"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_konsensierung_stimmberechtigt_member_runde" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("konsensierung_id")!!.id,
                        entity.attributeByName("member_id")!!.id,
                        entity.attributeByName("runde")!!.id,
                    )
            }
        }

        test("konsensierung_teilnahme table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "konsensierung_teilnahme" }
            val real = transaction { introspectKonsensierungTable("konsensierung_teilnahme") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["konsensierung_id"] shouldBe "konsensierung"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("konsensierung_id")?.foreignKey?.targetEntityId ?: "") shouldBe "konsensierung"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("konsensierung_teilnahme's composite UNIQUE constraint is pinned via a class-level «Index», including the runde dimension") {
            // uq_konsensierung_teilnahme_member_runde UNIQUE (konsensierung_id, member_id, runde)
            // -- the GEHEIM-path one-member-one-vote-per-round backstop (see file header comment
            // in 09-konsensierung.kuml.kts).
            val entity = model.entities.single { it.name == "konsensierung_teilnahme" }
            val real = transaction { introspectKonsensierungTable("konsensierung_teilnahme") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("konsensierung_id", "member_id", "runde"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_konsensierung_teilnahme_member_runde" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("konsensierung_id")!!.id,
                        entity.attributeByName("member_id")!!.id,
                        entity.attributeByName("runde")!!.id,
                    )
            }
        }

        test("konsensierung_stimmzettel table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "konsensierung_stimmzettel" }
            val real = transaction { introspectKonsensierungTable("konsensierung_stimmzettel") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["konsensierung_id"] shouldBe "konsensierung"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("konsensierung_id")?.foreignKey?.targetEntityId ?: "") shouldBe "konsensierung"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // Ballot secrecy: member_id is nullable, always NULL for a geheim Konsensierung --
            // pinned explicitly (see file header comment in 09-konsensierung.kuml.kts).
            entity.attributeByName("member_id")?.nullable shouldBe true
        }

        test("konsensierung_stimmzettel's composite UNIQUE constraint is pinned via a class-level «Index», including the runde dimension") {
            val entity = model.entities.single { it.name == "konsensierung_stimmzettel" }
            val real = transaction { introspectKonsensierungTable("konsensierung_stimmzettel") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("konsensierung_id", "member_id", "runde"))
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_konsensierung_stimmzettel_member_runde" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("konsensierung_id")!!.id,
                        entity.attributeByName("member_id")!!.id,
                        entity.attributeByName("runde")!!.id,
                    )
            }
        }

        test("konsensierung_widerstand table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "konsensierung_widerstand" }
            val real = transaction { introspectKonsensierungTable("konsensierung_widerstand") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            // stimmzettel_id -> konsensierung_stimmzettel, NOT NULL: default would be
            // "konsensierung_stimmzettel_id" -- plain «Column» attribute pinned via «Column».fkEntity.
            real.foreignKeys["stimmzettel_id"] shouldBe "konsensierung_stimmzettel"
            model.entityNameOf(entity.attributeByName("stimmzettel_id")?.foreignKey?.targetEntityId ?: "") shouldBe
                "konsensierung_stimmzettel"

            // option_id -> konsensierung_option, NOT NULL: default would be
            // "konsensierung_option_id" -- plain «Column» attribute pinned via «Column».fkEntity.
            real.foreignKeys["option_id"] shouldBe "konsensierung_option"
            model.entityNameOf(entity.attributeByName("option_id")?.foreignKey?.targetEntityId ?: "") shouldBe "konsensierung_option"
        }

        // ── (2) Model vs. generated Exposed Table objects ────────────────────

        test("konsensierung entity column-name set matches the generated KonsensierungTable 1:1") {
            model.entities
                .single { it.name == "konsensierung" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder KonsensierungTable.columns.map { it.name }
        }

        test("konsensierung_option entity column-name set matches the generated KonsensierungOptionTable 1:1") {
            model.entities
                .single { it.name == "konsensierung_option" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder KonsensierungOptionTable.columns.map { it.name }
        }

        test("konsensierung_stimmberechtigt entity column-name set matches the generated KonsensierungStimmberechtigtTable 1:1") {
            model.entities
                .single { it.name == "konsensierung_stimmberechtigt" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder KonsensierungStimmberechtigtTable.columns.map { it.name }
        }

        test("konsensierung_teilnahme entity column-name set matches the generated KonsensierungTeilnahmeTable 1:1") {
            model.entities
                .single { it.name == "konsensierung_teilnahme" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder KonsensierungTeilnahmeTable.columns.map { it.name }
        }

        test("konsensierung_stimmzettel entity column-name set matches the generated KonsensierungStimmzettelTable 1:1") {
            model.entities
                .single { it.name == "konsensierung_stimmzettel" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder KonsensierungStimmzettelTable.columns.map { it.name }
        }

        test("konsensierung_widerstand entity column-name set matches the generated KonsensierungWiderstandTable 1:1") {
            model.entities
                .single { it.name == "konsensierung_widerstand" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder KonsensierungWiderstandTable.columns.map { it.name }
        }

        test("konsensierung.status/aggregation/tiebreak_regel/verbindlichkeit are modelled as real ErmDataType.Enum columns") {
            val entity = model.entities.single { it.name == "konsensierung" }
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "KonsensierungStatus",
                    values = listOf("SAMMLUNG", "BEWERTUNG", "GESCHLOSSEN", "AUSGEWERTET", "ABGEBROCHEN"),
                    externalFqName = "network.lapis.cloud.shared.domain.KonsensierungStatus",
                )
            entity.attributeByName("aggregation")?.type shouldBe
                ErmDataType.Enum(
                    name = "SkAggregation",
                    values = listOf("MITTELWERT", "SUMME"),
                    externalFqName = "network.lapis.cloud.shared.domain.SkAggregation",
                )
            entity.attributeByName("tiebreak_regel")?.type shouldBe
                ErmDataType.Enum(
                    name = "SkTiebreakRegel",
                    values = listOf("NIEDRIGSTER_MAXWIDERSTAND", "NIEDRIGSTE_STDABW", "WIEDERHOLUNG"),
                    externalFqName = "network.lapis.cloud.shared.domain.SkTiebreakRegel",
                )
            entity.attributeByName("verbindlichkeit")?.type shouldBe
                ErmDataType.Enum(
                    name = "SkVerbindlichkeit",
                    values = listOf("SONDIERUNG", "BESCHLUSS"),
                    externalFqName = "network.lapis.cloud.shared.domain.SkVerbindlichkeit",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedKonsensierungTable(
    val columns: Map<String, IntrospectedKonsensierungColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedKonsensierungColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors [WahlSchemaDriftTest]'s (private, wahl-domain-scoped)
 * `introspectWahlTable`.
 */
private fun JdbcTransaction.introspectKonsensierungTable(tableName: String): IntrospectedKonsensierungTable {
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

    // Detects both inline CONSTRAINT ... UNIQUE and standalone CREATE UNIQUE INDEX (generated via
    // a class-level «Index») — H2's information_schema.table_constraints only surfaces the
    // former, never a plain named unique index, so both sources are unioned.
    val uniqueColumnsByConstraint = mutableMapOf<String, MutableSet<String>>()
    exec(
        """
        SELECT tc.constraint_name AS name, kcu.column_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
            ON tc.constraint_name = kcu.constraint_name
            AND tc.table_schema = kcu.table_schema
        WHERE tc.constraint_type = 'UNIQUE' AND tc.table_name = '$tableName'
        UNION
        SELECT i.index_name AS name, ic.column_name
        FROM information_schema.index_columns ic
        JOIN information_schema.indexes i
            ON ic.index_name = i.index_name AND ic.table_name = i.table_name
        WHERE i.index_type_name = 'UNIQUE INDEX' AND ic.table_name = '$tableName'
        """.trimIndent(),
    ) { rs ->
        while (rs.next()) {
            uniqueColumnsByConstraint
                .getOrPut(rs.getString("name")) { mutableSetOf() }
                .add(rs.getString("column_name"))
        }
    }

    val columns =
        nullableByColumn.mapValues { (_, nullable) ->
            IntrospectedKonsensierungColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedKonsensierungTable(
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
