-- Antragsverwaltung (V0.2.2): Antragstellung durch Mitglieder (Mitgliederversammlung oder ein
-- spezifisches Gremium als Ziel), Vorpruefung, Terminierung als Tagesordnungspunkt einer Sitzung
-- und Ueberfuehrung in einen Beschluss ueber das bestehende Beschlussbuch (V0.2.1). Siehe
-- network.lapis.cloud.server.db.tables.GovernanceTables (AntragTable) und
-- network.lapis.cloud.server.rpc.GovernanceService (submitAntrag/reviewAntrag/scheduleAntrag/
-- resolveAntrag/withdrawAntrag).
--
-- Kein neuer "Mitgliederversammlung"-Zieltyp auf DB-Ebene: GremiumType wird um
-- MITGLIEDERVERSAMMLUNG erweitert (unconstrained VARCHAR(20), siehe V6__governance.sql --
-- Zero-Migration-Enum-Erweiterung).

-- Antrag.begruendung ist VARCHAR(4000); scheduleAntrag legt daraus einen Tagesordnungspunkt an
-- (description = Antrag.begruendung) -- die bisherige VARCHAR(1000)-Grenze wuerde Mitgliedertext
-- stillschweigend abschneiden.
ALTER TABLE tagesordnungspunkt ALTER COLUMN description TYPE VARCHAR(4000);

-- gremium.type war VARCHAR(20); GremiumType.MITGLIEDERVERSAMMLUNG (neu in dieser Welle) ist mit
-- 21 Zeichen einen Zeichen zu lang dafuer.
ALTER TABLE gremium ALTER COLUMN type TYPE VARCHAR(30);

-- antrag.status ist von Anfang an VARCHAR(30), nicht VARCHAR(20) wie bei den uebrigen
-- Governance-Enums: AntragStatus.ABGELEHNT_VORPRUEFUNG ist 21 Zeichen lang.
CREATE TABLE antrag (
    id                     UUID          PRIMARY KEY,
    target_gremium_id      UUID          NOT NULL REFERENCES gremium (id),
    title                  VARCHAR(300)  NOT NULL,
    begruendung            VARCHAR(4000) NOT NULL,
    text                   VARCHAR(4000) NOT NULL,
    submitter_member_id    UUID          NOT NULL REFERENCES member (id),
    status                 VARCHAR(30)   NOT NULL,
    submitted_at           TIMESTAMP     NOT NULL,
    reviewed_by            UUID          REFERENCES member (id),
    reviewed_at            TIMESTAMP,
    review_note            VARCHAR(1000),
    sitzung_id             UUID          REFERENCES sitzung (id),
    tagesordnungspunkt_id  UUID          REFERENCES tagesordnungspunkt (id),
    beschluss_id           UUID          REFERENCES beschluss (id),
    withdrawn_at           TIMESTAMP
);

CREATE INDEX idx_antrag_target_gremium ON antrag (target_gremium_id);
CREATE INDEX idx_antrag_status ON antrag (status);
CREATE INDEX idx_antrag_submitter ON antrag (submitter_member_id);
CREATE INDEX idx_antrag_sitzung ON antrag (sitzung_id);
