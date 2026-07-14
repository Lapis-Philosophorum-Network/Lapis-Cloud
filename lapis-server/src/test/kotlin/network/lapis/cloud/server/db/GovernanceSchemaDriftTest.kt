package network.lapis.cloud.server.db

import dev.kuml.erm.model.ErmDataType
import dev.kuml.erm.model.ErmModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.db.generated.AntragTable
import network.lapis.cloud.server.db.generated.AnwesenheitTable
import network.lapis.cloud.server.db.generated.BeschlussTable
import network.lapis.cloud.server.db.generated.GremiumMitgliedschaftTable
import network.lapis.cloud.server.db.generated.GremiumTable
import network.lapis.cloud.server.db.generated.SitzungTable
import network.lapis.cloud.server.db.generated.TagesordnungspunktTable
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — governance domain.
 *
 * Verifies that `lapis-server/src/main/kuml/05-governance.kuml.kts` is a faithful model of both
 * (a) the real, Flyway-migrated H2 schema (`gremium`/`gremium_mitgliedschaft`/`sitzung`/
 * `tagesordnungspunkt`/`anwesenheit`/`beschluss`/`antrag` — V6__governance.sql +
 * V7__antragsverwaltung.sql, plus the beschluss.resolution_mode/abstimmung_id/wahl_id columns
 * added by V8/V9), and (b) the hand-written `GremiumTable`/`GremiumMitgliedschaftTable`/
 * `SitzungTable`/`TagesordnungspunktTable`/`AnwesenheitTable`/`BeschlussTable`/`AntragTable`
 * Exposed objects (the `AbstimmungTable`/`AbstimmungOptionTable`/`AbstimmungStimmeTable` objects
 * in the same hand-written `GovernanceTables.kt` file are OUT of scope here — later
 * abstimmung-domain wave).
 *
 * Mirrors [SchemaDriftTest] (foundation domain), [ContributionSchemaDriftTest] (contribution
 * domain), [DocumentSchemaDriftTest] (document domain), [CommunicationSchemaDriftTest]
 * (communication domain) and [DsgvoSchemaDriftTest] (dsgvo domain) — see [SchemaDriftTest]'s KDoc
 * for the full designModelStrategy option B rationale (verification-only artifact; hand-written
 * `Table` objects remain the actually-compiled/actually-imported-by-N-files runtime artifact).
 */
class GovernanceSchemaDriftTest :
    FunSpec({
        beforeSpec { DatabaseConfig.connect() }

        val scriptFile = File(KumlModelLoader.kumlSourceDir, "05-governance.kuml.kts")
        val model: ErmModel by lazy { KumlModelLoader.loadErmModel(scriptFile) }

        /** Resolves an `ErmForeignKey.targetEntityId` back to its entity name within [model]. */
        fun ErmModel.entityNameOf(entityId: String): String? = entities.firstOrNull { it.id == entityId }?.name

        test("model declares exactly the seven governance entities plus the Member and Document stubs") {
            model.entities.map { it.name }.toSet() shouldBe
                setOf(
                    "member",
                    "document",
                    "gremium",
                    "gremium_mitgliedschaft",
                    "sitzung",
                    "tagesordnungspunkt",
                    "anwesenheit",
                    "beschluss",
                    "antrag",
                )
        }

        // ── (1) Model vs. real H2-migrated schema ───────────────────────────────

        test("gremium table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "gremium" }
            val real = transaction { introspectGovernanceTable("gremium") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys.keys shouldBe emptySet()
        }

        test("gremium_mitgliedschaft table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "gremium_mitgliedschaft" }
            val real = transaction { introspectGovernanceTable("gremium_mitgliedschaft") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // Both FKs match UmlToErmTransformer's association-derived default name exactly
            // ("gremium_id" / "member_id"), so both are real UML associations.
            real.foreignKeys["gremium_id"] shouldBe "gremium"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("gremium_id")?.foreignKey?.targetEntityId ?: "") shouldBe "gremium"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("sitzung table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "sitzung" }
            val real = transaction { introspectGovernanceTable("sitzung") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // gremium_id matches the association-derived default and is a real UML association.
            real.foreignKeys["gremium_id"] shouldBe "gremium"
            model.entityNameOf(entity.attributeByName("gremium_id")?.foreignKey?.targetEntityId ?: "") shouldBe "gremium"

            // The N=4 multi-role-FK-collision case this domain was specifically flagged for in
            // the retrofit plan: all three real member-FKs on this table (called_by,
            // chair_member_id, minute_taker_member_id) are modelled as plain «Column» attributes,
            // NOT UML associations — see the .kuml.kts file header comment for the empirical
            // finding (the FIRST association processed for a (fkClass, refClass) pair always
            // claims the bare "member_id" default regardless of its own role, so no ordering of
            // these three real column names could all be reproduced via real associations) —
            // pinned instead via «Column».fkEntity.
            real.foreignKeys["called_by"] shouldBe "member"
            real.foreignKeys["chair_member_id"] shouldBe "member"
            real.foreignKeys["minute_taker_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("called_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            model.entityNameOf(entity.attributeByName("chair_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
            model.entityNameOf(
                entity.attributeByName("minute_taker_member_id")?.foreignKey?.targetEntityId ?: "",
            ) shouldBe "member"

            // protocol_document_id -> document: plain «Column» attribute (naming-gap: derived
            // default would be "document_id", not "protocol_document_id") — pinned instead via
            // «Column».fkEntity against this file's Document stub.
            real.foreignKeys["protocol_document_id"] shouldBe "document"
            model.entityNameOf(
                entity.attributeByName("protocol_document_id")?.foreignKey?.targetEntityId ?: "",
            ) shouldBe "document"
        }

        test("tagesordnungspunkt table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "tagesordnungspunkt" }
            val real = transaction { introspectGovernanceTable("tagesordnungspunkt") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["sitzung_id"] shouldBe "sitzung"
            model.entityNameOf(entity.attributeByName("sitzung_id")?.foreignKey?.targetEntityId ?: "") shouldBe "sitzung"

            // presenter_member_id -> member: plain «Column» attribute (default would be
            // "member_id", not "presenter_member_id") — pinned instead via «Column».fkEntity.
            real.foreignKeys["presenter_member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("presenter_member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        test("tagesordnungspunkt's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_tagesordnungspunkt_position UNIQUE (sitzung_id, position) — pinned via a
            // class-level «Index» (composite, unique=true), same mechanism as contribution's/
            // document's/communication's own composite UNIQUE constraints.
            val real = transaction { introspectGovernanceTable("tagesordnungspunkt") }
            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("sitzung_id", "position"))

            val entity = model.entities.single { it.name == "tagesordnungspunkt" }
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_tagesordnungspunkt_position" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("sitzung_id")!!.id,
                        entity.attributeByName("position")!!.id,
                    )
            }
        }

        test("anwesenheit table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "anwesenheit" }
            val real = transaction { introspectGovernanceTable("anwesenheit") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["sitzung_id"] shouldBe "sitzung"
            real.foreignKeys["member_id"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("sitzung_id")?.foreignKey?.targetEntityId ?: "") shouldBe "sitzung"
            model.entityNameOf(entity.attributeByName("member_id")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // represented_by_member_id -> member: plain «Column» attribute (default "member_id"
            // already claimed by the real member_id association above) — pinned instead via
            // «Column».fkEntity.
            real.foreignKeys["represented_by_member_id"] shouldBe "member"
            model.entityNameOf(
                entity.attributeByName("represented_by_member_id")?.foreignKey?.targetEntityId ?: "",
            ) shouldBe "member"
        }

        test("anwesenheit's composite UNIQUE constraint is pinned via a class-level «Index»") {
            // uq_anwesenheit_member UNIQUE (sitzung_id, member_id) — pinned via a class-level
            // «Index» (composite, unique=true).
            val real = transaction { introspectGovernanceTable("anwesenheit") }
            real.compositeUniqueConstraints shouldContainExactlyInAnyOrder listOf(setOf("sitzung_id", "member_id"))

            val entity = model.entities.single { it.name == "anwesenheit" }
            entity.attributes.none { it.unique } shouldBe true
            entity.indexes.single { it.name == "uq_anwesenheit_member" }.let {
                it.unique shouldBe true
                it.attributeIds.toSet() shouldBe
                    setOf(
                        entity.attributeByName("sitzung_id")!!.id,
                        entity.attributeByName("member_id")!!.id,
                    )
            }
        }

        test("beschluss table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "beschluss" }
            val real = transaction { introspectGovernanceTable("beschluss") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            real.foreignKeys["sitzung_id"] shouldBe "sitzung"
            real.foreignKeys["tagesordnungspunkt_id"] shouldBe "tagesordnungspunkt"
            model.entityNameOf(entity.attributeByName("sitzung_id")?.foreignKey?.targetEntityId ?: "") shouldBe "sitzung"
            model.entityNameOf(
                entity.attributeByName("tagesordnungspunkt_id")?.foreignKey?.targetEntityId ?: "",
            ) shouldBe "tagesordnungspunkt"

            // recorded_by -> member: plain «Column» attribute (default would be "member_id", not
            // "recorded_by") — pinned instead via «Column».fkEntity.
            real.foreignKeys["recorded_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("recorded_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"

            // abstimmung_id / wahl_id: forward references into the later abstimmung/wahl waves,
            // modelled as plain nullable UUID «Column» attributes rather than pinned via
            // «Column».fkEntity — beschluss<->abstimmung and beschluss<->wahl are both genuinely
            // bidirectional, so the real risk is Kotlin `object`-initializer circularity at the
            // Exposed layer, same reasoning as document.current_version_id. Since the SQL/Flyway
            // baseline is now generated from this same model, the real schema (unlike the
            // pre-swap hand-written V6/V8/V9 migrations) consequently has no FK here either — a
            // deliberate, pre-existing trade-off, not a new regression.
            real.foreignKeys["abstimmung_id"] shouldBe null
            real.foreignKeys["wahl_id"] shouldBe null
            entity.attributeByName("abstimmung_id")?.foreignKey shouldBe null
            entity.attributeByName("wahl_id")?.foreignKey shouldBe null
        }

        test("antrag table shape matches the real migrated schema") {
            val entity = model.entities.single { it.name == "antrag" }
            val real = transaction { introspectGovernanceTable("antrag") }

            entity.attributes.map { it.name }.toSet() shouldBe real.columns.keys
            entity.attributes.forEach { attr ->
                val col = real.columns.getValue(attr.name!!)
                withClue("column '${attr.name}'") {
                    col.nullable shouldBe attr.nullable
                }
            }
            // sitzung_id/tagesordnungspunkt_id/beschluss_id all match the association-derived
            // default and are real UML associations.
            real.foreignKeys["sitzung_id"] shouldBe "sitzung"
            real.foreignKeys["tagesordnungspunkt_id"] shouldBe "tagesordnungspunkt"
            real.foreignKeys["beschluss_id"] shouldBe "beschluss"
            model.entityNameOf(entity.attributeByName("sitzung_id")?.foreignKey?.targetEntityId ?: "") shouldBe "sitzung"
            model.entityNameOf(
                entity.attributeByName("tagesordnungspunkt_id")?.foreignKey?.targetEntityId ?: "",
            ) shouldBe "tagesordnungspunkt"
            model.entityNameOf(entity.attributeByName("beschluss_id")?.foreignKey?.targetEntityId ?: "") shouldBe "beschluss"

            // target_gremium_id / submitter_member_id / reviewed_by: plain «Column» attributes —
            // none match the association-derived default ("gremium_id" / "member_id" / "member_id")
            // — pinned instead via «Column».fkEntity.
            real.foreignKeys["target_gremium_id"] shouldBe "gremium"
            real.foreignKeys["submitter_member_id"] shouldBe "member"
            real.foreignKeys["reviewed_by"] shouldBe "member"
            model.entityNameOf(entity.attributeByName("target_gremium_id")?.foreignKey?.targetEntityId ?: "") shouldBe "gremium"
            model.entityNameOf(
                entity.attributeByName("submitter_member_id")?.foreignKey?.targetEntityId ?: "",
            ) shouldBe "member"
            model.entityNameOf(entity.attributeByName("reviewed_by")?.foreignKey?.targetEntityId ?: "") shouldBe "member"
        }

        // ── (2) Model vs. hand-written Exposed Table objects ────────────────────

        test("gremium entity column-name set matches the hand-written GremiumTable 1:1") {
            model.entities
                .single { it.name == "gremium" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder GremiumTable.columns.map { it.name }
        }

        test("gremium_mitgliedschaft entity column-name set matches the hand-written GremiumMitgliedschaftTable 1:1") {
            model.entities
                .single { it.name == "gremium_mitgliedschaft" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder GremiumMitgliedschaftTable.columns.map { it.name }
        }

        test("sitzung entity column-name set matches the hand-written SitzungTable 1:1") {
            model.entities
                .single { it.name == "sitzung" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder SitzungTable.columns.map { it.name }
        }

        test("tagesordnungspunkt entity column-name set matches the hand-written TagesordnungspunktTable 1:1") {
            model.entities
                .single { it.name == "tagesordnungspunkt" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder TagesordnungspunktTable.columns.map { it.name }
        }

        test("anwesenheit entity column-name set matches the hand-written AnwesenheitTable 1:1") {
            model.entities
                .single { it.name == "anwesenheit" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AnwesenheitTable.columns.map { it.name }
        }

        test("beschluss entity column-name set matches the hand-written BeschlussTable 1:1") {
            model.entities
                .single { it.name == "beschluss" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder BeschlussTable.columns.map { it.name }
        }

        test("antrag entity column-name set matches the hand-written AntragTable 1:1") {
            model.entities
                .single { it.name == "antrag" }
                .attributes
                .map { it.name } shouldContainExactlyInAnyOrder AntragTable.columns.map { it.name }
        }

        // ── (3) Enum-fidelity gap closure ────────────────────────────────────────

        test("gremium.type is modelled as a real ErmDataType.Enum column") {
            // Same gap-closure as all prior domains' enum columns — with the «Column».sqlType
            // override removed, kUML's enum-to-Enum+CHECK fallback path applies.
            val type = model.entities.single { it.name == "gremium" }.attributeByName("type")
            type?.type shouldBe
                ErmDataType.Enum(
                    name = "GremiumType",
                    values = listOf("VORSTAND", "ARBEITSKREIS", "AUSSCHUSS", "SONSTIGES", "MITGLIEDERVERSAMMLUNG"),
                    externalFqName = "network.lapis.cloud.shared.domain.GremiumType",
                )
        }

        test("gremium_mitgliedschaft.rolle is modelled as a real ErmDataType.Enum column") {
            val rolle = model.entities.single { it.name == "gremium_mitgliedschaft" }.attributeByName("rolle")
            rolle?.type shouldBe
                ErmDataType.Enum(
                    name = "GremiumRolle",
                    values = listOf("VORSITZ", "STELLV_VORSITZ", "SCHRIFTFUEHRUNG", "MITGLIED", "BEISITZ"),
                    externalFqName = "network.lapis.cloud.shared.domain.GremiumRolle",
                )
        }

        test("sitzung.format/status are modelled as real ErmDataType.Enum columns") {
            val format = model.entities.single { it.name == "sitzung" }.attributeByName("format")
            val status = model.entities.single { it.name == "sitzung" }.attributeByName("status")
            format?.type shouldBe
                ErmDataType.Enum(
                    name = "SitzungsFormat",
                    values = listOf("PRAESENZ", "ONLINE", "HYBRID"),
                    externalFqName = "network.lapis.cloud.shared.domain.SitzungsFormat",
                )
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "SitzungsStatus",
                    values = listOf("GEPLANT", "DURCHGEFUEHRT", "ABGESAGT"),
                    externalFqName = "network.lapis.cloud.shared.domain.SitzungsStatus",
                )
        }

        test("anwesenheit.status is modelled as a real ErmDataType.Enum column") {
            val status = model.entities.single { it.name == "anwesenheit" }.attributeByName("status")
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "AnwesenheitStatus",
                    values = listOf("ANWESEND", "ENTSCHULDIGT", "UNENTSCHULDIGT", "VERTRETEN"),
                    externalFqName = "network.lapis.cloud.shared.domain.AnwesenheitStatus",
                )
        }

        test("beschluss.status/resolution_mode are modelled as real ErmDataType.Enum columns") {
            val status = model.entities.single { it.name == "beschluss" }.attributeByName("status")
            val resolutionMode = model.entities.single { it.name == "beschluss" }.attributeByName("resolution_mode")
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "BeschlussStatus",
                    values = listOf("ANGENOMMEN", "ABGELEHNT", "VERTAGT"),
                    externalFqName = "network.lapis.cloud.shared.domain.BeschlussStatus",
                )
            resolutionMode?.type shouldBe
                ErmDataType.Enum(
                    name = "ResolutionMode",
                    values = listOf("GREMIUM_QUORUM", "MERITOKRATISCH", "DEMOKRATISCH"),
                    externalFqName = "network.lapis.cloud.shared.domain.ResolutionMode",
                )
        }

        test("antrag.status is modelled as a real ErmDataType.Enum column") {
            val status = model.entities.single { it.name == "antrag" }.attributeByName("status")
            status?.type shouldBe
                ErmDataType.Enum(
                    name = "AntragStatus",
                    values =
                        listOf(
                            "EINGEREICHT",
                            "GEPRUEFT",
                            "ABGELEHNT_VORPRUEFUNG",
                            "TERMINIERT",
                            "BESCHLOSSEN",
                            "ABGELEHNT",
                            "VERTAGT",
                            "ZURUECKGEZOGEN",
                        ),
                    externalFqName = "network.lapis.cloud.shared.domain.AntragStatus",
                )
        }
    })

/** Result of introspecting one real table's shape via `information_schema`, including composite uniques. */
private data class IntrospectedGovernanceTable(
    val columns: Map<String, IntrospectedGovernanceColumn>,
    /** FK column name -> referenced table name. */
    val foreignKeys: Map<String, String>,
    /** Each element is the full column-name set of one multi-column UNIQUE constraint (2+ columns). */
    val compositeUniqueConstraints: List<Set<String>>,
)

private data class IntrospectedGovernanceColumn(
    val nullable: Boolean,
)

/**
 * ANSI `information_schema` walk for a single table's columns, nullability, FK targets and
 * composite UNIQUE constraints. Mirrors [CommunicationSchemaDriftTest]'s (private,
 * communication-domain-scoped) `introspectCommunicationTable`.
 */
private fun JdbcTransaction.introspectGovernanceTable(tableName: String): IntrospectedGovernanceTable {
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
            IntrospectedGovernanceColumn(nullable = nullable)
        }
    val compositeUniques = uniqueColumnsByConstraint.values.filter { it.size > 1 }.map { it.toSet() }
    return IntrospectedGovernanceTable(
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
