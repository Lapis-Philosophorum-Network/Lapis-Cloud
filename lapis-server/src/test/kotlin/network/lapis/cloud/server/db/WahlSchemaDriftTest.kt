package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.WahlFreigabeTable
import network.lapis.cloud.server.db.generated.WahlKandidaturTable
import network.lapis.cloud.server.db.generated.WahlOptionTable
import network.lapis.cloud.server.db.generated.WahlStimmzettelAuswahlTable
import network.lapis.cloud.server.db.generated.WahlStimmzettelTable
import network.lapis.cloud.server.db.generated.WahlTable
import network.lapis.cloud.server.db.generated.WahlTeilnahmeTable
import network.lapis.cloud.server.db.generated.WahlWahlberechtigtTable
import network.lapis.cloud.server.db.generated.WahlWahlvorstandTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — wahl domain.
 *
 * Verifies that `lapis-server/src/main/kuml/07-wahl.kuml.kts` is a faithful model of both (a) the
 * real, Flyway-migrated H2 schema (`wahl`/`wahl_kandidatur`/`wahl_option`/`wahl_wahlvorstand`/
 * `wahl_wahlberechtigt`/`wahl_teilnahme`/`wahl_freigabe`/`wahl_stimmzettel`/
 * `wahl_stimmzettel_auswahl` — V9__demokratische_wahlen.sql), and (b) the hand-written `WahlTable`/
 * `WahlKandidaturTable`/`WahlOptionTable`/`WahlWahlvorstandTable`/`WahlWahlberechtigtTable`/
 * `WahlTeilnahmeTable`/`WahlFreigabeTable`/`WahlStimmzettelTable`/`WahlStimmzettelAuswahlTable`
 * Exposed objects (`network.lapis.cloud.server.db.generated.WahlTables.kt`).
 *
 * Mirrors [SchemaDriftTest] (foundation domain), [ContributionSchemaDriftTest] (contribution
 * domain), [DocumentSchemaDriftTest] (document domain), [CommunicationSchemaDriftTest]
 * (communication domain), [DsgvoSchemaDriftTest] (dsgvo domain), [GovernanceSchemaDriftTest]
 * (governance domain) and [AbstimmungSchemaDriftTest] (abstimmung domain) — see [SchemaDriftTest]'s
 * KDoc for the full designModelStrategy option B rationale (verification-only artifact;
 * hand-written `Table` objects remain the actually-compiled/actually-imported-by-N-files runtime
 * artifact).
 */
class WahlSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "07-wahl.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the nine wahl entities plus the Member/Antrag/Sitzung/Gremium/Beschluss stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "antrag",
                    "sitzung",
                    "gremium",
                    "beschluss",
                    "wahl",
                    "wahl_kandidatur",
                    "wahl_option",
                    "wahl_wahlvorstand",
                    "wahl_wahlberechtigt",
                    "wahl_teilnahme",
                    "wahl_freigabe",
                    "wahl_stimmzettel",
                    "wahl_stimmzettel_auswahl",
                )
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("wahl table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "wahl" }
            val real = transaction { introspectWahlTable("wahl") }

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

            // ziel_gremium_id -> gremium, nullable: same naming-gap class as document/
            // communication/dsgvo/governance/abstimmung's own mismatched FK columns (default
            // would be "gremium_id") — pinned instead via «Column».fkEntity.
            real.foreignKeys["ziel_gremium_id"] shouldBe "gremium"
            model.entityNameOf(entity.attributeByName("ziel_gremium_id")?.foreignKey?.targetEntityId ?: "") shouldBe "gremium"

            // opened_by -> member, NOT NULL: same naming-gap class (default would be "member_id")
            // — pinned instead via «Column».fkEntity.
            real.foreignKeys["opened_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("opened_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("wahl_kandidatur table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "wahl_kandidatur" }
            val real = transaction { introspectWahlTable("wahl_kandidatur") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["wahl_id"] shouldBe "wahl"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("wahl_id")?.foreignKey?.targetEntityId ?: "") shouldBe "wahl"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("wahl_option table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "wahl_option" }
            val real = transaction { introspectWahlTable("wahl_option") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["wahl_id"] shouldBe "wahl"
            model.entityNameOf(entity.attributeByName("wahl_id")?.foreignKey?.targetEntityId ?: "") shouldBe "wahl"

            // kandidatur_id -> wahl_kandidatur, nullable: default would be "wahl_kandidatur_id",
            // not the real schema's "kandidatur_id" — plain «Column» attribute pinned instead via
            // «Column».fkEntity.
            real.foreignKeys["kandidatur_id"] shouldBe "wahl_kandidatur"
            model.entityNameOf(entity.attributeByName("kandidatur_id")?.foreignKey?.targetEntityId ?: "") shouldBe "wahl_kandidatur"
        }

        test("wahl_wahlvorstand table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "wahl_wahlvorstand" }
            val real = transaction { introspectWahlTable("wahl_wahlvorstand") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["wahl_id"] shouldBe "wahl"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("wahl_id")?.foreignKey?.targetEntityId ?: "") shouldBe "wahl"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("wahl_wahlvorstand's composite UNIQUE constraint has no kUML ERM equivalent (accepted gap, pinned)") {
            // uq_wahl_wahlvorstand_member UNIQUE (wahl_id, member_id) — same accepted gap as
            // contribution's/document's/communication's/governance's/abstimmung's own composite
            // UNIQUE constraints. ErmProfileNames' «Column».unique tag is single-column only.
            val entity = model.entities.single { it.name == "wahl_wahlvorstand" }
            val real = transaction { introspectWahlTable("wahl_wahlvorstand") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("wahl_id", "member_id"))
            entity.attributes.none { it.unique } shouldBe true
        }

        test("wahl_wahlberechtigt table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "wahl_wahlberechtigt" }
            val real = transaction { introspectWahlTable("wahl_wahlberechtigt") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["wahl_id"] shouldBe "wahl"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("wahl_id")?.foreignKey?.targetEntityId ?: "") shouldBe "wahl"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("wahl_wahlberechtigt's composite UNIQUE constraint has no kUML ERM equivalent (accepted gap, pinned)") {
            // uq_wahl_wahlberechtigt_member UNIQUE (wahl_id, member_id).
            val entity = model.entities.single { it.name == "wahl_wahlberechtigt" }
            val real = transaction { introspectWahlTable("wahl_wahlberechtigt") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("wahl_id", "member_id"))
            entity.attributes.none { it.unique } shouldBe true
        }

        test("wahl_teilnahme table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "wahl_teilnahme" }
            val real = transaction { introspectWahlTable("wahl_teilnahme") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["wahl_id"] shouldBe "wahl"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("wahl_id")?.foreignKey?.targetEntityId ?: "") shouldBe "wahl"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("wahl_teilnahme's composite UNIQUE constraint has no kUML ERM equivalent (accepted gap, pinned)") {
            // uq_wahl_teilnahme_member UNIQUE (wahl_id, member_id) — the GEHEIM-path
            // one-member-one-vote backstop (see file header comment in 07-wahl.kuml.kts).
            val entity = model.entities.single { it.name == "wahl_teilnahme" }
            val real = transaction { introspectWahlTable("wahl_teilnahme") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("wahl_id", "member_id"))
            entity.attributes.none { it.unique } shouldBe true
        }

        test("wahl_freigabe table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "wahl_freigabe" }
            val real = transaction { introspectWahlTable("wahl_freigabe") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["wahl_id"] shouldBe "wahl"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("wahl_id")?.foreignKey?.targetEntityId ?: "") shouldBe "wahl"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("wahl_freigabe's composite UNIQUE constraint has no kUML ERM equivalent (accepted gap, pinned)") {
            // uq_wahl_freigabe_member UNIQUE (wahl_id, member_id).
            val entity = model.entities.single { it.name == "wahl_freigabe" }
            val real = transaction { introspectWahlTable("wahl_freigabe") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("wahl_id", "member_id"))
            entity.attributes.none { it.unique } shouldBe true
        }

        test("wahl_stimmzettel table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "wahl_stimmzettel" }
            val real = transaction { introspectWahlTable("wahl_stimmzettel") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            real.foreignKeys["wahl_id"] shouldBe "wahl"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("wahl_id")?.foreignKey?.targetEntityId ?: "") shouldBe "wahl"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // Ballot secrecy: member_id is nullable, always NULL for a geheim Wahl — pinned
            // explicitly (see file header comment in 07-wahl.kuml.kts for the full rationale).
            entity.attributeByName("member_id")?.nullable shouldBe true
        }

        test("wahl_stimmzettel's composite UNIQUE constraint has no kUML ERM equivalent (accepted gap, pinned)") {
            // uq_wahl_stimmzettel_member UNIQUE (wahl_id, member_id) — the non-secret-path
            // one-member-one-vote backstop (see file header comment in 07-wahl.kuml.kts).
            val entity = model.entities.single { it.name == "wahl_stimmzettel" }
            val real = transaction { introspectWahlTable("wahl_stimmzettel") }

            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("wahl_id", "member_id"))
            entity.attributes.none { it.unique } shouldBe true
        }

        test("wahl_stimmzettel_auswahl table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "wahl_stimmzettel_auswahl" }
            val real = transaction { introspectWahlTable("wahl_stimmzettel_auswahl") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }

            // stimmzettel_id -> wahl_stimmzettel, NOT NULL: default would be "wahl_stimmzettel_id"
            // — plain «Column» attribute pinned instead via «Column».fkEntity.
            real.foreignKeys["stimmzettel_id"] shouldBe "wahl_stimmzettel"
            model.entityNameOf(entity.attributeByName("stimmzettel_id")?.foreignKey?.targetEntityId ?: "") shouldBe "wahl_stimmzettel"

            // option_id -> wahl_option, NOT NULL: default would be "wahl_option_id" — plain
            // «Column» attribute pinned instead via «Column».fkEntity.
            real.foreignKeys["option_id"] shouldBe "wahl_option"
            model.entityNameOf(entity.attributeByName("option_id")?.foreignKey?.targetEntityId ?: "") shouldBe "wahl_option"
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("wahl entity column-name set matches the hand-written WahlTable 1:1") {
            model.entities
                .single { it.name == "wahl" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder WahlTable.columns.map { it.name }
        }

        test("wahl_kandidatur entity column-name set matches the hand-written WahlKandidaturTable 1:1") {
            model.entities
                .single { it.name == "wahl_kandidatur" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder WahlKandidaturTable.columns.map { it.name }
        }

        test("wahl_option entity column-name set matches the hand-written WahlOptionTable 1:1") {
            model.entities
                .single { it.name == "wahl_option" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder WahlOptionTable.columns.map { it.name }
        }

        test("wahl_wahlvorstand entity column-name set matches the hand-written WahlWahlvorstandTable 1:1") {
            model.entities
                .single { it.name == "wahl_wahlvorstand" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder WahlWahlvorstandTable.columns.map { it.name }
        }

        test("wahl_wahlberechtigt entity column-name set matches the hand-written WahlWahlberechtigtTable 1:1") {
            model.entities
                .single { it.name == "wahl_wahlberechtigt" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder WahlWahlberechtigtTable.columns.map { it.name }
        }

        test("wahl_teilnahme entity column-name set matches the hand-written WahlTeilnahmeTable 1:1") {
            model.entities
                .single { it.name == "wahl_teilnahme" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder WahlTeilnahmeTable.columns.map { it.name }
        }

        test("wahl_freigabe entity column-name set matches the hand-written WahlFreigabeTable 1:1") {
            model.entities
                .single { it.name == "wahl_freigabe" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder WahlFreigabeTable.columns.map { it.name }
        }

        test("wahl_stimmzettel entity column-name set matches the hand-written WahlStimmzettelTable 1:1") {
            model.entities
                .single { it.name == "wahl_stimmzettel" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder WahlStimmzettelTable.columns.map { it.name }
        }

        test("wahl_stimmzettel_auswahl entity column-name set matches the hand-written WahlStimmzettelAuswahlTable 1:1") {
            model.entities
                .single { it.name == "wahl_stimmzettel_auswahl" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder WahlStimmzettelAuswahlTable.columns.map { it.name }
        }

        test("wahl.wahl_typ/status/ziel_rolle are modelled as real ErmDataType.Enum columns") {
            // Same gap-closure as MemberStatus/AccountRole/.../ResolutionMode/AbstimmungStatus in
            // the prior domains — with the «Column».sqlType overrides removed, kUML's
            // enum-to-Enum+CHECK fallback path applies.
            val entity = model.entities.single { it.name == "wahl" }
            entity.attributeByName("wahl_typ")?.type shouldBe
                ErmDataType.Enum(
                    name = "WahlTyp",
                    values = listOf("JA_NEIN", "EINZELWAHL", "MEHRFACHWAHL", "LISTENWAHL", "RANGLISTENWAHL"),
                    externalFqName = "network.lapis.cloud.shared.domain.WahlTyp",
                )
            entity.attributeByName("status")?.type shouldBe
                ErmDataType.Enum(
                    name = "WahlStatus",
                    values =
                        listOf(
                            "VORBEREITUNG",
                            "KANDIDATENLISTE_FREIGEGEBEN",
                            "OFFEN",
                            "GESCHLOSSEN",
                            "AUSGEZAEHLT",
                            "ABGEBROCHEN",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.WahlStatus",
                )
            entity.attributeByName("ziel_rolle")?.type shouldBe
                ErmDataType.Enum(
                    name = "GremiumRolle",
                    values = listOf("VORSITZ", "STELLV_VORSITZ", "SCHRIFTFUEHRUNG", "MITGLIED", "BEISITZ"),
                    externalFqName = "network.lapis.cloud.shared.domain.GremiumRolle",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedWahlTable(
    val columns: Map<String, IntrospectedWahlColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedWahlColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors [AbstimmungSchemaDriftTest]'s (private,
 * abstimmung-domain-scoped) `introspectAbstimmungTable`.
 */
private fun JdbcTransaction.introspectWahlTable(tableName: String): IntrospectedWahlTable {
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
            IntrospectedWahlColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedWahlTable(
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
