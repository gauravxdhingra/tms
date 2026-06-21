# 10 — Implementation Roadmap, Risks, and Tradeoffs (v2)

## Phased Delivery Overview

```
Phase 1 (MVP)              Phase 2 (Core Platform)       Phase 3 (Enterprise)
──────────────             ────────────────────────       ────────────────────
Months 1–6                 Months 7–14                    Months 15–24
────────────               ────────────────────           ────────────────────
Platform infra             Payment Hub full               In-House Bank (IHB)
Core messaging             All network adapters           POBO / COBO / Netting
Reference data             Trade capture (all types)      Intercompany lending
Identity / auth            Settlement full lifecycle      Multilateral netting
Basic payments             NOSTRO reconciliation          Multi-ledger accounting
  (SWIFT stub)             Confirmation matching          Multi-GAAP (IFRS / US GAAP)
ECF + BAT ingestion        Accounting + CoA               Hedge accounting
Cash position (ladder)     Accrual engine                 Full risk management
BFF + Angular UI           Compliance (live sanctions)    DV01, PSR, IR gap
Audit trail                Risk (limits + exposure)       FX NOP monitor
Basic observability        FX rates + revaluation         Liquidity planning
                           Bank recon saga                Strategic cash forecasting
                           Contract tests + Gatling       CLS / DVP settlement
                                                          ISO 20022 MX first-class
                                                          Regulatory reporting hooks
                                                          Istio service mesh
                                                          Chaos engineering
```

---

## Phase 1: MVP (Months 1–6)

### Goal
A treasury team can create payments, approve them via maker-checker, track them end-to-end, ingest bank statements, and see a live cash ladder. Full auditability from day one.

---

### Sprint 0 (Weeks 1–2): Platform Foundation

- [ ] Kubernetes cluster: dev / staging / prod namespaces; namespaced network policies
- [ ] PostgreSQL 17 cluster (Patroni + streaming replication + pg_partman extension)
- [ ] Kafka 3.8 cluster (3 brokers KRaft mode, Schema Registry, MirrorMaker 2 skeleton)
- [ ] RabbitMQ 3.13 (Quorum queues, x-delayed-message plugin)
- [ ] Redis 7 Sentinel
- [ ] MinIO (4-node erasure-coded, WORM bucket for audit)
- [ ] Keycloak 26.2 (tms-realm + tms-system-realm, PKCE config, service accounts)
- [ ] HashiCorp Vault (dynamic PostgreSQL credentials, KV v2 for app secrets)
- [ ] Spring Cloud Config Server + Git-backed config repo
- [ ] Spring Cloud Gateway (JWT validation, correlation ID injection, rate limiting)
- [ ] GitHub Actions CI pipeline (build → test → Jib image → push to GHCR)
- [ ] ArgoCD GitOps delivery (app-of-apps pattern per environment)
- [ ] Grafana + Prometheus + Loki + Tempo + Alertmanager stack
- [ ] `tms-events-schema` module: Avro codegen, Schema Registry plugin, BACKWARD compat check
- [ ] `tms-common-*` modules: audit, outbox, idempotency, security, money, validation, messaging, test
- [ ] Base Spring Boot service template (OTel, health probes, outbox, Flyway, Clock bean)
- [ ] Docker Compose infra file for local development

---

### Sprints 1–2 (Weeks 3–6): Reference Data + Identity

- [ ] `tms-reference-data`: legal entities, counterparties, currencies, calendars, portfolios, books
  - Kafka compacted topics for all reference data events
  - Redis cache (TTL 5m, invalidated on event)
- [ ] `tms-bank-accounts`: account master data, IBAN validation, account hierarchy
- [ ] Keycloak roles wired to all services via `tms-common-security` `TmsPrincipal`
- [ ] PostgreSQL RLS skeleton: `app.legal_entity_id` session variable; RLS policies on all tables

---

### Sprints 3–4 (Weeks 7–10): Integration + Rules Engine Skeleton

- [ ] `tms-integration`: SFTP file drop ingestion, file fingerprint dedup, virus scan hook
- [ ] `tms-message-manager`: MT940 parsing (WIFE library), routing to `tms-cash-bat`, basic CAMT.053 skeleton
- [ ] `tms-rules-engine`: DMN/FEEL decision tables (Camunda DMN embedded), gRPC API (`ValidatePayment`, `RouteMessage`), versioned rule sets

---

### Sprints 5–7 (Weeks 11–16): Payment Hub (Core)

- [ ] Payment creation (single currency WIRE), JSR-354 `MonetaryAmount` throughout
- [ ] Payment Processing Saga (Steps 1–6: Validate → Enrich → Sanctions stub → Approve → Release → Await Settlement)
- [ ] Maker-checker approval (RabbitMQ tms.approval.payment queues, 2-level)
- [ ] Payment event store (append-only `payment_events` table, snapshot every 50 events)
- [ ] Outbox + Debezium CDC: all payment events guaranteed to Kafka
- [ ] SWIFT MT103 network adapter (SWIFT Service Bureau sandbox or WireMock stub)
- [ ] Idempotency-Key filter on all payment endpoints (`tms-common-idempotency`)
- [ ] Payment exception / repair queue
- [ ] BFF: payment queue view, payment detail aggregated view

---

### Sprints 8–9 (Weeks 17–20): Cash ECF + BAT + Position

- [ ] `tms-cash-ecf`: ECF creation on `PaymentCreated`; ECF amendment on `PaymentAmended`; ECF settlement on `PaymentSettled`
- [ ] `tms-cash-bat`: MT940 ingestion via Spring Batch; `BankTransactionPosted` events; dedup by statement line hash
- [ ] `tms-cash-position`: CQRS aggregation of ECF + BAT → cash ladder; confirmed vs anticipated splits; all four balance types (book, available, ledger, float)
- [ ] Cash position BFF SSE stream (`/bff/v1/stream/cash-position`)
- [ ] BFF: cash dashboard aggregated view

---

### Sprints 10–11 (Weeks 21–24): UI + Audit + Hardening

- [ ] `tms-ui` Angular SPA: Payment Queue, Payment Detail, Create Payment wizard, Cash Dashboard, Cash Ladder, Approval Inbox
- [ ] BFF SSE: real-time payment status updates in Payment Queue
- [ ] Immutable audit log (hash chaining, outbox → Kafka → OpenSearch `tms-audit-events-*`)
- [ ] WORM MinIO bucket — Kafka Connect S3 Sink for 7-year audit retention
- [ ] Grafana dashboards: Payment Operations, Cash & Liquidity, System Health
- [ ] P1/P2 alerting rules
- [ ] Gatling load test: 10,000 payments/hour sustained for 30 minutes
- [ ] OWASP top-10 security review; PII log scan in CI
- [ ] ArchUnit rules enforced in CI (no double/float, no `LocalDate.now()`, no cross-service DB)
- [ ] DR runbook + Patroni failover test

### Phase 1 Explicitly Out of Scope

| Item | Reason |
|------|--------|
| SEPA / ACH / RTGS adapters | Network integration requires dedicated environments; SWIFT stub sufficient |
| Trade capture | Distinct domain; prove payment flow first |
| Settlement full lifecycle | Depends on trade capture |
| Bank reconciliation saga | Needs stable ECF + BAT data over multiple months |
| NOSTRO reconciliation | Settlement must be stable first |
| Live sanctions screening | Live watchlist requires legal/security review; use stub |
| Risk limits | Needs trade data to be meaningful |
| IHB / POBO | Phase 3; depends on multi-entity accounting being solid |
| Multi-GAAP accounting | Phase 3; Phase 1 runs single IFRS ledger only |
| Hedge accounting | Phase 3 |
| FX rate management | Phase 2 (needed for trade revaluation) |
| Confirmation matching | Phase 2 (needs trade capture) |
| Istio service mesh | Phase 3; operational overhead not justified early |
| ISO 20022 MX first-class | Phase 3; MT sufficient for Phase 1 |
| Accrual engine | Phase 2 (needs trade capture) |

### Phase 1 Exit Criteria

| Metric | Target |
|--------|--------|
| Payment creation → release p95 latency | < 500ms (excluding approval wait) |
| Payment saga completion p99 | < 5 min (excluding network settlement) |
| Cash position update after payment event | < 30 seconds |
| API uptime (30-day rolling) | > 99.9% |
| Audit trail completeness | 100% of payment state transitions captured |
| Unit + integration test line coverage | > 80% on business logic packages |
| Zero PII in logs | Verified by CI log-scan step |
| SoD enforcement | Integration tests confirm maker ≠ approver |
| Load test | 10,000 payments/hour × 30 min without degradation |
| Failover test | Patroni primary failover < 60 seconds with zero data loss |
| ArchUnit gates | 0 violations in CI |

---

## Phase 2: Core Platform (Months 7–14)

### Goal
Full payment lifecycle across all major networks. Trade capture for all instrument types. Settlement full lifecycle with NOSTRO recon. Real accounting with CoA, accruals, and period management. Live compliance. Risk limits and exposure tracking. Multi-entity ABAC active.

---

### Payment Hub Completion (Months 7–8)
- [ ] SEPA Credit Transfer (SCT) adapter; ISO pain.001 generation
- [ ] ACH adapter (NACHA CCD/PPD formats)
- [ ] RTGS adapter (TARGET2 / CHAPS; ISO pacs.008)
- [ ] SEPA Instant (SCT Inst) adapter
- [ ] Bulk payment processing (Spring Batch partitioned job)
- [ ] Multi-level approval (configurable tiers by amount + currency)
- [ ] Return payment handling (`PaymentReturnReceived` → repair saga)
- [ ] Payment prioritisation (RabbitMQ priority queues)

### Trade Capture (Months 7–9)
- [ ] `tms-trade`: FX spot + forward, deposits, loans, repos, interest rate swaps (fixed/floating legs)
- [ ] Trade capture UI (context-sensitive form per instrument type)
- [ ] Trade Processing Saga → ECF flows generated on `TradeCaptured`
- [ ] Pre-Trade Risk Limit Check Saga (gRPC sync fast path + async override)
- [ ] Trade amendment lifecycle + audit
- [ ] Trade Maturity Processing Saga (auto-executes at maturity)
- [ ] Confirmation generation (MT300 for FX, MT320 for MM)
- [ ] `tms-confirmation-matching`: internal matching; `ExternalMatchServiceResultReceived` hook (SWIFT Accord / Traiana / MarkitSERV stub)
- [ ] Confirmation matching UI: pending / matched / disputed views

### Settlement (Months 8–10)
- [ ] `tms-settlement`: full instruction lifecycle (CREATED → SENT → CONFIRMED / FAILED)
- [ ] SSI management: create, amend, effective date, priority ranking
- [ ] Settlement Netting Saga (bilateral netting by counterparty + currency + date)
- [ ] Settlement cutoff enforcement (per channel + currency)
- [ ] Settlement fail / repair workflow
- [ ] NOSTRO Reconciliation Saga (settlement instructions vs BAT)
- [ ] Trade Settlement Saga fully wired end-to-end
- [ ] SWIFT MT202 / MT210 messages

### Accounting (Months 8–11)
- [ ] `tms-accounting`: full Chart of Accounts (class → category → subcategory → account)
- [ ] Double-entry journal posting with balanced journal DB constraint
- [ ] Accounting rules via Rules Engine (FEEL DMN: which debit/credit per event type + instrument)
- [ ] Multi-GAAP ledger support (IFRS + LOCAL_GAAP + MGMT minimum)
- [ ] Nightly Accrual Posting Saga (Spring Batch partitioned; ACT/360, ACT/365, ACT/ACT, 30/360)
- [ ] Period open/close lifecycle (closed-period guard trigger)
- [ ] Reversal workflow
- [ ] Trial balance + P&L + Balance Sheet reports

### Bank Reconciliation (Month 10)
- [ ] `tms-reconciliation`: Bank Reconciliation Saga (ECF vs BAT auto-matching)
- [ ] Match rules: exact reference, exact amount+date, tolerance match, narrative keyword
- [ ] Break management: OPEN / AUTO_RESOLVED / MANUALLY_RESOLVED
- [ ] Reconciliation UI: open breaks, aged items, resolve/escalate inline

### Compliance (Months 9–10)
- [ ] `tms-compliance`: live OFAC / EU / UN / HMT sanctions screening (real integration)
- [ ] PEP screening
- [ ] Watchlist ingestion (SFTP → OpenSearch `tms-watchlist-*` index)
- [ ] Compliance Alert Saga fully wired with Payment Hub hold/release
- [ ] Compliance UI: alert queue, severity triage, resolution workflow

### Risk (Months 10–12)
- [ ] `tms-risk`: counterparty credit limits, PSR (Pre-Settlement Risk), hard/soft limit model
- [ ] Risk limit check (gRPC) wired into Trade Processing Saga
- [ ] Limit breach detection + override approval workflow
- [ ] Exposure browser UI; limit manager UI; live breach alerts via SSE
- [ ] `tms-fx-rates`: spot + forward rate ingestion (Reuters / Bloomberg stub), manual rate entry, approval workflow
- [ ] FX revaluation run (nightly): `RevaluationRunCompleted` event → accounting entries

### FX Rates (Months 10–11)
- [ ] `tms-fx-rates` service fully deployed
- [ ] FX rates compacted Kafka topic (`tms.fx.rates.spot`, `tms.fx.rates.forward`)
- [ ] Materialised view `fx_rates_latest` for fast lookup
- [ ] Manual rate override + approval (4-eyes)

### Multi-Entity ABAC (Month 12)
- [ ] PostgreSQL RLS fully activated on all service schemas
- [ ] ABAC policies enforced: `legalEntityId` isolation, SoD policies, amount-based approval tiers
- [ ] Per-entity configuration: approval thresholds, settlement calendar, limit set

### Phase 2 UI Screens Added
- Trade Blotter, Trade Capture, Trade Detail, Confirmation queue
- Settlement Queue, Settlement Detail, NOSTRO Reconciliation, SSI Manager
- Accounting GL, CoA Tree, Period Management, P&L Report, Balance Sheet, Accrual Monitor
- Risk Dashboard, Limit Manager, Exposure Browser, FX NOP Monitor, Limit Override queue
- Bank Reconciliation screen, Compliance Alert queue

### Phase 2 Exit Criteria

| Metric | Target |
|--------|--------|
| Trade capture → settlement instruction p95 | < 2s |
| Accrual run (10,000 instruments) | < 5 min |
| Bank recon match rate (auto) | > 90% |
| Settlement fail rate | < 0.5% |
| Risk limit check (gRPC, p99) | < 200ms |
| FX revaluation run (nightly) | < 30 min |
| Multi-entity isolation tests | 100% pass (ArchUnit + integration) |
| Contract tests (all services) | 100% pass; no contract regressions |
| Gatling load: trade + payment combined | 50 RPS trades, 100 RPS payments, p95 < 800ms |

---

## Phase 3: Enterprise Grade (Months 15–24)

### Goal
Full enterprise TMS. In-House Bank with POBO/COBO/multilateral netting. Multi-GAAP + hedge accounting. Full risk suite (DV01, IR gap). Strategic liquidity planning. CLS/DVP settlement. ISO 20022 MX first-class. Regulatory reporting hooks. Platform scaled to millions of events/day.

---

### In-House Bank (Months 15–17)
- [ ] `tms-ihb`: virtual account management, subsidiary onboarding
- [ ] POBO Payment Saga (subsidiary request → treasury executes → virtual account updated → intercompany entries)
- [ ] COBO Collection Saga (inbound collection → credit virtual account → notify subsidiary)
- [ ] Intercompany loans: issuance, interest accrual, repayment
- [ ] Intercompany FX deals (treasury buys/sells on behalf of subsidiary)
- [ ] Multilateral Netting Saga (collect instructions → calculate net → single net payment)
- [ ] Intercompany statement (virtual account ledger per subsidiary)
- [ ] IHB UI: POBO request, COBO tracking, intercompany loans, netting run, statements

### Advanced Accounting (Months 16–18)
- [ ] US GAAP ledger alongside IFRS
- [ ] LOCAL GAAP ledger support (configurable per legal entity)
- [ ] MGMT accounting ledger (management accounts, non-GAAP adjustments)
- [ ] Hedge accounting (IFRS 9 / ASC 815): OCI vs P&L bifurcation, hedge designation, effectiveness testing
- [ ] Amortisation schedules (bond premium/discount, loan origination fees)
- [ ] Deferred income / prepaid expense tracking

### Full Risk Suite (Months 16–19)
- [ ] DV01 (dollar value of 1bp) for interest rate positions
- [ ] IR Gap Analysis (rate sensitivity per bucket)
- [ ] FX Net Open Position (NOP) per currency pair + consolidated
- [ ] Counterparty credit exposure aggregation (gross vs net)
- [ ] Stress testing scenarios (what-if rate shock, credit event)
- [ ] Risk reports: VaR sketch, position summary, limit utilisation history

### CLS / DVP Settlement (Months 17–19)
- [ ] CLS settlement adapter (FX PVP via CLS Bank)
- [ ] DVP settlement (Delivery Versus Payment for securities)
- [ ] Fails management and buy-in workflow (CSDR)
- [ ] SWIFT MT300 confirmations fully integrated (not stub)

### ISO 20022 MX First-Class (Months 18–20)
- [ ] pain.001 generation (SEPA Credit Transfer initiation) — production grade
- [ ] camt.053 parsing (bank statement) replaces MT940 as primary format
- [ ] pacs.008 (FI Credit Transfer) for SWIFT MX network
- [ ] sese.023 (Securities Settlement Instruction)
- [ ] MT↔MX translation service (`MessageMTToMXTranslated` / `MessageMXToMTTranslated` events)
- [ ] ISO 20022 adoption phased in alongside MT (parallel run for 12 months)

### Strategic Liquidity Planning (Months 18–21)
- [ ] `tms-liquidity`: short (0–7 day), medium (8–90 day), strategic (91–365 day) horizons
- [ ] Cash flow forecasting: rules-based + ML-assisted (time-series on historical ECF)
- [ ] Funding gap analysis; counterbalancing capacity (eligible liquid assets)
- [ ] Intraday liquidity monitoring (real-time position vs intraday limit)
- [ ] Scenario analysis (stress test: what if a major counterparty defaults?)
- [ ] Liquidity UI: horizon tabs, funding gap alerts, counterbalancing capacity view

### Regulatory Reporting Hooks (Months 20–22)
- [ ] MiFID II transaction reporting event emission (`TradeReportableEvent`)
- [ ] EMIR trade repository hooks
- [ ] AML transaction monitoring (pattern-based detection — velocity, structuring, round-tripping)
- [ ] GDPR right-to-erasure workflow (anonymise personal data while preserving transaction integrity)
- [ ] Regulatory filing status tracking in compliance UI

### Platform Hardening (Months 20–24)
- [ ] Istio service mesh (mTLS between all services, traffic management)
- [ ] Chaos engineering: monthly scheduled chaos days (Toxiproxy + Chaos Monkey)
- [ ] Multi-region active-passive deployment (primary + DR with MirrorMaker 2 < 1min RPO)
- [ ] Performance tuning: 10M+ events/day; Kafka partition rebalancing; PostgreSQL vacuum tuning
- [ ] pg_partman archival automation (detect old partitions, batch export to MinIO Parquet, drop)
- [ ] BI connector (reporting API → Power BI / Tableau via REST)
- [ ] Self-service report builder UI (OpenSearch-backed filter builder, CSV/Excel export)

---

## Team Topology

```
Stream-aligned teams (end-to-end ownership):

  Team: Payments & Compliance
    owns: tms-payment-hub, tms-compliance, tms-notifications, network adapters

  Team: Cash & Liquidity
    owns: tms-cash-ecf, tms-cash-bat, tms-cash-position, tms-liquidity

  Team: Trade & Risk
    owns: tms-trade, tms-confirmation-matching, tms-risk, tms-fx-rates

  Team: Settlement & Reconciliation
    owns: tms-settlement, tms-reconciliation, tms-bank-accounts

  Team: Finance & Accounting
    owns: tms-accounting, tms-ihb

  Team: Platform & Integration
    owns: tms-integration, tms-message-manager, tms-rules-engine,
          tms-reference-data, tms-bff, tms-gateway, tms-config-server

  Team: UI
    owns: tms-ui (Angular SPA, design system, BFF view contracts)

Enabling teams:
  Platform Engineering — Kubernetes, Kafka, PostgreSQL, CI/CD, Vault, MinIO
  Security Engineering — Keycloak, pen testing, compliance audit, mTLS
```

---

## Risk Register

### R1: Distributed Transaction Complexity
**Risk:** 9 sagas with many steps are hard to debug when partial failures occur.
**Mitigation:** Saga state persisted in `saga_instances` table — inspectable at any time. Full OTel traces link all saga steps. `saga.type` + `saga.step` span attributes on every step. Grafana panel shows stuck/timed-out saga counts by type. Timeout recovery matrix defined per step (doc 07).

### R2: Outbox Latency
**Risk:** Debezium CDC introduces latency in event publication; lag if WAL slot is behind.
**Mitigation:** Debezium reads WAL (< 100ms in normal conditions). Outbox depth monitored in Grafana; alert if unpublished rows > 100 for > 30s. PostgreSQL WAL segment retention sized for Debezium lag tolerance (1h minimum).

### R3: Kafka Consumer Lag During Load Spikes
**Risk:** Payment Hub emits a burst; downstream consumers lag.
**Mitigation:** 48 partitions for `tms.payment.payments.lifecycle`. KEDA auto-scales consumer pods on consumer-lag metric. All read-model consumers are idempotent — lag = delayed projection, not data loss.

### R4: Avro Schema Evolution Breaking Consumers
**Risk:** A schema change in a producer breaks existing consumers.
**Mitigation:** Schema Registry enforces BACKWARD compatibility before every deployment. Schema compatibility is verified in CI via `mvn exec:java` against the Schema Registry API. All new fields have defaults. Spring Cloud Contract consumer tests run against provider-generated stubs.

### R5: Compliance Screening Availability
**Risk:** `tms-compliance` down → payments cannot be released.
**Mitigation:** Resilience4j circuit breaker. Fallback: hold payment for manual review (never auto-clear). Compliance pods have PodDisruptionBudget (min 2 replicas always up). Separate `tms-compliance` Kubernetes namespace with dedicated resource quotas.

### R6: SoD Bypass via Service Accounts
**Risk:** Service account JWT could bypass SoD checks.
**Mitigation:** ABAC policy checks `actor_type == USER` for all approval/release endpoints. Service accounts cannot approve or release. All service account actions flagged separately in audit trail. Penetration test scope includes SoD bypass attempts.

### R7: PostgreSQL Event Store Growth
**Risk:** Payment and settlement event tables grow without bound; query degrades.
**Mitigation:** Monthly partitions via `pg_partman`. Snapshots after every 50 events. Old partitions exported to MinIO Parquet and dropped (archival automation in Phase 3). Aggregate queries use snapshot + recent delta only.

### R8: Kafka Topic Retention Mismatch
**Risk:** Consumer group lags more than retention window; events consumed before processing.
**Mitigation:** Alert on consumer lag > 50% of retention window. Payment lifecycle topics: 30-day operational retention; 7-year audit copy to MinIO via Kafka Connect S3 Sink. DLT for events exceeding consumer retry policy.

### R9: Multi-Entity Data Isolation Breach
**Risk:** A bug bypasses `legalEntityId` filtering, leaking data between entities.
**Mitigation:** PostgreSQL RLS as last-resort enforcement. ABAC at service layer as primary. Integration tests explicitly verify cross-entity isolation (different entity tokens cannot read each other's data). Pen test scope includes entity isolation.

### R10: Audit Log Integrity
**Risk:** Hash chain broken by bug or tampering.
**Mitigation:** Hash chain verified nightly by batch job; alert on any failure (P1). WORM MinIO bucket (immutable after write, object lock). OpenSearch index write permission restricted to `tms-audit-writer` service account only. Spot-check verification on every audit query.

### R11: Cash Position Staleness (Three-Service Cash Model)
**Risk:** `tms-cash-position` projects from ECF + BAT events; if a consumer lags, the cash ladder shows stale data.
**Mitigation:** `tms-cash-position` consumer group lag alerted at > 500 rows. BFF SSE push notifies UI immediately on `CashPositionUpdated` events. Cash ladder shows `lastUpdatedAt` timestamp so users see data freshness. Separate consumer for ECF and BAT streams (isolated scaling).

### R12: IHB Intercompany Netting Settlement Failure
**Risk:** Multilateral netting calculates net correctly but the single net payment fails; gross instructions were already cancelled.
**Mitigation:** Gross instructions held in `NETTING_PENDING` state until net payment is confirmed. If net payment fails: revert all gross instructions to original state and retry individually. Idempotency on netting group ensures no double-processing.

### R13: Accrual Run Failure Mid-Batch
**Risk:** Nightly accrual batch runs for 5,000 of 10,000 instruments then crashes; morning opening has partial accruals.
**Mitigation:** UNIQUE constraint on `(instrument_id, accrual_date, accrual_type, ledger, is_reversal)` — re-run is idempotent. Spring Batch `restart=true` resumes from last committed chunk. Alert if accrual run does not complete by 03:00 UTC. Per-instrument errors logged but do not abort the batch; ops reviews error log.

---

## Architecture Tradeoffs

| Decision | Chosen | Alternative | Why |
|----------|--------|-------------|-----|
| Saga pattern | Orchestration (central state in DB) | Choreography (pure events) | Easier to debug, trace, compensate; timeout recovery is explicit |
| Event sourcing scope | Payment Hub + Settlement only | All domains | Full ES adds operational overhead; most domains don't benefit from replay |
| CQRS scope | Cash Position + Liquidity read models | All domains | Most services don't need a distinct read model |
| Blocking vs reactive | Spring MVC + virtual threads | Spring WebFlux | Virtual threads close the throughput gap; blocking is simpler to test and matches Spring Batch |
| Modular monolith vs microservices | Modular monolith (`treasury-api`) — Spring Modulith + ArchUnit | 20+ independent services | Too early to pay distributed-systems tax; module boundaries enforced in-process by ArchUnit. Extract services when a module proves it needs independent scaling or separate deployment cadence. |
| Custom event store vs Axon | Custom PostgreSQL tables | Axon Framework | No framework lock-in; full SQL access for ad-hoc analytics; period constraints via DB triggers |
| Outbox vs direct Kafka | Outbox + Debezium CDC | Direct `KafkaTemplate` in same TX | Outbox guarantees exactly-once; direct write risks dual-write inconsistency without 2PC |
| Avro + Schema Registry | Avro | JSON Schema / Protobuf | Strongest backward-compat enforcement in Confluent ecosystem; binary compact |
| Shared-schema multi-tenancy | PostgreSQL RLS | Schema-per-tenant | Lower ops overhead; RLS enforced at DB; upgradable to schema-per-tenant if needed |
| Clock injection | `java.time.Clock` bean mandatory | Direct `LocalDate.now()` | Makes all date-sensitive code deterministic in tests; catches settlement cutoff bugs before production |
| MonetaryAmount | JSR-354 Moneta | `BigDecimal` raw | Prevents silent float rounding bugs; currency is always attached; HALF_EVEN rounding enforced |
| SWIFT connectivity | Service Bureau (Phase 1–2) | Alliance Lite2 / Alliance Access | Lower capital investment; fastest time-to-connect; bureau handles SWIFT network operations |
