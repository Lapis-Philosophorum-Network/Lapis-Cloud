package network.lapis.cloud.server.dsgvo

/**
 * Single compile-time-typed source of truth for which [PersonalDataContributor] owns which
 * personal-data-bearing table — mirrors the explicit `initRpc { registerService(...) }` style in
 * `network.lapis.cloud.server.Application.module`. Export (`DsgvoService.exportManifest` / the
 * HTTP export route) and erasure (`DsgvoService.executeErasure`) both iterate [contributors]
 * inside one `transaction {}`, so a future domain area (e.g. an "events" wave) automatically
 * participates in both once it registers here.
 *
 * **This list, by itself, does not prevent rot.** Forgetting to add a new
 * [PersonalDataContributor] here compiles fine. The actual enforcement mechanism is
 * `PersonalDataCoverageTest`: it walks `information_schema` for every foreign key referencing
 * `member(id)` and asserts each `(table, column)` is inside some contributor's `coveredTables`
 * or explicitly listed in [noPersonalDataAllowlist] with a written reason. The day a later wave
 * adds e.g. `event_registration.member_id`, that test goes red until someone writes an
 * `EventPersonalData` contributor (or allowlists it) — `./gradlew clean check` enforces it, a
 * hand-maintained table list alone could not.
 */
object PersonalDataRegistry {
    val contributors: List<PersonalDataContributor> =
        listOf(
            FoundationPersonalData,
            ContributionPersonalData,
            DocumentPersonalData,
            CommunicationPersonalData,
        )

    /**
     * Tables that are not covered by a [PersonalDataContributor] on purpose, each with a written
     * reason — checked by `PersonalDataCoverageTest` alongside [contributors]. `member` itself is
     * *not* listed here: it is covered by [FoundationPersonalData.coveredTables] even though it
     * is the PK side, not the FK side, of every relationship (see that object's KDoc).
     */
    val noPersonalDataAllowlist: Map<String, String> =
        mapOf(
            "membership_tier" to "Reine Produktdefinition (Beitragshoehe/-intervall), kein Personenbezug.",
            "document_folder" to "Reine Organisationsstruktur, kein Personenbezug.",
            "erasure_request" to
                "Verwaltet den Loeschprozess selbst und referenziert Mitglieder nur per UUID. Bleibt " +
                "nach der Loeschung als Verfahrensnachweis bestehen (siehe dsgvo.adoc).",
            "dsgvo_audit_log" to
                "Bewusst NICHT vom Loesch-Walk erfasst: referenziert das Subjekt nur per UUID, " +
                "Rechenschaftspflicht (Art. 5 Abs. 2 DSGVO) ist eigene Rechtsgrundlage fuer die " +
                "Aufbewahrung. Siehe dsgvo.adoc \"Audit-Log-Datenschutz\".",
        )

    init {
        val owners = mutableMapOf<String, PersonalDataContributor>()
        for (contributor in contributors) {
            for (table in contributor.coveredTables) {
                val tableName = table.tableName
                val existingOwner = owners[tableName]
                check(existingOwner == null) {
                    "Table '$tableName' is covered by both '${existingOwner?.sectionKey}' and " +
                        "'${contributor.sectionKey}' PersonalDataContributor -- each table may be " +
                        "covered by exactly one contributor."
                }
                owners[tableName] = contributor
            }
        }
    }

    /** Every table name (lowercase, matching Exposed's [org.jetbrains.exposed.v1.core.Table.tableName]) any contributor owns. */
    fun coveredTableNames(): Set<String> = contributors.flatMap { it.coveredTables }.map { it.tableName }.toSet()
}
