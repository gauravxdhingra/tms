CREATE TABLE IF NOT EXISTS payments (
    payment_id             UUID            NOT NULL,
    legal_entity_id        VARCHAR(50)     NOT NULL,
    payment_type           VARCHAR(30)     NOT NULL,
    amount_value           NUMERIC(38, 10) NOT NULL,
    amount_currency        CHAR(3)         NOT NULL,
    value_date             DATE            NOT NULL,
    debit_account_id       VARCHAR(100)    NOT NULL,
    credit_account_id      VARCHAR(100),
    beneficiary_iban       VARCHAR(34),
    beneficiary_bic        VARCHAR(11),
    beneficiary_name       VARCHAR(200),
    remittance_info        VARCHAR(140),
    status                 VARCHAR(30)     NOT NULL,
    idempotency_key        VARCHAR(100)    NOT NULL,
    network_reference      VARCHAR(50),
    rejection_reason       VARCHAR(500),
    initiated_by_user_id   VARCHAR(100)    NOT NULL,
    approved_by_user_id    VARCHAR(100),
    created_at             TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    version                BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT pk_payments PRIMARY KEY (payment_id),
    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key)
);

-- Partial index on pending statuses for approval queue queries
CREATE INDEX IF NOT EXISTS idx_payments_pending
    ON payments (legal_entity_id, status)
    WHERE status IN ('PENDING_VALIDATION', 'PENDING_COMPLIANCE', 'PENDING_APPROVAL');

CREATE INDEX IF NOT EXISTS idx_payments_legal_entity_id ON payments (legal_entity_id);
CREATE INDEX IF NOT EXISTS idx_payments_value_date       ON payments (value_date);
CREATE INDEX IF NOT EXISTS idx_payments_status           ON payments (status);

COMMENT ON TABLE payments IS 'Payment instruction aggregate. One row per payment lifecycle.';
