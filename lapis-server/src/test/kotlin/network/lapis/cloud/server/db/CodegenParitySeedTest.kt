package network.lapis.cloud.server.db

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.codegen.m2m.exposed.UmlToExposedViaErmScriptTransformer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import java.io.File

/**
 * ADR-0016 (MDA persistence pipeline) — real codegen verification.
 *
 * Unlike [SchemaDriftTest] and its per-domain siblings (which only run `UmlToErmTransformer` and
 * compare the resulting [dev.kuml.erm.model.ErmModel] shape against the real schema/hand-written
 * tables), this test exercises the **full** `UmlToExposedViaErmScriptTransformer` chain
 * (`UmlToErmTransformer` + `ErmToExposedTransformer`) for every one of the 9 domain scripts under
 * `src/main/kuml/`, writes the generated Kotlin Exposed `Table`/enum sources to
 * `build/generated/kuml/`, and asserts that every domain's *own* (non-stub) entities produced the
 * expected `«kotlinObjectName».kt` file — the mechanical name derived from the plan's Teil 2
 * mapping table, now backed by the (locally published, unreleased) kUML
 * `«Entity».kotlinObjectName` ERM tag instead of the mechanically-derived (and colliding)
 * `toPascalCase(tableName)` default.
 *
 * Package isolation per domain (see the plan's "Kritische Design-Entscheidung"): cross-domain
 * stub entities (e.g. `Member`, `Antrag`, `Sitzung`, `Gremium`, `Beschluss`) are declared in
 * multiple `.kuml.kts` files and, since Teil 2 gives every declaration of the same table the same
 * `kotlinObjectName`, would collide if all 9 domains generated into one shared Kotlin package.
 * Each domain therefore gets its own `network.lapis.cloud.server.db.generated.<suffix>` package
 * (and matching directory under `build/generated/kuml/`), so e.g. `MemberTable.kt` legitimately
 * exists 6 times under 6 different fully-qualified names — once per domain that declares a
 * `Member` entity or stub.
 *
 * `compileKumlGeneratedKotlin` (wired in `lapis-server/build.gradle.kts` as a `check` dependency
 * that runs after `test`) then compiles every written file against an isolated Exposed-only
 * classpath — verification part (a) from the plan ("Kompilierbarkeit"). This test itself covers
 * part (b) ("jede eigene Entität erzeugt die erwartete Datei") via the [Domain.ownEntities]
 * assertions below.
 */
class CodegenParitySeedTest :
    FunSpec({
        for (domain in domains) {
            test(
                "${domain.packageSuffix}: uml-to-exposed-via-erm generates the expected " +
                    "kotlinObjectName-named file for every owning entity",
            ) {
                val scriptFile = File(KumlModelLoader.kumlSourceDir, domain.scriptFileName)
                val diagram = KumlModelLoader.loadUmlDiagram(scriptFile)

                val packageName = "network.lapis.cloud.server.db.generated.${domain.packageSuffix}"
                val result =
                    UmlToExposedViaErmScriptTransformer()
                        .transform(diagram, TransformContext(mapOf("idType" to "uuid", "package" to packageName)))

                val files =
                    when (result) {
                        is TransformResult.Success -> result.output
                        is TransformResult.Failure ->
                            error(
                                "uml-to-exposed-via-erm transform failed for '${scriptFile.path}': " +
                                    result.errors.joinToString("; ") { it.message },
                            )
                    }

                // Part (a) — write every generated file so `compileKumlGeneratedKotlin` (a `check`
                // dependency, see build.gradle.kts) can verify it actually compiles.
                val packagePath = packageName.replace('.', '/')
                val outputDir = File(generatedRoot, packagePath)
                outputDir.mkdirs()
                files.forEach { file -> File(outputDir, file.relativePath).writeText(file.content) }

                // Part (b) — every owning (non-stub) entity produced its expected kotlinObjectName file.
                val fileNames = files.map { it.relativePath }.toSet()
                domain.ownEntities.forEach { (_, objectName) ->
                    fileNames shouldContain "$objectName.kt"
                }
            }
        }
    })

/** One domain script's codegen expectations. */
private data class Domain(
    val scriptFileName: String,
    /** Kotlin package suffix — `network.lapis.cloud.server.db.generated.<packageSuffix>`. */
    val packageSuffix: String,
    /** tableName -> expected kotlinObjectName, restricted to entities *owned* by this domain (excludes stubs). */
    val ownEntities: Map<String, String>,
)

/** The 9 ADR-0016 domain scripts, mirrored 1:1 from the plan's Teil 2/Teil 3 mapping tables. */
private val domains =
    listOf(
        Domain(
            scriptFileName = "00-foundation.kuml.kts",
            packageSuffix = "foundation",
            ownEntities = mapOf("member" to "MemberTable", "account" to "AccountTable"),
        ),
        Domain(
            scriptFileName = "01-contribution.kuml.kts",
            packageSuffix = "contribution",
            ownEntities =
                mapOf(
                    "membership_tier" to "MembershipTierTable",
                    "contribution" to "ContributionTable",
                ),
        ),
        Domain(
            scriptFileName = "02-document.kuml.kts",
            packageSuffix = "document",
            ownEntities =
                mapOf(
                    "document_folder" to "DocumentFolderTable",
                    "document" to "DocumentTable",
                    "document_version" to "DocumentVersionTable",
                ),
        ),
        Domain(
            scriptFileName = "03-communication.kuml.kts",
            packageSuffix = "communication",
            ownEntities =
                mapOf(
                    "mailing_list" to "MailingListTable",
                    "mailing_list_subscription" to "MailingListSubscriptionTable",
                    "mailing_message" to "MailingMessageTable",
                    "mailing_delivery_log" to "MailingDeliveryLogTable",
                    "direct_message" to "DirectMessageTable",
                ),
        ),
        Domain(
            scriptFileName = "04-dsgvo.kuml.kts",
            packageSuffix = "dsgvo",
            ownEntities =
                mapOf(
                    "erasure_request" to "ErasureRequestTable",
                    "dsgvo_audit_log" to "DsgvoAuditLogTable",
                ),
        ),
        Domain(
            scriptFileName = "05-governance.kuml.kts",
            packageSuffix = "governance",
            ownEntities =
                mapOf(
                    "gremium" to "GremiumTable",
                    "gremium_mitgliedschaft" to "GremiumMitgliedschaftTable",
                    "sitzung" to "SitzungTable",
                    "tagesordnungspunkt" to "TagesordnungspunktTable",
                    "anwesenheit" to "AnwesenheitTable",
                    "beschluss" to "BeschlussTable",
                    "antrag" to "AntragTable",
                ),
        ),
        Domain(
            scriptFileName = "06-abstimmung.kuml.kts",
            packageSuffix = "abstimmung",
            ownEntities =
                mapOf(
                    "abstimmung" to "AbstimmungTable",
                    "abstimmung_option" to "AbstimmungOptionTable",
                    "abstimmung_stimme" to "AbstimmungStimmeTable",
                ),
        ),
        Domain(
            scriptFileName = "07-wahl.kuml.kts",
            packageSuffix = "wahl",
            ownEntities =
                mapOf(
                    "wahl" to "WahlTable",
                    "wahl_kandidatur" to "WahlKandidaturTable",
                    "wahl_option" to "WahlOptionTable",
                    "wahl_wahlvorstand" to "WahlWahlvorstandTable",
                    "wahl_wahlberechtigt" to "WahlWahlberechtigtTable",
                    "wahl_teilnahme" to "WahlTeilnahmeTable",
                    "wahl_freigabe" to "WahlFreigabeTable",
                    "wahl_stimmzettel" to "WahlStimmzettelTable",
                    "wahl_stimmzettel_auswahl" to "WahlStimmzettelAuswahlTable",
                ),
        ),
        Domain(
            scriptFileName = "08-ltr-balance.kuml.kts",
            packageSuffix = "ltrbalance",
            ownEntities = mapOf("ltr_balance" to "LtrBalanceTable"),
        ),
    )

/**
 * Output root for generated Exposed sources. Same Gradle-working-directory-ambiguity fix as
 * [KumlModelLoader.kumlSourceDir] (tests sometimes run from the module dir, sometimes from repo
 * root) — probed via the same `src/main/kuml` marker so both paths agree on which case applies.
 */
private val generatedRoot: File =
    if (File("src/main/kuml").exists()) File("build/generated/kuml") else File("lapis-server/build/generated/kuml")
