-- Append-only audit ledger. Never UPDATE or DELETE rows.
-- Forwarded to OpenSearch by tms-common-audit Kafka consumer for search/reporting.
CREATE TABLE IF NOT EXISTS audit_events (
    audit_id        UUID            NOT NULL,
    aggregate_type  VARCHAR(100)    NOT NULL,
    aggregate_id    VARCHAR(100)    NOT NULL,
    action          VARCHAR(100)    NOT NULL,
    user_id         VARCHAR(100),
    legal_entity_id VARCHAR(50)     NOT NULL,
    correlation_id  VARCHAR(100)    NOT NULL,
    payload         JSONB,
    diff            JSONB,
    occurred_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    source_ip       VARCHAR(45),

    CONSTRAINT pk_audit_events PRIMARY KEY (audit_id)
);

-- Partial index for the most common query pattern: history of a specific entity
CREATE INDEX IF NOT EXISTS idx_audit_aggregate
    ON audit_events (aggregate_type, aggregate_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_user
    ON audit_events (user_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_legal_entity
    ON audit_events (legal_entity_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_correlation_id
    ON audit_events (correlation_id);

COMMENT ON TABLE audit_events IS
    'Immutable audit log. Append-only. Do not delete or update rows.';
