package network.lapis.cloud.server.audit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * GoBD Unveraenderbarkeit (V0.5.3): a structural, source-text-scan guard that production code
 * (`src/main/kotlin`, never `src/test/kotlin` -- tests legitimately simulate tampering to prove
 * [AuditLogService.verifyChainIntegrity] detects it, see `AuditLogServiceTest`) never calls
 * `AuditLogEntryTable.update(`/`.deleteWhere(` anywhere. [AuditLogRecorder.record] is the sole
 * write path (`INSERT` only) -- see that object's KDoc. Same "structural coverage test, not a
 * behavioral one" idiom as [network.lapis.cloud.server.dsgvo.PersonalDataCoverageTest].
 *
 * Deliberately does NOT flag `AuditLogChainStateTable.update(` -- that table is the genesis-
 * singleton sequence-number/last-hash counter [AuditLogRecorder.record] legitimately advances on
 * every write; it is bookkeeping metadata, not an audit-log entry itself, and is not subject to the
 * same append-only guarantee.
 */
class AuditLogImmutabilityTest :
    FunSpec({
        val mainSourceDir =
            File("src/main/kotlin").let { relative -> if (relative.exists()) relative else File("lapis-server/src/main/kotlin") }

        test("no production source file calls AuditLogEntryTable.update( or AuditLogEntryTable.deleteWhere(") {
            require(mainSourceDir.exists()) { "main source dir not found: ${mainSourceDir.absolutePath}" }

            val forbiddenPatterns = listOf("AuditLogEntryTable.update(", "AuditLogEntryTable.deleteWhere(")
            val offenders =
                mainSourceDir
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .flatMap { file ->
                        file.readLines().mapIndexedNotNull { index, line ->
                            val hit = forbiddenPatterns.firstOrNull { line.contains(it) }
                            if (hit != null) "${file.path}:${index + 1}: matched '$hit'" else null
                        }
                    }.toList()

            offenders.shouldBeEmpty()
        }

        test("AuditLogRecorder.kt contains no update( or deleteWhere( call at all -- INSERT-only against AuditLogEntryTable") {
            val recorderFile = File(mainSourceDir, "network/lapis/cloud/server/audit/AuditLogRecorder.kt")
            require(recorderFile.exists()) { "AuditLogRecorder.kt not found at ${recorderFile.absolutePath}" }
            val lines = recorderFile.readLines()
            // AuditLogChainStateTable.update(...) is the ONE legitimate update() in this file (the
            // genesis-singleton counter, see class KDoc) -- everything else touching
            // AuditLogEntryTable must be an insert.
            val auditLogEntryTableMutations =
                lines.filter { it.contains("AuditLogEntryTable.update(") || it.contains("AuditLogEntryTable.deleteWhere(") }
            auditLogEntryTableMutations.shouldBeEmpty()
        }
    })
