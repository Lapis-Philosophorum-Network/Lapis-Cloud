# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and adheres to
[Semantic Versioning](https://semver.org/).

## [0.1.0] — 2026-07-12

### Added

**Project foundation** — Gradle multi-module build (`lapis-shared`, `lapis-server`, `lapis-client`) following the Kilua RPC fullstack convention: a Kotlin Multiplatform shared module holding RPC service interfaces and domain DTOs, a Ktor JVM server, and a KVision Kotlin/JS client. CI workflow runs `./gradlew clean check` on push/PR. Persistence via Exposed ORM + Flyway migrations against PostgreSQL.

**Mitgliederverwaltung** — member master data, join/leave workflow (application → approval → active, with exit transitioning to guest status per the PZB legal-framework reference), membership tiers and roles.

**Beiträge, Dokumente, Kommunikation** — basic recurring-contribution tracking per membership tier (manual payment marking, no SEPA/dunning automation yet), a versioned document store with access tiers, and mailing-list/direct-message data models with typed Kilua RPC services.

**DSGVO-Grundlagen** — a self-registering `PersonalDataContributor`/`PersonalDataRegistry` mechanism so future entities opt into data-subject-access-request coverage without hand-maintaining a table list, enforced by an `information_schema`-based coverage test. Erasure requests support both anonymization (default, since accounting retention will later require it for financial records) and hard deletion where legally unconstrained, via a request → decide → execute workflow with an audit trail, exposed over both RPC and HTTP with self-or-ADMIN access control.

### Security

- Enforced the `ADMIN_ONLY` document access tier and gated version-listing/double-send paths that were previously open.
- Closed an unauthenticated member email/role leak in `listMembers()`.
- Made demo-data seeding opt-in with a guard against running against a real database.
- Fixed an ambiguous-join bug where `ErasureRequestTable`'s three separate foreign keys to `MemberTable` made Exposed's implicit join throw `IllegalStateException` at runtime; replaced with an explicit join condition.

### Known limitations (tracked for later versions)

- The kUML MDA persistence pipeline (UML → ERM → Exposed/Flyway, per [ADR-0016](https://github.com/kuml-dev/kUML) in the sibling kUML project) is not yet wired into this repo's build — Exposed tables are hand-written for now, with a kUML diagram kept as documentation only (`docs/architecture/domain-model.adoc`). Wiring the generator is tracked as follow-up work.
- Beitragsverwaltung has no SEPA direct-debit or dunning (Mahnwesen) automation.
- No governance layer yet (Gremien, Sitzungen, Anträge, Abstimmungen) — planned for V0.2.
