# 05 — API Contracts (v2)

## What Changed from v1
- Cash APIs split into three services: `tms-cash-ecf`, `tms-cash-bat`, `tms-cash-position`
- Added Risk API (`tms-risk`): limit management, pre-trade check, exposure queries
- Added FX Rate API (`tms-fx-rates`): rate queries, rate approval, revaluation
- Added IHB API (`tms-ihb`): POBO, COBO, intercompany, netting
- Added Confirmation Matching API (`tms-confirmation-matching`)
- Accounting API extended: Chart of Accounts management, accrual queries, multi-GAAP endpoints
- Liquidity API extended: multi-horizon views, limit monitoring, scenario queries
- Settlement API extended: SSI lifecycle, netting, CLS, NOSTRO recon status
- BFF API added: aggregated views for the Angular UI
- API versioning rules made explicit

---

## Global Standards

- REST over HTTPS for all external-facing APIs
- gRPC for high-frequency internal calls (Rules Engine, Risk pre-trade check, Identity validation)
- All endpoints versioned: `/api/v1/...` → `/api/v2/...` on breaking change
- `Idempotency-Key` header required on all `POST`/`PUT` that mutate state
- `Authorization: Bearer <JWT>` on all endpoints
- `X-Correlation-ID` header propagated on all requests (injected by Gateway if absent)
- Cursor-based pagination: `?cursor=<token>&limit=<n>` on all list endpoints
- Errors: RFC 7807 Problem Details (`application/problem+json`)
- `Deprecation` + `Sunset` headers on deprecated endpoints
- Monetary amounts: JSON string `"amount": "1234.56"` + `"currency": "EUR"` (never float)

---

## Payment Hub — `tms-payment-hub`

### Commands
```
POST   /api/v1/payments
       Body: { clientPaymentId, legalEntityId, portfolioId, bookId, paymentType,
               amount, currency, debitAccountId, creditAccountId, counterpartyId,
               valueDate, urgency, narrative, remittanceInfo, networkChannel?,
               poboSubsidiaryId? }
       Returns: 202 { paymentId, status: RECEIVED }

POST   /api/v1/payments/bulk
       Content-Type: multipart/form-data
       Body: file (pain.001 XML | NACHA | CSV), metadata
       Returns: 202 { batchId, paymentCount }

POST   /api/v1/payments/{paymentId}/submit
       Returns: 202 { paymentId, status: PENDING_VALIDATION | PENDING_APPROVAL }

POST   /api/v1/payments/{paymentId}/approve
       Body: { approverId, comments }
       Returns: 200 { paymentId, status: APPROVED | RELEASED }

POST   /api/v1/payments/{paymentId}/reject
       Body: { approverId, reason }
       Returns: 200 { paymentId, status: REJECTED }

POST   /api/v1/payments/{paymentId}/cancel
       Body: { reason }
       Returns: 202 { paymentId, status: CANCELLATION_PENDING }

POST   /api/v1/payments/{paymentId}/repair
       Body: { corrections{} }
       Returns: 202 { paymentId, status: REPAIR_IN_PROGRESS }

POST   /api/v1/payments/templates
       Body: CreatePaymentTemplateCommand
       Returns: 201 { templateId }

POST   /api/v1/payments/from-template/{templateId}
       Body: { overrides{}, valueDate }
       Returns: 202 { paymentId }
```

### Queries
```
GET    /api/v1/payments/{paymentId}
GET    /api/v1/payments/{paymentId}/events          — full event sourced history
GET    /api/v1/payments/{paymentId}/audit-trail
GET    /api/v1/payments?status=&currency=&from=&to=&networkChannel=&cursor=&limit=
GET    /api/v1/payments/pending-approval?approverId=
GET    /api/v1/payments/exceptions?from=&to=
GET    /api/v1/payments/bulk/{batchId}
GET    /api/v1/payments/templates
GET    /api/v1/payments/cutoffs?date=&networkChannel=&currency=
```

---

## Expected Cash Flows — `tms-cash-ecf`

### Commands
```
POST   /api/v1/cash/ecf
       Body: { sourceType, sourceId, flowDirection, amount, currency,
               accountId, valueDate, narrative, flowStatus }
       Returns: 202 { flowId }

PUT    /api/v1/cash/ecf/{flowId}/confirm
       Returns: 200 { flowId, status: CONFIRMED }

PUT    /api/v1/cash/ecf/{flowId}/cancel
       Body: { reason }
       Returns: 200 { flowId, status: CANCELLED }
```

### Queries
```
GET    /api/v1/cash/ecf?accountId=&currency=&from=&to=&flowStatus=&sourceType=
GET    /api/v1/cash/ecf/{flowId}
GET    /api/v1/cash/ecf/net?accountId=&currency=&from=&to=
       Returns: NetCashFlow[] { date, totalInflows, totalOutflows, netFlow,
                                confirmedInflows, anticipatedInflows }
```

---

## Bank Account Transactions — `tms-cash-bat`

### Commands
```
POST   /api/v1/cash/bat/statements
       Content-Type: multipart/form-data
       Body: file (MT940 | CAMT.053), metadata{ accountId, bankId, currency }
       Returns: 202 { statementId }

POST   /api/v1/cash/bat/statements/api
       Body: { accountId, bankId, statementDate, openingBalance, closingBalance,
               transactions[], currency, messageStandard }
       Returns: 202 { statementId }
```

### Queries
```
GET    /api/v1/cash/bat/statements?accountId=&from=&to=
GET    /api/v1/cash/bat/statements/{statementId}
GET    /api/v1/cash/bat/statements/{statementId}/transactions
GET    /api/v1/cash/bat/transactions?accountId=&from=&to=&status=
GET    /api/v1/cash/bat/fee-statements?accountId=&period=
```

---

## Cash Position — `tms-cash-position`

### Queries (read model — no commands)
```
GET    /api/v1/cash/position?accountId=&currency=&date=&legalEntityId=
       Returns: CashPosition {
         accountId, currency, date, legalEntityId,
         openingBalance, closingBalance,
         confirmedInflows, confirmedOutflows,
         anticipatedInflows, anticipatedOutflows,
         availableBalance, bookBalance, ledgerBalance
       }

GET    /api/v1/cash/position/ladder?legalEntityId=&currency=&from=&to=
       Returns: CashLadder {
         currency, legalEntityId, horizon,
         rows: [{ date, confirmedNetFlow, anticipatedNetFlow,
                  cumulativeConfirmed, cumulativeTotal }]
       }

GET    /api/v1/cash/position/intraday?accountId=&currency=
       Returns: IntradayPosition { snapshots[{ time, balance, pendingCredits, pendingDebits }] }

GET    /api/v1/cash/position/eod?accountId=&currency=&from=&to=
       Returns: Page<EODPosition>

GET    /api/v1/cash/position/multi-currency?legalEntityId=&reportingCurrency=&date=
       Returns: MultiCurrencyPosition { accounts[], totalInReportingCurrency, fxRatesApplied{} }

GET    /api/v1/cash/position/group?groupLegalEntityId=&date=&reportingCurrency=
       Returns: GroupCashPosition { entities[], consolidatedPosition }
```

---

## Liquidity — `tms-liquidity`

### Commands
```
POST   /api/v1/liquidity/plans
       Body: { horizon, currency, legalEntityId, method }
       Returns: 202 { planId }

POST   /api/v1/liquidity/scenarios
       Body: { planId, scenarioType, stressParameters{} }
       Returns: 202 { scenarioId }

POST   /api/v1/liquidity/limits
       Body: { limitType, legalEntityId, accountId?, currency, limitAmount, effectiveFrom }
       Returns: 201 { limitId }

PUT    /api/v1/liquidity/limits/{limitId}
```

### Queries
```
GET    /api/v1/liquidity/plans/{planId}
GET    /api/v1/liquidity/view/short-term?legalEntityId=&currency=
       Returns: ShortTermLiquidityView {
         horizon: 5,
         days: [{ date, confirmedInflows, confirmedOutflows, netPosition,
                  cumulativePosition, limitAmount?, alertLevel? }]
       }

GET    /api/v1/liquidity/view/medium-term?legalEntityId=&currency=&horizonDays=
GET    /api/v1/liquidity/view/strategic?legalEntityId=&currency=
GET    /api/v1/liquidity/limits?legalEntityId=&currency=
GET    /api/v1/liquidity/limits/{limitId}/utilisation
       Returns: LimitUtilisation { limitAmount, currentUsage, utilisationPct, breachStatus }

GET    /api/v1/liquidity/counterbalancing?legalEntityId=&currency=
       Returns: CounterbalancingCapacity {
         totalCapacity, components[{ type, instrument, maturityDate, amount, liquidityHorizon }]
       }

GET    /api/v1/liquidity/scenarios/{scenarioId}
GET    /api/v1/liquidity/funding-gaps?legalEntityId=&currency=&horizon=
       Returns: FundingGapAnalysis {
         gaps[{ date, shortfallAmount, durationDays, coverableBy[] }]
       }
```

---

## Trade — `tms-trade`

### Commands
```
POST   /api/v1/trades
       Body: CaptureTradeCommand {
         tradeType, instrumentType, counterpartyId, bookId, portfolioId,
         notionalAmount, currency, tradeDate, maturityDate, rate?,
         dayCountConvention?, fixedLeg?, floatingLeg?,
         repoDetails?, fxDetails?, optionDetails?
       }
       Returns: 202 { tradeId }

PUT    /api/v1/trades/{tradeId}/amend
       Body: AmendTradeCommand { amendedFields{}, reason }
       Returns: 202 { tradeId, amendmentId }

POST   /api/v1/trades/{tradeId}/cancel
       Body: { reason }
       Returns: 202

POST   /api/v1/trades/{tradeId}/rollover
       Body: { newMaturityDate, newRate?, rollAmount? }
       Returns: 202 { tradeId, rollEventId }

POST   /api/v1/trades/{tradeId}/early-terminate
       Body: { terminationDate, reason }
       Returns: 202 { tradeId, terminationId }
```

### Queries
```
GET    /api/v1/trades/{tradeId}
GET    /api/v1/trades/{tradeId}/events
GET    /api/v1/trades/{tradeId}/cash-flows         — projected ECF schedule
GET    /api/v1/trades/{tradeId}/valuations
GET    /api/v1/trades?tradeType=&counterpartyId=&status=&from=&to=&cursor=
GET    /api/v1/trades/blotter?bookId=&date=         — the trade blotter view
GET    /api/v1/trades/maturing?from=&to=            — trades maturing in date range
GET    /api/v1/trades/pending-confirmation
```

---

## Risk — `tms-risk`

### gRPC API (pre-trade limit check — synchronous, high frequency)
```protobuf
service RiskLimitService {
  rpc CheckLimit(LimitCheckRequest) returns (LimitCheckResponse);
  rpc GetCounterpartyExposure(ExposureRequest) returns (ExposureResponse);
}

message LimitCheckRequest {
  string  trade_id        = 1;
  string  counterparty_id = 2;
  string  limit_type      = 3;  // COUNTERPARTY_CREDIT | CURRENCY | BOOK | OVERNIGHT
  string  amount          = 4;  // BigDecimal as string
  string  currency        = 5;
  string  legal_entity_id = 6;
  string  correlation_id  = 7;
}

message LimitCheckResponse {
  bool    approved         = 1;
  string  limit_id         = 2;
  string  limit_amount     = 3;
  string  utilisation_before = 4;
  string  utilisation_after  = 5;   // if approved
  string  rejection_reason   = 6;   // if rejected
  bool    requires_override  = 7;
}
```

### REST API (limit management, exposure queries)
```
POST   /api/v1/risk/limits
       Body: { limitType, counterpartyId?, currency?, bookId?, legalEntityId,
               limitAmount, currency, effectiveFrom, effectiveTo? }
       Returns: 201 { limitId }

PUT    /api/v1/risk/limits/{limitId}
POST   /api/v1/risk/limits/{limitId}/deactivate

POST   /api/v1/risk/limit-overrides
       Body: { limitId, tradeId, reason, requestedApprovers[] }
       Returns: 202 { overrideId }

POST   /api/v1/risk/limit-overrides/{overrideId}/approve
POST   /api/v1/risk/limit-overrides/{overrideId}/reject
```

### Queries
```
GET    /api/v1/risk/limits?legalEntityId=&limitType=&counterpartyId=&status=
GET    /api/v1/risk/limits/{limitId}
GET    /api/v1/risk/limits/{limitId}/utilisation
       Returns: LimitUtilisation { limitAmount, currentExposure, utilisationPct,
                                   trades[], breachStatus }

GET    /api/v1/risk/exposure/counterparty/{counterpartyId}
       Returns: CounterpartyExposure { psr, replacementCost, currentExposure,
                                       instruments[], currency, asOf }

GET    /api/v1/risk/exposure/fx-nop?legalEntityId=&reportingCurrency=
       Returns: FXNetOpenPosition { currencies[{ currency, netPosition, reportingAmount }] }

GET    /api/v1/risk/reports/gap-analysis?legalEntityId=&reportingCurrency=&date=
       Returns: IRGapAnalysis { buckets[{ tenor, fixedAmount, floatingAmount, gap, dv01 }] }

GET    /api/v1/risk/breaches?legalEntityId=&status=&from=&to=
       Returns: Page<LimitBreach>
```

---

## Settlement — `tms-settlement`

### Commands
```
POST   /api/v1/settlement/instructions
       Body: CreateInstructionCommand { tradeId?, paymentId?, settlementType,
                                        delivererId, receiverId, amount, currency,
                                        settleDate, networkChannel }
       Returns: 202 { instructionId }

PUT    /api/v1/settlement/instructions/{instructionId}/cancel
       Body: { reason }
       Returns: 202

POST   /api/v1/settlement/instructions/{instructionId}/repair
       Body: { corrections{} }
       Returns: 202

POST   /api/v1/settlement/netting/trigger
       Body: { counterpartyId, settleDate, currency }
       Returns: 202 { nettingId }

POST   /api/v1/settlement/ssi
       Body: CreateSSICommand { counterpartyId, currency, settlementMethod,
                                 accountDetails{}, effectiveFrom }
       Returns: 201 { ssiId }

PUT    /api/v1/settlement/ssi/{ssiId}/amend
       Body: { newDetails{}, effectiveFrom, confirmationRef }
       Returns: 202
```

### Queries
```
GET    /api/v1/settlement/instructions/{instructionId}
GET    /api/v1/settlement/instructions/{instructionId}/events
GET    /api/v1/settlement/instructions?status=&settleDate=&currency=&networkChannel=
GET    /api/v1/settlement/instructions/fails?from=&to=
GET    /api/v1/settlement/cutoffs?date=&networkChannel=&currency=
GET    /api/v1/settlement/netting/{nettingId}
GET    /api/v1/settlement/ssi?counterpartyId=&currency=
GET    /api/v1/settlement/nostro-recon?accountId=&date=
       Returns: NOSTROReconciliation { matched[], unmatched[], breaks[] }
```

---

## Confirmation Matching — `tms-confirmation-matching`

### Commands
```
POST   /api/v1/confirmations/{confirmationId}/override
       Body: { reason, overriddenBy }
       Returns: 200

POST   /api/v1/confirmations/{confirmationId}/raise-dispute
       Body: { disputeReason, mismatchedFields[] }
       Returns: 202 { disputeId }

POST   /api/v1/confirmations/disputes/{disputeId}/resolve
       Body: { resolution, resolvedBy }
       Returns: 200
```

### Queries
```
GET    /api/v1/confirmations?tradeId=&status=&from=&to=
GET    /api/v1/confirmations/{confirmationId}
GET    /api/v1/confirmations/unmatched?from=&to=
GET    /api/v1/confirmations/disputes?status=
```

---

## Accounting — `tms-accounting`

### Commands
```
POST   /api/v1/accounting/journals
       Body: { entries[], period, ledger, sourceId, sourceType }
       Returns: 202 { journalId }

POST   /api/v1/accounting/journals/{journalId}/reverse
       Body: { reason, reversalDate }
       Returns: 202 { reversalJournalId }

POST   /api/v1/accounting/periods/{period}/close
       Body: { legalEntityId, ledger }
       Returns: 202

POST   /api/v1/accounting/coa/accounts
       Body: { parentAccountId?, code, name, accountType, ledger, effectiveFrom }
       Returns: 201 { coaAccountId }

PUT    /api/v1/accounting/coa/accounts/{coaAccountId}

POST   /api/v1/accounting/hedges
       Body: HedgeDesignationCommand { hedgeType, hedgedItemId, hedgingInstrumentId }
       Returns: 201 { hedgeId }
```

### Queries
```
GET    /api/v1/accounting/journals/{journalId}
GET    /api/v1/accounting/entries?coaAccount=&ledger=&from=&to=&legalEntityId=
GET    /api/v1/accounting/accruals?instrumentId=&from=&to=
GET    /api/v1/accounting/ledger?coaAccount=&ledger=&period=&legalEntityId=
       Returns: LedgerAccount { coaAccount, period, ledger, openingBalance,
                                 totalDebits, totalCredits, closingBalance, movements[] }

GET    /api/v1/accounting/coa?ledger=&legalEntityId=
       Returns: ChartOfAccounts { hierarchy: [{ class, categories[{ name, accounts[] }] }] }

GET    /api/v1/accounting/periods?legalEntityId=&ledger=
GET    /api/v1/accounting/reports/trial-balance?period=&ledger=&legalEntityId=
GET    /api/v1/accounting/reports/pl?period=&ledger=&legalEntityId=
GET    /api/v1/accounting/reports/balance-sheet?period=&ledger=&legalEntityId=
GET    /api/v1/accounting/hedge/{hedgeId}/effectiveness
```

---

## FX Rates — `tms-fx-rates`

### Commands
```
POST   /api/v1/fx-rates/manual
       Body: { baseCurrency, quoteCurrency, rate, rateType, effectiveAt }
       Returns: 202 { rateId, status: PENDING_APPROVAL }

POST   /api/v1/fx-rates/{rateId}/approve
       Returns: 200 { rateId, status: ACTIVE }

POST   /api/v1/fx-rates/revaluation
       Body: { legalEntityId, valuationDate, rateSource }
       Returns: 202 { revalRunId }
```

### Queries
```
GET    /api/v1/fx-rates/spot?baseCurrency=&quoteCurrency=&asOf=
       Returns: FXRate { baseCurrency, quoteCurrency, mid, bid, ask, source, asOf }

GET    /api/v1/fx-rates/history?baseCurrency=&quoteCurrency=&from=&to=&rateType=
       Returns: Page<FXRate>

GET    /api/v1/fx-rates/forward?baseCurrency=&quoteCurrency=&tenor=
       Returns: ForwardRate { baseCurrency, quoteCurrency, tenor, points, outright, asOf }

GET    /api/v1/fx-rates/revaluation/{revalRunId}
       Returns: RevaluationResult { runId, date, pnlByCurrency[], totalPnL, reportingCurrency }
```

---

## In-House Bank — `tms-ihb`

### Commands
```
POST   /api/v1/ihb/pobo-requests
       Body: { subsidiaryId, beneficiaryAccountId, amount, currency,
               valueDate, subsidiaryReference, narrative }
       Returns: 202 { poboRequestId }

POST   /api/v1/ihb/intercompany-loans
       Body: { lendingEntityId, borrowingEntityId, amount, currency,
               interestRate, maturityDate, dayCountConvention }
       Returns: 201 { loanId }

POST   /api/v1/ihb/intercompany-fx
       Body: { buyingEntityId, sellingEntityId, buyCurrency, sellCurrency,
               buyAmount, internalRate, valueDate }
       Returns: 201 { fxId }

POST   /api/v1/ihb/netting/trigger
       Body: { period, participatingEntities[] }
       Returns: 202 { nettingId }
```

### Queries
```
GET    /api/v1/ihb/pobo-requests?subsidiaryId=&status=&from=&to=
GET    /api/v1/ihb/pobo-requests/{poboRequestId}
GET    /api/v1/ihb/cobo-collections?subsidiaryId=&from=&to=
GET    /api/v1/ihb/virtual-accounts?subsidiaryId=&legalEntityId=
GET    /api/v1/ihb/intercompany-positions?legalEntityId=&reportingCurrency=
       Returns: IntercompanyPositions { subsidiaries[], netGroupPosition,
                                         intraGroupBreaks[] }
GET    /api/v1/ihb/netting/{nettingId}
GET    /api/v1/ihb/statements/{subsidiaryId}?period=
       Returns: IntercompanyStatement (equivalent to bank statement for internal accounts)
```

---

## Reconciliation — `tms-reconciliation`

### Commands
```
POST   /api/v1/reconciliation/runs
       Body: { reconType, period, legalEntityId, accountId? }
       Returns: 202 { reconId }

POST   /api/v1/reconciliation/breaks/{breakId}/resolve
       Body: { resolution, resolvedBy, notes }
       Returns: 200

POST   /api/v1/reconciliation/matches/manual
       Body: { internalRef, externalRef, reconType, matchedBy, notes }
       Returns: 201 { matchId }

POST   /api/v1/reconciliation/rules
       Body: { reconType, ruleName, conditions[], toleranceAmount?, tolerancePct? }
       Returns: 201 { ruleId }
```

### Queries
```
GET    /api/v1/reconciliation/runs/{reconId}
GET    /api/v1/reconciliation/breaks?reconType=&status=&from=&to=&ageGtDays=
GET    /api/v1/reconciliation/matches?reconId=
GET    /api/v1/reconciliation/rules?reconType=
GET    /api/v1/reconciliation/summary?legalEntityId=&period=
       Returns: ReconciliationSummary { byType[{ reconType, matched, breaks, autoResolved }] }
```

---

## Rules Engine — `tms-rules-engine`

### gRPC (high-frequency evaluation)
```protobuf
service RulesEngineService {
  rpc Evaluate(EvaluateRequest) returns (EvaluateResponse);
  rpc EvaluateRuleSet(EvaluateRuleSetRequest) returns (EvaluateRuleSetResponse);
}
```

### REST (admin and configuration)
```
POST   /api/v1/rules/rule-sets
       Body: { name, type, dmnXml (base64-encoded .dmn file) }
       Returns: 201 { ruleSetId, version: 1 }

POST   /api/v1/rules/rule-sets/{ruleSetId}/publish
POST   /api/v1/rules/rule-sets/{ruleSetId}/deprecate

POST   /api/v1/rules/rule-sets/{ruleSetId}/test
       Body: { testCases[{ input{}, expectedDecision }] }
       Returns: TestResult { passed, failed, cases[] }

GET    /api/v1/rules/rule-sets
GET    /api/v1/rules/rule-sets/{ruleSetId}/versions
GET    /api/v1/rules/evaluations?ruleSetId=&from=&to=  (audit of decisions)
```

---

## BFF — `tms-bff`

The BFF is consumed exclusively by `tms-ui`. It aggregates microservice responses into view-optimised payloads.

### Aggregated Views (REST)
```
GET    /bff/v1/payments/dashboard
       Aggregates: payment summary + pending approvals + exceptions + compliance holds
       → single response for the payment dashboard landing page

GET    /bff/v1/payments/{paymentId}/detail
       Aggregates: payment data + counterparty + account + compliance status + ECF entry + accounting entries
       → all data to render the payment detail screen in one call

GET    /bff/v1/cash/dashboard?legalEntityId=
       Aggregates: multi-currency positions + intraday snapshots + liquidity short-term view
       → cash management dashboard landing page

GET    /bff/v1/trades/blotter?bookId=&date=
       Aggregates: trades + valuations + settlement instruction status + confirmation status

GET    /bff/v1/settlement/queue?settleDate=
       Aggregates: settlement instructions + SSI status + cutoff times + NOSTRO recon status

GET    /bff/v1/risk/dashboard?legalEntityId=
       Aggregates: limit utilisations + FX NOP + counterparty exposure top-10 + recent breaches
```

### Real-Time SSE (Server-Sent Events)
```
GET    /bff/v1/stream/cash-position?legalEntityId=
       Content-Type: text/event-stream
       Events: { event: "cash-position-update", data: { accountId, currency, balance, updatedAt } }

GET    /bff/v1/stream/payments?legalEntityId=
       Events: { event: "payment-status-update", data: { paymentId, status, updatedAt } }

GET    /bff/v1/stream/notifications?userId=
       Events: { event: "notification", data: { type, message, severity, entityId, entityType } }

GET    /bff/v1/stream/risk-alerts?legalEntityId=
       Events: { event: "risk-alert", data: { limitId, alertType, severity, message } }
```

### Auth
```
POST   /bff/v1/auth/session       — exchange code for session (PKCE flow)
POST   /bff/v1/auth/refresh
DELETE /bff/v1/auth/session       — logout
GET    /bff/v1/auth/me            — current user profile + permissions + legalEntityId
```
