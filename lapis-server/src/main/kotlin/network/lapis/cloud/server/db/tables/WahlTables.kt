package network.lapis.cloud.server.db.tables

import network.lapis.cloud.shared.domain.GremiumRolle
import network.lapis.cloud.shared.domain.WahlStatus
import network.lapis.cloud.shared.domain.WahlTyp
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime

/**
 * Demokratische Wahlen (V0.2.4) -- hand-written, following the same
 * `object : Table`/Flyway-owns-schema pattern as [GovernanceTables] (the `uml-to-exposed` MDA
 * pipeline does not exist for this repository, see CLAUDE.md "Vorab-Befund"). Schema lives in
 * `V9__demokratische_wahlen.sql`.
 *
 * Table-creation order in that migration deliberately differs from a naive "wahl, wahl_option,
 * wahl_kandidatur, ..." reading: [WahlOptionTable.kandidaturId] references
 * [WahlKandidaturTable], so `wahl_kandidatur` must exist *before* `wahl_option` is created, not
 * after.
 *
 * **Ballot secrecy is a practical DB-level table split, not cryptography.** No homomorphic
 * encryption/mix-net/threshold-signature scheme exists anywhere in this codebase yet (confirmed
 * by grep at plan time) -- secrecy for a `geheim` Wahl is achieved by [WahlStimmzettelTable]
 * having no FK-joinable link back to [WahlTeilnahmeTable] (the "this member voted" proof), not by
 * unlinkability guaranteed by cryptography. This is explicitly *not* Helios/Belenios-grade
 * end-to-end verifiability; full crypto stays future work (see
 * `03 Bereiche/Lapis Cloud/Demokratische Wahlen.md` "Offene Fragen").
 *
 * **One-member-one-vote enforcement differs between the two ballot paths on purpose:**
 * - Non-secret (`geheim = false`) Wahlen: [WahlStimmzettelTable.memberId] is set, and the
 *   migration's `UNIQUE (wahl_id, member_id)` constraint on `wahl_stimmzettel` is the DB-level
 *   backstop -- standard SQL NULL semantics make this constraint a no-op for NULL `member_id`
 *   rows, which is fine because those never occur on this path.
 * - Secret (`geheim = true`) Wahlen: [WahlStimmzettelTable.memberId] is always `NULL` (the whole
 *   point), so `wahl_stimmzettel`'s unique constraint cannot help here -- linking it to
 *   `member_id` would defeat the anonymity purpose. Instead, [WahlService.castStimme] inserts
 *   into [WahlTeilnahmeTable] (which *does* carry `member_id`, proving "voted" without proving
 *   "voted for what") first, inside the same transaction as the [WahlStimmzettelTable] insert;
 *   `wahl_teilnahme`'s own `UNIQUE (wahl_id, member_id)` constraint is the real DB-level backstop
 *   for the secret path, one table removed from the anonymous ballot itself. A caught
 *   `ExposedSQLException` on that insert (not merely the preceding application-level
 *   existence check, which is racy under concurrency on its own) is what turns a concurrent
 *   double-vote attempt into a rejected `ConflictException` -- see `WahlServiceTest`'s
 *   concurrency test.
 */
object WahlTable : Table("wahl") {
    val id = uuid("id")
    val antragId = uuid("antrag_id").references(AntragTable.id)
    val sitzungId = uuid("sitzung_id").references(SitzungTable.id)
    val title = varchar("title", 300)
    val wahlTyp = enumerationByName<WahlTyp>("wahl_typ", 20)
    val geheim = bool("geheim")
    val sitzeCount = integer("sitze_count")

    // Nullable: null for WahlTyp.JA_NEIN, which seats nobody. Required for personnel WahlTyp
    // (enforced by WahlService.openWahl, not the DB) -- see WahlOpenInput KDoc for why this can
    // differ from the hosting Antrag's own targetGremiumId (e.g. a Mitgliederversammlung-hosted
    // Antrag electing the Vorstand).
    val zielGremiumId = uuid("ziel_gremium_id").references(GremiumTable.id).nullable()
    val zielRolle = enumerationByName<GremiumRolle>("ziel_rolle", 20).nullable()
    val requiredMajorityPercent = integer("required_majority_percent")
    val status = enumerationByName<WahlStatus>("status", 30)
    val openedBy = uuid("opened_by").references(MemberTable.id)
    val openedAt = datetime("opened_at")
    val candidateListApprovedAt = datetime("candidate_list_approved_at").nullable()
    val votingOpenedAt = datetime("voting_opened_at").nullable()
    val votingClosedAt = datetime("voting_closed_at").nullable()
    val tallyThreshold = integer("tally_threshold")
    val tallyRunAt = datetime("tally_run_at").nullable()
    val beschlussId = uuid("beschluss_id").references(BeschlussTable.id).nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * One member's candidacy (self-nomination only, this wave -- see [WahlService] KDoc). No unique
 * constraint on `(wahl_id, member_id)`, deliberately mirroring [GremiumMitgliedschaftTable]'s own
 * documented precedent: a withdrawn Kandidatur must not block the same member from re-submitting
 * later, and an unconditional DB unique constraint (unlike the historically-shaped
 * `abstimmung_stimme`/`anwesenheit` uniques, which are current-state-per-member) cannot express
 * "unique only among non-withdrawn rows" without a partial index. The application-level guard
 * lives in `WahlService.submitKandidatur` ("no existing *active* candidacy").
 */
object WahlKandidaturTable : Table("wahl_kandidatur") {
    val id = uuid("id")
    val wahlId = uuid("wahl_id").references(WahlTable.id)
    val memberId = uuid("member_id").references(MemberTable.id)
    val motivationText = varchar("motivation_text", 1000).nullable()
    val submittedAt = datetime("submitted_at")
    val withdrawnAt = datetime("withdrawn_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * A ballot-selectable option -- fixed JA/NEIN/ENTHALTUNG rows for [network.lapis.cloud.shared
 * .domain.WahlTyp.JA_NEIN] (created by `WahlService.openWahl`), or one row per approved
 * [WahlKandidaturTable] ([kandidaturId] set, created by `WahlService.freigebenKandidatenliste`).
 * No personal data of its own -- [kandidaturId] is the only member-bearing reference, one hop
 * away (see [network.lapis.cloud.server.dsgvo.PersonalDataRegistry.noPersonalDataAllowlist]).
 */
object WahlOptionTable : Table("wahl_option") {
    val id = uuid("id")
    val wahlId = uuid("wahl_id").references(WahlTable.id)
    val label = varchar("label", 200)
    val position = integer("position")
    val kandidaturId = uuid("kandidatur_id").references(WahlKandidaturTable.id).nullable()

    override val primaryKey = PrimaryKey(id)
}

/** Vier-Augen-Wahlvorstand appointment -- see [WahlTable] KDoc and `WahlAuthorization.kt`. */
object WahlWahlvorstandTable : Table("wahl_wahlvorstand") {
    val id = uuid("id")
    val wahlId = uuid("wahl_id").references(WahlTable.id)
    val memberId = uuid("member_id").references(MemberTable.id)
    val appointedAt = datetime("appointed_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Eligibility snapshot taken once at `WahlService.openVoting` -- frozen at that moment so
 * membership churn during the voting window cannot add or remove voters mid-election (documented
 * decision point, see `WahlDto` / concept document "Offene Fragen"). `WahlService.castStimme`
 * checks against this table, never a live re-query of Gremium/Mitgliederversammlung membership.
 */
object WahlWahlberechtigtTable : Table("wahl_wahlberechtigt") {
    val id = uuid("id")
    val wahlId = uuid("wahl_id").references(WahlTable.id)
    val memberId = uuid("member_id").references(MemberTable.id)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Proves "this member voted" without carrying any vote content -- the GEHEIM-path backstop
 * described in [WahlTable] KDoc. Always populated when [WahlTable.geheim] is `true`; never
 * populated on the non-secret path (that path relies on [WahlStimmzettelTable]'s own unique
 * constraint instead).
 */
object WahlTeilnahmeTable : Table("wahl_teilnahme") {
    val id = uuid("id")
    val wahlId = uuid("wahl_id").references(WahlTable.id)
    val memberId = uuid("member_id").references(MemberTable.id)
    val votedAt = datetime("voted_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * One Vier-Augen-Prinzip approval by a named [WahlWahlvorstandTable] member to permit
 * `WahlService.auszaehlen` to run -- a plain N-of-M approval count, not a cryptographic
 * threshold-signature scheme (see [WahlTable] KDoc "Offene Fragen" cross-reference).
 */
object WahlFreigabeTable : Table("wahl_freigabe") {
    val id = uuid("id")
    val wahlId = uuid("wahl_id").references(WahlTable.id)
    val memberId = uuid("member_id").references(MemberTable.id)
    val approvedAt = datetime("approved_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * The ballot itself. [memberId] is `NULL` when [WahlTable.geheim] is `true` -- the whole point,
 * see [WahlTable] KDoc. [receiptCode] is always generated (even on the non-secret path, for
 * schema uniformity) but only ever returned to the caller when the Wahl is secret -- see
 * `WahlService.castStimme`. Generated via `SecureRandom` with >=128 bits of entropy, checked
 * against a small in-transaction retry loop for the astronomically unlikely collision case.
 *
 * [castAt] is coarsened to the calendar date (time-of-day zeroed) whenever [WahlTable.geheim] is
 * `true` -- deliberately *not* the same instant written to [WahlTeilnahmeTable.votedAt] for that
 * member's vote. Ballots are inserted one row at a time, so a bit-identical timestamp on both
 * rows would let anyone join `voted_at = cast_at` and re-link a "secret" ballot straight back to
 * its voter, defeating the whole point of the table split described above. Non-secret ballots
 * keep full timestamp precision -- `memberId` is already stored in the clear there, so there is
 * no timing correlation to protect against.
 */
object WahlStimmzettelTable : Table("wahl_stimmzettel") {
    val id = uuid("id")
    val wahlId = uuid("wahl_id").references(WahlTable.id)
    val memberId = uuid("member_id").references(MemberTable.id).nullable()
    val receiptCode = varchar("receipt_code", 40)
    val castAt = datetime("cast_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * Child rows: which option(s) a ballot selected -- more than one row per
 * [WahlStimmzettelTable] only for [network.lapis.cloud.shared.domain.WahlTyp.MEHRFACHWAHL]
 * (up to `sitzeCount` distinct options). No `member_id` of its own -- only reachable via
 * [stimmzettelId]/[optionId], both of which resolve to member data at most one hop further away
 * (see [network.lapis.cloud.server.dsgvo.PersonalDataRegistry.noPersonalDataAllowlist]).
 */
object WahlStimmzettelAuswahlTable : Table("wahl_stimmzettel_auswahl") {
    val id = uuid("id")
    val stimmzettelId = uuid("stimmzettel_id").references(WahlStimmzettelTable.id)
    val optionId = uuid("option_id").references(WahlOptionTable.id)

    override val primaryKey = PrimaryKey(id)
}
