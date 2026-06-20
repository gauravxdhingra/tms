# 04 — Messaging Topology (v2)

## What Changed from v1
- Cash domain topics split into three namespaces: `ecf`, `bat`, `position`
- Added Risk domain topics (limit checks, exposure, NOP)
- Added FX Rate topics (rate publication, revaluation)
- Added IHB topics (POBO, COBO, intercompany, netting)
- Added Settlement netting and CLS topics
- Added Confirmation Matching topics
- Added Accrual topics (inside accounting namespace)
- Added Bank Account lifecycle topics
- SSI lifecycle topics added inside settlement namespace
- Partitions and retention updated to reflect corrected domain volumes

---

## Kafka Topic Naming Convention

```
tms.<domain>.<entity>.<category>

domain    = integration | messaging | payment | cash.ecf | cash.bat | cash.position |
            liquidity | trade | risk | settlement | ihb | confirmation |
            accounting | reconciliation | compliance | fx | refdata | bank-accounts | audit

category  = lifecycle | events | alerts | snapshots | commands (rare) | DLT
```

---

## Kafka Topics — Full Catalogue

### Integration Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.integration.files.lifecycle` | 12 | 30d | `fileId` | tms-integration | tms-message-manager, tms-reporting |
| `tms.integration.messages.inbound` | 24 | 30d | `correlationId` | tms-integration | tms-message-manager |
| `tms.integration.messages.duplicates` | 6 | 30d | `messageId` | tms-integration | tms-reporting |
| `tms.integration.messages.status` | 12 | 30d | `messageId` | tms-integration | tms-reporting |
| `tms.integration.files.lifecycle.DLT` | 3 | 7d | — | Kafka DLT | tms-integration (ops) |

### Message Processing Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.messaging.canonical.lifecycle` | 24 | 30d | `messageId` | tms-message-manager | tms-payment-hub, tms-trade, tms-settlement, tms-ihb |
| `tms.messaging.routing.decisions` | 12 | 30d | `messageId` | tms-message-manager | tms-reporting |
| `tms.messaging.outbound.generated` | 12 | 30d | `messageId` | tms-message-manager | tms-integration |
| `tms.messaging.translations.mx` | 12 | 30d | `messageId` | tms-message-manager | tms-reporting |

### Payment Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.payment.payments.lifecycle` | 48 | **7y** | `paymentId` | tms-payment-hub | tms-cash-ecf, tms-settlement, tms-accounting, tms-reporting, tms-reconciliation |
| `tms.payment.payments.sanctions` | 24 | **7y** | `paymentId` | tms-payment-hub | tms-compliance |
| `tms.payment.payments.approvals` | 12 | 90d | `paymentId` | tms-payment-hub | tms-reporting |
| `tms.payment.payments.exceptions` | 12 | 90d | `paymentId` | tms-payment-hub | tms-reporting |
| `tms.payment.payments.network` | 24 | **7y** | `paymentId` | tms-payment-hub | tms-accounting, tms-reconciliation, tms-reporting |
| `tms.payment.payments.lifecycle.DLT` | 6 | 30d | — | Kafka DLT | tms-payment-hub (ops console) |

### Expected Cash Flows (ECF)

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.cash.ecf.lifecycle` | 48 | **7y** | `accountId+currency+valueDate` | tms-cash-ecf | tms-cash-position, tms-liquidity, tms-reporting |
| `tms.cash.ecf.amendments` | 24 | **7y** | `flowId` | tms-cash-ecf | tms-cash-position, tms-reporting |

### Bank Account Transactions (BAT)

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.cash.bat.statements` | 12 | **7y** | `accountId+statementDate` | tms-cash-bat | tms-cash-position, tms-reconciliation, tms-reporting |
| `tms.cash.bat.transactions` | 48 | **7y** | `accountId+currency+valueDate` | tms-cash-bat | tms-cash-position, tms-settlement (NOSTRO), tms-reconciliation, tms-reporting |
| `tms.cash.bat.fee-statements` | 6 | **7y** | `accountId+period` | tms-cash-bat | tms-bank-accounts, tms-reporting |

### Cash Position (Cash Ladder)

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.cash.position.updated` | 24 | **7y** | `accountId+currency+valueDate` | tms-cash-position | tms-liquidity, tms-reporting, tms-accounting |
| `tms.cash.position.intraday` | 24 | 7d | `accountId+currency` | tms-cash-position | tms-liquidity, tms-reporting, tms-bff (SSE) |
| `tms.cash.position.eod` | 12 | **7y** | `accountId+currency+date` | tms-cash-position | tms-reporting, tms-accounting |
| `tms.cash.position.concentration` | 6 | 30d | `legalEntityId` | tms-cash-position | tms-payment-hub, tms-reporting |

### Liquidity Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.liquidity.plans.lifecycle` | 12 | 2y | `planId` | tms-liquidity | tms-reporting |
| `tms.liquidity.limits.alerts` | 6 | 90d | `legalEntityId` | tms-liquidity | tms-compliance, tms-reporting, tms-bff (SSE) |
| `tms.liquidity.scenarios.results` | 6 | 90d | `scenarioId` | tms-liquidity | tms-reporting |
| `tms.liquidity.intraday.alerts` | 12 | 7d | `accountId+currency` | tms-liquidity | tms-reporting, tms-bff (SSE) |

### Trade Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.trade.trades.lifecycle` | 24 | **7y** | `tradeId` | tms-trade | tms-cash-ecf, tms-settlement, tms-accounting, tms-risk, tms-confirmation-matching, tms-reporting |
| `tms.trade.trades.valuations` | 12 | 2y | `tradeId` | tms-trade | tms-risk, tms-accounting, tms-reporting |
| `tms.trade.confirmations.lifecycle` | 12 | **7y** | `tradeId` | tms-trade, tms-confirmation-matching | tms-settlement, tms-reporting |

### Risk Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.risk.limits.lifecycle` | 12 | **7y** | `limitId` | tms-risk | tms-reporting, tms-compliance |
| `tms.risk.exposure.updated` | 24 | 2y | `counterpartyId` | tms-risk | tms-reporting, tms-compliance |
| `tms.risk.limits.alerts` | 12 | 90d | `limitId` | tms-risk | tms-compliance, tms-reporting, tms-bff (SSE) |
| `tms.risk.fx-nop.updated` | 12 | 2y | `currency` | tms-risk | tms-reporting, tms-liquidity |

### Settlement Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.settlement.instructions.lifecycle` | 24 | **7y** | `instructionId` | tms-settlement | tms-accounting, tms-cash-ecf, tms-reporting, tms-reconciliation |
| `tms.settlement.instructions.fails` | 12 | **7y** | `instructionId` | tms-settlement | tms-reporting, tms-compliance |
| `tms.settlement.netting.lifecycle` | 6 | **7y** | `nettingId` | tms-settlement | tms-reporting |
| `tms.settlement.cls.lifecycle` | 12 | **7y** | `instructionId` | tms-settlement | tms-reporting, tms-accounting |
| `tms.settlement.ssi.lifecycle` | 6 | **7y** | `ssiId` | tms-settlement | tms-reporting |
| `tms.settlement.nostro.recon` | 12 | **7y** | `instructionId` | tms-settlement | tms-reporting, tms-reconciliation |

### Confirmation Matching Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.confirmation.matching.lifecycle` | 12 | **7y** | `confirmationId` | tms-confirmation-matching | tms-settlement, tms-reporting |
| `tms.confirmation.disputes.lifecycle` | 6 | **7y** | `confirmationId` | tms-confirmation-matching | tms-reporting |

### Accounting Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.accounting.journals.posted` | 24 | **7y** | `journalId` | tms-accounting | tms-reporting, tms-reconciliation |
| `tms.accounting.entries.lifecycle` | 48 | **7y** | `legalEntityId+coaAccount` | tms-accounting | tms-reporting |
| `tms.accounting.accruals.posted` | 24 | **7y** | `instrumentId+accrualDate` | tms-accounting | tms-reporting |
| `tms.accounting.periods.lifecycle` | 6 | **7y** | `legalEntityId+period` | tms-accounting | tms-reporting |
| `tms.accounting.reversals.posted` | 12 | **7y** | `entryId` | tms-accounting | tms-reporting |

### In-House Bank Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.ihb.pobo.lifecycle` | 12 | **7y** | `poboRequestId` | tms-ihb | tms-payment-hub, tms-cash-ecf, tms-accounting, tms-reporting |
| `tms.ihb.cobo.lifecycle` | 12 | **7y** | `coboId` | tms-ihb | tms-cash-ecf, tms-accounting, tms-reporting |
| `tms.ihb.intercompany.lifecycle` | 12 | **7y** | `loanId` | tms-ihb | tms-cash-ecf, tms-accounting, tms-reporting |
| `tms.ihb.netting.lifecycle` | 6 | **7y** | `nettingId` | tms-ihb | tms-settlement, tms-accounting, tms-reporting |

### FX Rate Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.fx.rates.spot` | 12 | compact + 90d | `baseCurrency+quoteCurrency` | tms-fx-rates | tms-cash-position, tms-liquidity, tms-risk, tms-accounting, tms-reporting |
| `tms.fx.rates.forward` | 12 | compact + 90d | `baseCurrency+quoteCurrency+tenor` | tms-fx-rates | tms-risk, tms-trade, tms-reporting |
| `tms.fx.revaluation.results` | 6 | 2y | `revalRunId` | tms-fx-rates | tms-accounting, tms-reporting |

> **Compacted + time retention:** Spot rate topics use log compaction (always-latest per currency pair) with a 90-day time-based retention fallback. This means the latest rate per pair is always available, and historical rates for the past 90 days are also available.

### Reconciliation Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.reconciliation.runs.lifecycle` | 6 | 2y | `reconId` | tms-reconciliation | tms-reporting |
| `tms.reconciliation.breaks.lifecycle` | 12 | 2y | `breakId` | tms-reconciliation | tms-reporting, tms-accounting |
| `tms.reconciliation.matches.found` | 24 | 2y | `reconId` | tms-reconciliation | tms-reporting |

### Compliance Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.compliance.sanctions.screening` | 24 | **7y** | `screeningId` | tms-compliance | tms-payment-hub, tms-reporting |
| `tms.compliance.alerts.lifecycle` | 12 | **7y** | `alertId` | tms-compliance | tms-reporting |

### Bank Account Domain

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.bank-accounts.lifecycle` | 6 | **7y** | `accountId` | tms-bank-accounts | tms-cash-bat, tms-cash-position, tms-payment-hub, tms-reporting |

### Reference Data (Compacted)

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.refdata.counterparties` | 6 | compact | `counterpartyId` | tms-reference-data | all services |
| `tms.refdata.legal-entities` | 3 | compact | `legalEntityId` | tms-reference-data | all services |
| `tms.refdata.currencies` | 3 | compact | `currencyCode` | tms-reference-data | all services |
| `tms.refdata.calendars` | 3 | compact | `calendarId` | tms-reference-data | tms-payment-hub, tms-settlement, tms-cash-position |
| `tms.refdata.accounts` | 6 | compact | `accountId` | tms-bank-accounts | tms-payment-hub, tms-cash-ecf, tms-cash-position |

### Audit Domain (Immutable)

| Topic | Partitions | Retention | Partition Key | Producers | Consumers |
|-------|-----------|-----------|---------------|-----------|-----------|
| `tms.audit.events.immutable` | 24 | **10y** | `legalEntityId` | all services | tms-reporting (OpenSearch sink), Kafka Connect S3 Sink (WORM) |

> 10-year retention satisfies financial regulatory audit requirements in most jurisdictions.

---

## RabbitMQ Exchanges and Queues (v2)

### Exchange Design

```
tms.payment.commands          — topic exchange  — payment release, repair, cancel
tms.settlement.commands       — topic exchange  — instruct, cancel, repair
tms.trade.commands            — topic exchange  — capture, amend, mature
tms.risk.commands             — topic exchange  — limit override request, approval
tms.ihb.commands              — topic exchange  — POBO request, netting trigger
tms.approval.workflow         — headers exchange — all maker-checker workflows
tms.notifications             — topic exchange  — email, SMS, webhook, compliance
tms.delayed.retry             — x-delayed-message — scheduled retry with configurable delay
tms.dlx.retry                 — topic exchange  — retry routing (receives from queue DLX)
tms.dlx.dead                  — fanout          — final dead letter (all unrecoverable messages)
```

### Payment Command Queues

```
tms.payment.create.q
  Exchange: tms.payment.commands / payment.create.*
  Quorum: true | DLX: tms.dlx.retry

tms.payment.release.normal.q
  Exchange: tms.payment.commands / payment.release.normal
  Quorum: true | x-max-priority: 5

tms.payment.release.urgent.q
  Exchange: tms.payment.commands / payment.release.urgent
  Quorum: true | x-max-priority: 10
  Consumer: dedicated thread pool, higher concurrency

tms.payment.cancel.q
  Exchange: tms.payment.commands / payment.cancel.*
  Quorum: true | DLX: tms.dlx.retry

tms.payment.repair.q
  Exchange: tms.payment.commands / payment.repair.*
  Quorum: true | DLX: tms.dlx.retry
```

### Approval Workflow Queues

```
tms.approval.payment.level1.q
  Exchange: tms.approval.workflow / x-match=all
            entity-type=payment, approval-level=1
  Quorum: true | Message-TTL: 86400000 (24h)

tms.approval.payment.level2.q
  Exchange: tms.approval.workflow / x-match=all
            entity-type=payment, approval-level=2
  Quorum: true | Message-TTL: 86400000

tms.approval.limit-override.q
  Exchange: tms.approval.workflow / x-match=all
            entity-type=limit-override
  Quorum: true | Message-TTL: 3600000 (1h — time-sensitive)

tms.approval.signatory-change.q
  Exchange: tms.approval.workflow / x-match=all
            entity-type=signatory-change
  Quorum: true | Message-TTL: 604800000 (7d)

tms.approval.period-close.q
  Exchange: tms.approval.workflow / x-match=all
            entity-type=period-close
  Quorum: true
```

### Settlement Command Queues

```
tms.settlement.instruct.q
  Exchange: tms.settlement.commands / settlement.instruct.*
  Quorum: true | DLX: tms.dlx.retry

tms.settlement.cancel.q
  Exchange: tms.settlement.commands / settlement.cancel.*
  Quorum: true | DLX: tms.dlx.retry

tms.settlement.netting.trigger.q
  Exchange: tms.settlement.commands / settlement.netting.*
  Quorum: true
```

### Risk Command Queues

```
tms.risk.limit-override.request.q
  Exchange: tms.risk.commands / risk.limit-override.request
  Quorum: true | Message-TTL: 3600000 (1h)

tms.risk.limit-override.approved.q
  Exchange: tms.risk.commands / risk.limit-override.approved
  Quorum: true
```

### IHB Command Queues

```
tms.ihb.pobo.request.q
  Exchange: tms.ihb.commands / ihb.pobo.request
  Quorum: true | DLX: tms.dlx.retry

tms.ihb.netting.trigger.q
  Exchange: tms.ihb.commands / ihb.netting.*
  Quorum: true
  Consumer: runs on scheduled basis (e.g., daily at 12:00 for multilateral netting)
```

### Retry and Dead Letter Infrastructure

```
Retry ladder (via tms.delayed.retry — x-delayed-message plugin):

  Attempt 1  → immediate delivery
  Attempt 2  → 1s   delay  (transient network blip)
  Attempt 3  → 5s   delay
  Attempt 4  → 30s  delay
  Attempt 5  → 5m   delay  (downstream service recovering)
  Attempt 6  → 1h   delay  (for time-insensitive jobs)
  Attempt 7+ → tms.dlx.dead (manual ops review required)

Per-queue retry counter: x-death header incremented by RabbitMQ on each DLX delivery.
Consumer reads x-death count to decide retry vs dead-letter routing.

tms.dlx.dead — all unrecoverable messages land here:
  - Manual ack required (consumer ops tooling)
  - Grafana alert: depth > 0 = P1 alert (immediately)
  - Retention: unlimited until manually acked or re-queued
```

### Notification Queues

```
tms.notifications.email.q
  Exchange: tms.notifications / notify.email.*

tms.notifications.push.q
  Exchange: tms.notifications / notify.push.*    (in-app / WebSocket push to BFF)

tms.notifications.compliance.q
  Exchange: tms.notifications / notify.compliance.*
  Quorum: true | x-max-priority: 10  (highest — compliance alerts pre-empt all)

tms.notifications.risk-alert.q
  Exchange: tms.notifications / notify.risk.*
  Quorum: true | x-max-priority: 8
```

---

## Kafka Exactly-Once Configuration

```yaml
spring.kafka:
  producer:
    acks: all
    enable-idempotence: true
    transactional-id: tms-${spring.application.name}-${HOSTNAME}
    retries: 10
    max-in-flight-requests-per-connection: 5
    properties:
      delivery.timeout.ms: 120000

  consumer:
    isolation-level: read_committed
    enable-auto-commit: false
    auto-offset-reset: earliest
    properties:
      session.timeout.ms: 30000
      heartbeat.interval.ms: 10000
```

Combined with Outbox pattern (DB write + outbox row in one transaction) = end-to-end exactly-once from business action to downstream consumer.

---

## Message Header Standard

Every Kafka record and RabbitMQ message must carry these headers:

```
x-correlation-id       UUID      — trace across all systems
x-causation-id         UUID      — parent event/command that caused this
x-saga-id              UUID?     — saga instance (if message is part of a saga)
x-legal-entity-id      String    — tenant / entity isolation enforcement
x-source-service       String    — emitting service name + version
x-schema-version       Int       — Avro schema Registry ID
x-event-timestamp      ISO-8601  — event occurrence time (not Kafka ingestion time)
x-user-id              String    — human user or system service account
x-trace-id             String    — OpenTelemetry W3C traceparent trace ID
x-span-id              String    — OpenTelemetry span ID
x-retry-count          Int       — retry attempt number (RabbitMQ only)
x-idempotency-key      String    — consumer-side deduplication key
x-message-standard     String?   — MT | MX | NACHA | SEPA_XML (messaging domain only)
```

---

## Partition Strategy Summary

| Domain | Partition Key | Rationale |
|--------|--------------|-----------|
| Payment lifecycle | `paymentId` | Strict event ordering per payment required |
| ECF flows | `accountId+currency+valueDate` | Position calculation partitioned by account-date |
| BAT transactions | `accountId+currency+valueDate` | NOSTRO recon partitioned by account-date |
| Cash position | `accountId+currency+valueDate` | Position updates for same account-date ordered |
| Settlement instructions | `instructionId` | Lifecycle ordering per instruction |
| Trade lifecycle | `tradeId` | Ordering per trade lifecycle |
| Risk exposure | `counterpartyId` | Exposure updates ordered per counterparty |
| Accounting entries | `legalEntityId+coaAccount` | Ledger balance updates per account ordered |
| Accruals | `instrumentId+accrualDate` | Dedup and ordering per instrument-date |
| FX rates | `baseCurrency+quoteCurrency` | Latest rate per pair (compacted) |
| Reference data | Entity ID | Log-compacted: latest master record per entity |
| Audit events | `legalEntityId` | Entity-level ordering; compliance queries scoped by entity |

---

## Consumer Group Naming Convention

```
{service-name}-{domain}-{entity}-cg

Examples:
  tms-accounting-payment-lifecycle-cg
  tms-cash-position-ecf-lifecycle-cg
  tms-reporting-settlement-instructions-cg
  tms-risk-trade-lifecycle-cg
  tms-bff-cash-position-intraday-cg        (SSE streaming)
```

One consumer group per (service, topic) pair. Each service gets independent offset tracking.

---

## BFF Kafka Subscription (Real-Time SSE)

The BFF subscribes to short-lived, high-frequency topics for pushing real-time updates to the React UI via SSE:

```
tms.cash.position.intraday       → CashPositionView per account (refreshed every 15s)
tms.payment.payments.lifecycle   → PaymentStatusUpdate for payments on-screen
tms.risk.limits.alerts           → LimitAlertNotification
tms.liquidity.intraday.alerts    → IntradayLiquidityAlert
tms.notifications.push.*         → UserNotification (approval requests, alerts)
```

BFF SSE implementation:
```java
// One SseEmitter per user session, scoped to their legalEntityId
// Kafka consumer filters messages by legalEntityId header
// Emits JSON events to the browser:
//   event: cash-position-update
//   data: {"accountId":"...","balance":"...","currency":"...","updatedAt":"..."}
```
