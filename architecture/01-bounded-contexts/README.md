# 01 — Bounded Context Map (v2)

## What Changed from v1
- Cash Management split into **three distinct subdomains**: Expected Cash Flows, Bank Account Transactions, Cash Position / Cash Ladder
- Liquidity expanded to cover multi-horizon views, limit monitoring, counterbalancing capacity, intraday
- **Risk Management** added as a first-class domain (limits, exposure, FX open position, counterparty credit)
- **FX Rate Management** added as a shared platform service (rate store, triangulation, revaluation)
- **In-House Bank (IHB)** added as a separate bounded context (POBO, COBO, intercompany)
- **Accrual Engine** added inside Accounting (daily interest, amortization, day-count conventions)
- **Chart of Accounts** modeled as a proper hierarchy inside Accounting, not a VARCHAR column
- Settlement enriched: CLS, DVP, SSI lifecycle, settlement netting, NOSTRO reconciliation
- Reconciliation split into five named sub-processes (NOSTRO, trade confirmation, position, intragroup, accounting)
- Confirmation Matching separated from Trade — it integrates with external matching services
- Shared library modules (`tms-common-*`, `tms-events-schema`) added as a monorepo layer
- UI layer and BFF (Backend for Frontend) added

---

## Full Context Map

```
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                              TMS — Bounded Context Map (v2)                                 │
│                                                                                             │
│  ┌─────────────────────────┐    ┌─────────────────────────────────────────────────────┐   │
│  │  Integration &           │───▶│  Canonical Message Processing / Message Manager     │   │
│  │  Message Ingestion       │    └──────────────────────────────┬──────────────────────┘   │
│  └─────────────────────────┘                                    │                           │
│                                           ┌─────────────────────┼──────────────────────┐   │
│                                           ▼                     ▼                      ▼   │
│                              ┌──────────────────┐  ┌─────────────────┐  ┌───────────────┐ │
│                              │  Payment Hub      │  │  Trade &        │  │  In-House     │ │
│                              │                  │  │  Instrument Mgmt│  │  Bank (IHB)   │ │
│                              └────────┬─────────┘  └────────┬────────┘  └───────┬───────┘ │
│                                       │                     │                    │          │
│          ┌────────────────────────────┼─────────────────────┼────────────────────┘          │
│          ▼                            ▼                     ▼                               │
│  ┌──────────────────┐   ┌──────────────────────┐  ┌─────────────────────┐                 │
│  │  Risk &           │   │  Settlement Mgmt      │  │  Confirmation       │                 │
│  │  Limit Mgmt       │   │  (incl. CLS, DVP,     │  │  Matching           │                 │
│  └──────────────────┘   │  NOSTRO Recon,         │  └─────────────────────┘                 │
│                          │  Netting)              │                                          │
│                          └──────────┬─────────────┘                                         │
│                                     │                                                        │
│          ┌──────────────────────────┼────────────────────────┐                             │
│          ▼                          ▼                         ▼                             │
│  ┌────────────────────┐  ┌──────────────────────┐  ┌───────────────────────┐              │
│  │  Cash: Expected     │  │  Cash: Bank Account   │  │  Cash: Position /     │              │
│  │  Cash Flows (ECF)   │  │  Transactions (BAT)   │  │  Cash Ladder          │              │
│  └────────────────────┘  └──────────────────────┘  └────────────┬──────────┘              │
│                                                                   │                          │
│                                     ┌─────────────────────────────┘                         │
│                                     ▼                                                        │
│                          ┌──────────────────────┐                                           │
│                          │  Liquidity &          │                                           │
│                          │  Funding Mgmt         │                                           │
│                          └──────────────────────┘                                           │
│                                                                                             │
│  ┌──────────────────┐  ┌──────────────────┐  ┌───────────────────────────────────────────┐ │
│  │  Accounting &    │  │  Reconciliation   │  │  Compliance, Sanctions & Alerts           │ │
│  │  Finance Control │  │  (5 sub-processes)│  └───────────────────────────────────────────┘ │
│  │  (CoA, Accruals, │  └──────────────────┘                                               │
│  │  Multi-GAAP,     │                                                                      │
│  │  Hedge Accounting)│                                                                     │
│  └──────────────────┘                                                                      │
│                                                                                             │
│  ─────────────────────────── Shared Platform Services ──────────────────────────────────── │
│                                                                                             │
│  ┌────────────┐ ┌───────────────┐ ┌───────────────┐ ┌──────────────┐ ┌─────────────────┐ │
│  │ Rules      │ │ Reference &   │ │ Bank Account  │ │ FX Rate Mgmt │ │ User, Identity  │ │
│  │ Engine     │ │ Static Data   │ │ Management    │ │              │ │ & Authorization │ │
│  └────────────┘ └───────────────┘ └───────────────┘ └──────────────┘ └─────────────────┘ │
│                                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Treasury Reporting & Analytics                                                       │  │
│  └──────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
│  ─────────────────────────── UI Layer ──────────────────────────────────────────────────── │
│                                                                                             │
│  ┌─────────────────────────────────────────────────────┐  ┌──────────────────────────────┐ │
│  │  BFF (Backend for Frontend) — tms-bff               │  │  Angular SPA — tms-ui        │ │
│  └─────────────────────────────────────────────────────┘  └──────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Service Decomposition Decision Criteria

A context is a **microservice** when it has independent scaling, its own data lifecycle, separate deployment cadence, or cross-team ownership.
A context is a **module** (within a service or promoted later) when it shares a transactional boundary and is too small to justify independent operational overhead in early phases.

---

## Service Catalogue — Complete

### `tms-integration`
**Capabilities:** SFTP file pickup, API ingestion, bulk import, duplicate detection (fingerprinting), virus scanning, file validation, inbound status tracking, outbound file delivery.
**Upstream:** External banks, partners, SWIFT network, SFTP drops
**Downstream:** Publishes to `tms.integration.messages.inbound`, `tms.integration.files.received`
**Phase:** 1

---

### `tms-message-manager`
**Capabilities:** Parsing (MT, MX, NACHA, SEPA XML, proprietary), validation, enrichment, canonical transformation, routing decisions, outbound message generation, workflow state machine, configurable mapping engine.
**Key design note:** MT↔MX translation is a first-class concern. The ISO 20022 migration (TARGET2, CHAPS, SWIFT CBPR+) means every inbound MT has a potential MX equivalent and vice versa. The mapping engine must handle both directions without bespoke code per message type.
**Upstream:** `tms-integration`
**Downstream:** `tms-payment-hub`, `tms-trade`, `tms-settlement`, `tms-ihb`
**Phase:** 1

---

### `tms-payment-hub`
**Capabilities:** Payment creation, enrichment, validation, approval (maker-checker), release, repair, exception handling, payment factory (POBO aggregation), bulk payment processing, real-time and batch payments, network adapters (SWIFT MT/MX, SEPA SCT/SDD/Instant, ACH NACHA, TARGET2, CHAPS, FPS, CHIPS, Fedwire, H2H), payment templates, standing orders, cutoff management, sanctions hook, idempotency.
**Payment types modeled:**
- SWIFT MT103 / pacs.008 (cross-border customer credit transfer)
- SWIFT MT202 / pacs.009 (bank-to-bank / treasury transfer)
- SEPA SCT / SCT Inst (pain.001 → pacs.008)
- SEPA SDD Core / B2B (pain.008 → pacs.003)
- NACHA ACH (fixed-width file format)
- TARGET2 / CHAPS (RTGS — ISO 20022 pacs.008 mandatory from 2025)
- FPS / Instant schemes
- Direct Debit / Debit Memo (incoming)
- Payment factory / POBO batch (pain.001 multi-credit)
**Upstream:** `tms-message-manager`, direct API, `tms-ihb` (POBO flows)
**Downstream:** `tms-cash-ecf` (payment-derived expected flows), `tms-settlement`, `tms-accounting`, `tms-compliance`, `tms-reporting`
**Event sourced:** Yes — full payment event log
**Phase:** 1 (core wire); 2 (SEPA, ACH, RTGS, bulk); 3 (POBO factory, all schemes)

---

### `tms-cash-ecf` (Expected Cash Flows)
**What it is:** The forward-looking view of cash. Receives confirmed and anticipated cash movements from all instrument-producing domains and builds a dated, valued, attributed flow register.
**Capabilities:**
- Receive and store Expected Cash Flows from trades (at capture), payments (at creation), settlements (at instruction), IHB (at intercompany booking), manual entries
- Flow status lifecycle: ANTICIPATED → CONFIRMED → SETTLED → CANCELLED
- Flow attribution: which instrument, trade, payment, or contract produced this flow?
- Net flow calculation per account / currency / value date (the input to the cash ladder)
- Flow amendment when upstream instrument changes (trade amendment → flow revision)
- Cutoff-aware: flows past cutoff become "today's confirmed" vs "future anticipated"
**Upstream:** `tms-payment-hub`, `tms-trade`, `tms-settlement`, `tms-ihb`, `tms-bank-accounts` (interest flows), manual API
**Downstream:** `tms-cash-position` (primary feed), `tms-liquidity`, `tms-reporting`
**Not event sourced:** Standard mutable model with full event trail (flows change status, amounts, dates on amendments)
**Phase:** 1

---

### `tms-cash-bat` (Bank Account Transactions)
**What it is:** The ground truth of what the bank actually did. Receives bank-confirmed movements from inbound bank statements and real-time bank notifications.
**Capabilities:**
- Ingest bank statement files (MT940, CAMT.053 — both supported as first-class)
- Ingest real-time bank transaction notifications (camt.054, proprietary H2H feeds)
- Store individual bank transactions with full statement context
- Map transactions to internal accounts
- Status: RECEIVED → VALIDATED → MATCHED (after reconciliation) | UNMATCHED
- Expose transaction feed for NOSTRO reconciliation and cash position confirmation
- Bank fee statement ingestion (camt.086) for fee tracking and validation
**Upstream:** `tms-integration` (statement files), real-time bank feeds
**Downstream:** `tms-cash-position` (confirmed movements), `tms-reconciliation` (NOSTRO match input), `tms-reporting`
**Phase:** 1

---

### `tms-cash-position` (Cash Ladder)
**What it is:** The consolidated, real-time cash position and cash ladder. A pure aggregation and projection service.
**Capabilities:**
- Maintain the cash ladder: per-account, per-currency, per-value-date grid of confirmed vs forecast balances
- Balance types distinguished: book balance, ledger balance, available balance, float balance
- Intraday position: updated in near-real-time as BAT arrives and ECF flow statuses change
- End-of-day position sweep: triggered by EOD event, calculates confirmed closing balance
- Multiway view: drill from consolidated group → legal entity → portfolio → account → individual flows
- Account interest calculation (credit/debit interest on balances — feeds back to ECF)
- Cash concentration triggers: zero-balancing, target-balancing, notional pooling rules
- FX conversion for reporting currency views (uses `tms-fx-rates` for spot rates)
**CQRS pattern:** This service is almost entirely a read model. Writes come from consuming ECF and BAT events. Query API is the primary interface.
**Upstream:** `tms-cash-ecf` (events), `tms-cash-bat` (events), `tms-fx-rates` (conversion)
**Downstream:** `tms-liquidity`, `tms-reporting`, `tms-accounting` (EOD position → accounting entry)
**Phase:** 1

---

### `tms-liquidity`
**What it is:** Multi-horizon liquidity planning and monitoring across short-term (0–5 days), medium-term (1 week–3 months), and strategic (90+ days) views.
**Capabilities:**
- Short-term liquidity: consume real-time position from `tms-cash-position`; show confirmed + intraday flows
- Medium-term: cash ladder extended forward using ECFs from trades, outstanding payables/receivables, scheduled payments, IHB intercompany positions
- Strategic: budget-driven, configurable horizon, scenario-based
- **Limit monitoring:** hard limits (overdraft, counterparty), soft limits (comfort threshold, early-warning); alerts when breached
- **Funding gap analysis:** earliest date position turns negative; magnitude; days duration
- **Counterbalancing capacity:** what instruments can be liquidated or drawn (callable deposits, reverse repos, revolving credit facilities); models available capacity against gap
- Stress scenarios: configurable (e.g., "what if our largest counterparty defaults?", "what if inflows delayed 3 days?")
- Intraday liquidity monitoring: distinct from overnight; critical for RTGS participants; updates every 15 minutes minimum
- FX liquidity: multilateral FX netting across currencies before consolidated position
- Committed vs uncommitted funding lines: credit facilities modeled as capacity, not yet cash
**Upstream:** `tms-cash-position`, `tms-cash-ecf`, `tms-trade`, `tms-ihb`, `tms-bank-accounts` (credit facilities)
**Downstream:** `tms-reporting`, alert events to `tms-compliance` (liquidity stress alerts)
**Phase:** 2 (basic); 3 (full scenarios, stress, strategic)

---

### `tms-trade`
**What it is:** Trade capture and lifecycle management for treasury instruments.
**Instrument types:**
- FX: Spot, Forward, Swap (NDF included)
- Money Market: Fixed Deposits, Call/Notice Money, CDs
- Interest Rate: IRS (fixed/floating), Basis Swaps, FRAs, Cross-Currency Swaps
- FX Options: Vanilla European/American (barrier optional later)
- Repos / Reverse Repos (GBP, EUR, USD standard repos)
- Commercial Paper (issuance and investment)
- Bonds (investment portfolio, coupon-bearing)
- Revolving Credit Facilities / Overdraft Facilities (drawdowns and repayments)
**Capabilities:**
- Trade capture with full economic terms per instrument type
- Trade lifecycle: CAPTURED → CONFIRMED → ACTIVE → MATURED | CANCELLED | AMENDED
- Rollovers (deposits, call money extending maturity)
- Partial settlements
- Netting elections (bilateral netting with counterparties)
- Early termination / break clause exercise
- **Automatic maturity processing:** on maturity date, generate settlement instructions and accounting entries without human intervention
- Amendment lifecycle with full history
- Valuation hooks: pluggable pricing model interface; daily MTM run
- Accrual data: provides interest rate, day count convention, and accrual basis to `tms-accounting`
**Upstream:** `tms-message-manager`, direct API
**Downstream:** `tms-cash-ecf` (flow generation at capture), `tms-settlement` (instruction generation), `tms-accounting` (event-driven), `tms-risk` (exposure data), `tms-confirmation-matching`, `tms-reporting`
**Phase:** 2 (FX, MM); 3 (IRS, options, repos, bonds)

---

### `tms-risk`
**What it is:** Market risk, counterparty credit risk, and limit management. Consumes positions and trades; enforces limits before and after transactions.
**Capabilities:**
- **Counterparty credit risk:** Pre-Settlement Risk (PSR) = NPV of open trades with counterparty; Replacement Cost; Current Exposure
- **FX risk:** Net Open Position (NOP) per currency; VaR (parametric or historical simulation)
- **Interest rate risk:** Gap analysis (repricing gap by maturity bucket); DV01 (dollar value of 1bp); duration
- **Limit engine:**
  - Hard limits: block a trade if limit would be breached (synchronous pre-trade check via gRPC)
  - Soft limits: alert when 80% consumed; breach notified but not blocked
  - Limit types: counterparty credit limit, currency limit, product limit, book limit, overnight limit
  - Limit utilisation: real-time aggregate across all open positions
- **Pre-trade limit check:** called synchronously by `tms-trade` before booking; returns APPROVED | REJECTED | REQUIRES_OVERRIDE
- **Exposure aggregation:** across all instrument types per counterparty
- **Limit breach workflow:** automatic alert, optional override request (dual approval)
- Market risk reports: daily risk report, VaR report, sensitivity report
**Upstream:** `tms-trade` (position events), `tms-settlement` (confirmed changes), `tms-fx-rates` (revaluation)
**Downstream:** `tms-trade` (pre-trade check), `tms-reporting`, `tms-compliance` (large exposure alerts)
**Phase:** 2 (counterparty limits, FX NOP); 3 (VaR, IR risk, full gap analysis)

---

### `tms-settlement`
**What it is:** Settlement instruction lifecycle management, covering cash and securities settlement across all channels and mechanisms.
**Capabilities:**
- Settlement instruction generation from trades, maturity events, and payments
- **SSI lifecycle management:** SSIs have effective dates, expiry dates, amendment workflow (counterparty must confirm SSI change), priority ranking, and automatic update feeds (SWIFT BANA/ALERT messages)
- **Settlement netting:** consolidate multiple instructions between same counterparties on same value date into a single net instruction
- **CLS (Continuous Linked Settlement):** PVP FX settlement; separate instruction structure; CLS-specific message generation (CLS IN messages)
- **DVP (Delivery Versus Payment):** securities settlement; custodian/SWIFT sese.023/sese.024 messages; Euroclear/Clearstream connectivity model
- Cutoff management: per-network, per-currency, per-value-date; holiday-calendar-aware
- Settlement lifecycle: CREATED → MATCHED → VALIDATED → SENT → CONFIRMED | FAILED → REPAIR | CANCELLED
- Fails management: partial delivery, buy-in procedures (securities), CSDR penalty calculation, fail-chain tracking
- **NOSTRO reconciliation** (owned here, not in `tms-reconciliation`): expected settlement movements vs actual BAT movements from `tms-cash-bat`. This is time-critical (T+0) and must be near-real-time.
- Network adapters: SWIFT FIN (MT), SWIFT MX (pacs, sese), TARGET2, CHAPS, Euroclear, Clearstream
**Upstream:** `tms-trade` (maturity/confirmation events), `tms-payment-hub` (payment settlement confirmation)
**Downstream:** `tms-cash-ecf` (confirmed settlement flows), `tms-accounting` (confirmed entries), `tms-reporting`
**Event sourced:** Yes — settlement lifecycle state machine is event-sourced
**Phase:** 2 (cash settlement, SSI, cutoffs); 3 (CLS, DVP, securities, CSDR)

---

### `tms-ihb` (In-House Bank)
**What it is:** Manages the treasury acting as internal bank for group subsidiaries.
**Capabilities:**
- **POBO (Payment on Behalf Of):** treasury makes external payments on behalf of subsidiaries; routes through a master account; tracks subsidiary obligation
- **COBO (Collection on Behalf Of):** treasury collects payments into master account on behalf of subsidiaries; allocates receipts to subsidiary virtual accounts
- Intercompany lending: book intercompany loans with interest; generate internal confirmations and settlement instructions
- Intercompany FX: internal FX trades between group entities at defined transfer rates
- **Virtual account management:** each subsidiary gets a virtual account within the group's master accounts; positions tracked internally
- Internal transfer pricing: configurable markup on intercompany lending rates
- Netting centre: multilateral netting of intercompany flows across group entities; net settlement instruction generated
- Intercompany account statements: internal equivalent of bank statement per subsidiary
- **Intragroup reconciliation:** intercompany positions must net to zero at group level; breaks are flagged
**Upstream:** Subsidiary payment requests (API / file), `tms-payment-hub` (POBO execution), `tms-trade` (intercompany FX and loans), `tms-reference-data` (group entity structure)
**Downstream:** `tms-payment-hub` (POBO payment commands), `tms-cash-ecf` (intercompany flows), `tms-accounting`, `tms-reporting`
**Phase:** 3

---

### `tms-confirmation-matching`
**What it is:** Matches internal trade confirmations against counterparty confirmations received via SWIFT or external matching services.
**Capabilities:**
- Receive counterparty confirmations (MT300 FX, MT320 MM deposit, MT330 call money, MT340 FRA) via `tms-message-manager`
- Match against internal trade terms from `tms-trade`
- Partial match detection: which fields match, which differ
- **Integration with external matching services:** SWIFT Accord (primary), Traiana, MarkitSERV for eligible instrument types — TMS sends its side; external service performs bilateral match
- ISDA master agreement context per counterparty (governs dispute resolution)
- Discrepancy workflow: auto-generate amendment request or dispute notice to counterparty
- Temporal matching: same-day vs T+1 confirmation tolerance configurable per counterparty/instrument
- Match status: UNCONFIRMED → MATCHED | MISMATCHED → DISPUTED | OVERRIDDEN
**Upstream:** `tms-trade` (internal confirmation), `tms-message-manager` (counterparty MT/MX inbound)
**Downstream:** `tms-settlement` (confirmed trade triggers final settlement instruction), `tms-reporting`
**Phase:** 2 (internal auto-match); 3 (SWIFT Accord integration, external services)

---

### `tms-accounting`
**What it is:** Finance control and general ledger subledger. Produces double-entry accounting records from all business events.
**Key domain model corrections:**

**Chart of Accounts (CoA):**
- Hierarchical structure: Class (Asset/Liability/Equity/Income/Expense) → Category → Subcategory → Account → Sub-account
- CoA version-controlled: changes to the CoA require period-level migration
- Each legal entity has its own CoA (may share a master template)
- CoA drives P&L, balance sheet, and regulatory reporting structure

**Multi-GAAP:**
- Every accounting entry belongs to a **Ledger** (e.g., IFRS, US_GAAP, LOCAL_GAAP, MGMT)
- Same economic event produces parallel entries across relevant ledgers
- GAAP-to-GAAP reconciliation report

**Accrual Engine (critical — entirely absent from v1):**
- Daily accruals for all interest-bearing instruments: deposits, loans, bonds, IRS legs, repos
- Day-count conventions: ACT/360, ACT/365, ACT/ACT (ISDA), 30/360, 30E/360 — must be instrument-aware
- Straight-line amortization of bond premium/discount
- Commitment fee accruals for undrawn credit facilities
- **Accrual posting job:** runs nightly (or intraday for MX systems); generates accrual journal entries
- Accrual reversal: posted next business day morning (accrual-reversal pattern)

**Hedge accounting (Phase 3):**
- Hedge designation and documentation (IFRS 9 / ASC 815)
- Effectiveness testing (retrospective and prospective)
- OCI vs P&L bifurcation for qualifying hedges

**Suspense accounts:**
- Entries that cannot be immediately attributed go to a suspense account
- Aging report for suspense items
- Resolution workflow to move from suspense to final account

**Capabilities:**
- Double-entry journal posting (sum(debits) = sum(credits) enforced transactionally)
- Period open/close lifecycle with sub-period support (daily for accruals, monthly, quarterly, year-end)
- Reversals (never deletes; new compensating entry)
- Ledger balance maintenance per CoA account per period per GAAP
- Accounts payable / receivable subledger integration hooks
- Trial balance, general ledger report, P&L statement, balance sheet (read model via `tms-reporting`)
**Upstream:** `tms-payment-hub`, `tms-settlement`, `tms-trade`, `tms-ihb`, `tms-cash-position` (EOD events)
**Downstream:** `tms-reporting`, external GL / ERP integration (via outbound message)
**Phase:** 1 (basic journals, period close); 2 (CoA management, multi-GAAP, accrual engine); 3 (hedge accounting)

---

### `tms-reconciliation`
**What it is:** Five distinct reconciliation sub-processes, each with different source data, algorithms, tolerances, and resolution paths.

**Sub-process 1 — NOSTRO Reconciliation (owned by `tms-settlement`, not here)**
This is time-critical, near-real-time matching of settlement instructions against bank movements. Moved to `tms-settlement` because it is tightly coupled to the settlement lifecycle and must complete same-day.

**Sub-process 2 — Trade / Confirmation Reconciliation (owned by `tms-confirmation-matching`)**
Internal trade terms vs counterparty confirmation. Moved to `tms-confirmation-matching`.

**Sub-process 3 — Bank Statement Reconciliation (owned here)**
Internal cash entries (from `tms-cash-ecf`) vs bank-confirmed movements (from `tms-cash-bat`) — typically run daily after statement receipt. This is the traditional "bank rec."

**Sub-process 4 — Position Reconciliation (owned here)**
Internal position (from `tms-cash-position` and `tms-trade`) vs custodian or prime broker position statements. Includes securities positions, FX positions held externally.

**Sub-process 5 — Intragroup Reconciliation (owned here, triggered by `tms-ihb`)**
Intercompany positions across group entities must net to zero. Breaks indicate unbooked intercompany items.

**Sub-process 6 — Accounting Subledger Reconciliation (owned here)**
Subledger detail (cash entries, trade entries) vs control account balances in the general ledger.

**Shared capabilities:**
- Auto-match engine with configurable rules per sub-process (exact amount, date tolerance, narrative match, reference match)
- Manual match interface
- Break management: aging, escalation, resolution workflow
- Exception reporting with root cause tagging
- Re-run is idempotent (clears prior results for same run parameters)
**Phase:** 1 (bank statement recon); 2 (position recon, accounting recon); 3 (intragroup)

---

### `tms-fx-rates`
**What it is:** FX rate store and distribution service. Shared platform service consumed by Cash Position, Liquidity, Risk, Accounting (revaluation), and Reporting.
**Capabilities:**
- Ingest rates from external feeds: Reuters/Refinitiv, Bloomberg, ECB reference rates, bank quote feeds
- Rate types: spot mid, spot bid, spot ask, forward points per tenor, ECB reference rate
- Rate storage: versioned by source, timestamp, and rate type
- Triangulation: USD/GBP derived from USD/EUR × EUR/GBP when direct pair unavailable
- Rate publication to Kafka (compacted topic): downstream services subscribe to latest rates
- Historical rate lookup: "what was the EUR/USD mid rate on 2024-06-15 at 16:00 CET?" — required for revaluation and accounting
- Revaluation engine: apply current rates to open positions to calculate unrealised P&L
- Rate approval workflow: manually entered rates must be approved before use (for manual rate entry fallback when feed is unavailable)
**Upstream:** External rate feeds (Reuters TREP, Bloomberg BLPAPI, ECB data feed), manual API
**Downstream:** `tms-cash-position`, `tms-liquidity`, `tms-risk`, `tms-accounting`, `tms-reporting`
**Phase:** 2 (basic feed, spot rates); 3 (full forward curve, historical, revaluation P&L)

---

### `tms-rules-engine`
**Capabilities:** Versioned business rule sets, decision tables, FEEL DSL evaluation, rule types: validation, routing, settlement, payment, accounting, workflow.
**FEEL DSL note:** Use the open-source **Camunda DMN engine** (Apache-licensed, Java native) as the FEEL/DMN evaluation runtime. This avoids building a custom DSL parser and provides a standard decision table format (`.dmn` XML files stored and versioned in the DB).
**Upstream:** Called synchronously via gRPC by `tms-payment-hub`, `tms-message-manager`, `tms-settlement`, `tms-accounting`, `tms-risk`
**Phase:** 1 (validation and routing rules only, decision tables); 2 (FEEL/DMN, accounting rules, settlement rules); 3 (ML-assisted routing, anomaly detection rules)

---

### `tms-reference-data`
**Capabilities:** Counterparties (with LEI, BIC, ISDA agreement), legal entities, portfolios, books, business units, calendars, holidays, banks, mandates, currencies, countries, message standards, templates, formats, credit facility master data.
**New in v2:** Credit facilities and revolving credit lines are reference-managed here (terms, limits, tenors). Active drawdowns are managed in `tms-trade`.
**Phase:** 1

---

### `tms-bank-accounts`
**Capabilities:** Account master data, lifecycle (with workflow for opening/closing — requires board resolution and bank confirmation), signatories (with change control workflow: signatory cannot approve their own appointment), limits, account structures, virtual accounts, IBAN/account validation, mandates, bank fee tracking (camt.086 ingestion and matching against service contracts), account interest rate tracking.
**Phase:** 1 (core account master, signatories, limits); 2 (fee management, interest tracking, virtual accounts); 3 (pooling structures, mandate workflow)

---

### `tms-compliance`
**Capabilities:** Real-time and batch sanctions screening (OFAC, EU, UN, HMT, custom lists), PEP screening, alert workflow, notification routing, escalation, watchlist management.
**New in v2:** Transaction monitoring rules (pattern-based AML detection — Phase 3), correspondent banking risk scoring (Phase 3), large exposure alerting (triggered by `tms-risk`), liquidity stress alerting (triggered by `tms-liquidity`).
**Phase:** 1 (stub / configurable response); 2 (real sanctions screening); 3 (transaction monitoring, EDD)

---

### Identity (Keycloak — external service, not a TMS module)
**Capabilities:** OIDC/OAuth2, RBAC + ABAC, SSO, MFA, SoD enforcement, user activity audit.
Identity is provided by Keycloak 26.2 running as a managed service (Docker Compose locally, dedicated pod in Kubernetes). There is no `tms-identity` Spring Boot module — all JWT validation and role extraction runs inside each service via `tms-common-security`.
**Phase:** 1

---

### `tms-reporting`
**Capabilities:** All read models, operational and treasury reporting, analytics, drill-down, historical analysis, replay capability, report export.
**New in v2:** Multi-GAAP reporting views, risk report subscriptions, liquidity report (multi-horizon), regulatory report hooks (MiFID II transaction reporting, EMIR trade reporting — Phase 3).
**Phase:** 1 (payment, cash position); 2 (all domains); 3 (regulatory, BI connectors)

---

### `tms-bff` (Backend for Frontend)
**What it is:** An aggregation layer between the Angular UI and the microservices. The UI talks only to the BFF; the BFF fans out to microservices, aggregates responses, and returns a single view-optimised payload.
**Why:** Without a BFF, the payment detail screen would require 6+ API calls (payment data, counterparty, account details, compliance status, audit trail, accounting entries). This is slow, brittle, and exposes microservice internals to the UI.
**Capabilities:**
- Aggregated views: payment detail, trade blotter, cash position dashboard, liquidity dashboard, settlement queue
- WebSocket / SSE endpoint for real-time position and payment status updates (subscribes to Kafka internally)
- UI-session-aware caching in Redis (user-specific, short TTL)
- JWT pass-through (validates token, does not re-issue)
- Response shaping: include only fields the UI needs
- Feature flag evaluation (Phase 2/3 progressive feature rollout)
**Technology:** Spring Boot (lightweight), WebFlux for SSE streaming endpoints only, RestClient for microservice calls
**Phase:** 1 (payment and cash views); 2 (all domain views); 3 (analytics, drill-down)

---

### `tms-ui` (Angular SPA)
See [11-ui-architecture/README.md](../11-ui-architecture/README.md) for full specification.
**Phase:** 1 (payment ops, cash position, user admin); 2 (trade blotter, settlement queue, recon, accounting); 3 (liquidity dashboard, risk views, analytics)

---

## Shared Library Modules (Monorepo — not deployable services)

These are Maven modules that produce `.jar` libraries consumed by all services.
See [12-monorepo-common-libraries/README.md](../12-monorepo-common-libraries/README.md) for full specification.

| Module | Contents |
|--------|----------|
| `tms-events-schema` | All Avro `.avsc` schemas; generated Java classes shared across producers and consumers |
| `tms-common-audit` | `AuditLog` entity, `AuditService`, hash-chain logic, outbox integration |
| `tms-common-outbox` | `OutboxEntry` table DDL, `OutboxPublisher`, Debezium configuration template |
| `tms-common-idempotency` | `RedisIdempotencyStore`, `@Idempotent` annotation, idempotency filter |
| `tms-common-security` | JWT extraction utilities, `LegalEntityContext` holder, ABAC base policy classes |
| `tms-common-money` | `MonetaryAmount` wrapper (JSR-354 backed), currency rounding rules, day-count conventions |
| `tms-common-validation` | Shared domain validators (IBAN, BIC, LEI, SWIFT character set, value date) |
| `tms-common-messaging` | Kafka producer/consumer base config, Avro serializer config, header propagation |
| `tms-common-test` | Testcontainers base classes, Kafka test utilities, audit assertion helpers, `TestClock` |

---

## Context Relationships (corrected)

```
Upstream-Downstream (U → D):
  tms-message-manager → tms-payment-hub, tms-trade, tms-settlement, tms-ihb
  tms-payment-hub → tms-compliance [sync, blocking pre-release]
  tms-payment-hub → tms-cash-ecf, tms-settlement, tms-accounting [events]
  tms-trade → tms-cash-ecf, tms-settlement, tms-risk, tms-confirmation-matching, tms-accounting [events]
  tms-trade → tms-risk [sync pre-trade limit check via gRPC]
  tms-settlement → tms-cash-ecf, tms-accounting [events]
  tms-ihb → tms-payment-hub, tms-cash-ecf, tms-accounting [events + commands]
  tms-cash-ecf + tms-cash-bat → tms-cash-position [events]
  tms-cash-position → tms-liquidity [events]
  tms-settlement → NOSTRO recon (internal, same service)
  tms-confirmation-matching → tms-settlement [confirmed trade triggers final instruction]
  All domains → tms-reporting [events via Kafka]

Shared Kernel (read-only):
  tms-reference-data → all services (cached via Redis)
  tms-fx-rates → tms-cash-position, tms-liquidity, tms-risk, tms-accounting
  tms-rules-engine → tms-payment-hub, tms-message-manager, tms-settlement, tms-accounting (gRPC)
  Keycloak (external) → all services (JWT validation via tms-common-security)
  tms-bank-accounts → tms-payment-hub, tms-cash-bat, tms-reconciliation

Anti-Corruption Layer (ACL):
  tms-integration → tms-message-manager (raw bytes → canonical message)
  tms-message-manager → tms-payment-hub (canonical → CreatePaymentCommand)
  tms-message-manager → tms-trade (canonical → CaptureTradeCommand)
  tms-settlement ↔ external custodians (internal instruction ↔ SWIFT sese / CLS IN)

UI Layer:
  tms-ui → tms-bff [only; never calls microservices directly]
  tms-bff → microservices [aggregation, REST/gRPC]
  tms-bff → Kafka [SSE real-time subscription for position/payment updates]
```

---

## Multi-Tenancy Model (explicit decision)

**Decision: Shared Schema with PostgreSQL Row-Level Security (Option A), promotable to Schema-per-Tenant.**

**Rationale:**
- Phase 1 serves a single legal entity or a small number of tightly related group entities
- RLS + ABAC provides sufficient isolation for group treasury deployments
- Schema-per-tenant requires Flyway schema management across N schemas — significantly more operational overhead
- If regulatory or contractual data residency requirements demand full isolation for a client, the schema-per-tenant upgrade path is documented in the roadmap

**Tenant isolation enforcement layers (defence in depth):**
1. ABAC policy (Spring Security): `legalEntityId` in JWT claim must match resource's `legal_entity_id`
2. PostgreSQL RLS: every multi-tenant table has a policy checking `current_setting('app.legal_entity_id')`
3. Kafka message headers: consumers reject messages where `x-legal-entity-id` ≠ configured entity
4. Integration tests: explicit cross-entity isolation tests in every service's test suite

**Kafka multi-tenancy:**
- `legalEntityId` is a partition key component and a required header
- Kafka ACLs restrict topic access to the producing service's service account
- For high-isolation requirements: separate topic prefixes per tenant (`tms.{tenantId}.payment.*`)

---

## Service Count Summary

| Category | Services / Modules |
|----------|-------------------|
| Integration & Messaging | `tms-integration`, `tms-message-manager` |
| Payments | `tms-payment-hub` |
| Cash Management | `tms-cash-ecf`, `tms-cash-bat`, `tms-cash-position` |
| Liquidity | `tms-liquidity` |
| Trade & Instruments | `tms-trade` |
| Risk | `tms-risk` |
| Settlement | `tms-settlement` |
| In-House Bank | `tms-ihb` |
| Confirmation Matching | `tms-confirmation-matching` |
| Accounting | `tms-accounting` |
| Reconciliation | `tms-reconciliation` |
| FX Rates | `tms-fx-rates` |
| Rules Engine | `tms-rules-engine` |
| Reference Data | `tms-reference-data` |
| Bank Accounts | `tms-bank-accounts` |
| Compliance | `tms-compliance` |
| Identity | Keycloak 26.2 (external; no TMS module) |
| Reporting | `tms-reporting` |
| UI Layer | `tms-bff`, `tms-ui` |
| Shared Libraries | `tms-events-schema`, `tms-common-audit`, `tms-common-outbox`, `tms-common-idempotency`, `tms-common-security`, `tms-common-money`, `tms-common-validation`, `tms-common-messaging`, `tms-common-test` |
| **Total deployable services** | **19** (Keycloak is an external dependency, not a TMS service) |
| **Total shared library modules** | **9** |
