# 06 — Database Strategy (v2)

## What Changed from v1
- Cash management split into three separate schemas: `ecf`, `bat`, `position`
- Accounting schema extended: Chart of Accounts hierarchy, accrual tables, multi-GAAP ledger, hedge accounting tables
- Risk schema added: limits, limit utilisations, exposures, FX NOP
- FX Rate schema added: rate store, forward curves, revaluation runs
- IHB schema added: POBO, COBO, intercompany loans and FX, virtual accounts, netting
- Confirmation Matching schema added
- Settlement extended: SSI lifecycle, netting records, CLS instructions, NOSTRO recon
- Reconciliation split into distinct tables per sub-process type
- `MonetaryAmount` storage pattern documented
- Archival strategy documented (pg_partman + cold tier procedure)

---

## Core Principles (unchanged)

1. **Database per service** — no cross-service joins, no shared schemas
2. **No shared databases** across service boundaries, ever
3. **Flyway migrations** — all DDL changes versioned, no manual DDL
4. **PostgreSQL RLS** — every multi-tenant table enforces `legalEntityId` isolation
5. **Monthly partitioning** via `pg_partman` on all append-heavy time-series tables
6. **MonetaryAmount storage** — always `NUMERIC(24,8)` for amounts; `CHAR(3)` for currency
7. **Optimistic locking** — `version BIGINT NOT NULL` on all mutable aggregate roots
8. **Archival** — partitions older than retention threshold are detached and exported to MinIO (cold tier) by a monthly batch job

---

## Archival Procedure

```sql
-- pg_partman automates partition creation and detachment
-- After detachment, a Spring Batch job exports to MinIO:

-- 1. Detach old partition (pg_partman runs monthly)
ALTER TABLE payment_events DETACH PARTITION payment_events_2022_01;

-- 2. Spring Batch archival job:
--    a. SELECT * FROM payment_events_2022_01 → parquet export
--    b. Upload to MinIO: s3://tms-archive/{service}/{table}/year=2022/month=01/data.parquet
--    c. Verify row count matches
--    d. DROP TABLE payment_events_2022_01 (after verification)

-- 3. Archived data queryable via: DuckDB or Athena against S3 Parquet (Phase 3)
```

---

## `tms-payment-hub` — Event Sourced

```sql
-- Append-only event log (source of truth)
CREATE TABLE payment_events (
  id              BIGSERIAL,
  payment_id      UUID NOT NULL,
  event_id        UUID NOT NULL UNIQUE,
  event_type      VARCHAR(100) NOT NULL,
  event_version   INT NOT NULL DEFAULT 1,
  payload         JSONB NOT NULL,
  occurred_at     TIMESTAMPTZ NOT NULL,
  correlation_id  UUID NOT NULL,
  causation_id    UUID,
  saga_id         UUID,
  user_id         VARCHAR(100),
  legal_entity_id VARCHAR(50) NOT NULL,
  PRIMARY KEY (payment_id, id)
) PARTITION BY RANGE (occurred_at);

-- Current state snapshot (rebuilt from events; updated after each event)
CREATE TABLE payment_snapshots (
  payment_id        UUID PRIMARY KEY,
  legal_entity_id   VARCHAR(50) NOT NULL,
  portfolio_id      UUID,
  book_id           UUID,
  client_payment_id VARCHAR(200) UNIQUE,      -- idempotency
  status            VARCHAR(50) NOT NULL,
  payment_type      VARCHAR(50) NOT NULL,
  network_channel   VARCHAR(50),
  amount            NUMERIC(24,8) NOT NULL,
  currency          CHAR(3) NOT NULL,
  debit_account_id  UUID NOT NULL,
  credit_account_id UUID NOT NULL,
  counterparty_id   UUID,
  value_date        DATE NOT NULL,
  urgency           VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  network_ref       VARCHAR(200),
  version           BIGINT NOT NULL DEFAULT 1,
  created_at        TIMESTAMPTZ NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL
);

CREATE TABLE payment_templates (
  id              UUID PRIMARY KEY,
  legal_entity_id VARCHAR(50) NOT NULL,
  name            VARCHAR(200) NOT NULL,
  payment_type    VARCHAR(50) NOT NULL,
  template_fields JSONB NOT NULL,
  active          BOOLEAN NOT NULL DEFAULT TRUE,
  created_by      VARCHAR(100) NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL
);

-- Indexes
CREATE INDEX ON payment_snapshots (legal_entity_id, status, value_date);
CREATE INDEX ON payment_snapshots (client_payment_id);
CREATE INDEX ON payment_events (payment_id, occurred_at DESC);
CREATE INDEX ON payment_events (legal_entity_id, event_type, occurred_at);

-- RLS
ALTER TABLE payment_snapshots ENABLE ROW LEVEL SECURITY;
CREATE POLICY entity_isolation ON payment_snapshots
  USING (legal_entity_id = current_setting('app.legal_entity_id'));
```

---

## `tms-cash-ecf` — Expected Cash Flows

```sql
CREATE TABLE expected_cash_flows (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  legal_entity_id VARCHAR(50) NOT NULL,
  account_id      UUID NOT NULL,
  currency        CHAR(3) NOT NULL,
  amount          NUMERIC(24,8) NOT NULL,   -- positive = inflow, negative = outflow
  flow_direction  VARCHAR(10) NOT NULL,      -- INFLOW | OUTFLOW
  flow_status     VARCHAR(20) NOT NULL,      -- ANTICIPATED | CONFIRMED | SETTLED | CANCELLED
  value_date      DATE NOT NULL,
  source_type     VARCHAR(50) NOT NULL,      -- PAYMENT | TRADE | SETTLEMENT | IHB | MANUAL | INTEREST
  source_id       UUID NOT NULL,
  narrative       TEXT,
  created_at      TIMESTAMPTZ NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL,
  version         BIGINT NOT NULL DEFAULT 1
) PARTITION BY RANGE (value_date);

-- Amendment history (append-only)
CREATE TABLE ecf_amendments (
  id              UUID PRIMARY KEY,
  flow_id         UUID NOT NULL,
  previous_amount NUMERIC(24,8),
  new_amount      NUMERIC(24,8),
  previous_value_date DATE,
  new_value_date  DATE,
  amendment_reason TEXT,
  amended_by      VARCHAR(100),
  amended_at      TIMESTAMPTZ NOT NULL
) PARTITION BY RANGE (amended_at);

CREATE INDEX ON expected_cash_flows (account_id, currency, value_date, flow_status);
CREATE INDEX ON expected_cash_flows (source_type, source_id);
CREATE INDEX ON expected_cash_flows (legal_entity_id, value_date);
```

---

## `tms-cash-bat` — Bank Account Transactions

```sql
CREATE TABLE bank_statements (
  id              UUID PRIMARY KEY,
  legal_entity_id VARCHAR(50) NOT NULL,
  account_id      UUID NOT NULL,
  bank_id         UUID NOT NULL,
  statement_date  DATE NOT NULL,
  opening_balance NUMERIC(24,8) NOT NULL,
  closing_balance NUMERIC(24,8) NOT NULL,
  currency        CHAR(3) NOT NULL,
  message_standard VARCHAR(10) NOT NULL,    -- MT940 | CAMT053
  source_file_id  UUID,
  received_at     TIMESTAMPTZ NOT NULL,
  UNIQUE (account_id, statement_date)
);

CREATE TABLE bank_transactions (
  id              UUID PRIMARY KEY,
  statement_id    UUID NOT NULL REFERENCES bank_statements(id),
  legal_entity_id VARCHAR(50) NOT NULL,
  account_id      UUID NOT NULL,
  amount          NUMERIC(24,8) NOT NULL,
  currency        CHAR(3) NOT NULL,
  entry_direction VARCHAR(10) NOT NULL,     -- CREDIT | DEBIT
  value_date      DATE NOT NULL,
  booking_date    DATE,
  entry_reference VARCHAR(200),
  narrative       TEXT,
  proprietary_code VARCHAR(50),
  match_status    VARCHAR(20) NOT NULL DEFAULT 'UNMATCHED',
  matched_flow_id UUID,
  matched_at      TIMESTAMPTZ
) PARTITION BY RANGE (value_date);

CREATE TABLE bank_fee_statements (
  id              UUID PRIMARY KEY,
  account_id      UUID NOT NULL,
  bank_id         UUID NOT NULL,
  period          VARCHAR(7) NOT NULL,       -- YYYY-MM
  total_fees      NUMERIC(24,8) NOT NULL,
  currency        CHAR(3) NOT NULL,
  source_file_id  UUID,
  received_at     TIMESTAMPTZ NOT NULL,
  UNIQUE (account_id, period)
);

CREATE TABLE bank_fee_line_items (
  id              UUID PRIMARY KEY,
  fee_statement_id UUID NOT NULL REFERENCES bank_fee_statements(id),
  service_code    VARCHAR(50) NOT NULL,
  service_name    VARCHAR(200),
  charged_amount  NUMERIC(24,8) NOT NULL,
  expected_amount NUMERIC(24,8),
  currency        CHAR(3) NOT NULL,
  discrepancy     NUMERIC(24,8)
);

CREATE INDEX ON bank_transactions (account_id, value_date, match_status);
CREATE INDEX ON bank_transactions (entry_reference);
```

---

## `tms-cash-position` — Cash Ladder (CQRS Read Model)

```sql
-- Maintained by consuming ECF and BAT events
CREATE TABLE cash_positions (
  account_id                  UUID NOT NULL,
  currency                    CHAR(3) NOT NULL,
  value_date                  DATE NOT NULL,
  legal_entity_id             VARCHAR(50) NOT NULL,
  opening_balance             NUMERIC(24,8) NOT NULL DEFAULT 0,
  -- Confirmed flows (from SETTLED ECFs and matched BAT)
  confirmed_inflows           NUMERIC(24,8) NOT NULL DEFAULT 0,
  confirmed_outflows          NUMERIC(24,8) NOT NULL DEFAULT 0,
  -- Anticipated flows (from ANTICIPATED ECFs)
  anticipated_inflows         NUMERIC(24,8) NOT NULL DEFAULT 0,
  anticipated_outflows        NUMERIC(24,8) NOT NULL DEFAULT 0,
  -- Balance types
  book_balance                NUMERIC(24,8) NOT NULL DEFAULT 0,  -- opening + confirmed net
  available_balance           NUMERIC(24,8) NOT NULL DEFAULT 0,  -- book - holds + confirmed credits
  ledger_balance              NUMERIC(24,8) NOT NULL DEFAULT 0,  -- bank-confirmed only
  -- Reporting currency equivalent (updated on FX rate change)
  reporting_currency          CHAR(3),
  reporting_amount            NUMERIC(24,8),
  fx_rate_applied             NUMERIC(18,10),
  fx_rate_timestamp           TIMESTAMPTZ,
  updated_at                  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (account_id, currency, value_date)
) PARTITION BY RANGE (value_date);

-- Intraday snapshots (short-lived, 7-day retention)
CREATE TABLE intraday_snapshots (
  id              UUID PRIMARY KEY,
  account_id      UUID NOT NULL,
  currency        CHAR(3) NOT NULL,
  snapshot_time   TIMESTAMPTZ NOT NULL,
  current_balance NUMERIC(24,8) NOT NULL,
  pending_credits NUMERIC(24,8) NOT NULL DEFAULT 0,
  pending_debits  NUMERIC(24,8) NOT NULL DEFAULT 0,
  available_balance NUMERIC(24,8) NOT NULL
) PARTITION BY RANGE (snapshot_time);

-- EOD positions (long-lived — 7y)
CREATE TABLE eod_positions (
  account_id      UUID NOT NULL,
  currency        CHAR(3) NOT NULL,
  eod_date        DATE NOT NULL,
  legal_entity_id VARCHAR(50) NOT NULL,
  confirmed_closing_balance NUMERIC(24,8) NOT NULL,
  ledger_balance  NUMERIC(24,8) NOT NULL,
  finalised_at    TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (account_id, currency, eod_date)
) PARTITION BY RANGE (eod_date);

CREATE INDEX ON cash_positions (legal_entity_id, currency, value_date);
```

---

## `tms-accounting` — Extended Schema

```sql
-- Chart of Accounts
CREATE TABLE coa_classes (
  id              UUID PRIMARY KEY,
  code            VARCHAR(10) NOT NULL UNIQUE,
  name            VARCHAR(100) NOT NULL,
  class_type      VARCHAR(20) NOT NULL      -- ASSET | LIABILITY | EQUITY | INCOME | EXPENSE
);

CREATE TABLE coa_accounts (
  id              UUID PRIMARY KEY,
  legal_entity_id VARCHAR(50) NOT NULL,
  parent_id       UUID REFERENCES coa_accounts(id),
  code            VARCHAR(20) NOT NULL,
  name            VARCHAR(200) NOT NULL,
  account_type    VARCHAR(20) NOT NULL,     -- CONTROL | POSTING | SUBTOTAL
  ledger          VARCHAR(20) NOT NULL,     -- IFRS | US_GAAP | LOCAL_GAAP | MGMT
  class_id        UUID NOT NULL REFERENCES coa_classes(id),
  normal_balance  VARCHAR(6) NOT NULL,      -- DEBIT | CREDIT
  active          BOOLEAN NOT NULL DEFAULT TRUE,
  effective_from  DATE NOT NULL,
  effective_to    DATE,
  UNIQUE (legal_entity_id, ledger, code)
);

-- Journals
CREATE TABLE accounting_journals (
  id              UUID PRIMARY KEY,
  legal_entity_id VARCHAR(50) NOT NULL,
  ledger          VARCHAR(20) NOT NULL,
  status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  period          VARCHAR(7) NOT NULL,
  sub_period      VARCHAR(10),              -- daily sub-period for accruals: YYYY-MM-DD
  journal_type    VARCHAR(50) NOT NULL,     -- PAYMENT | TRADE | ACCRUAL | REVERSAL | ADJUSTMENT | HEDGE
  source_id       UUID,
  source_type     VARCHAR(50),
  total_debits    NUMERIC(24,8) NOT NULL,
  total_credits   NUMERIC(24,8) NOT NULL,
  posted_at       TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL,
  CONSTRAINT balanced_journal CHECK (total_debits = total_credits)
);

-- Journal entries
CREATE TABLE accounting_entries (
  id              UUID PRIMARY KEY,
  journal_id      UUID NOT NULL REFERENCES accounting_journals(id),
  legal_entity_id VARCHAR(50) NOT NULL,
  ledger          VARCHAR(20) NOT NULL,
  coa_account_id  UUID NOT NULL REFERENCES coa_accounts(id),
  debit_amount    NUMERIC(24,8) NOT NULL DEFAULT 0,
  credit_amount   NUMERIC(24,8) NOT NULL DEFAULT 0,
  currency        CHAR(3) NOT NULL,
  posting_date    DATE NOT NULL,
  narrative       TEXT,
  reversed_by     UUID,
  reversal_of     UUID,
  created_at      TIMESTAMPTZ NOT NULL,
  CONSTRAINT one_side_only CHECK (
    (debit_amount = 0 AND credit_amount > 0) OR
    (credit_amount = 0 AND debit_amount > 0)
  )
) PARTITION BY RANGE (posting_date);

-- Accrual entries (separate table for query performance)
CREATE TABLE accrual_entries (
  id                UUID PRIMARY KEY,
  journal_id        UUID NOT NULL REFERENCES accounting_journals(id),
  instrument_id     UUID NOT NULL,
  instrument_type   VARCHAR(50) NOT NULL,
  legal_entity_id   VARCHAR(50) NOT NULL,
  ledger            VARCHAR(20) NOT NULL,
  accrual_type      VARCHAR(30) NOT NULL,   -- INTEREST | AMORTISATION | COMMITMENT_FEE
  accrual_date      DATE NOT NULL,
  days_accrued      INT NOT NULL,
  accrual_rate      NUMERIC(18,10) NOT NULL,
  principal_amount  NUMERIC(24,8) NOT NULL,
  accrual_amount    NUMERIC(24,8) NOT NULL,
  currency          CHAR(3) NOT NULL,
  day_count_convention VARCHAR(20) NOT NULL,
  is_reversal       BOOLEAN NOT NULL DEFAULT FALSE,
  reversal_of       UUID,
  created_at        TIMESTAMPTZ NOT NULL,
  UNIQUE (instrument_id, accrual_date, accrual_type, ledger, is_reversal)  -- dedup key
) PARTITION BY RANGE (accrual_date);

-- Accounting periods (one row per period per ledger per entity)
CREATE TABLE accounting_periods (
  period          VARCHAR(7) NOT NULL,
  ledger          VARCHAR(20) NOT NULL,
  legal_entity_id VARCHAR(50) NOT NULL,
  status          VARCHAR(10) NOT NULL DEFAULT 'OPEN',
  closed_at       TIMESTAMPTZ,
  closed_by       VARCHAR(100),
  PRIMARY KEY (period, ledger, legal_entity_id)
);

-- Ledger balances (denormalised for fast reporting)
CREATE TABLE ledger_balances (
  coa_account_id  UUID NOT NULL REFERENCES coa_accounts(id),
  period          VARCHAR(7) NOT NULL,
  ledger          VARCHAR(20) NOT NULL,
  legal_entity_id VARCHAR(50) NOT NULL,
  opening_balance NUMERIC(24,8) NOT NULL DEFAULT 0,
  total_debits    NUMERIC(24,8) NOT NULL DEFAULT 0,
  total_credits   NUMERIC(24,8) NOT NULL DEFAULT 0,
  closing_balance NUMERIC(24,8) NOT NULL DEFAULT 0,
  updated_at      TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (coa_account_id, period, ledger, legal_entity_id)
);

-- Hedge accounting
CREATE TABLE hedge_designations (
  id                    UUID PRIMARY KEY,
  legal_entity_id       VARCHAR(50) NOT NULL,
  hedge_type            VARCHAR(30) NOT NULL,  -- FAIR_VALUE | CASH_FLOW | NET_INVESTMENT
  hedged_item_id        UUID NOT NULL,
  hedged_item_type      VARCHAR(50) NOT NULL,
  hedging_instrument_id UUID NOT NULL,
  designated_at         DATE NOT NULL,
  dedesignated_at       DATE,
  status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  isda_hedge_doc_ref    VARCHAR(200)
);

CREATE TABLE hedge_effectiveness_tests (
  id              UUID PRIMARY KEY,
  hedge_id        UUID NOT NULL REFERENCES hedge_designations(id),
  test_date       DATE NOT NULL,
  effectiveness_pct NUMERIC(8,4) NOT NULL,
  result          VARCHAR(15) NOT NULL,     -- EFFECTIVE | INEFFECTIVE
  oci_amount      NUMERIC(24,8),
  pnl_amount      NUMERIC(24,8),
  currency        CHAR(3) NOT NULL,
  tested_by       VARCHAR(100)
);

-- Closed period guard (trigger)
CREATE OR REPLACE FUNCTION prevent_closed_period_posting()
RETURNS TRIGGER AS $$
DECLARE period_rec RECORD;
BEGIN
  SELECT status INTO period_rec
  FROM accounting_periods
  WHERE period = to_char(NEW.posting_date, 'YYYY-MM')
    AND ledger = (SELECT ledger FROM accounting_journals WHERE id = NEW.journal_id)
    AND legal_entity_id = NEW.legal_entity_id;
  IF period_rec.status = 'CLOSED' THEN
    RAISE EXCEPTION 'Cannot post to closed period %', to_char(NEW.posting_date, 'YYYY-MM');
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER check_period_open
  BEFORE INSERT ON accounting_entries
  FOR EACH ROW EXECUTE FUNCTION prevent_closed_period_posting();
```

---

## `tms-risk` — Risk and Limit Management

```sql
CREATE TABLE risk_limits (
  id              UUID PRIMARY KEY,
  legal_entity_id VARCHAR(50) NOT NULL,
  limit_type      VARCHAR(50) NOT NULL,     -- COUNTERPARTY_CREDIT | CURRENCY | BOOK | OVERNIGHT | INTRADAY
  counterparty_id UUID,
  currency        CHAR(3),
  book_id         UUID,
  limit_amount    NUMERIC(24,8) NOT NULL,
  limit_currency  CHAR(3) NOT NULL,
  soft_threshold_pct NUMERIC(5,2) DEFAULT 80.00,
  effective_from  DATE NOT NULL,
  effective_to    DATE,
  status          VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
  approved_by     VARCHAR(100),
  created_at      TIMESTAMPTZ NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL,
  version         BIGINT NOT NULL DEFAULT 1
);

-- Current utilisation (updated in real-time by event consumers)
CREATE TABLE limit_utilisations (
  limit_id            UUID NOT NULL REFERENCES risk_limits(id),
  current_exposure    NUMERIC(24,8) NOT NULL DEFAULT 0,
  currency            CHAR(3) NOT NULL,
  utilisation_pct     NUMERIC(8,4) NOT NULL DEFAULT 0,
  last_updated_at     TIMESTAMPTZ NOT NULL,
  contributing_trades UUID[],              -- top N trade IDs for drill-down
  PRIMARY KEY (limit_id)
);

-- Exposure per counterparty (aggregated across all instruments)
CREATE TABLE counterparty_exposures (
  counterparty_id     UUID NOT NULL,
  legal_entity_id     VARCHAR(50) NOT NULL,
  exposure_type       VARCHAR(30) NOT NULL, -- PSR | REPLACEMENT_COST | CURRENT_EXPOSURE
  currency            CHAR(3) NOT NULL,
  exposure_amount     NUMERIC(24,8) NOT NULL,
  calculated_at       TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (counterparty_id, legal_entity_id, exposure_type)
);

-- FX Net Open Position per currency
CREATE TABLE fx_net_open_positions (
  legal_entity_id     VARCHAR(50) NOT NULL,
  currency            CHAR(3) NOT NULL,
  net_position        NUMERIC(24,8) NOT NULL,   -- in currency native units
  reporting_currency  CHAR(3) NOT NULL,
  reporting_amount    NUMERIC(24,8) NOT NULL,
  calculated_at       TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (legal_entity_id, currency)
);

-- Limit breach audit trail (append-only)
CREATE TABLE limit_breaches (
  id              UUID PRIMARY KEY,
  limit_id        UUID NOT NULL REFERENCES risk_limits(id),
  breach_type     VARCHAR(10) NOT NULL,   -- SOFT | HARD
  exposure_at_breach NUMERIC(24,8) NOT NULL,
  currency        CHAR(3) NOT NULL,
  triggered_by    UUID,                   -- tradeId or paymentId
  detected_at     TIMESTAMPTZ NOT NULL,
  status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  resolved_at     TIMESTAMPTZ
) PARTITION BY RANGE (detected_at);

-- Limit override approvals
CREATE TABLE limit_override_approvals (
  id              UUID PRIMARY KEY,
  limit_id        UUID NOT NULL,
  trade_id        UUID NOT NULL,
  requested_by    VARCHAR(100) NOT NULL,
  override_reason TEXT NOT NULL,
  status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  approved_by     VARCHAR(100),
  approved_at     TIMESTAMPTZ,
  expires_at      TIMESTAMPTZ NOT NULL
);
```

---

## `tms-fx-rates` — FX Rate Store

```sql
CREATE TABLE fx_rates (
  id              UUID PRIMARY KEY,
  base_currency   CHAR(3) NOT NULL,
  quote_currency  CHAR(3) NOT NULL,
  rate_type       VARCHAR(20) NOT NULL,    -- SPOT_MID | SPOT_BID | SPOT_ASK | ECB_REF | MANUAL
  rate            NUMERIC(18,10) NOT NULL,
  inverse_rate    NUMERIC(18,10),
  rate_source     VARCHAR(100) NOT NULL,   -- REUTERS | BLOOMBERG | ECB | MANUAL
  effective_at    TIMESTAMPTZ NOT NULL,
  approved        BOOLEAN NOT NULL DEFAULT TRUE,
  approved_by     VARCHAR(100),
  created_at      TIMESTAMPTZ NOT NULL
) PARTITION BY RANGE (effective_at);

-- Forward curves
CREATE TABLE fx_forward_curves (
  id              UUID PRIMARY KEY,
  base_currency   CHAR(3) NOT NULL,
  quote_currency  CHAR(3) NOT NULL,
  value_date      DATE NOT NULL,           -- the forward delivery date
  forward_points  NUMERIC(18,10) NOT NULL,
  outright_rate   NUMERIC(18,10) NOT NULL,
  rate_source     VARCHAR(100) NOT NULL,
  effective_at    TIMESTAMPTZ NOT NULL,
  UNIQUE (base_currency, quote_currency, value_date, effective_at)
) PARTITION BY RANGE (effective_at);

-- Revaluation runs
CREATE TABLE revaluation_runs (
  id              UUID PRIMARY KEY,
  legal_entity_id VARCHAR(50) NOT NULL,
  valuation_date  DATE NOT NULL,
  reporting_currency CHAR(3) NOT NULL,
  rate_snapshot   JSONB NOT NULL,          -- { "EUR/USD": "1.0845", ... }
  total_unrealised_pnl NUMERIC(24,8),
  status          VARCHAR(20) NOT NULL,
  run_at          TIMESTAMPTZ NOT NULL,
  completed_at    TIMESTAMPTZ
);

-- Materialised latest rates (for fast queries — refreshed on each rate update)
CREATE MATERIALIZED VIEW fx_rates_latest AS
SELECT DISTINCT ON (base_currency, quote_currency, rate_type)
  base_currency, quote_currency, rate_type, rate, inverse_rate, rate_source, effective_at
FROM fx_rates
ORDER BY base_currency, quote_currency, rate_type, effective_at DESC;

CREATE UNIQUE INDEX ON fx_rates_latest (base_currency, quote_currency, rate_type);
```

---

## `tms-settlement` — Extended Schema

```sql
-- Settlement instructions (event-sourced)
CREATE TABLE settlement_events (
  id              BIGSERIAL,
  instruction_id  UUID NOT NULL,
  event_id        UUID NOT NULL UNIQUE,
  event_type      VARCHAR(100) NOT NULL,
  payload         JSONB NOT NULL,
  occurred_at     TIMESTAMPTZ NOT NULL,
  legal_entity_id VARCHAR(50) NOT NULL,
  PRIMARY KEY (instruction_id, id)
) PARTITION BY RANGE (occurred_at);

CREATE TABLE settlement_snapshots (
  instruction_id    UUID PRIMARY KEY,
  legal_entity_id   VARCHAR(50) NOT NULL,
  trade_id          UUID,
  payment_id        UUID,
  settlement_type   VARCHAR(20) NOT NULL,   -- CASH | SECURITIES | CLS | DVP
  status            VARCHAR(30) NOT NULL,
  deliverer_id      UUID NOT NULL,
  receiver_id       UUID NOT NULL,
  amount            NUMERIC(24,8) NOT NULL,
  currency          CHAR(3) NOT NULL,
  settle_date       DATE NOT NULL,
  network_channel   VARCHAR(50),
  network_ref       VARCHAR(200),
  ssi_id            UUID,
  version           BIGINT NOT NULL DEFAULT 1,
  created_at        TIMESTAMPTZ NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL
);

-- SSI lifecycle (not static — has amendments and effective dates)
CREATE TABLE settlement_ssis (
  id                UUID PRIMARY KEY,
  counterparty_id   UUID NOT NULL,
  legal_entity_id   VARCHAR(50) NOT NULL,
  currency          CHAR(3) NOT NULL,
  settlement_method VARCHAR(50) NOT NULL,
  bic               VARCHAR(11),
  iban              VARCHAR(34),
  account_number    VARCHAR(50),
  correspondent_bic VARCHAR(11),
  priority_rank     INT NOT NULL DEFAULT 1,
  effective_from    DATE NOT NULL,
  effective_to      DATE,
  status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  amendment_ref     VARCHAR(200),         -- SWIFT BANA message reference
  created_at        TIMESTAMPTZ NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL,
  version           BIGINT NOT NULL DEFAULT 1
);

-- Settlement netting
CREATE TABLE settlement_netting (
  id              UUID PRIMARY KEY,
  counterparty_id UUID NOT NULL,
  currency        CHAR(3) NOT NULL,
  settle_date     DATE NOT NULL,
  gross_amount    NUMERIC(24,8) NOT NULL,
  net_amount      NUMERIC(24,8) NOT NULL,
  status          VARCHAR(20) NOT NULL,
  netted_instruction_ids UUID[] NOT NULL,
  net_instruction_id UUID,               -- the single net instruction generated
  created_at      TIMESTAMPTZ NOT NULL
);

-- NOSTRO reconciliation breaks (per-settlement real-time)
CREATE TABLE nostro_recon_items (
  id              UUID PRIMARY KEY,
  instruction_id  UUID NOT NULL,
  bat_transaction_id UUID,
  legal_entity_id VARCHAR(50) NOT NULL,
  account_id      UUID NOT NULL,
  settle_date     DATE NOT NULL,
  expected_amount NUMERIC(24,8),
  actual_amount   NUMERIC(24,8),
  currency        CHAR(3) NOT NULL,
  match_status    VARCHAR(20) NOT NULL DEFAULT 'UNMATCHED',
  break_type      VARCHAR(30),            -- MISSING | AMOUNT_DIFF | DATE_DIFF | EXTRA
  break_amount    NUMERIC(24,8),
  matched_at      TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL
) PARTITION BY RANGE (settle_date);
```

---

## `tms-ihb` — In-House Bank

```sql
CREATE TABLE pobo_requests (
  id              UUID PRIMARY KEY,
  legal_entity_id VARCHAR(50) NOT NULL,   -- the master/treasury entity
  subsidiary_id   VARCHAR(50) NOT NULL,
  beneficiary_account_id UUID NOT NULL,
  amount          NUMERIC(24,8) NOT NULL,
  currency        CHAR(3) NOT NULL,
  requested_value_date DATE NOT NULL,
  subsidiary_reference VARCHAR(200),
  narrative       TEXT,
  status          VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
  payment_id      UUID,                   -- set when treasury makes the payment
  executed_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE virtual_accounts (
  id              UUID PRIMARY KEY,
  legal_entity_id VARCHAR(50) NOT NULL,   -- master entity
  subsidiary_id   VARCHAR(50) NOT NULL,
  master_account_id UUID NOT NULL,        -- physical bank account
  currency        CHAR(3) NOT NULL,
  current_balance NUMERIC(24,8) NOT NULL DEFAULT 0,
  status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE intercompany_loans (
  id              UUID PRIMARY KEY,
  lending_entity_id   VARCHAR(50) NOT NULL,
  borrowing_entity_id VARCHAR(50) NOT NULL,
  amount          NUMERIC(24,8) NOT NULL,
  currency        CHAR(3) NOT NULL,
  interest_rate   NUMERIC(18,10) NOT NULL,
  day_count_convention VARCHAR(20) NOT NULL,
  start_date      DATE NOT NULL,
  maturity_date   DATE NOT NULL,
  status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  accrued_interest NUMERIC(24,8) NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE intercompany_fx (
  id              UUID PRIMARY KEY,
  buying_entity_id  VARCHAR(50) NOT NULL,
  selling_entity_id VARCHAR(50) NOT NULL,
  buy_amount      NUMERIC(24,8) NOT NULL,
  buy_currency    CHAR(3) NOT NULL,
  sell_amount     NUMERIC(24,8) NOT NULL,
  sell_currency   CHAR(3) NOT NULL,
  internal_rate   NUMERIC(18,10) NOT NULL,
  value_date      DATE NOT NULL,
  status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE multilateral_netting (
  id              UUID PRIMARY KEY,
  legal_entity_id VARCHAR(50) NOT NULL,   -- coordinating entity
  period          VARCHAR(7) NOT NULL,
  participant_ids JSONB NOT NULL,
  gross_flows     NUMERIC(24,8),
  net_flows       NUMERIC(24,8),
  reduction_pct   NUMERIC(8,4),
  status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  net_instruction_ids UUID[],
  completed_at    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL
);
```

---

## `tms-confirmation-matching`

```sql
CREATE TABLE trade_confirmations (
  id              UUID PRIMARY KEY,
  trade_id        UUID NOT NULL,
  legal_entity_id VARCHAR(50) NOT NULL,
  counterparty_id UUID NOT NULL,
  message_id      UUID,                   -- inbound MT/MX message
  message_type    VARCHAR(10) NOT NULL,   -- MT300 | MT320 | MT330 | MX_FXTR
  confirmation_terms JSONB NOT NULL,
  match_status    VARCHAR(20) NOT NULL DEFAULT 'UNCONFIRMED',
  matched_at      TIMESTAMPTZ,
  match_method    VARCHAR(30),            -- INTERNAL | SWIFT_ACCORD | MARKITSERV | MANUAL
  external_ref    VARCHAR(200),
  received_at     TIMESTAMPTZ NOT NULL
);

CREATE TABLE confirmation_discrepancies (
  id              UUID PRIMARY KEY,
  confirmation_id UUID NOT NULL REFERENCES trade_confirmations(id),
  field_name      VARCHAR(100) NOT NULL,
  internal_value  TEXT,
  counterparty_value TEXT,
  discrepancy_type VARCHAR(30) NOT NULL   -- AMOUNT | DATE | RATE | ACCOUNT | OTHER
);

CREATE TABLE confirmation_disputes (
  id              UUID PRIMARY KEY,
  confirmation_id UUID NOT NULL REFERENCES trade_confirmations(id),
  dispute_reason  TEXT NOT NULL,
  status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  raised_by       VARCHAR(100) NOT NULL,
  raised_at       TIMESTAMPTZ NOT NULL,
  resolved_by     VARCHAR(100),
  resolved_at     TIMESTAMPTZ,
  resolution      TEXT
);
```

---

## `tms-reconciliation` — Extended Schema

```sql
CREATE TABLE recon_run_configs (
  id              UUID PRIMARY KEY,
  recon_type      VARCHAR(30) NOT NULL,   -- BANK_STATEMENT | POSITION | INTRAGROUP | ACCOUNTING_SUBLEDGER
  legal_entity_id VARCHAR(50) NOT NULL,
  name            VARCHAR(200) NOT NULL,
  schedule_cron   VARCHAR(50),
  active          BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE recon_runs (
  id              UUID PRIMARY KEY,
  config_id       UUID REFERENCES recon_run_configs(id),
  recon_type      VARCHAR(30) NOT NULL,
  legal_entity_id VARCHAR(50) NOT NULL,
  period          VARCHAR(7),
  account_id      UUID,
  status          VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
  total_items     INT,
  matched_count   INT DEFAULT 0,
  break_count     INT DEFAULT 0,
  auto_resolved   INT DEFAULT 0,
  started_at      TIMESTAMPTZ NOT NULL,
  completed_at    TIMESTAMPTZ
);

CREATE TABLE recon_matches (
  id              UUID PRIMARY KEY,
  recon_run_id    UUID NOT NULL REFERENCES recon_runs(id),
  recon_type      VARCHAR(30) NOT NULL,
  internal_ref    VARCHAR(200) NOT NULL,
  external_ref    VARCHAR(200) NOT NULL,
  matched_amount  NUMERIC(24,8),
  currency        CHAR(3),
  match_rule_id   UUID,
  match_method    VARCHAR(20) NOT NULL DEFAULT 'AUTO',
  matched_by      VARCHAR(100),
  matched_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE recon_breaks (
  id              UUID PRIMARY KEY,
  recon_run_id    UUID NOT NULL REFERENCES recon_runs(id),
  recon_type      VARCHAR(30) NOT NULL,
  legal_entity_id VARCHAR(50) NOT NULL,
  internal_ref    VARCHAR(200),
  external_ref    VARCHAR(200),
  break_type      VARCHAR(50) NOT NULL,   -- MISSING_INTERNAL | MISSING_EXTERNAL | AMOUNT_DIFF | DATE_DIFF
  break_amount    NUMERIC(24,8),
  currency        CHAR(3),
  break_reason    TEXT,
  age_days        INT NOT NULL DEFAULT 0,
  status          VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  resolved_by     VARCHAR(100),
  resolved_at     TIMESTAMPTZ,
  resolution      TEXT,
  created_at      TIMESTAMPTZ NOT NULL
) PARTITION BY RANGE (created_at);

CREATE TABLE match_rules (
  id              UUID PRIMARY KEY,
  recon_type      VARCHAR(30) NOT NULL,
  rule_name       VARCHAR(200) NOT NULL,
  priority        INT NOT NULL DEFAULT 100,
  conditions      JSONB NOT NULL,
  tolerance_amount NUMERIC(24,8),
  tolerance_pct   NUMERIC(8,4),
  active          BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL
);

-- Aged break alerting
CREATE INDEX ON recon_breaks (recon_type, status, created_at) WHERE status = 'OPEN';
```

---

## Summary Table (v2)

| Service | Store | Pattern | Partitioned | CQRS | Event Sourced |
|---------|-------|---------|-------------|------|---------------|
| tms-payment-hub | PostgreSQL | Event Store + Snapshot | Yes (monthly) | Partial | Yes |
| tms-cash-ecf | PostgreSQL | Mutable + History | Yes (monthly) | No | No |
| tms-cash-bat | PostgreSQL | Append-only | Yes (monthly) | No | No |
| tms-cash-position | PostgreSQL | CQRS Read Model | Yes (monthly) | Yes (read-only) | No |
| tms-settlement | PostgreSQL | Event Store + Snapshot | Yes (monthly) | No | Yes |
| tms-accounting | PostgreSQL | Append-only journals | Yes (monthly) | No | Partial |
| tms-risk | PostgreSQL | Mutable + Audit trail | Yes (breaches) | No | No |
| tms-fx-rates | PostgreSQL | Append + Materialised View | Yes (monthly) | No | No |
| tms-trade | PostgreSQL | Mutable + History | Yes (valuations) | No | No |
| tms-ihb | PostgreSQL | Standard | No | No | No |
| tms-confirmation-matching | PostgreSQL | Standard | No | No | No |
| tms-reconciliation | PostgreSQL | Standard | Yes (breaks) | No | No |
| tms-integration | PostgreSQL + MinIO | Standard + Object | No | No | No |
| tms-rules-engine | PostgreSQL | Versioned records | Yes (evaluations) | No | No |
| tms-reference-data | PostgreSQL + Redis | Standard + Cache | No | No | No |
| tms-bank-accounts | PostgreSQL | Standard | Yes (activity) | No | No |
| tms-compliance | PostgreSQL + OpenSearch | Standard + Search | No | No | No |
| tms-identity | PostgreSQL / Keycloak | Standard | Yes (activity) | No | No |
| tms-reporting | OpenSearch + PostgreSQL | Projections + CP | No | Read-only | No |
| tms-liquidity | PostgreSQL | Standard | Yes (flows) | No | No |
