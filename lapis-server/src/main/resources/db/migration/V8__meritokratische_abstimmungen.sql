-- Meritokratische Abstimmungen (V0.2.3): eBay/Vickrey-Korb-Auktion fuer Sach-/
-- Projektentscheidungen -- Mitglieder staken LTR in die Koerbe der Optionen, die sie
-- unterstuetzen; Gewinner-Korb ist der mit der hoechsten Summe, Vickrey-Settlement zieht den
-- Gewinnern nur so viel ab, wie zum Ueberbieten des zweitplatzierten Korbs noetig war
-- (proportional zum eigenen Einsatz). Siehe network.lapis.cloud.server.db.tables.GovernanceTables
-- (AbstimmungTable/AbstimmungOptionTable/AbstimmungStimmeTable), network.lapis.cloud.server.db
-- .tables.LtrTables (LtrBalanceTable, Platzhalter fuer das vollstaendige LTR-Ledger aus V0.6) und
-- network.lapis.cloud.server.rpc.AbstimmungSettlement (reine Settlement-Funktion).
--
-- Reihenfolge nach Abhaengigkeit: abstimmung -> abstimmung_option -> abstimmung_stimme ->
-- ltr_balance -> ALTER TABLE beschluss (FK-Ziel abstimmung existiert erst danach).

CREATE TABLE abstimmung (
    id                  UUID          PRIMARY KEY,
    antrag_id           UUID          NOT NULL REFERENCES antrag (id),
    sitzung_id          UUID          NOT NULL REFERENCES sitzung (id),
    title               VARCHAR(300)  NOT NULL,
    status              VARCHAR(30)   NOT NULL,
    opened_by           UUID          NOT NULL REFERENCES member (id),
    opened_at           TIMESTAMP     NOT NULL,
    closed_at           TIMESTAMP,
    winner_option_id    UUID,
    second_price_ltr    DECIMAL(18,2),
    beschluss_id        UUID          REFERENCES beschluss (id)
);

-- Kein basket_total_ltr-Feld: der Korbstand wird stets aus abstimmung_stimme summiert (Single
-- Source of Truth = die Stimmen selbst), nicht als eigene, potenziell drift-anfaellige Spalte
-- vorgehalten.
CREATE TABLE abstimmung_option (
    id             UUID          PRIMARY KEY,
    abstimmung_id  UUID          NOT NULL REFERENCES abstimmung (id),
    label          VARCHAR(200)  NOT NULL,
    position       INT           NOT NULL
);

-- UNIQUE(abstimmung_id, member_id) verhindert Ballot-Stuffing auf DB-Ebene -- der Service
-- upsertet analog zu AnwesenheitTable/recordAttendance (V0.2.1), die Unique Constraint bleibt der
-- Backstop.
CREATE TABLE abstimmung_stimme (
    id             UUID           PRIMARY KEY,
    abstimmung_id  UUID           NOT NULL REFERENCES abstimmung (id),
    option_id      UUID           NOT NULL REFERENCES abstimmung_option (id),
    member_id      UUID           NOT NULL REFERENCES member (id),
    stake_ltr      DECIMAL(18,2)  NOT NULL,
    settled_ltr    DECIMAL(18,2),
    cast_at        TIMESTAMP      NOT NULL,
    CONSTRAINT uq_abstimmung_stimme_member UNIQUE (abstimmung_id, member_id)
);

-- Platzhalter fuer das vollstaendige LTR-Ledger (V0.6) -- keine Debit-/Credit-/Reserve-Spalten,
-- nur ein Kontostand. DECIMAL(18,2), breiter als contribution.amount_due's (12,2), weil sich hier
-- ueber die Zeit ein kumulierter Kontostand aufbaut; Skala 2 passt zur 0.01-LTR-Mindeststakegrenze.
CREATE TABLE ltr_balance (
    member_id    UUID           PRIMARY KEY REFERENCES member (id),
    balance_ltr  DECIMAL(18,2)  NOT NULL,
    updated_at   TIMESTAMP      NOT NULL
);

-- beschluss.resolution_mode unterscheidet das bestehende Gremium-Quorum-Beschlussbuch (V0.2.1/
-- V0.2.2) vom neuen meritokratischen Pfad. DEFAULT 'GREMIUM_QUORUM' haelt bestehende Zeilen sowie
-- recordBeschluss/resolveAntrag unveraendert. abstimmung_id ist nullable und nur bei
-- MERITOKRATISCH gesetzt.
ALTER TABLE beschluss ADD COLUMN resolution_mode VARCHAR(20) NOT NULL DEFAULT 'GREMIUM_QUORUM';
ALTER TABLE beschluss ADD COLUMN abstimmung_id UUID REFERENCES abstimmung (id);

CREATE INDEX idx_abstimmung_antrag ON abstimmung (antrag_id);
CREATE INDEX idx_abstimmung_status ON abstimmung (status);
CREATE INDEX idx_abstimmung_option_abstimmung ON abstimmung_option (abstimmung_id);
CREATE INDEX idx_abstimmung_stimme_abstimmung ON abstimmung_stimme (abstimmung_id);
CREATE INDEX idx_abstimmung_stimme_member ON abstimmung_stimme (member_id);
