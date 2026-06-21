# 07 — Saga Workflows (v2)

## What Changed from v1
- Added Pre-Trade Risk Limit Check saga (synchronous + async override path)
- Added Nightly Accrual Posting saga (runs for every interest-bearing instrument)
- Added Settlement Netting saga
- Added POBO Payment saga (IHB orchestrates treasury payment on behalf of subsidiary)
- Added Trade Maturity Processing saga (automatic at maturity — no human trigger)
- Clarified Compliance Alert saga (added auto-STP path when circuit breaker opens)
- Added `saga_step_results` table for idempotent step caching
- Timeout recovery: every timeout path now has an explicit STP vs escalate decision

---

## Saga Infrastructure

```sql
-- Saga instance state (per-service, identical schema)
CREATE TABLE saga_instances (
  saga_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  saga_type       VARCHAR(100) NOT NULL,
  entity_id       UUID NOT NULL,
  current_step    VARCHAR(100) NOT NULL,
  status          VARCHAR(20) NOT NULL,    -- RUNNING | COMPLETED | COMPENSATING | FAILED
  context         JSONB NOT NULL,
  correlation_id  UUID NOT NULL,
  legal_entity_id VARCHAR(50) NOT NULL,
  started_at      TIMESTAMPTZ NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL,
  completed_at    TIMESTAMPTZ,
  next_timeout_at TIMESTAMPTZ
);

-- Idempotent step result cache
CREATE TABLE saga_step_results (
  saga_id     UUID NOT NULL,
  step_name   VARCHAR(100) NOT NULL,
  result      JSONB NOT NULL,
  completed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (saga_id, step_name)
);

CREATE INDEX ON saga_instances (status, next_timeout_at) WHERE status = 'RUNNING';
CREATE INDEX ON saga_instances (saga_type, entity_id);
```

Idempotent step execution pattern (all sagas follow this):
```java
Result executeStep(SagaInstance saga, String stepName, Supplier<Result> action) {
    return saga.stepResult(stepName)
        .orElseGet(() -> {
            Result r = action.get();
            saga.recordStepResult(stepName, r);
            sagaRepository.save(saga);
            return r;
        });
}
```

Timeout check (per-service, runs every 60 seconds):
```java
@Scheduled(fixedDelay = 60_000)
void checkTimeouts() {
    sagaRepository.findTimedOut(Instant.now(clock)).forEach(timeoutHandler::handle);
}
```

---

## Saga 1 — Payment Processing

**Orchestrator:** `tms-payment-hub`
**Trigger:** `POST /api/v1/payments/{id}/submit` → command published to `tms.payment.create.q`

```
[START]
   │
   ▼
Step 1: VALIDATE
   │  → Rules Engine (gRPC): payment validation rule set
   │  [FAIL]  → emit PaymentValidationFailed; [END: REJECTED]
   │  [PASS]  → emit PaymentValidated
   │
   ▼
Step 2: ENRICH
   │  → Reference Data (REST): resolve BIC, counterparty, account details
   │  → SSI lookup for counterparty / currency
   │  [NO SSI FOUND] → emit PaymentEnrichmentFailed → repair queue; [END: EXCEPTION]
   │  [OK]  → emit PaymentEnriched
   │
   ▼
Step 3: SANCTIONS_SCREENING
   │  → tms-compliance POST /screen (sync, timeout = 10s)
   │  [CLEARED]    → continue
   │  [HELD]       → emit PaymentSanctionsHeld; [END: HELD — awaits compliance resolution]
   │  [TIMEOUT x1] → retry once (immediate)
   │  [TIMEOUT x2] → circuit breaker logic:
   │       if(CircuitBreaker.isOpen) → HOLD payment; emit PaymentComplianceHold;
   │                                   notify compliance ops; [END: MANUAL HOLD]
   │
   ▼
Step 4: APPROVAL ← (conditional: only if Rules Engine says approval_required)
   │  → Publish approval request to RabbitMQ tms.approval.payment.level{n}.q
   │  → await PaymentApproved / PaymentRejected event (listen on Kafka)
   │  [APPROVED]       → continue
   │  [REJECTED]       → emit PaymentRejected; [END: REJECTED]
   │  [TIMEOUT = 24h]  → check rule: auto_release_on_timeout? 
   │       YES: continue as APPROVED (STP); emit PaymentSTPApproved
   │       NO:  escalate to Level 2 queue; reset timeout = 4h
   │            [L2 TIMEOUT] → dead letter; P1 alert to ops
   │
   ▼
Step 5: RELEASE_TO_NETWORK
   │  → Route to network adapter (SWIFT/SEPA/ACH/RTGS) via network-specific queue
   │  → Network adapter sends message; awaits ACK
   │  [ACK received]     → emit PaymentReleased, PaymentAcknowledged
   │  [NACK / error]     → retry 3x (exponential backoff via tms.delayed.retry)
   │  [3 retries failed] → emit PaymentFailed; route to tms.payment.repair.q; [END: EXCEPTION]
   │  [TIMEOUT = 15min]  → check if message was sent (idempotency check on network ref)
   │       SENT but no ACK: wait additional 15min (value date still valid)
   │       NOT SENT:        retry from beginning of Step 5
   │
   ▼
Step 6: AWAIT_SETTLEMENT
   │  → Wait for PaymentSettled / PaymentFailed event from network (Kafka)
   │  [SETTLED]   → emit PaymentSettled; [END: SETTLED]
   │  [FAILED]    → emit PaymentFailed; route to repair queue; [END: EXCEPTION]
   │  [RETURN]    → emit PaymentReturnReceived; begin return processing; [END: RETURNED]
   │  [TIMEOUT = 2 business days] → P2 alert to ops; saga stays RUNNING awaiting manual resolution
   │
[END]

Compensation (triggered if saga transitions to COMPENSATING):
  Step 5 → send cancellation to network (best-effort; idempotent via network reference)
  Step 4 → withdraw pending approval from queue; notify approvers
  Steps 1–3 → no side effects to compensate
```

---

## Saga 2 — Pre-Trade Risk Limit Check

**Orchestrator:** `tms-trade` (inline, synchronous fast path; async override path for breaches)
**Trigger:** `POST /api/v1/trades` received

```
[START: synchronous fast path — must complete before trade is booked]
   │
   ▼
Step 1: CHECK_LIMIT (synchronous gRPC — timeout = 2s)
   │  → tms-risk gRPC: CheckLimit(counterpartyId, limitType, amount, currency)
   │  [APPROVED]          → continue to trade booking; saga ends inline
   │  [REJECTED: HARD]    → return 422 to caller; saga ends (trade not booked)
   │  [REQUIRES_OVERRIDE] → transition to async override path (below)
   │  [TIMEOUT]           → fail safe: reject trade; return 503; log for investigation
   │
[END: fast path]

--- Async override path (only if REQUIRES_OVERRIDE) ---
   │
   ▼
Step 2: REQUEST_OVERRIDE_APPROVAL
   │  → Publish to tms.risk.limit-override.request.q (RabbitMQ)
   │  → Return 202 to original caller: { tradeId, overrideId, status: PENDING_OVERRIDE }
   │
   ▼
Step 3: AWAIT_OVERRIDE_DECISION (listen on Kafka tms.risk.limits.lifecycle)
   │  [APPROVED]           → continue: book trade (issue BookTradeCommand)
   │  [REJECTED]           → emit TradeLimitOverrideRejected; notify requester; [END: REJECTED]
   │  [TIMEOUT = 1h]       → auto-reject; emit TradeLimitOverrideExpired; [END: REJECTED]
   │
   ▼
Step 4: BOOK_TRADE
   │  → Create trade record; emit TradeCaptured
   │  → Update limit utilisation in tms-risk (via event)
   │
[END]
```

---

## Saga 3 — Trade Settlement

**Orchestrator:** `tms-settlement`
**Trigger:** `TradeConfirmed` event from `tms.trade.confirmations.lifecycle`

```
[START]
   │
   ▼
Step 1: CREATE_INSTRUCTION
   │  → Derive settlement amount, dates, parties from trade
   │  → Lookup SSI: tms-bank-accounts API for counterparty + currency
   │  [SSI FOUND]     → emit SettlementInstructionCreated
   │  [NO SSI FOUND]  → emit SSIMissingAlert → manual assignment queue; [PAUSE: await SSI assignment]
   │                    Resume: SSIAssigned event → continue to Step 2
   │
   ▼
Step 2: EVALUATE_NETTING
   │  → Check netting agreements for counterparty + currency + settle_date
   │  [NETTING ELECTED] → batch instruction to netting group (handled by Settlement Netting Saga)
   │                      this saga ends; netting saga takes over
   │  [NO NETTING]     → continue
   │
   ▼
Step 3: CHECK_CUTOFF
   │  → Is settle_date > today? If yes: defer to settle_date - 1 business day
   │  → Is current time before cutoff for network_channel + currency?
   │  [BEFORE CUTOFF]  → continue
   │  [AFTER CUTOFF]   → defer to next business day; emit SettlementCutoffMissed; 
   │                     reschedule saga to next business day 08:00 local time
   │
   ▼
Step 4: VALIDATE_INSTRUCTION
   │  → Rules Engine: settlement validation rules
   │  [VALID]   → continue
   │  [INVALID] → emit InstructionValidationFailed; route to repair queue
   │
   ▼
Step 5: SEND_TO_NETWORK
   │  → Transmit instruction to custodian / SWIFT / CLS
   │  → await network ACK
   │  [ACK]          → emit SettlementSent
   │  [NACK / error] → retry 3x; then dead letter + alert
   │
   ▼
Step 6: AWAIT_CONFIRMATION
   │  → Wait for SettlementConfirmed / SettlementFailed (Kafka)
   │  [CONFIRMED] → emit SettlementConfirmed; trigger ECF update + accounting entries
   │  [FAILED]    → emit SettlementFailed; begin fail/repair workflow
   │  [TIMEOUT = EOD of settle_date] → emit SettlementPendingEOD; P2 alert; saga stays RUNNING
   │  [TIMEOUT + 1 day]              → escalate; P1 alert; CSDR penalty calculation triggered
   │
[END]

Compensation:
  Step 5 sent → send cancellation request; emit InstructionCancellationRequested
  Step 5 not sent → emit InstructionCancelled
```

---

## Saga 4 — Settlement Netting

**Orchestrator:** `tms-settlement`
**Trigger:** Either: (a) netting elected flag in Trade Settlement Saga, or (b) scheduled trigger at configured netting cut time

```
[START]
   │
   ▼
Step 1: COLLECT_INSTRUCTIONS
   │  → Gather all instructions for same counterparty + currency + settle_date
   │  → Wait for collection window (configurable: e.g., 5 min before netting cutoff)
   │
   ▼
Step 2: CALCULATE_NET
   │  → Sum inflows and outflows per counterparty pair + currency
   │  → Calculate net amount and direction (pay or receive)
   │  → Apply bilateral netting agreement terms
   │  → emit SettlementNettingApplied
   │
   ▼
Step 3: GENERATE_NET_INSTRUCTION
   │  → Create a single net settlement instruction
   │  → Link to all gross instructions (stored in netting_id)
   │  → Continue as standard settlement flow from Step 3 of Trade Settlement Saga
   │
[END]

No compensation needed: gross instructions are not sent; only the net is sent.
If netting calculation fails: fall back to gross instructions individually.
```

---

## Saga 5 — Nightly Accrual Posting

**Orchestrator:** `tms-accounting`
**Trigger:** Scheduled nightly job, 22:00 UTC (configurable per entity / timezone)

```
[START: per legal entity, per ledger]
   │
   ▼
Step 1: CHECK_PERIOD_OPEN
   │  → Is current date's sub-period open for posting?
   │  [OPEN]   → continue
   │  [CLOSED] → skip (period already closed for this date); [END: SKIPPED]
   │
   ▼
Step 2: LOAD_ACTIVE_INSTRUMENTS
   │  → Query tms-trade for all ACTIVE interest-bearing instruments
   │    (deposits, loans, IRS floating legs, repos, bonds, IHB intercompany loans)
   │  → Also: tms-bank-accounts for account interest rates
   │  → Also: tms-ihb for intercompany loan accruals
   │
   ▼
Step 3: CALCULATE_ACCRUALS (per instrument — parallelised by Spring Batch partition)
   │  For each instrument:
   │    interest_amount = principal × rate × (days / day_count_basis)
   │    day_count_basis per instrument's dayCountConvention (ACT/360, ACT/365, ACT/ACT, 30/360)
   │  → Determine CoA accounts from Rules Engine (which debit/credit for this instrument type + ledger)
   │
   ▼
Step 4: POST_ACCRUAL_ENTRIES
   │  → Idempotency: UNIQUE constraint on (instrument_id, accrual_date, accrual_type, ledger, is_reversal)
   │  → Create one journal per batch (configurable batch size, e.g. 1000 entries per journal)
   │  → emit AccrualEntryPosted, JournalPosted
   │  [FAIL on any instrument] → that instrument's accrual goes to error log; 
   │                             saga continues for remaining; alerts ops
   │
   ▼
Step 5: POST_REVERSAL_FOR_PRIOR_ACCRUAL
   │  → Reverse yesterday's accrual entries (accrual-reversal pattern)
   │  → Creates mirror entries: debit ↔ credit reversed for each yesterday entry
   │  → Idempotency: (instrument_id, yesterday's date, accrual_type, ledger, is_reversal=true) UNIQUE
   │
   ▼
Step 6: UPDATE_LEDGER_BALANCES
   │  → Materialise ledger_balances for affected CoA accounts + period
   │
   ▼
Step 7: EMIT_COMPLETION
   │  → emit AccrualRunCompleted { date, instrumentCount, totalAccrualAmount, errorCount }
   │
[END]

Compensation: none (reversals handle correction).
Re-run is idempotent: duplicate protection by UNIQUE constraint on accrual_entries.
```

---

## Saga 6 — Trade Maturity Processing

**Orchestrator:** `tms-trade`
**Trigger:** Scheduled daily job checks for trades where `maturity_date = today(clock)` and `status = ACTIVE`

```
[START: per maturing trade]
   │
   ▼
Step 1: VALIDATE_MATURITY
   │  → Confirm trade is still ACTIVE (not cancelled, not already matured)
   │  → Calculate final principal + interest due
   │  → Confirm today is a business day in the trade's settlement calendar
   │  [NOT BUSINESS DAY] → defer to next business day; reschedule
   │
   ▼
Step 2: GENERATE_SETTLEMENT_INSTRUCTION
   │  → Emit TradeMatured event with principal + interest amounts
   │  → tms-settlement consumes event → creates settlement instructions automatically
   │  → ECF flows created for maturity date
   │
   ▼
Step 3: GENERATE_ACCOUNTING_ENTRIES
   │  → tms-accounting consumes TradeMatured event
   │  → Posts: principal repayment (debit/credit per instrument type)
   │  → Posts: final interest (any remaining accrual not yet posted)
   │
   ▼
Step 4: MARK_TRADE_MATURED
   │  → Update trade status to MATURED
   │  → Emit TradeMatured (idempotently)
   │
[END]

No compensation: maturity is a terminal state; reversals done via Accounting reversal workflow if error.
```

---

## Saga 7 — POBO Payment (In-House Bank)

**Orchestrator:** `tms-ihb`
**Trigger:** Subsidiary submits POBO request via `POST /api/v1/ihb/pobo-requests`

```
[START]
   │
   ▼
Step 1: VALIDATE_POBO_REQUEST
   │  → Confirm subsidiary is authorised for POBO on this master account
   │  → Confirm virtual account has sufficient "balance" (internal tracking)
   │  → Confirm beneficiary account is valid (tms-bank-accounts validation)
   │  [INVALID] → reject with reason; [END: REJECTED]
   │
   ▼
Step 2: RESERVE_VIRTUAL_BALANCE
   │  → Debit subsidiary's virtual account (reduce internal balance)
   │  → This is internal bookkeeping only — no real money moves yet
   │  → Emit COBOFundsReserved
   │
   ▼
Step 3: INITIATE_EXTERNAL_PAYMENT
   │  → Publish CreatePaymentCommand to tms-payment-hub
   │    (with poboSubsidiaryId, masterAccountId as debit)
   │  → Payment Hub creates a POBO-flagged payment
   │  → await PaymentSettled / PaymentFailed event (Kafka, correlationId = poboRequestId)
   │  [SETTLED]   → continue
   │  [FAILED]    → reverse virtual balance (compensation); emit POBOFailed; [END: FAILED]
   │  [TIMEOUT]   → check payment status via API; if failed: compensate
   │
   ▼
Step 4: RECONCILE_AND_CLOSE
   │  → Mark POBO request as EXECUTED
   │  → Generate intercompany accounting entry:
   │    Debit: Subsidiary payable (POBO liability cleared)
   │    Credit: Master account (real payment made)
   │  → emit POBOPaymentExecuted
   │
[END]

Compensation (if external payment fails):
  → Credit back subsidiary's virtual account (reverse Step 2)
  → Mark POBO as FAILED with reason
  → Notify subsidiary
```

---

## Saga 8 — Bank Reconciliation

**Orchestrator:** `tms-reconciliation`
**Trigger:** `BankStatementReceived` event OR scheduled daily job

```
[START]
   │
   ▼
Step 1: LOAD_DATA
   │  → Fetch bank transactions from tms-cash-bat (for account + date range)
   │  → Fetch expected cash flows from tms-cash-ecf (confirmed, for same account + dates)
   │  → Both fetched via REST API; results stored in saga context
   │
   ▼
Step 2: RUN_AUTO_MATCH (Spring Batch partitioned step, parallelised)
   │  → Apply match rules in priority order:
   │    Rule 1: exact reference match (entry_reference = ECF source_id)
   │    Rule 2: exact amount + value_date match
   │    Rule 3: amount within tolerance (±0.01) + value_date match
   │    Rule 4: narrative keyword match + approximate amount
   │  → For each match: create recon_match record
   │  → For each BAT with no match: create recon_break (MISSING_INTERNAL)
   │  → For each ECF with no matching BAT: create recon_break (MISSING_EXTERNAL)
   │
   ▼
Step 3: AUTO_RESOLVE_BREAKS
   │  → For breaks matching configured auto-resolution rules:
   │    (e.g., bank charges < €10 → auto-resolve as BANK_CHARGE)
   │  → Generates accounting entries for auto-resolved breaks (via tms-accounting)
   │
   ▼
Step 4: ROUTE_OPEN_BREAKS
   │  → Breaks not auto-resolved → status = OPEN → appear in reconciliation dashboard
   │  → Aged breaks (> N days): emit ReconciliationBreakEscalated; notify ops team
   │
   ▼
Step 5: COMPLETE
   │  → Update recon_runs record with final counts
   │  → emit ReconciliationRunCompleted
   │
[END]

Re-run is idempotent: clears all matches/breaks for same reconId before re-running.
```

---

## Saga 9 — Compliance Alert

**Orchestrator:** `tms-compliance`
**Trigger:** `SanctionsAlertGenerated` event

```
[START]
   │
   ▼
Step 1: ASSESS_CONFIDENCE
   │  [EXACT or FUZZY_HIGH ≥ 85%] → hold payment; escalate immediately
   │  [FUZZY_LOW < 85%]           → notify for review; payment not held
   │  [PEP flag]                  → flag for EDD; payment not held by default
   │
   ▼
Step 2: HOLD_PAYMENT (if applicable)
   │  → emit PaymentSanctionsHeld to Kafka (tms.payment.payments.sanctions)
   │  → Payment Hub puts payment in HELD status
   │
   ▼
Step 3: NOTIFY_COMPLIANCE_OFFICER
   │  → Publish to tms.notifications.compliance.q (RabbitMQ, priority=10)
   │  → Compliance officer receives in-app push + email
   │
   ▼
Step 4: AWAIT_REVIEW
   │  [RESOLVED: CLEARED] → emit PaymentSanctionsCleared → Payment Hub resumes saga
   │  [RESOLVED: BLOCKED] → emit PaymentCancelled; report for regulatory filing
   │  [TIMEOUT = 4h — FUZZY_LOW] → auto-resolve as false positive; emit AlertAutoResolved
   │  [TIMEOUT = 1h — EXACT/HIGH] → auto-escalate to senior compliance; reset timeout = 2h
   │  [TIMEOUT = 2h — escalated]  → P1 alert to compliance director; saga stays RUNNING
   │
[END]
```

---

## Saga Timeout Recovery Matrix

| Saga | Step | Timeout | Action |
|------|------|---------|--------|
| Payment Processing | SANCTIONS_SCREENING | 10s | Retry 1x; then circuit-breaker-aware hold |
| Payment Processing | APPROVAL | 24h | Check rule: STP or L2 escalation |
| Payment Processing | RELEASE_TO_NETWORK | 15min | Retry 3x; then exception queue |
| Payment Processing | AWAIT_SETTLEMENT | 2 business days | P2 alert; saga stays RUNNING |
| Pre-Trade Risk Check | AWAIT_OVERRIDE | 1h | Auto-reject override request |
| Trade Settlement | AWAIT_CONFIRMATION | EOD | P2 alert; +1 day = P1 + CSDR check |
| Accrual Posting | Per-instrument error | N/A | Skip instrument; continue; alert |
| Trade Maturity | Non-business-day | N/A | Defer to next business day |
| POBO Payment | AWAIT_EXTERNAL_PAYMENT | 2 business days | Check status; compensate if failed |
| Compliance Alert | AWAIT_REVIEW (EXACT) | 1h | Escalate to senior |
| Compliance Alert | AWAIT_REVIEW (FUZZY_LOW) | 4h | Auto-resolve as false positive |
