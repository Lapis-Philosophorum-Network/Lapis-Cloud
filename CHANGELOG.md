# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and adheres to
[Semantic Versioning](https://semver.org/).

## [0.6.5] — 2026-07-22

### Added

**Price-Oracle fuer die Anker-Bindung** — the first real money-to-LTR conversion boundary this codebase has ever had. Three independent, free, no-API-key public exchange feeds (Coinbase, Kraken, Bitstamp) are queried in parallel for the current BTC/donation-currency price; a provisional median is computed, any source deviating beyond a configurable outlier threshold (basis points) is dropped, and if the surviving sources' own spread is still too wide the quote is rejected rather than trusted. A quote is `LIVE` when every configured source agreed, `DEGRADED` when a reduced-but-sufficient quorum survived, or `CACHED` when a live quorum could not be reached but a still-fresh cached price (within a configurable TTL) was used instead. All of this is governed by a single-row, ADMIN-tunable `price_oracle_config` (anchor asset, donation currency, the peg itself, cache TTL, quorum, outlier/spread thresholds) — seeded with sane BTC/EUR defaults on first migration, retunable via `updateOracleConfig`.

**Load-bearing `convertDonationToLtr`** — a TREASURER/BOARD/ADMIN-gated RPC that books an already-received donation: it fetches a current oracle quote for the active anchor and, if (and only if) it is not halted, MINTs the computed LTR amount into the recipient's ledger (a real `MINT` `ltr_ledger_entry` row) and writes a permanent `price_oracle_conversion` provenance row in the same transaction, capturing the donation amount/currency, the anchor and median price actually used, the peg snapshot, the resulting LTR amount, the quote's status, how many and which sources contributed, and the price's own timestamp — the full audit trail the concept's "Trust und Auditierbarkeit" section calls for. A diagnostic `previewCurrentPrice` lets an operator check oracle health without minting anything.

### Security

Every oracle source resolves against a compile-time-fixed hostname allowlist (`api.coinbase.com`/`api.kraken.com`/`www.bitstamp.net`) — `price_oracle_config` carries no URL/host/source field at all, so an ADMIN can retune policy but can never point a source at an arbitrary host. Every outbound request is HTTPS-only (enforced by the same allowlist guard), never follows redirects, carries bounded connect/request/socket timeouts, and caps the response body it will ever read into memory at 64 KiB, discarding anything larger unparsed. A source failure (network error, timeout, non-2xx status, unparseable body, oversized body, a non-allowlisted URL) is caught inside the source and mapped to `null` — one misbehaving source can never abort the others' fan-out or crash the RPC call — and only a sanitized message (source id plus exception class name) is ever logged, never a response body or raw exception message.

### Known limitations (tracked for later versions)

- No persistent halt-queue: the concept document's deferred/retroactive-vs-forward re-pricing mechanism is not built. HALT instead rejects `convertDonationToLtr` outright (mints nothing) — `PriceStatus.DEFERRED` is defined but reserved-and-unused, so a later wave can add the queue without an enum-literal-order-breaking re-model.
- Bitcoin-only anchor: `AnchorAsset.GOLD_XAU`/`FIAT` are defined enum literals with no wired price sources. A robust, free, no-API-key, multi-source feed set for spot gold needs paid API keys this codebase has no secret-management story for yet, and fiat cross-rates have essentially one authoritative free source (ECB reference rates) — a degenerate single-source case that cannot satisfy the quorum design leans on. `updateOracleConfig` rejects any non-Bitcoin anchor with a clear "not yet implemented" error rather than silently accepting a config with no sources behind it.
- The quote cache is in-memory and per-server (per JVM), not shared/federated — a multi-server deployment does not share a warm cache. Tracked for V0.7 (federation).
- `convertDonationToLtr` is an operator-triggered booking of an already-received donation, not a payment-gateway/PSP-webhook intake — no automatic donation-detection integration exists or is planned this wave.
- The seeded `anchor_units_per_ltr` peg (1 LTR = 0.000001 BTC) is a placeholder; an ADMIN must set the real peg via `updateOracleConfig` before any conversion should be trusted in production.
- Source governance (a board/meritocratic choice of which price sources to trust, per the concept's own open point) is deferred — the source set is installation-fixed for now, changeable only by shipping new code.
- This wave's build could not be verified with a real `./gradlew clean check` run in the authoring sandbox: the pinned Gradle 9.6.1 wrapper distribution download redirects to a host outside this session's GitHub repo scope and is denied by the egress proxy, and the only locally available Gradle (8.14.3) cannot configure this build's Kilua/KVision plugins under any available JDK — a known, previously reproduced environment gap, not specific to this wave. Correctness was instead verified by careful manual read-through (types, imports, brace/paren balance, Exposed query correctness, import completeness, kUML-model-to-generated-table consistency). A real `./gradlew clean check` run in an actual CI/dev environment is still required before this wave is considered fully done.
- V0.6.2 (Auction) remains blocked on legal review and is not part of this release. V0.6.4 (Politician Profiles/Ranking, below) was merged onto `master` alongside this wave — both were authored independently off the same V0.6.3 base and neither has been build-verified with a real Gradle 9.6.1 run yet (see that wave's own build verification note).

## [0.6.4] — 2026-07-22

### Added

**Politiker-Profile und Politiker-Ranking** — an explicit, member-only Like/Dislike ranking layer for politicians, built on the LTR ledger introduced in V0.6.1. A BOARD/ADMIN grants/revokes `PoliticianProfile` status per member (upsert-by-member semantics: a re-grant after revocation reactivates the same profile row rather than creating a second one, starting back at Korb=0 with no persisted rating history — "kein strategischer Vorteil durch Status-Reset"); any `AKTIV` member can cast one Like/Dislike per politician. Trust weight is computed as a **single shared LTR pool** — the current free-LTR balance of every distinct rater across every active politician, summed once per person — apportioned across politicians in proportion to their basket (`likes - dislikes`, floored at zero) via the existing `LargestRemainderApportionment` (the same rounding-exact allocator V0.6.1's monthly EUR distribution already uses). This is recomputed fresh on every read, never cached: because the pool is shared, any single rater's balance change moves every active politician's number simultaneously, so there is no valid incremental-update strategy. `OrganizationSettings.politicianRankingEnabled` (default off) gates every endpoint, including granting status itself. A manually-triggered, idempotent-per-month `snapshotWeights` action (BOARD/ADMIN, same "no scheduler infrastructure" precedent as the monthly EUR distribution) persists a historical trend line into `PoliticianWeightSnapshot`. Revoking status deletes all of that politician's ratings and snapshots outright ("Bewertungsstatistik wird gelöscht"); the profile row itself, and who granted/revoked it and when, is retained. `getTopPoliticians` backs the dashboard's Top‑6 view.

A new batched `LtrBalanceProvider.freeBalances` reads every rater's balance in one query instead of one query per rater; `CrowdfundingService`'s originally-private active-membership check was lifted into a shared `MembershipGuards` helper so both services use the identical gate. Concurrent revoke-vs-rate on the same politician is serialized with a `SELECT ... FOR UPDATE` row lock on the profile, so a vote can never survive alongside a `FORMER` status.

### Fixed

- **`snapshotWeights` is now idempotent per (politician, period) via per-row `insertIgnore`, not an all-or-nothing pre-check.** The initial implementation blocked the *entire* call with a `Conflict` if any active politician already had a snapshot for the period, which both made it impossible to backfill a politician granted status after the first snapshot run for that month, and reintroduced a check-then-insert TOCTOU race two concurrent BOARD calls could hit. Now mirrors `CrowdfundingService.computeMonthlyDistribution`'s existing per-row `insertIgnore` idiom exactly: existing rows for the period are left untouched, missing rows are inserted, and the call is safe to re-run at any time.
- **`politicianRankingEnabled` was never actually wired into `OrganizationSettingsService` — the whole feature was unreachable through the real API.** `updateOrganizationSettings` wrote every other field from the input into the row but silently dropped `politicianRankingEnabled`, and `toOrganizationSettingsDto` never read the column back — so an ADMIN toggling the feature on had the write discarded, and `getOrganizationSettings()` always reported it as `false` regardless of actual DB state. Every `PoliticianService` method, including `grantPoliticianStatus` itself, gates on this same flag and would therefore always reject with `Conflict` in a real deployment; there is no other RPC endpoint that can set it. This was invisible in the existing test suite only because `PoliticianServiceTest` flips the flag via a direct DB transaction, bypassing `OrganizationSettingsService` entirely. Fixed by wiring the field into both the update and read path, and adding the analogous `OrganizationSettingsServiceTest` update→get round-trip test the sibling `postalMailEnabled`/`isPoliticalParty` fields already had (round 2 review finding).
- **`grantPoliticianStatus`'s first-grant race could surface a raw DB unique-constraint failure instead of the idempotent-upsert behavior its own contract promises.** `SELECT ... FOR UPDATE` locks an existing profile row, but locks nothing when no row exists yet — two concurrent grants for the same never-before-politician member could both observe `existing == null` and both attempt the insert, racing on `uq_politician_profile_member`. Now catches the resulting `ExposedSQLException` (same "pre-check + backstop" idiom `AccountingService.createLedgerAccount` already uses) and retries once in a fresh transaction, so the race loser converges to the same `ACTIVE` profile the winner just committed instead of a 500 (round 2 review finding; low real-world likelihood given this is a privileged BOARD/ADMIN-only action, but now covered by a dedicated concurrency test).

### Known limitations (tracked for later versions)

- **Guest (Gast) rating basket is entirely cut.** The concept's Mitglied/Gast two-basket mechanic needs an operational Gast identity that does not exist anywhere in this codebase yet (`MemberStatus.GAST` is an inert enum literal nothing currently sets or transitions into) — building a permanently-empty guest basket against it would be decorative, not functional. `PoliticianProfileDto` has `memberTrustWeight` only; a future wave adds `guestTrustWeight`/`combinedTrustWeight` additively once a real Gast identity model lands. **Flagged by round 2 review as a material narrowing of a headline spec feature (the spec's whole point is separating Mitglieder- from Gästebewertung) — this cut still needs an explicit product-owner sign-off, not just this changelog note, before it can be considered accepted rather than merely documented.**
  - **Round 4 fix-agent status (unresolved by design, not by omission):** round 3 review confirmed the round-3 fix only restated this requirement in changelog prose and asked for either an actual code change or the sign-off itself. Neither is available to an autonomous fix pass: adding `guestTrustWeight`/`combinedTrustWeight` fields now, with no member ever in `GAST` status and no anonymous/non-member rating path in the auth layer, would only ever compute to zero/`memberTrustWeight` — i.e. exactly the "decorative, not functional" shape this same note already rejects one paragraph up, dressed up as API completeness. And `MemberStatus` itself is documented as a `V0.1.2`-`V0.1.4` **stub** (`Foundation.kt`: "Mitglieder-Stammdaten, Beitritts-/Austrittsworkflow, Auth/Session do not exist yet") — an operational Gast/exit workflow is a cross-cutting prerequisite bigger than this wave, not a corner that can be closed inside a review-fix round. This is therefore recorded here as a genuinely open product decision requiring the product owner directly, and is now also cross-referenced from `README.adoc`'s Status section (not only this changelog) so it cannot be missed. It is **not** silently treated as accepted by shipping V0.6.4.
- **Real-name enforcement is a documented no-op.** This codebase has no pseudonym-display layer to override in the first place — every member, politician or not, is already always shown under `displayName`.
- No comment/discussion feed under a politician profile (mirrors the same, already-established cut for Crowdfunding projects) — this wave is a data/ranking feature only.
- Historical trend snapshots are manually triggered (BOARD/ADMIN), not automatic — no scheduler/cron infrastructure exists anywhere in this codebase yet, the same limitation the V0.6.1 monthly EUR distribution already lives with.
- No self-rating prevention, mirroring the existing, unguarded `CrowdfundingService.castReaction` precedent.
- No LTR auction yet — the remaining item of the V0.6 LTR-economy arc (ledger, internal crowdfunding, direct peer-to-peer transfer, and now politician profiles/ranking are done).
- Federation (multi-server operation) is not yet built — planned for V0.7.

### Build verification note (round 4)

`./gradlew clean check` could not be run to completion in the round 3 review sandbox nor in the round 4 fix-agent sandbox — independently reproduced, same root cause, with the exact failure now pinned down precisely:

- `gradle-wrapper.properties` requires Gradle **9.6.1**; downloading it fails with HTTP 403 from `services.gradle.org` under both sandboxes' organization egress policy. Root cause pinned down precisely in round 4: `services.gradle.org` itself replies with an HTTP 307 redirect to `https://github.com/gradle/gradle-distributions/releases/download/v9.6.1/gradle-9.6.1-bin.zip` (Gradle's actual binaries are GitHub-Release-hosted, fronted by that vanity domain) — and the sandbox's GitHub-aware egress proxy then rejects *that* request specifically because `gradle/gradle-distributions` is not one of this session's authorized repo sources (`"GitHub access to this repository is not enabled for this session. Use add_repo to request access."`). Confirmed not fixable mid-session: the session's GitHub proxy tier is scoped to this project's own repo owners and explicitly refuses a cross-owner `add_repo` request for an unrelated third-party repo like `gradle/gradle-distributions`. This is a policy/scoping gap, not a proxy misconfiguration or transient outage — a future session (or CI) would need either a non-GitHub-redirecting Gradle distribution mirror, a pre-provisioned Gradle 9.6.1 install, or this specific repo allow-listed.
- The only locally available Gradle is **8.14.3**, and it cannot configure this build on *either* available local JDK:
  - Under JDK 25: fails during build-script (Kotlin DSL) compilation itself, before any project logic runs — `java.lang.IllegalArgumentException: 25.0.3` thrown from `org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse`, i.e. Gradle 8.14.3's *bundled* Kotlin-DSL compiler does not recognize a JDK 25 version string yet.
  - Under JDK 21: the build script compiles, but dependency resolution then rejects the root project's own plugins — `io.kvision:kvision-gradle-plugin:9.6.0` and `dev.kilua:kilua-rpc-gradle-plugin:0.0.45` both declare `Dependency requires at least JVM runtime version 25`.
  - Net effect: Gradle 8.14.3 has no available JDK combination in this environment that satisfies both its own Kotlin-DSL compiler and this project's plugin requirements — only Gradle 9.6.1 (blocked from downloading, see above) resolves this.

This is an environment/tooling limitation of both review/fix sandboxes, not a defect in this wave's diff — static review of the changes found nothing that would newly break compilation. It still needs to be run and confirmed green in a real CI/dev environment with Gradle 9.6.1 available before this wave is merged past this branch.

### Note on merging onto `master` alongside V0.6.5

This wave's kUML domain model file was renumbered from its original `19-politician.kuml.kts` to `20-politician.kuml.kts` when consolidated onto `master`: both this wave and V0.6.5 were authored independently off the same V0.6.3 base and each claimed the next-free slot `19` for its own domain model, which only became a naming collision once both landed on the same branch. No functional code changed as part of the rename — see `DomainModelMergerTest`'s updated counts (66 distinct tables across 21 domain scripts, combining both waves' additions).

## [0.5.1] — 2026-07-21

### Added

Completes the V0.5 compliance bundle that 0.5.0 deliberately narrowed in scope — the three remaining items from that release's "known limitations" list.

**GoBD audit log** — a hash-chained (SHA-256), append-only `AuditLogEntry` log written in the same transaction as the business mutation it records, serialized via a genesis-singleton `AuditLogChainState` row (`SELECT ... FOR UPDATE`). Covers the JournalEntry lifecycle (draft/post), Resolution creation, BoardMembership changes, and PartyDonationCompliance verdicts for postings that actually committed. Deliberately out of scope: ledger/cost-center master-data CRUD, DSGVO erasure (has its own separate, unchanged `dsgvo_audit_log`), and any retention/archival policy. Read access is TREASURER/BOARD/ADMIN-gated with capped pagination; before/after snapshots are excluded from a member's own GDPR export.

**Full-organization backup/restore/export** — an ADMIN-only, streamed ZIP export/restore covering every table in the schema (discovered dynamically via `information_schema`, not a hand-maintained list — any table a future domain wave adds is automatically in scope) plus document blobs. Export streams row-by-row without materializing the database in memory; restore is upsert-based, gated by a formatVersion + SHA-256 schema-checksum compatibility check and a non-empty-target pre-flight guard against accidental cross-organization merges. Zip-Slip is guarded on both the export and restore paths. Infrastructure-level backup (`pg_dump`/WAL archiving) remains explicitly out of scope — an operations concern, not solved here.

**DSGVO-Vollausbau (AVV, TOMs, DSFA, Datenpannenmeldung)** — four record-keeping/workflow tools, none of them automated legal advice: an AVV register for third-party processors (status/dates/document reference, coupled to the existing postal-mail opt-in only as a non-blocking advisory log, never a hard gate); TOM documentation across the eight Art. 32 / Anlage §64 BDSG categories; a DPIA template where the required-or-not verdict is always a stored human judgment (a `DpiaRiskMatrix` helper only renders a display band, it never decides); and a data-breach-incident workflow that surfaces the Art. 33 72-hour clock as a read-time warning without ever auto-filing a notification. Authorization is ADMIN-only for AVV/TOM writes, BOARD/ADMIN for DPIA/breach read and write.

### Known limitations (tracked for later versions)

- No LTR economy yet (internal crowdfunding, auction, direct transfer, politician profiles/ranking) — planned for V0.6.
- Federation (multi-server operation) is not yet built — planned for V0.7.
- Audit log's hash chain is plain SHA-256 (no HMAC/external anchoring) and immutability is enforced only at the application layer (no DB-level UPDATE/DELETE grant restriction) — both accepted, documented residual risks, not defects against this wave's own requirements.
- Backup/restore has no decompression-ratio/zip-bomb cap beyond the 512 MiB compressed-upload limit — low severity given the actor is already ADMIN-only.

## [0.5.0] — 2026-07-19

### Added

**§25 PartG donation-acceptance check** — a pure, DB-free `PartyDonationComplianceCalculator` (same idiom as `JournalEntryBalance`/`UseOfFundsCalculator`) returning ALLOWED/PROHIBITED verdicts plus additional-duty flags (anonymous-forwarding, prompt Bundestag report, annual Rechenschaftsbericht disclosure) for donations to political parties, with all thresholds as named constants explicitly flagged as current understanding requiring legal verification. The accounting model gains an `ExternalDonor` entity and `DonorCategory` enum so a `JournalEntry` can attribute a donation to a non-member donor (mutually exclusive with the existing `donorMemberId`). The check is hooked into `postJournalEntry`/`postDraftEntry`, gated strictly on `OrganizationSettings.isPoliticalParty`, hard-blocking PROHIBITED donations while never blocking ALLOWED-with-duties postings. A new read-only, TREASURER/BOARD/ADMIN-gated report lists open prompt-report and annual-disclosure duties for a given calendar year.

**§20 GwG Transparenzregister board-change reminders** — a queryable board roster with history (`BoardMembership`: member, committee role, start/end), written in lockstep with the existing `CommitteeMembership` seating at election-tally time and via a new manual appoint/end-membership action for co-options, resignations, and recalls that don't go through a fresh election. `Member` gains the two missing beneficial-owner fields (date of birth, nationality), both nullable and covered by GDPR export/erasure. A persisted `TransparenzregisterReminder` log records every JOINED/LEFT board-change event, plus a read-only report of open reminders and members still missing beneficial-owner data — reminder/acknowledgement only, no automated filing to transparenzregister.de (no suitable public API exists). Unlike the PartG check, this duty is **not** gated on `isPoliticalParty` — §20 GwG transparency duties apply to every Verein/Partei.

### Known limitations (tracked for later versions)

- No automated filing to transparenzregister.de — reminders and reports only, filing itself stays a manual, human-triggered step.
- Audit-log/GoBD tamper-evidence, retention enforcement, and TSE integration, plus a full backup/restore/data-export guarantee and full GDPR build-out (AVV, TOMs, DSFA, breach reporting), are not yet implemented — the original V0.5 scope for these was narrowed to the two donation/transparency compliance checks above; the rest remains open, tentatively folded into a later wave.
- No LTR economy yet (internal crowdfunding, auction, direct transfer, politician profiles/ranking) — planned for V0.6.
- Federation (multi-server operation) is not yet built — planned for V0.7.

## [0.4.0] — 2026-07-19

### Added

**Mail-merge/PDF engine** — Beitragsrechnung (membership dues invoice), a §50 EStDV Spendenbescheinigung (donation receipt, following the official BMF Muster pattern, distinguishing §10b EStG association donations from §34g EStG political-party donations), and an Einladung (invitation letter), all rendered with Apache PDFBox and delivered as raw PDF bytes over plain Ktor HTTP routes rather than Kilua RPC, mirroring the existing document-download idiom. Guessed or simplified legal wording in the donation receipt is explicitly flagged in code for human/tax-advisor review before real-world use. To make the templates fillable, this release also adds: a minimal nullable postal address on `Member` (with a new `updateMemberAddress` endpoint), a single-row admin-editable `OrganizationSettings` entity (letterhead, bank details, Gemeinnützigkeit tax-exemption reference), and an optional `donorMemberId` bridge on `JournalEntry` so a posted donation can be traced back to its donor for receipt generation. Beitragsrechnung and Spendenbescheinigung PDFs are additionally archived into the existing document store for retention.

**Letterxpress postal-mail dispatch** — an explicit, human-triggered path to mail a generated Beitragsrechnung, Spendenbescheinigung, or Einladung to members without email, via a new `PostalMailProvider` abstraction with a Letterxpress implementation. Gated behind a new `OrganizationSettings.postalMailEnabled` opt-in (default off), since enabling it in real operation requires a Data Processing Agreement (Auftragsverarbeitungsvertrag/AVV) with Letterxpress; defaults to Letterxpress's sandbox/non-live mode until explicitly switched to live dispatch. A new `PostalDeliveryLog` records every dispatch attempt (status, provider reference, a sanitized error message — never a raw exception or provider response body). Dispatch requires the same authorization tier as PDF generation and a bounded, explicit recipient list (no unbounded batch sends). The Letterxpress wire format could not be verified against live documentation in the build environment and is explicitly flagged in code as needing a human check before production use.

### Known limitations (tracked for later versions)

- The Letterxpress integration's exact API wire format (endpoints, field names, auth flow) is implemented from general knowledge, not verified against live/current Letterxpress documentation — verify before enabling live dispatch.
- Spendenbescheinigung is issued per single donation entry, not aggregated into an official BMF-style Sammelbestätigung across a period — aggregation rules need a human/tax-advisor check.
- No compliance bundle yet (§25 PartG donation-acceptance check, §20 GwG transparency-register reporting, full GoBD audit-log/tamper-evidence/retention/TSE, backup/restore guarantee, full GDPR build-out) — planned for V0.5.
- No LTR economy yet (internal crowdfunding, auction, direct transfer, politician profiles/ranking) — planned for V0.6.
- Contribution management still has no SEPA direct-debit or dunning automation (tracked since 0.1.0).

## [0.3.0] — 2026-07-19

### Added

**Accounting core** — SKR42 chart of accounts and double-entry bookkeeping (originally modeled on SKR49, switched to SKR42 since that is DATEV's current recommendation for new non-profit clients): ledger accounts, journal entries/postings with a server-enforced balance invariant (Σdebit = Σcredit, validated independently of client input, immutable once `POSTED`), a general ledger view, and treasurer/board/admin-tiered authorization throughout.

**Financial statements** — `GuV` (income statement), `Bilanz` (balance sheet), and a combined `Jahresabschluss` (annual financial statement), all derived purely from `POSTED` journal postings with no new persisted state. The balance sheet surfaces an explicit cumulative-result equity line so Aktiva = Passiva always holds, since income/expense are not closed to equity in this version.

**Four-sphere Gemeinnützigkeit separation** — every posting now carries a mandatory sphere (Ideeller Bereich / Vermögensverwaltung / Zweckbetrieb / Wirtschaftlicher Geschäftsbetrieb, DATEV-KOST1-flavored), enforced with no default and no nullable transition period, plus a per-sphere income-statement report.

**§55 AO Mittelverwendungsrechnung and §62 AO Rücklagenbildung** — reserve categories (Projektrücklage, freie Rücklage, Wiederbeschaffungsrücklage, Betriebsmittelrücklage) as an optional classification on equity ledger accounts, funded via ordinary double-entry transfers, plus a derived use-of-funds statement with a FIFO timely-use carry-forward and overdue-amount tracking anchored at inception. The freie-Rücklage percentage cap and the §55 small-organization exemption are deliberately not hard-coded — both are surfaced as data for human verification rather than enforced constants.

**Kassenbuch** — a chronological, gapless cash-book view for designated cash-register accounts, derived from existing immutable `POSTED` postings, with two GoBD-informed guards: no posting without a voucher reference for cash accounts, and the cash balance may never go negative (enforced with row-level locking to close a same-account race). This is explicitly a GoBD foundation only — cryptographic tamper-evidence, retention enforcement, and TSE integration remain out of scope, planned for V0.5.

**Kostenstellen/cost-center accounting** — an open-ended, user-created `CostCenter` entity (unlike the fixed sphere/reserve enums) with the same create/list/deactivate lifecycle as ledger accounts, optional per-posting assignment (most routine bookings have no project association), and a minimal per-cost-center income/expense/result report. Lays the general mechanism V0.6 (Crowdfunding/Auktion) will later attach campaigns to, without building any campaign-specific logic yet.

### Changed

Dependency bumps: Kotlin 2.4.0 → 2.4.10, KSP 2.3.9 → 2.3.10, kuml 0.35.0 → 0.36.1. JVM toolchain corrected from an accidental 26 pin to 25, the actual requirement for loading Kilua RPC's published jars.

### Security

- Fixed an unmapped `IllegalArgumentException` for an out-of-range `fiscalYear` in `getAnnualFinancialStatement`, replaced with a typed `BadRequestException`.
- Closed a check-then-act race in the Kassenbuch's never-negative-balance guard by adding row-level locking (`SELECT ... FOR UPDATE`) with a deterministic lock-acquisition order, preventing both a balance-check bypass under concurrent postings and a possible deadlock when a single entry locks more than one cash account.

### Known limitations (tracked for later versions)

- No mail-merge/PDF engine or postal-mail path yet — planned for V0.4.
- No compliance bundle yet (§25 PartG donation-acceptance check, §20 GwG transparency-register reporting, full GoBD audit-log/tamper-evidence/retention/TSE, backup/restore guarantee, full GDPR build-out) — planned for V0.5.
- No LTR economy yet (internal crowdfunding, auction, direct transfer, politician profiles/ranking) — planned for V0.6; cost centers (this release) lay the groundwork for attaching campaigns/auctions.
- Contribution management still has no SEPA direct-debit or dunning automation (tracked since 0.1.0).

## [0.2.0] — 2026-07-18

### Added

**Governance** — committee/working-group management and meeting management (agenda, resolution register, minutes template, attendance tracking, quorum check); motion management for general assemblies and committees.

**Voting — three orthogonal modes**:
- **Meritocratic votes** — LTR-weighted voting on substantive/project questions.
- **Democratic elections** — one-member-one-vote for legally mandated personnel and constitutional decisions (board elections, bylaw amendments), including election board oversight, eligible-voter snapshots, candidacy management, secret and open ballot modes, and a configurable N-of-M tally-approval step.
- **Systemic consensus** — resistance-based decision-finding (Visotschnig/Schrotta method): each voter rates every option 0–10, the option with the lowest cumulative resistance wins, with a group-conflict index, configurable tiebreak rules, and an automatic "status quo" option.

All three modes share the same resolution register (`Resolution`) and reuse a single anonymous/open ballot infrastructure end to end.

**MDA persistence pipeline fully wired** — the kUML UML→ERM→Exposed/Flyway pipeline (ADR-0016, tracked as a known limitation in 0.1.0) is now the actual production persistence layer: all hand-written Exposed tables were deleted and replaced with kUML-generated code from versioned `.kuml.kts` domain models, and the Flyway baseline migration is generated from the same source of truth. Multiple real kUML gaps surfaced and were fixed upstream along the way (enum-to-`VARCHAR` type fidelity, Kotlin object-name overrides, KMP-safe UUID/date-time representations, explicit FK targeting via `fkEntity`/`fkAttribute`, a new `«Index»` stereotype for composite unique constraints) — see [ADR-0016](https://github.com/kuml-dev/kUML) for details. The project now depends on the real Maven Central `kuml` artifact (currently 0.35.0); the temporary `mavenLocal` bridge used during development has been retired.

### Changed

**English-only domain terminology.** The entire governance/voting domain, previously named in German, was renamed to English end to end (entities, tables, classes, DTOs, services, tests): Gremium→Committee, Sitzung→Meeting, Tagesordnungspunkt→AgendaItem, Anwesenheit→Attendance, Antrag→Motion, Beschluss→Resolution, Abstimmung→Vote, Wahl→Election, Konsensierung→SystemicConsensus. `README.adoc` and `docs/architecture/domain-model.adoc` were fully translated to English. This aligns the codebase with this project's own documented convention (English documentation and class names for all `kuml-dev`/Lapis repos).

### Known limitations (tracked for later versions)

- Contribution management still has no SEPA direct-debit or dunning automation (tracked since 0.1.0).
- No accounting core yet (chart of accounts, non-profit four-sphere separation, use-of-funds statement) — planned for V0.3.
- No mail-merge/PDF engine or postal-mail path yet — planned for V0.4.
- No compliance bundle yet (PartG donation-acceptance check, transparency-register reporting, GoBD audit log, backup/restore guarantee, full GDPR build-out) — planned for V0.5.

## [0.1.0] — 2026-07-12

### Added

**Project foundation** — Gradle multi-module build (`lapis-shared`, `lapis-server`, `lapis-client`) following the Kilua RPC fullstack convention: a Kotlin Multiplatform shared module holding RPC service interfaces and domain DTOs, a Ktor JVM server, and a KVision Kotlin/JS client. CI workflow runs `./gradlew clean check` on push/PR. Persistence via Exposed ORM + Flyway migrations against PostgreSQL.

**Member management** — member master data, join/leave workflow (application → approval → active, with exit transitioning to guest status per the PZB legal-framework reference), membership tiers and roles.

**Contributions, documents, communication** — basic recurring-contribution tracking per membership tier (manual payment marking, no SEPA/dunning automation yet), a versioned document store with access tiers, and mailing-list/direct-message data models with typed Kilua RPC services.

**GDPR basics** — a self-registering `PersonalDataContributor`/`PersonalDataRegistry` mechanism so future entities opt into data-subject-access-request coverage without hand-maintaining a table list, enforced by an `information_schema`-based coverage test. Erasure requests support both anonymization (default, since accounting retention will later require it for financial records) and hard deletion where legally unconstrained, via a request → decide → execute workflow with an audit trail, exposed over both RPC and HTTP with self-or-ADMIN access control.

### Security

- Enforced the `ADMIN_ONLY` document access tier and gated version-listing/double-send paths that were previously open.
- Closed an unauthenticated member email/role leak in `listMembers()`.
- Made demo-data seeding opt-in with a guard against running against a real database.
- Fixed an ambiguous-join bug where `ErasureRequestTable`'s three separate foreign keys to `MemberTable` made Exposed's implicit join throw `IllegalStateException` at runtime; replaced with an explicit join condition.

### Known limitations (tracked for later versions)

- The kUML MDA persistence pipeline (UML → ERM → Exposed/Flyway, per [ADR-0016](https://github.com/kuml-dev/kUML) in the sibling kUML project) is not yet wired into this repo's build — Exposed tables are hand-written for now, with a kUML diagram kept as documentation only (`docs/architecture/domain-model.adoc`). Wiring the generator is tracked as follow-up work.
- Contribution management has no SEPA direct-debit or dunning automation.
- No governance layer yet (committees, meetings, motions, votes) — planned for V0.2.
