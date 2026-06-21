-- Transactional outbox table.
-- Written by OutboxPublisher within the same DB transaction as the business change.
-- Read by Debezium CDC (prod) or OutboxRelayScheduler (local-dev).
CREATE TABLE IF NOT EXISTS outbox_events (
    event_id        UUID            NOT NULL,
    aggregate_type  VARCHAR(100)    NOT NULL,
    aggregate_id    VARCHAR(100)    NOT NULL,
    event_type      VARCHAR(200)    NOT NULL,
    kafka_topic     VARCHAR(200)    NOT NULL,
    kafka_key       VARCHAR(200)    NOT NULL,
    payload         BYTEA           NOT NULL,
    schema_version  INTEGER         NOT NULL,
    correlation_id  VARCHAR(100)    NOT NULL,
    legal_entity_id VARCHAR(50)     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ,
    version         BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_outbox_events PRIMARY KEY (event_id)
);

CREATE INDEX IF NOT EXISTS idx_outbox_published_at   ON outbox_events (published_at) WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_id   ON outbox_events (aggregate_id);
CREATE INDEX IF NOT EXISTS idx_outbox_correlation_id ON outbox_events (correlation_id);

COMMENT ON TABLE outbox_events IS
    'Transactional outbox. Debezium reads via logical replication slot; OutboxRelayScheduler polls in local-dev.';
