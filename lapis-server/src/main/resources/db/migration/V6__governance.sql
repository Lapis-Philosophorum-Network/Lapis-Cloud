-- Gremien- und Sitzungsverwaltung (V0.2.1): Gremien/Arbeitskreise, Mitgliedschaften darin,
-- Sitzungen mit Tagesordnung/Anwesenheit/Beschlussfaehigkeit sowie ein Beschlussbuch. Siehe
-- network.lapis.cloud.server.db.tables.GovernanceTables und network.lapis.cloud.server.rpc
-- .GovernanceService (Quorum-Berechnung, Beschluss-Nummerierung).
--
-- Reihenfolge nach Abhaengigkeit: gremium -> gremium_mitgliedschaft -> sitzung ->
-- tagesordnungspunkt -> anwesenheit -> beschluss.

CREATE TABLE gremium (
    id              UUID          PRIMARY KEY,
    name            VARCHAR(200)  NOT NULL,
    type            VARCHAR(20)   NOT NULL,
    description     VARCHAR(1000) NOT NULL,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    quorum_percent  INT           NOT NULL DEFAULT 50,
    created_at      TIMESTAMP     NOT NULL
);

-- Kein UNIQUE(gremium_id, member_id) -- eine Person kann spaeter mit neuem since/until erneut
-- beitreten. Stattdessen prueft GovernanceService.addGremiumMitglied vor dem Insert, ob bereits
-- eine *aktive* (until IS NULL) Mitgliedschaft fuer dieselbe Person+Gremium existiert.
CREATE TABLE gremium_mitgliedschaft (
    id          UUID        PRIMARY KEY,
    gremium_id  UUID        NOT NULL REFERENCES gremium (id),
    member_id   UUID        NOT NULL REFERENCES member (id),
    rolle       VARCHAR(20) NOT NULL,
    since       DATE        NOT NULL,
    until       DATE
);

CREATE TABLE sitzung (
    id                     UUID          PRIMARY KEY,
    gremium_id             UUID          NOT NULL REFERENCES gremium (id),
    title                  VARCHAR(300)  NOT NULL,
    scheduled_at           TIMESTAMP     NOT NULL,
    location               VARCHAR(300),
    format                 VARCHAR(20)   NOT NULL,
    status                 VARCHAR(20)   NOT NULL,
    called_by              UUID          REFERENCES member (id),
    called_at              TIMESTAMP,
    chair_member_id        UUID          REFERENCES member (id),
    minute_taker_member_id UUID          REFERENCES member (id),
    -- Forward-looking Hook fuer die Dokumentenablage, sobald ein finales Protokoll-PDF
    -- hochgeladen wird -- in dieser Welle noch ohne Verhalten verdrahtet.
    protocol_document_id   UUID          REFERENCES document (id),
    created_at             TIMESTAMP     NOT NULL
);

CREATE TABLE tagesordnungspunkt (
    id                   UUID          PRIMARY KEY,
    sitzung_id           UUID          NOT NULL REFERENCES sitzung (id),
    position             INT           NOT NULL,
    title                VARCHAR(300)  NOT NULL,
    description          VARCHAR(1000),
    presenter_member_id  UUID          REFERENCES member (id),
    CONSTRAINT uq_tagesordnungspunkt_position UNIQUE (sitzung_id, position)
);

CREATE TABLE anwesenheit (
    id                        UUID          PRIMARY KEY,
    sitzung_id                UUID          NOT NULL REFERENCES sitzung (id),
    member_id                 UUID          NOT NULL REFERENCES member (id),
    status                    VARCHAR(20)   NOT NULL,
    represented_by_member_id  UUID          REFERENCES member (id),
    note                      VARCHAR(500),
    recorded_at               TIMESTAMP     NOT NULL,
    CONSTRAINT uq_anwesenheit_member UNIQUE (sitzung_id, member_id)
);

-- number ("<GremiumType>-<Jahr>-<laufendeNummer>", z.B. "VORSTAND-2026-03") wird in
-- GovernanceService berechnet, nicht per DB-Sequenz -- konsistent mit dem uebrigen
-- Codebase-Stil (einfache Transaktionen statt Sequenzen in dieser Groessenordnung).
CREATE TABLE beschluss (
    id                       UUID          PRIMARY KEY,
    sitzung_id               UUID          NOT NULL REFERENCES sitzung (id),
    tagesordnungspunkt_id    UUID          REFERENCES tagesordnungspunkt (id),
    number                   VARCHAR(50)   NOT NULL,
    title                    VARCHAR(300)  NOT NULL,
    text                     VARCHAR(4000) NOT NULL,
    votes_yes                INT           NOT NULL,
    votes_no                 INT           NOT NULL,
    votes_abstain            INT           NOT NULL,
    -- Zum Entscheidungszeitpunkt via checkQuorum eingefroren -- Anwesenheit-Zeilen koennen sich
    -- nachtraeglich aendern (Korrekturen), die historische Beschlussfaehigkeits-Feststellung
    -- fuer diesen konkreten Beschluss darf sich dadurch nicht stillschweigend mitaendern.
    quorum_met               BOOLEAN       NOT NULL,
    status                   VARCHAR(20)   NOT NULL,
    decided_at               TIMESTAMP     NOT NULL,
    recorded_by              UUID          NOT NULL REFERENCES member (id)
);

CREATE INDEX idx_gremium_mitgliedschaft_gremium ON gremium_mitgliedschaft (gremium_id);
CREATE INDEX idx_gremium_mitgliedschaft_member ON gremium_mitgliedschaft (member_id);
CREATE INDEX idx_sitzung_gremium ON sitzung (gremium_id);
CREATE INDEX idx_tagesordnungspunkt_sitzung ON tagesordnungspunkt (sitzung_id);
CREATE INDEX idx_anwesenheit_sitzung ON anwesenheit (sitzung_id);
CREATE INDEX idx_beschluss_sitzung ON beschluss (sitzung_id);
