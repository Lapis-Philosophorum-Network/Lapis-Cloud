-- Demokratische Wahlen (V0.2.4): one-person-one-vote elections/ballots for personnel
-- (EINZELWAHL/MEHRFACHWAHL) and Ja/Nein (JA_NEIN) decisions, structurally distinct from
-- abstimmung (V0.2.3's LTR-weighted eBay/Vickrey basket auction). See
-- network.lapis.cloud.server.db.tables.WahlTables for the full KDoc on each table, and
-- network.lapis.cloud.server.rpc.WahlService for the lifecycle these tables back.
--
-- Table-creation order follows dependency, not alphabetical reading: wahl_kandidatur must exist
-- before wahl_option (wahl_option.kandidatur_id references it). wahl_stimmzettel must exist
-- before wahl_stimmzettel_auswahl (the child selection rows).
--
-- Ballot secrecy is a practical DB-level table split, not cryptography: wahl_stimmzettel.member_id
-- is nullable and always NULL for a geheim Wahl -- there is no FK-joinable link back to
-- wahl_teilnahme (the "this member voted" proof) in that case. See WahlTables KDoc "Offene
-- Fragen" cross-reference.
--
-- One-member-one-vote enforcement differs between the two ballot paths on purpose:
-- - Non-secret (geheim = false): UNIQUE (wahl_id, member_id) on wahl_stimmzettel is the backstop.
--   Standard SQL NULL semantics make this a no-op for NULL member_id rows, which never occur on
--   this path.
-- - Secret (geheim = true): wahl_stimmzettel.member_id is always NULL, so that table's own unique
--   constraint cannot help. wahl_teilnahme's UNIQUE (wahl_id, member_id) constraint is the real
--   DB-level backstop for the secret path instead -- WahlService.castStimme inserts into
--   wahl_teilnahme (proving "voted" without proving "voted for what") inside the same transaction
--   as the wahl_stimmzettel insert, and a caught unique-constraint violation on that insert turns
--   a concurrent double-vote attempt into a rejected ConflictException.

CREATE TABLE wahl (
    id                          UUID          PRIMARY KEY,
    antrag_id                   UUID          NOT NULL REFERENCES antrag (id),
    sitzung_id                  UUID          NOT NULL REFERENCES sitzung (id),
    title                       VARCHAR(300)  NOT NULL,
    wahl_typ                    VARCHAR(20)   NOT NULL,
    geheim                      BOOLEAN       NOT NULL,
    sitze_count                 INT           NOT NULL,
    ziel_gremium_id             UUID          REFERENCES gremium (id),
    ziel_rolle                  VARCHAR(20),
    required_majority_percent   INT           NOT NULL,
    status                      VARCHAR(30)   NOT NULL,
    opened_by                   UUID          NOT NULL REFERENCES member (id),
    opened_at                   TIMESTAMP     NOT NULL,
    candidate_list_approved_at  TIMESTAMP,
    voting_opened_at            TIMESTAMP,
    voting_closed_at            TIMESTAMP,
    tally_threshold              INT          NOT NULL,
    tally_run_at                TIMESTAMP,
    beschluss_id                UUID          REFERENCES beschluss (id)
);

-- No UNIQUE(wahl_id, member_id): a withdrawn Kandidatur must not block the same member from
-- re-submitting later (mirrors gremium_mitgliedschaft's own documented precedent). The
-- application-level "no existing active candidacy" guard lives in
-- WahlService.submitKandidatur.
CREATE TABLE wahl_kandidatur (
    id               UUID           PRIMARY KEY,
    wahl_id          UUID           NOT NULL REFERENCES wahl (id),
    member_id        UUID           NOT NULL REFERENCES member (id),
    motivation_text  VARCHAR(1000),
    submitted_at     TIMESTAMP      NOT NULL,
    withdrawn_at     TIMESTAMP
);

CREATE TABLE wahl_option (
    id             UUID          PRIMARY KEY,
    wahl_id        UUID          NOT NULL REFERENCES wahl (id),
    label          VARCHAR(200)  NOT NULL,
    position       INT           NOT NULL,
    kandidatur_id  UUID          REFERENCES wahl_kandidatur (id)
);

CREATE TABLE wahl_wahlvorstand (
    id            UUID       PRIMARY KEY,
    wahl_id       UUID       NOT NULL REFERENCES wahl (id),
    member_id     UUID       NOT NULL REFERENCES member (id),
    appointed_at  TIMESTAMP  NOT NULL,
    CONSTRAINT uq_wahl_wahlvorstand_member UNIQUE (wahl_id, member_id)
);

-- Eligibility snapshot taken once at WahlService.openVoting -- frozen at that moment so
-- membership churn during the voting window cannot add or remove voters mid-election.
CREATE TABLE wahl_wahlberechtigt (
    id         UUID  PRIMARY KEY,
    wahl_id    UUID  NOT NULL REFERENCES wahl (id),
    member_id  UUID  NOT NULL REFERENCES member (id),
    CONSTRAINT uq_wahl_wahlberechtigt_member UNIQUE (wahl_id, member_id)
);

-- The GEHEIM-path one-member-one-vote backstop (see file header). Always populated when
-- wahl.geheim is true; never populated on the non-secret path.
CREATE TABLE wahl_teilnahme (
    id         UUID       PRIMARY KEY,
    wahl_id    UUID       NOT NULL REFERENCES wahl (id),
    member_id  UUID       NOT NULL REFERENCES member (id),
    voted_at   TIMESTAMP  NOT NULL,
    CONSTRAINT uq_wahl_teilnahme_member UNIQUE (wahl_id, member_id)
);

-- One Vier-Augen-Prinzip approval by a named wahl_wahlvorstand member to permit
-- WahlService.auszaehlen to run -- a plain N-of-M approval count, not a cryptographic
-- threshold-signature scheme.
CREATE TABLE wahl_freigabe (
    id            UUID       PRIMARY KEY,
    wahl_id       UUID       NOT NULL REFERENCES wahl (id),
    member_id     UUID       NOT NULL REFERENCES member (id),
    approved_at   TIMESTAMP  NOT NULL,
    CONSTRAINT uq_wahl_freigabe_member UNIQUE (wahl_id, member_id)
);

-- member_id is NULL when wahl.geheim is true -- the whole point, see file header.
-- receipt_code is always generated (even on the non-secret path, for schema uniformity) but only
-- ever returned to the caller when the Wahl is secret.
CREATE TABLE wahl_stimmzettel (
    id             UUID           PRIMARY KEY,
    wahl_id        UUID           NOT NULL REFERENCES wahl (id),
    member_id      UUID           REFERENCES member (id),
    receipt_code   VARCHAR(40)    NOT NULL,
    cast_at        TIMESTAMP      NOT NULL,
    CONSTRAINT uq_wahl_stimmzettel_member UNIQUE (wahl_id, member_id)
);

-- Child rows: which option(s) a ballot selected -- more than one row per wahl_stimmzettel only
-- for MEHRFACHWAHL (up to sitze_count distinct options).
CREATE TABLE wahl_stimmzettel_auswahl (
    id                UUID  PRIMARY KEY,
    stimmzettel_id    UUID  NOT NULL REFERENCES wahl_stimmzettel (id),
    option_id         UUID  NOT NULL REFERENCES wahl_option (id)
);

-- beschluss.wahl_id links a Wahl's tally back into the same Beschlussbuch resolveAntrag/
-- recordBeschluss/closeAbstimmung already use, tagged ResolutionMode.DEMOKRATISCH. Nullable and
-- only set for that resolution mode -- mirrors abstimmung_id's role exactly.
ALTER TABLE beschluss ADD COLUMN wahl_id UUID REFERENCES wahl (id);

CREATE INDEX idx_wahl_antrag ON wahl (antrag_id);
CREATE INDEX idx_wahl_sitzung ON wahl (sitzung_id);
CREATE INDEX idx_wahl_status ON wahl (status);
CREATE INDEX idx_wahl_kandidatur_wahl ON wahl_kandidatur (wahl_id);
CREATE INDEX idx_wahl_kandidatur_member ON wahl_kandidatur (member_id);
CREATE INDEX idx_wahl_option_wahl ON wahl_option (wahl_id);
CREATE INDEX idx_wahl_wahlvorstand_wahl ON wahl_wahlvorstand (wahl_id);
CREATE INDEX idx_wahl_wahlberechtigt_wahl ON wahl_wahlberechtigt (wahl_id);
CREATE INDEX idx_wahl_teilnahme_wahl ON wahl_teilnahme (wahl_id);
CREATE INDEX idx_wahl_freigabe_wahl ON wahl_freigabe (wahl_id);
CREATE INDEX idx_wahl_stimmzettel_wahl ON wahl_stimmzettel (wahl_id);
CREATE INDEX idx_wahl_stimmzettel_auswahl_stimmzettel ON wahl_stimmzettel_auswahl (stimmzettel_id);
CREATE INDEX idx_wahl_stimmzettel_auswahl_option ON wahl_stimmzettel_auswahl (option_id);
