package network.lapis.cloud.server.db

import dev.kuml.codegen.m2m.TransformContext
import dev.kuml.codegen.m2m.TransformResult
import dev.kuml.core.model.KumlDiagram
import dev.kuml.core.script.DiagramExtractor
import dev.kuml.core.script.ExtractedDiagram
import dev.kuml.core.script.KumlScriptHost
import dev.kuml.core.script.ScriptEvaluationException
import dev.kuml.erm.model.ErmModel
import dev.kuml.transform.umlerm.UmlToErmTransformer
import java.io.File
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Evaluates a `src/main/kuml/` design model script and runs it through the same
 * `UmlToErmTransformer` (`kuml-transform-uml-to-erm`) chain the ADR-0016 MDA pipeline uses,
 * so [network.lapis.cloud.server.db.SchemaDriftTest] and its per-domain siblings can assert the
 * generated [ErmModel] shape against the real Flyway-migrated schema and the hand-written
 * Exposed `Table` objects.
 *
 * Runs entirely in-process in the test JVM (no Gradle worker/task involved) — this sidesteps the
 * Kotlin-scripting classloader isolation problems `kuml-gradle-plugin`'s `GradlePipeline`
 * documents for its own (unpublished) Gradle plugin: a plain forked JUnit/Kotest `Test` task JVM
 * does not have that parent/child classloader split, so [KumlScriptHost.eval] with its default
 * (`wholeClasspath = true`, no custom base classloader) trusted in-process path works unmodified.
 *
 * Deliberately test-scoped only — see `gradle/libs.versions.toml` for why kUML is never an
 * `implementation`/`api` dependency of `lapis-server`.
 */
internal object KumlModelLoader {
    /** Directory containing every domain's `NN-domain.kuml.kts` design model. */
    val kumlSourceDir: File =
        File("src/main/kuml").let { relative ->
            if (relative.exists()) relative else File("lapis-server/src/main/kuml")
        }

    /**
     * Evaluates [scriptFile] and returns its [KumlDiagram] — throws with a readable message on
     * any script compilation/evaluation error (mirrors `GenerateCommand.runClassicGenerate`'s
     * error handling, minus the CLI-specific `ProgramResult`/`echo` plumbing).
     */
    fun loadUmlDiagram(scriptFile: File): KumlDiagram {
        require(scriptFile.exists()) { "kUML script not found: ${scriptFile.absolutePath}" }
        val evalResult = KumlScriptHost.eval(scriptFile)
        val errors = evalResult.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }
        check(errors.isEmpty() && evalResult !is ResultWithDiagnostics.Failure) {
            "kUML script evaluation failed for '${scriptFile.path}':\n" + errors.joinToString("\n") { it.message }
        }
        val success = evalResult as ResultWithDiagnostics.Success
        val extracted =
            try {
                DiagramExtractor.extractAny(success.value.returnValue, scriptFile)
            } catch (e: ScriptEvaluationException) {
                error("kUML diagram extraction failed for '${scriptFile.path}': ${e.message}")
            }
        check(extracted is ExtractedDiagram.Uml) {
            "Expected a UML class diagram (`classDiagram { … }`) in '${scriptFile.path}', got $extracted"
        }
        return extracted.diagram
    }

    /**
     * Evaluates [scriptFile] and runs it through `UmlToErmTransformer` with `idType=uuid`
     * (Lapis-Cloud's PKs are all `UUID`, matching every hand-written Table's `uuid("id")`).
     */
    fun loadErmModel(scriptFile: File): ErmModel {
        val diagram = loadUmlDiagram(scriptFile)
        return when (val result = UmlToErmTransformer().transform(diagram, TransformContext(mapOf("idType" to "uuid")))) {
            is TransformResult.Success -> result.output
            is TransformResult.Failure ->
                error(
                    "uml-to-erm transform failed for '${scriptFile.path}': " +
                        result.errors.joinToString("; ") { it.message },
                )
        }
    }
}
