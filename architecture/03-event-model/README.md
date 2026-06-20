# 03 — Event Model (v2)

## What Changed from v1
- Cash domain events split into three namespaces: `ecf` (Expected Cash Flows), `bat` (Bank Account Transactions), `position` (Cash Ladder)
- Added Risk domain events (limit checks, breaches, exposure updates)
- Added Accrual Engine events (daily accruals, reversals, amortisation)
- Added In-House Bank events (POBO, COBO, intercompany)
- Added FX Rate events (rate published, revaluation run)
- Added ISO 20022 MX message events alongside MT message events
- Added Settlement netting and CLS events
- Added Chart of Accounts lifecycle events
- Added SSI lifecycle events (amendment, expiry, SWIFT BANA update)

---

## Event Design Principles (unchanged)

1. Events are **facts** — past tense, immutable, named after what happened
2. Events carry **full context** — no downstream lookups required to process an event
3. Events are **versioned** — Avro with backward compatibility enforced by Schema Registry
4. Events carry **correlation** — `eventId`, `correlationId`, `causationId`, `legalEntityId`, `userId`, `timestamp`
5. Events are **idempotent to consume** — `eventId` deduplication before processing
6. Events are **published via Outbox** — written atomically with the business record, never dual-write

---

## Base Event Envelope (Avro)

```avro
{
  "type": "record",
  "name": "DomainEventEnvelope",
  "namespace": "com.tms.events",
  "fields": [
    {"name": "eventId",          "type": "string"},
    {"name": "eventType",        "type": "string"},
    {"name": "eventVersion",     "type": "int",    "default": 1},
    {"name": "occurredAt",       "type": "long",   "logicalType": "timestamp-millis"},
    {"name": "correlationId",    "type": "string"},
    {"name": "causationId",      "type": ["null","string"], "default": null},
    {"name": "sagaId",           "type": ["null","string"], "default": null},
    {"name": "legalEntityId",    "type": "string"},
    {"name": "portfolioId",      "type": ["null","string"], "default": null},
    {"name": "bookId",           "type": ["null","string"], "default": null},
    {"name": "userId",           "type": ["null","string"], "default": null},
    {"name": "sourceService",    "type": "string"},
    {"name": "traceId",          "type": ["null","string"], "default": null},
    {"name": "spanId",           "type": ["null","string"], "default": null},
    {"name": "payload",          "type": "bytes",  "doc": "Avro-serialised specific event payload"}
  ]
}
```

---

## Domain Event Catalogue

### 1 — Integration & Ingestion

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `InboundFileReceived` | File lands in SFTP / drop zone | `fileId`, `fileName`, `channelType`, `sourceSystem`, `sizeBytes`, `checksumSha256`, `fingerprint` |
| `FileVirusScanPassed` | Scan result clean | `fileId`, `scanEngine`, `scannedAt` |
| `FileVirusScanFailed` | Virus detected | `fileId`, `threatName`, `quarantinedAt` |
| `FileValidationFailed` | Schema/format invalid | `fileId`, `validationErrors[]` |
| `InboundMessageReceived` | Individual message extracted from file or API | `messageId`, `messageStandard` (MT\|MX\|NACHA\|SEPA_XML), `messageType`, `rawFingerprint`, `fileId?` |
| `DuplicateMessageDetected` | Fingerprint match against prior | `messageId`, `originalMessageId`, `detectedAt` |
| `InboundMessageStatusUpdated` | Status change in tracking | `messageId`, `previousStatus`, `newStatus` |

---

### 2 — Message Processing

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `MessageParsed` | Raw → canonical | `messageId`, `messageStandard`, `canonicalType`, `parsedAt` |
| `MessageMTToMXTranslated` | MT→MX translation applied | `messageId`, `sourceMTType`, `targetMXNamespace`, `translationRuleVersion` |
| `MessageMXToMTTranslated` | MX→MT translation applied | `messageId`, `sourceMXNamespace`, `targetMTType` |
| `MessageEnriched` | Reference data applied | `messageId`, `enrichedFields[]` |
| `MessageValidationFailed` | Validation error | `messageId`, `errors[]` |
| `MessageRouted` | Routing decision made | `messageId`, `routedTo`, `routingRuleId`, `routingRuleVersion` |
| `OutboundMessageGenerated` | Outbound formatted for network | `messageId`, `targetFormat`, `networkChannel`, `targetBIC?` |
| `WorkflowStateTransitioned` | Processing step advanced | `messageId`, `previousState`, `newState`, `stepName` |

---

### 3 — Payment Hub

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `PaymentCreated` | New payment | `paymentId`, `clientPaymentId`, `paymentType`, `amount`, `currency`, `debitAccountId`, `creditAccountId`, `counterpartyId`, `valueDate`, `urgency`, `networkChannel` |
| `PaymentEnriched` | BIC/SSI/routing resolved | `paymentId`, `enrichedFields[]`, `resolvedBIC`, `resolvedSSI` |
| `PaymentValidated` | Rules passed | `paymentId`, `validationRuleIds[]` |
| `PaymentValidationFailed` | Rules failed | `paymentId`, `errors[]` |
| `PaymentSanctionsCheckRequested` | Compliance hook triggered | `paymentId`, `screeningRequestId`, `entityName`, `entityType` |
| `PaymentSanctionsCleared` | Compliance cleared | `paymentId`, `screeningRequestId`, `listsChecked[]`, `clearedAt` |
| `PaymentSanctionsHeld` | Hit on watchlist | `paymentId`, `alertId`, `matchedList`, `matchedEntry`, `matchScore` |
| `PaymentApprovalRequested` | Submitted for maker-checker | `paymentId`, `approvalLevel`, `requestedApproverIds[]`, `amount`, `currency` |
| `PaymentApproved` | Checker approves | `paymentId`, `approverId`, `approvalLevel`, `approvedAt` |
| `PaymentRejected` | Checker rejects | `paymentId`, `approverId`, `rejectionReason`, `rejectedAt` |
| `PaymentReleased` | Sent to network | `paymentId`, `networkChannel`, `networkMessageType`, `networkReference`, `releasedAt` |
| `PaymentAcknowledged` | Network ACK received | `paymentId`, `networkReference`, `acknowledgedAt` |
| `PaymentSettled` | Final confirmation | `paymentId`, `settlementReference`, `settlementDate`, `settledAmount`, `settledCurrency` |
| `PaymentFailed` | Network reject / timeout | `paymentId`, `failureReason`, `networkError`, `failedAt` |
| `PaymentReturnReceived` | Return from beneficiary bank | `paymentId`, `returnReason`, `returnCode`, `returnedAt`, `returnedAmount` |
| `PaymentRepairInitiated` | Manual repair started | `paymentId`, `repairReason`, `assignedTo` |
| `PaymentRepaired` | Repair completed | `paymentId`, `repairedBy`, `changes[]` |
| `PaymentCancelled` | Cancellation confirmed | `paymentId`, `cancelledBy`, `reason` |
| `POBOPaymentCreated` | IHB-originated POBO payment | `paymentId`, `subsidiarylegalEntityId`, `masterAccountId`, `subsidaryReference` |

---

### 4 — Expected Cash Flows (ECF)

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `ExpectedCashFlowCreated` | New ECF from any source | `flowId`, `sourceType` (PAYMENT\|TRADE\|SETTLEMENT\|IHB\|MANUAL), `sourceId`, `flowDirection` (INFLOW\|OUTFLOW), `amount`, `currency`, `accountId`, `valueDate`, `flowStatus` (ANTICIPATED\|CONFIRMED) |
| `ExpectedCashFlowConfirmed` | Flow moves to CONFIRMED | `flowId`, `confirmedAt`, `confirmedBy` (system or user) |
| `ExpectedCashFlowAmended` | Amount / date changed by upstream | `flowId`, `previousAmount`, `newAmount`, `previousValueDate`, `newValueDate`, `amendmentReason` |
| `ExpectedCashFlowCancelled` | Upstream instrument cancelled | `flowId`, `cancelledAt`, `reason` |
| `ExpectedCashFlowSettled` | Cash actually moved | `flowId`, `settledAt`, `actualAmount`, `batTransactionId` |

---

### 5 — Bank Account Transactions (BAT)

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `BankStatementReceived` | MT940 / CAMT.053 ingested | `statementId`, `accountId`, `bankId`, `statementDate`, `openingBalance`, `closingBalance`, `currency`, `messageStandard` (MT940\|CAMT053), `fileId` |
| `BankTransactionPosted` | Individual line item on statement | `transactionId`, `statementId`, `accountId`, `amount`, `currency`, `valueDate`, `bookingDate`, `entryReference`, `narrative`, `entryCode` (debit\|credit), `proprietaryCode?` |
| `BankTransactionMatched` | Matched to ECF in NOSTRO recon | `transactionId`, `matchedFlowId`, `matchedAt`, `matchRule` |
| `BankTransactionUnmatched` | Break raised | `transactionId`, `breakId`, `breakReason` |
| `BankFeeStatementReceived` | CAMT.086 bank fee statement | `feeStatementId`, `accountId`, `bankId`, `period`, `totalFees`, `currency`, `fileId` |

---

### 6 — Cash Position (Cash Ladder)

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `CashPositionUpdated` | ECF or BAT event changes position | `accountId`, `currency`, `valueDate`, `legalEntityId`, `openingBalance`, `closingBalance`, `totalConfirmedInflows`, `totalConfirmedOutflows`, `totalAnticipatedInflows`, `totalAnticipatedOutflows`, `availableBalance`, `updatedAt` |
| `IntradayCashPositionSnapshot` | Intraday snapshot (every 15 min) | `accountId`, `currency`, `snapshotTime`, `currentBalance`, `pendingCredits`, `pendingDebits`, `availableBalance` |
| `EndOfDayCashPositionFinalised` | EOD sweep | `accountId`, `currency`, `eodDate`, `confirmedClosingBalance`, `finalisedAt` |
| `CashConcentrationTriggered` | Zero-balance or target-balance rule met | `ruleId`, `sourceAccountId`, `targetAccountId`, `transferAmount`, `currency`, `triggerType` (ZERO_BALANCE\|TARGET_BALANCE) |
| `AccountInterestAccrued` | Daily interest on account balance | `accountId`, `currency`, `accrualDate`, `balance`, `rate`, `interestAmount`, `dayCountConvention` |

---

### 7 — Liquidity

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `LiquidityPlanCreated` | Plan generated | `planId`, `planDate`, `horizon`, `currency`, `legalEntityId` |
| `LiquidityForecastUpdated` | Forecast revised | `planId`, `deltaAmount`, `revisionReason`, `updatedAt` |
| `FundingShortfallDetected` | Position below limit | `accountId`, `currency`, `horizon`, `shortfallAmount`, `earliestShortfallDate`, `limitId` |
| `LiquidityLimitBreached` | Hard limit crossed | `limitId`, `limitType`, `legalEntityId`, `actualAmount`, `limitAmount`, `breachedAt` |
| `CounterbalancingCapacityUpdated` | Available liquidity buffer changed | `legalEntityId`, `currency`, `availableCapacity`, `components[]` |
| `LiquidityScenarioCompleted` | Stress scenario run finished | `scenarioId`, `scenarioType`, `planId`, `shortfallAmount?`, `stressedPositions[]` |
| `IntradayLiquidityAlertRaised` | Intraday position threshold breached | `accountId`, `currency`, `threshold`, `actualBalance`, `raisedAt` |

---

### 8 — Trade

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `TradeCaptured` | New trade booked | `tradeId`, `tradeType`, `instrumentType`, `counterpartyId`, `notionalAmount`, `currency`, `tradeDate`, `maturityDate`, `rate?`, `bookId`, `portfolioId` |
| `TradeConfirmed` | Counterparty confirmation matched | `tradeId`, `confirmationId`, `confirmedAt`, `matchMethod` (INTERNAL\|SWIFT_ACCORD\|MARKITSERV) |
| `TradeAmended` | Amendment applied | `tradeId`, `amendmentId`, `amendedFields[]`, `amendedBy`, `reason` |
| `TradeCancelled` | Trade voided | `tradeId`, `cancelledBy`, `reason` |
| `TradeMatured` | Maturity date reached | `tradeId`, `maturityDate`, `principalAmount`, `interestAmount`, `currency`, `autoSettlementGenerated` |
| `TradeRolledOver` | Deposit / call money extended | `tradeId`, `newMaturityDate`, `newRate`, `rolledAmount`, `rollDate` |
| `TradeNettingElected` | Netting agreement applied | `tradeId`, `nettingGroupId`, `counterpartyId`, `netSettlementAmount`, `currency` |
| `TradeValuationUpdated` | MTM / revaluation run | `tradeId`, `valuationDate`, `mtmValue`, `currency`, `unrealisedPnL`, `pricingModel`, `fxRateApplied?` |
| `EarlyTerminationExecuted` | Break clause exercised | `tradeId`, `terminationDate`, `breakValue`, `terminatedBy` |

---

### 9 — Risk

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `LimitCheckRequested` | Pre-trade check triggered | `checkId`, `tradeId`, `counterpartyId`, `limitType`, `exposureAmount`, `currency`, `requestedAt` |
| `LimitCheckApproved` | Limit available | `checkId`, `tradeId`, `limitId`, `utilisationBefore`, `utilisationAfter`, `approvedAt` |
| `LimitCheckRejected` | Limit would be breached | `checkId`, `tradeId`, `limitId`, `limitAmount`, `currentUtilisation`, `rejectedAt` |
| `LimitBreachDetected` | Post-booking breach (soft or hard) | `limitId`, `limitType`, `counterpartyId?`, `currencyCode?`, `utilisationPct`, `breachType` (SOFT\|HARD), `detectedAt` |
| `LimitBreachOverrideRequested` | Override approval needed | `limitId`, `requesterId`, `overrideReason`, `requestedApprovers[]` |
| `LimitBreachOverrideApproved` | Override granted | `limitId`, `approverId`, `approvedAt` |
| `ExposureUpdated` | Counterparty / currency exposure recalculated | `counterpartyId`, `currency?`, `exposureType` (PSR\|REPLACEMENT_COST\|NOP), `newExposure`, `limitId`, `utilisationPct` |
| `FXNetOpenPositionUpdated` | NOP recalculated after trade / revaluation | `currency`, `netPosition`, `reportingCurrency`, `reportingAmount`, `calculatedAt` |
| `CounterpartyCreditLimitUpdated` | Limit changed | `counterpartyId`, `limitId`, `oldLimit`, `newLimit`, `effectiveFrom`, `changedBy` |

---

### 10 — Settlement

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `SettlementInstructionCreated` | Instruction generated | `instructionId`, `tradeId?`, `paymentId?`, `settlementType` (CASH\|SECURITIES\|CLS\|DVP), `delivererId`, `receiverId`, `amount`, `currency`, `settleDate`, `networkChannel` |
| `SettlementNettingApplied` | Multiple instructions netted | `nettingId`, `instructionIds[]`, `netAmount`, `currency`, `counterpartyId`, `settleDate` |
| `SettlementSSIMatched` | SSI resolved for instruction | `instructionId`, `ssiId`, `ssiType`, `resolvedAt` |
| `SettlementSSIAmended` | SSI changed (SWIFT BANA update) | `ssiId`, `counterpartyId`, `currency`, `previousDetails{}`, `newDetails{}`, `effectiveDate`, `confirmationRef` |
| `SettlementCutoffApproaching` | Warning before cutoff | `instructionId`, `networkChannel`, `cutoffTime`, `timeRemaining` |
| `SettlementCutoffMissed` | Instruction past cutoff | `instructionId`, `cutoffTime`, `nextValueDate` |
| `SettlementSent` | Instruction transmitted to network | `instructionId`, `networkReference`, `messageType`, `sentAt` |
| `SettlementConfirmed` | Custodian / network confirms | `instructionId`, `confirmedAt`, `confirmedAmount`, `networkReference` |
| `SettlementFailed` | Fail received | `instructionId`, `failReason`, `failCode`, `failedAt`, `csdrPenaltyApplicable` |
| `SettlementRepairInitiated` | Repair workflow | `instructionId`, `repairReason`, `assignedTo` |
| `SettlementCancelled` | Cancellation confirmed | `instructionId`, `cancelledAt`, `cancelledBy` |
| `NOSTROReconciliationBreakFound` | BAT does not match instruction | `instructionId`, `batTransactionId?`, `breakType`, `breakAmount?`, `raisedAt` |
| `NOSTROReconciliationMatched` | BAT matched to instruction | `instructionId`, `batTransactionId`, `matchedAt` |
| `CLSPaymentSubmitted` | CLS IN message sent | `instructionId`, `clsReference`, `pvpCurrency`, `pvpAmount` |
| `CLSPaymentSettled` | CLS confirms PVP settlement | `instructionId`, `clsReference`, `settledAt` |

---

### 11 — Confirmation Matching

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `ConfirmationReceived` | Counterparty MT300/MT320/MX received | `confirmationId`, `tradeId?`, `counterpartyId`, `messageType`, `receivedAt` |
| `ConfirmationMatched` | Full match with internal trade | `confirmationId`, `tradeId`, `matchMethod`, `matchedAt` |
| `ConfirmationPartiallyMatched` | Some fields differ | `confirmationId`, `tradeId`, `matchedFields[]`, `mismatchedFields[]` |
| `ConfirmationMismatched` | No match | `confirmationId`, `tradeId?`, `mismatchReason`, `mismatchedFields[]` |
| `ConfirmationDisputeRaised` | Formal dispute with counterparty | `confirmationId`, `tradeId`, `disputeReason`, `raisedAt` |
| `ConfirmationDisputeResolved` | Dispute closed | `confirmationId`, `resolution`, `resolvedBy`, `resolvedAt` |
| `ExternalMatchServiceResultReceived` | SWIFT Accord / Traiana result | `confirmationId`, `externalService`, `externalResult` (MATCHED\|UNMATCHED), `externalReference` |

---

### 12 — Accounting

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `AccountingEntryCreated` | Journal entry posted | `entryId`, `journalId`, `ledger` (IFRS\|US_GAAP\|LOCAL_GAAP\|MGMT), `coaAccount`, `debitAmount`, `creditAmount`, `currency`, `postingDate`, `sourceId`, `sourceType` |
| `JournalPosted` | Journal batch committed | `journalId`, `ledger`, `entryCount`, `totalDebits`, `totalCredits`, `period`, `postedAt` |
| `AccrualEntryPosted` | Daily accrual run | `entryId`, `journalId`, `accrualType` (INTEREST\|AMORTISATION\|COMMITMENT_FEE), `instrumentId`, `accrualDate`, `accrualAmount`, `dayCountConvention`, `daysAccrued` |
| `AccrualReversalPosted` | Prior day accrual reversed | `reversalEntryId`, `originalEntryId`, `reversalDate` |
| `AccountingEntryReversed` | Manual or system reversal | `originalEntryId`, `reversalEntryId`, `reversalDate`, `reason` |
| `LedgerBalanceUpdated` | Balance materialised after journal | `coaAccount`, `ledger`, `period`, `openingBalance`, `totalDebits`, `totalCredits`, `closingBalance` |
| `AccountingPeriodClosed` | Period close workflow completed | `period`, `ledger`, `legalEntityId`, `closedAt`, `closedBy` |
| `ChartOfAccountsUpdated` | CoA changed | `coaVersion`, `changes[]`, `effectiveDate`, `changedBy` |
| `HedgeDesignationCreated` | IFRS 9 hedge documented | `hedgeId`, `hedgeType` (FAIR_VALUE\|CASH_FLOW\|NET_INVESTMENT), `hedgedItemId`, `hedgingInstrumentId`, `designatedAt` |
| `HedgeEffectivenessAssessed` | Periodic effectiveness test | `hedgeId`, `testDate`, `effectivenessPct`, `result` (EFFECTIVE\|INEFFECTIVE), `ociAmount?`, `pnlAmount?` |

---

### 13 — FX Rates

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `FXRatePublished` | New rate received from feed | `rateId`, `baseCurrency`, `quoteCurrency`, `rateType` (SPOT_MID\|SPOT_BID\|SPOT_ASK\|ECB_REF), `rate`, `rateSource`, `effectiveAt` |
| `FXRateApproved` | Manual rate approved | `rateId`, `approvedBy`, `approvedAt` |
| `FXForwardPointsPublished` | Forward curve update | `baseCurrency`, `quoteCurrency`, `tenors[]`, `forwardPoints[]`, `source`, `effectiveAt` |
| `RevaluationRunCompleted` | Portfolio revaluation finished | `revalRunId`, `valuationDate`, `fxRateSnapshot{}`, `totalUnrealisedPnL`, `currency`, `completedAt` |

---

### 14 — Reconciliation

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `ReconciliationRunStarted` | Batch recon initiated | `reconId`, `reconType` (BANK_STATEMENT\|POSITION\|INTRAGROUP\|ACCOUNTING_SUBLEDGER), `period`, `legalEntityId` |
| `ReconciliationMatchFound` | Auto-match | `reconId`, `internalRef`, `externalRef`, `matchedAmount`, `currency`, `matchRule`, `matchedAt` |
| `ReconciliationBreakFound` | No match / partial | `reconId`, `breakId`, `breakType`, `internalRef?`, `externalRef?`, `breakAmount?`, `currency?`, `breakReason` |
| `ReconciliationBreakResolved` | Break cleared | `reconId`, `breakId`, `resolution`, `resolvedBy`, `resolvedAt` |
| `ReconciliationBreakEscalated` | Aged break escalated | `breakId`, `ageInDays`, `escalatedTo`, `escalatedAt` |
| `ReconciliationRunCompleted` | Batch finished | `reconId`, `totalItems`, `matched`, `breaks`, `completedAt` |

---

### 15 — In-House Bank

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `POBOPaymentRequested` | Subsidiary requests payment | `poboRequestId`, `subsidiaryId`, `beneficiaryAccountId`, `amount`, `currency`, `requestedValueDate`, `subsidiaryReference` |
| `POBOPaymentExecuted` | Treasury makes payment on behalf | `poboRequestId`, `paymentId`, `masterAccountId`, `executedAt` |
| `COBOCollectionReceived` | Collection received for subsidiary | `coboId`, `subsidiaryId`, `receivedAmount`, `currency`, `receivedAt`, `virtualAccountId` |
| `IntercompanyLoanCreated` | IHB lends to subsidiary | `loanId`, `lendingEntityId`, `borrowingEntityId`, `amount`, `currency`, `interestRate`, `maturityDate` |
| `IntercompanyFXBooked` | Internal FX trade between entities | `fxId`, `buyingEntityId`, `sellingEntityId`, `buyAmount`, `sellAmount`, `buyCurrency`, `sellCurrency`, `internalRate`, `valueDate` |
| `MultilateralNettingCalculated` | Netting centre run | `nettingId`, `period`, `participatingEntities[]`, `grossFlows`, `netFlows`, `reductionPct` |
| `MultilateralNettingSettled` | Net instructions generated | `nettingId`, `generatedInstructions[]`, `settledAt` |
| `IntraGroupBreakFound` | Intercompany position mismatch | `breakId`, `entityA`, `entityB`, `entityABalance`, `entityBBalance`, `discrepancy`, `currency` |

---

### 16 — Compliance

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `SanctionsScreeningRequested` | Pre-payment check | `screeningId`, `paymentId?`, `entityName`, `entityType`, `screeningType` |
| `SanctionsScreeningCleared` | Clean result | `screeningId`, `clearedAt`, `listsChecked[]` |
| `SanctionsAlertGenerated` | Potential hit | `screeningId`, `alertId`, `alertLevel` (EXACT\|FUZZY_HIGH\|FUZZY_LOW\|PEP), `matchedList`, `matchedEntry`, `matchScore` |
| `AlertEscalated` | Alert escalated | `alertId`, `escalatedTo`, `escalatedAt`, `reason` |
| `AlertResolved` | Alert closed | `alertId`, `resolution` (CLEARED\|BLOCKED), `resolvedBy`, `resolvedAt` |
| `WatchlistUpdated` | New watchlist data loaded | `uploadId`, `listType`, `recordCount`, `loadedAt` |
| `LargeExposureAlertRaised` | Risk-triggered large exposure | `alertId`, `counterpartyId`, `exposureAmount`, `regulatoryThreshold`, `raisedAt` |

---

### 17 — Bank Accounts

| Event | Trigger | Key Payload Fields |
|-------|---------|-------------------|
| `BankAccountOpened` | Account lifecycle — opened | `accountId`, `bankId`, `accountNumber`, `iban?`, `currency`, `legalEntityId`, `openedDate` |
| `BankAccountClosed` | Account closed | `accountId`, `closedDate`, `reason` |
| `BankAccountSignatoryChanged` | Signatory updated | `accountId`, `signatoryId`, `changeType` (ADDED\|REMOVED), `effectiveDate`, `approvedBy` |
| `AccountLimitChanged` | Credit / debit limit updated | `accountId`, `limitType`, `oldLimit`, `newLimit`, `effectiveDate` |
| `BankFeeDiscrepancyFound` | Charged fee ≠ contracted fee | `feeStatementId`, `accountId`, `chargedFee`, `expectedFee`, `discrepancy`, `currency` |

---

## Schema Versioning

### Compatibility Rules
1. All schemas registered in Confluent Schema Registry with **BACKWARD** compatibility (default)
2. Adding optional fields: `"default": null` — always allowed
3. Removing or renaming fields: **never within a version** — bump event type name: `PaymentCreatedV2`
4. Breaking change migration path:
   ```
   1. Register V2 schema (backward compatible — new field has default)
   2. Producer writes V2 (populates new field)
   3. Old consumers read V2 fine (Avro resolution ignores unknown fields when reading older schema)
   4. New consumers use new field
   5. After all consumers upgraded: register V1 as deprecated in Registry
   6. After retention window: archive V1 schema
   ```

### Schema Modules
All `.avsc` files live in `tms-events-schema/src/main/avro/` organised by domain:
```
tms-events-schema/
  src/main/avro/
    common/     DomainEventEnvelope.avsc, MonetaryAmount.avsc
    integration/ InboundFileReceived.avsc, InboundMessageReceived.avsc ...
    payment/     PaymentCreated.avsc, PaymentSettled.avsc ...
    cash/        ecf/ExpectedCashFlowCreated.avsc ...
                 bat/BankStatementReceived.avsc ...
                 position/CashPositionUpdated.avsc ...
    trade/       TradeCaptured.avsc, TradeMatured.avsc ...
    risk/        LimitCheckApproved.avsc, ExposureUpdated.avsc ...
    settlement/  SettlementInstructionCreated.avsc ...
    accounting/  AccrualEntryPosted.avsc, JournalPosted.avsc ...
    ihb/         POBOPaymentExecuted.avsc, IntercompanyLoanCreated.avsc ...
    fx/          FXRatePublished.avsc, RevaluationRunCompleted.avsc ...
    compliance/  SanctionsAlertGenerated.avsc ...
    recon/       ReconciliationBreakFound.avsc ...
```

---

## Outbox Pattern — Standard DDL

Every service that produces domain events includes this table (identical schema per service):

```sql
CREATE TABLE domain_event_outbox (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id        UUID NOT NULL UNIQUE,
  event_type      VARCHAR(200) NOT NULL,
  topic           VARCHAR(300) NOT NULL,
  partition_key   VARCHAR(200),
  avro_schema_id  INT NOT NULL,            -- Schema Registry ID
  payload         BYTEA NOT NULL,          -- Avro-serialised payload
  headers         JSONB,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  published       BOOLEAN NOT NULL DEFAULT FALSE,
  published_at    TIMESTAMPTZ,
  retry_count     INT NOT NULL DEFAULT 0
);

CREATE INDEX ON domain_event_outbox (published, created_at) WHERE NOT published;
CREATE INDEX ON domain_event_outbox (event_id);
```

Published by Debezium CDC connector watching WAL, or a lightweight polling publisher (500ms interval fallback).

---

## Idempotency Key Strategy

| Layer | Mechanism | TTL |
|-------|-----------|-----|
| REST API | `Idempotency-Key` header → Redis key `idempotency:{service}:{key}` | 24h |
| Kafka consumer | `eventId` → checked in Redis `consumed:{consumerGroup}:{eventId}` before processing | 72h (> max replay window) |
| RabbitMQ consumer | `messageId` property → Redis dedup key | 24h |
| Payment creation | `clientPaymentId` — `UNIQUE` constraint on `payment_snapshots` table | Permanent |
| Saga step | `saga_step_results` table stores step output; re-entry returns cached result | Permanent (saga lifetime) |
| Accrual posting | `accrual_entries (trade_id, accrual_date)` UNIQUE constraint prevents duplicate postings | Permanent |
