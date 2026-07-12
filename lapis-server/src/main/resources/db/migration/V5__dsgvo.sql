-- DSGVO-Basis: Auskunft (Art. 15/20), Loeschung (Art. 17) als reviewbarer Workflow,
-- Audit-Trail (Rechenschaftspflicht, Art. 5 Abs. 2). Siehe docs/architecture/dsgvo.adoc und
-- network.lapis.cloud.server.dsgvo.PersonalDataRegistry.
--
-- anonymized_at markiert einen anonymisierten Mitgliedsdatensatz (display_name/email durch
-- Platzhalter ersetzt, siehe FoundationPersonalData). member.status (MemberStatus) bleibt
-- unveraendert -- ein eigenes Enum-Literal wuerde alle Konsumenten des Enums unnoetig anfassen,
-- das Flag genuegt.
ALTER TABLE member ADD COLUMN anonymized_at TIMESTAMP;

-- Loeschung ist ein reviewbarer Workflow, keine sofortige Aktion: contribution unterliegt der
-- handelsrechtlichen Aufbewahrungspflicht (GoBD/HGB/AO, 10 Jahre) und darf nicht sofort geloescht
-- werden -- ein ADMIN muss den Antrag pruefen (decide) und erst danach ausfuehren (execute). Die
-- Zeile bleibt nach der Ausfuehrung als Verfahrensnachweis bestehen; subject_member_id zeigt
-- weiterhin auf die (dann anonymisierte, niemals hart geloeschte) member-Zeile, daher ist die
-- FK-Referenz dauerhaft gueltig.
CREATE TABLE erasure_request (
    id                 UUID          PRIMARY KEY,
    subject_member_id  UUID          NOT NULL REFERENCES member (id),
    requested_at       TIMESTAMP     NOT NULL,
    requested_by       UUID          NOT NULL REFERENCES member (id),
    reason             VARCHAR(1000) NOT NULL,
    mode               VARCHAR(40)   NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    decided_by         UUID          REFERENCES member (id),
    decided_at         TIMESTAMP,
    decision_note      VARCHAR(1000),
    executed_at        TIMESTAMP,
    legal_hold         BOOLEAN       NOT NULL DEFAULT FALSE,
    -- JSON-Array von TableErasureOutcomeDto (Zaehler + Retention-Begruendung pro Tabelle),
    -- ausschliesslich Metadaten -- niemals Klartext-Inhalte. Siehe DsgvoService.
    outcome_summary    VARCHAR(4000)
);

-- Metadaten-only Audit-Log: NIE Klartext-Inhalte (E-Mail-Text, Nachrichtentext, Dateiname o.Ae.)
-- -- nur Zaehler und UUIDs (siehe PersonalDataCoverageTest-Gegenstueck, dem Negativtest
-- "keine Payload im Audit-Log"). Bewusst NICHT Teil des PersonalDataRegistry-Loesch-Walks
-- (siehe PersonalDataRegistry.noPersonalDataAllowlist): referenziert das Subjekt nur per UUID,
-- Rechenschaftspflicht (Art. 5 Abs. 2 DSGVO) ist die eigene Rechtsgrundlage fuer die
-- Aufbewahrung. Kein UPDATE/DELETE-Pfad in der Anwendung (Append-only) -- siehe
-- docs/architecture/dsgvo.adoc "Audit-Log-Datenschutz".
CREATE TABLE dsgvo_audit_log (
    id                 UUID          PRIMARY KEY,
    occurred_at        TIMESTAMP     NOT NULL,
    actor_member_id    UUID          REFERENCES member (id),
    actor_role         VARCHAR(20),
    action             VARCHAR(30)   NOT NULL,
    subject_member_id  UUID          NOT NULL REFERENCES member (id),
    request_id         UUID          REFERENCES erasure_request (id),
    outcome_summary    VARCHAR(4000),
    legal_basis        VARCHAR(500)
);

CREATE INDEX idx_erasure_request_subject ON erasure_request (subject_member_id);
CREATE INDEX idx_erasure_request_status ON erasure_request (status);
CREATE INDEX idx_dsgvo_audit_log_subject ON dsgvo_audit_log (subject_member_id);
CREATE INDEX idx_dsgvo_audit_log_request ON dsgvo_audit_log (request_id);
