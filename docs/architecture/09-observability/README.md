# 09 — Observability, Reporting, and Replay Strategy (v2)

## Observability Stack

```
Services (20 microservices + BFF)
  Spring Boot + Micrometer + OpenTelemetry SDK (auto-instrumented)
        │                          │
        ▼                          ▼
  Prometheus                 OTel Collector
  (metrics scrape             (traces + logs
   /actuator/prometheus)       pipeline)
        │                          │
        ▼                     ┌────┴────┐
  Grafana Dashboards          ▼         ▼
  + Alertmanager           Tempo      Loki
                           (traces)   (logs)
                                │
                                ▼
                          Grafana (unified UI)
```

---

## Distributed Tracing

### Configuration (per service, via `tms-common-*` auto-configuration)

```yaml
management:
  tracing:
    sampling:
      probability: 1.0     # 100% in dev; 10% in prod (configurable per service)

otel:
  exporter:
    otlp:
      endpoint: http://otel-collector:4318
  resource:
    attributes:
      service.name: ${spring.application.name}
      service.version: ${APP_VERSION}
      deployment.environment: ${ENVIRONMENT}
      legal_entity_id: ${LEGAL_ENTITY_ID:unknown}
```

### Trace Propagation

Every request enters with a W3C `traceparent` header. Spring Micrometer auto-propagates through:
- REST calls via `RestClient` interceptors
- Kafka producer/consumer via record headers
- RabbitMQ messages via message properties
- Virtual threads (inherits from parent — Java 21 Loom)
- gRPC calls (tms-risk, tms-rules-engine) via gRPC interceptor

**Custom span attributes (added in all services):**
```java
Span.current()
    .setAttribute("payment.id", paymentId.toString())
    .setAttribute("trade.id", tradeId.toString())
    .setAttribute("legal.entity.id", legalEntityId)
    .setAttribute("saga.type", sagaType)
    .setAttribute("saga.step", currentStep)
    .setAttribute("correlation.id", correlationId)
    .setAttribute("message.standard", "MT|MX")   // for integration services
    .setAttribute("risk.limit.type", limitType);  // for tms-risk
```

### Key Trace Scenarios

| Scenario | Services in trace |
|----------|-------------------|
| Payment end-to-end | Gateway → BFF → Payment Hub → Rules Engine → Compliance → Network Adapter |
| Trade capture + risk check | Trade → Risk (gRPC) → Rules Engine → ECF |
| Settlement full lifecycle | Settlement → Bank Accounts → Network → NOSTRO recon |
| Cash ladder update | Payment Hub event → ECF → Cash Position (event-driven; linked via Kafka header) |
| Nightly accrual | Accounting Batch → Trade (query) → Accounting (journal insert) |
| IHB POBO | IHB → Payment Hub → ECF → Accounting → IHB virtual account update |
| Confirmation matching | Trade → Confirmation Matching → External stub → Settlement |
| Bank statement ingestion | Integration → Message Manager → Cash BAT → Cash Position |

---

## Metrics

### JVM and Framework Metrics (auto-exposed by all services)

```
jvm.memory.*                  Heap and non-heap per pool
jvm.gc.*                      GC pause duration and count
jvm.threads.*                 Thread count; virtual thread count (Java 21)
process.cpu.usage             CPU utilisation
http.server.requests          Count, duration, status per endpoint
spring.kafka.*                Consumer lag, publish rate, poll rate per topic/group
hikaricp.*                    DB connection pool utilisation and wait time
resilience4j.*                Circuit breaker state, call metrics per service name
grpc.server.calls.*           gRPC call count and duration (tms-risk, tms-rules-engine)
```

### Business Metrics by Service

**tms-payment-hub**
```java
Counter.builder("tms.payments.created").tag("payment_type", ...).tag("legal_entity_id", ...).increment();
Counter.builder("tms.payments.settled").tag("network_channel", ...).increment();
Counter.builder("tms.payments.failed").tag("failure_reason", ...).increment();
Gauge.builder("tms.payments.pending_approval", repo, r -> r.countByStatus(PENDING_APPROVAL)).register(mr);
Gauge.builder("tms.payments.held_compliance", repo, r -> r.countByStatus(SANCTIONS_HELD)).register(mr);
Timer.builder("tms.payment.saga.duration").tag("final_status", ...).record(duration);
```

**tms-cash-ecf / tms-cash-bat / tms-cash-position**
```java
Counter.builder("tms.ecf.created").tag("source_type", ...).tag("currency", ...).increment();
Counter.builder("tms.bat.transactions.posted").tag("account_id", ...).increment();
Counter.builder("tms.cash.position.updates").tag("currency", ...).increment();
Gauge.builder("tms.cash.position.net", positionRepo,
    r -> r.netPositionForEntity(entityId, currency)).register(mr);
```

**tms-risk**
```java
Counter.builder("tms.risk.limit.checks").tag("limit_type", ...).tag("result", ...).increment();
Counter.builder("tms.risk.limit.breaches").tag("severity", ...).increment();
Gauge.builder("tms.risk.utilisation.pct", limitRepo,
    r -> r.avgUtilisationPct(entityId)).register(mr);
```

**tms-ihb**
```java
Counter.builder("tms.ihb.pobo.executed").tag("subsidiary_id", ...).increment();
Counter.builder("tms.ihb.netting.runs").tag("status", ...).increment();
Gauge.builder("tms.ihb.virtual.balance", virtualAccountRepo,
    r -> r.totalVirtualBalance(entityId, currency)).register(mr);
```

**tms-accounting**
```java
Counter.builder("tms.accounting.journals.posted").tag("ledger", ...).increment();
Counter.builder("tms.accounting.accrual.instruments").tag("accrual_type", ...).increment();
Timer.builder("tms.accounting.accrual.run.duration").tag("entity_id", ...).record(duration);
```

**tms-settlement**
```java
Counter.builder("tms.settlement.instructions.sent").tag("network", ...).increment();
Counter.builder("tms.settlement.instructions.confirmed").tag("network", ...).increment();
Counter.builder("tms.settlement.fails").tag("fail_reason", ...).increment();
Counter.builder("tms.settlement.netting.applied").tag("currency", ...).increment();
```

**tms-confirmation-matching**
```java
Counter.builder("tms.confirmation.matched").tag("match_source", ...).increment();
Counter.builder("tms.confirmation.mismatched").increment();
Counter.builder("tms.confirmation.disputes.raised").increment();
Gauge.builder("tms.confirmation.pending", repo, r -> r.countPending()).register(mr);
```

### Complete Business Metric Catalogue

| Metric | Type | Labels |
|--------|------|--------|
| `tms.payments.created.total` | Counter | payment_type, legal_entity_id |
| `tms.payments.settled.total` | Counter | payment_type, network_channel |
| `tms.payments.failed.total` | Counter | failure_reason, network_channel |
| `tms.payments.pending_approval` | Gauge | legal_entity_id |
| `tms.payments.held_compliance` | Gauge | legal_entity_id |
| `tms.payment.saga.duration.seconds` | Timer | final_status |
| `tms.ecf.created.total` | Counter | source_type, currency |
| `tms.bat.transactions.posted.total` | Counter | currency, legal_entity_id |
| `tms.cash.position.updates.total` | Counter | currency |
| `tms.trades.captured.total` | Counter | instrument_type, legal_entity_id |
| `tms.trades.maturing.count` | Gauge | days_to_maturity_bucket |
| `tms.settlement.instructions.total` | Counter | network, settlement_type |
| `tms.settlement.confirms.total` | Counter | network_channel |
| `tms.settlement.fails.total` | Counter | fail_reason |
| `tms.settlement.netting.applied.total` | Counter | currency |
| `tms.accounting.journals.posted.total` | Counter | ledger, journal_type |
| `tms.accounting.accrual.run.duration.seconds` | Timer | legal_entity_id |
| `tms.accounting.period.close.duration.seconds` | Timer | period, ledger |
| `tms.risk.limit.checks.total` | Counter | limit_type, result |
| `tms.risk.limit.breaches.total` | Counter | severity, limit_type |
| `tms.risk.utilisation.pct` | Gauge | limit_type, counterparty_id |
| `tms.fx.rates.approved.total` | Counter | rate_type |
| `tms.fx.revaluation.run.duration.seconds` | Timer | legal_entity_id |
| `tms.ihb.pobo.executed.total` | Counter | subsidiary_id |
| `tms.ihb.netting.runs.total` | Counter | status |
| `tms.confirmation.matched.total` | Counter | match_source |
| `tms.confirmation.disputes.total` | Counter | — |
| `tms.recon.matches.total` | Counter | recon_type |
| `tms.recon.breaks.open` | Gauge | recon_type, legal_entity_id |
| `tms.recon.break.age.days` | Histogram | recon_type |
| `tms.compliance.alerts.open` | Gauge | alert_level |
| `tms.compliance.alert.resolution.hours` | Timer | alert_level |
| `tms.rules.evaluations.total` | Counter | rule_set_type, decision |
| `tms.kafka.consumer.lag` | Gauge | topic, consumer_group |
| `tms.saga.instances.running` | Gauge | saga_type |
| `tms.saga.timeout.total` | Counter | saga_type, step |
| `tms.outbox.unpublished.rows` | Gauge | service_name |
| `tms.audit.hash.chain.errors.total` | Counter | service_name |

---

## Grafana Dashboards

### Dashboard 1: Payment Operations
```
Panels:
  Payments created/min (rate, by type)
  Payments by status — stacked bar (SETTLED / FAILED / PENDING / HELD)
  Payment saga duration — p50/p95/p99 histogram
  Pending approval queue depth (gauge with threshold lines)
  Compliance hold queue depth
  Payment fail rate by network channel
  Dead letter queue depth — critical threshold alert line at 0
  Payment throughput by legal entity
  Network adapter latency (p95 by channel)
```

### Dashboard 2: Cash & Position
```
Panels:
  Net cash position by currency (real-time gauge, coloured by sign)
  Intraday ECF cash flow waterfall (confirmed vs anticipated)
  Cash ladder heatmap (accounts × dates, colour = balance)
  ECF creation rate (by source: PAYMENT / TRADE / MANUAL)
  BAT ingestion rate (MT940 lines/min)
  Cash position update latency (ECF event → position update)
  Pending ECF count (confirmed, anticipated)
```

### Dashboard 3: Trade & Risk
```
Panels:
  Trades captured today (by instrument type)
  Limit utilisation gauges (per limit type — P&L, counterparty, FX NOP)
  Active limit breaches (count, severity colour)
  Risk limit check latency (gRPC p99 — must stay < 200ms)
  Pending confirmation matches (count by status)
  FX NOP by currency pair (bar chart vs limit)
  Maturing trades in next 7 / 30 / 90 days
```

### Dashboard 4: Settlement & Reconciliation
```
Panels:
  Settlement instructions by status (PENDING / SENT / CONFIRMED / FAILED)
  Settlement fail rate by network
  Settlement confirmation latency (send → confirm p95)
  Cutoff-missed events today
  NOSTRO recon break count (open vs resolved today)
  Bank recon break count by age bucket (< 1d / 1–3d / > 3d)
  Netting run status (last run time, savings %)
```

### Dashboard 5: Accounting & IHB
```
Panels:
  Journals posted today (by ledger: IFRS / US_GAAP / LOCAL / MGMT)
  Accrual run status (last completion time, instrument count, error count)
  Period close status per entity + ledger
  IHB POBO requests (submitted / executed / failed)
  IHB netting runs (calculated net vs gross)
  Virtual account balance trend (by subsidiary)
  Intercompany interest accrued today
```

### Dashboard 6: System Health
```
Panels:
  Service uptime (per pod; colour = up/down)
  API latency by service (p95 — alert line at 500ms)
  DB connection pool utilisation per service
  Kafka consumer lag by topic/group (colour = critical if > 10000)
  Outbox unpublished row count per service
  RabbitMQ queue depths (approval, IHB, risk override)
  Error rate by service (5xx / 4xx split)
  JVM heap and GC pressure per service
  Virtual thread count (Java 21)
```

### Dashboard 7: Audit & Compliance
```
Panels:
  Compliance alerts by severity (today)
  Alert resolution time (SLA lines at 1h / 4h)
  SoD violation attempts (bar chart by day)
  Failed authentication attempts
  Audit log write rate (events/min)
  Audit hash chain error total (must stay at 0)
  Watchlist update status (last update timestamp + health)
```

---

## Alerting Rules

### P1 — Page On-Call Immediately

```yaml
- alert: PaymentDLTDepthNonZero
  expr: rabbitmq_queue_messages{queue=~"tms\\..*\\.dlq"} > 0
  for: 1m
  severity: critical

- alert: KafkaConsumerLagCritical
  expr: kafka_consumer_group_lag{topic=~"tms\\.payment\\..*"} > 10000
  for: 5m
  severity: critical

- alert: AuditHashChainBroken
  expr: tms_audit_hash_chain_errors_total > 0
  severity: critical

- alert: ComplianceScreeningCircuitOpen
  expr: resilience4j_circuitbreaker_state{name="compliance-client"} == 2
  severity: critical

- alert: ServiceDown
  expr: up{job=~"tms-.*"} == 0
  for: 2m
  severity: critical

- alert: SettlementCutoffBreached
  expr: increase(tms_settlement_cutoff_missed_total[5m]) > 0
  severity: critical

- alert: OutboxUnpublishedRowsHigh
  expr: tms_outbox_unpublished_rows > 100
  for: 2m
  severity: critical

- alert: RiskGrpcDown
  expr: grpc_server_calls_total{service="tms-risk"} == 0
  for: 3m
  severity: critical   # pre-trade checks failing = trades blocked
```

### P2 — Notify, Business Hours Response

```yaml
- alert: PaymentPendingApprovalHigh
  expr: tms_payments_pending_approval > 50
  for: 30m
  severity: warning

- alert: ReconciliationBreaksHigh
  expr: tms_recon_breaks_open > 100
  for: 1h
  severity: warning

- alert: DBConnectionPoolSaturated
  expr: hikaricp_connections_active / hikaricp_connections_max > 0.9
  for: 5m
  severity: warning

- alert: SagaTimeoutHigh
  expr: rate(tms_saga_timeout_total[5m]) > 5
  severity: warning

- alert: AccrualRunNotCompleted
  expr: time() - tms_accounting_accrual_last_completion_ts > 18000  # 5h = expected by 03:00 UTC
  severity: warning

- alert: FxRatesStale
  expr: time() - tms_fx_rates_last_approved_ts > 86400  # 24h without new approved rates
  severity: warning

- alert: LimitBreachUnacknowledged
  expr: tms_risk_limit_breaches_total{acknowledged="false"} > 0
  for: 30m
  severity: warning

- alert: IHBNettingRunFailed
  expr: increase(tms_ihb_netting_runs_total{status="FAILED"}[1h]) > 0
  severity: warning
```

---

## Logging Strategy

### Structured JSON Log Format

```json
{
  "timestamp": "2025-06-15T10:23:45.123Z",
  "level": "INFO",
  "service": "tms-payment-hub",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "correlationId": "3e4d5f6a-...",
  "legalEntityId": "LE-001",
  "paymentId": "uuid",
  "sagaType": "PAYMENT_PROCESSING",
  "sagaStep": "RELEASE_TO_NETWORK",
  "userId": "jdoe",
  "message": "Payment released to SWIFT network",
  "networkChannel": "SWIFT",
  "duration_ms": 234
}
```

**Mandatory MDC fields (set by `tms-common-security` filter on every request):**
`traceId`, `spanId`, `correlationId`, `legalEntityId`, `userId`, `serviceVersion`

**Masking rules (Logback `MaskingPatternLayout` in `tms-common-audit`):**
- IBAN → `IBAN****1234`
- BIC → `BIC***` if not a public routing BIC
- Full name fields → `[MASKED-NAME]`
- Account numbers → `****NNNN` (last 4 digits)

**Log levels in production:**
- `ERROR` — failures requiring ops action
- `WARN` — anomalies (unexpected states, retry exhausted)
- `INFO` — business events (payment created, trade settled, accrual run completed)
- `DEBUG` — disabled in production; available via dynamic log level endpoint

### Log Pipeline

```
Logback (JSON appender) → stdout (pod logs)
  → Promtail DaemonSet (label extraction: service, legalEntityId, level, traceId)
  → Loki (indexed; 90-day retention in Loki; audit logs → MinIO WORM for 7 years)
  → Grafana Explore (trace-to-log correlation via traceId)
  → Loki Ruler (log-based alerting: e.g., ERROR rate spike)
```

---

## Reporting Architecture

### OpenSearch Index Design

**Consumer:** `tms-reporting` service — pure consumer, never modifies source data.

```
Domain events (Kafka) → tms-reporting consumers → OpenSearch indexes

Consumers (one consumer group per domain):
  PaymentReportingConsumer          → tms-payments-{YYYY-MM}
  EcfReportingConsumer              → tms-ecf-{YYYY-MM}
  BatReportingConsumer              → tms-bat-{YYYY-MM}
  CashPositionReportingConsumer     → tms-cash-positions-{YYYY-MM}
  TradeReportingConsumer            → tms-trades-{YYYY-MM}
  SettlementReportingConsumer       → tms-settlement-{YYYY-MM}
  AccountingReportingConsumer       → tms-accounting-entries-{YYYY-MM}
  RiskReportingConsumer             → tms-risk-events-{YYYY-MM}
  IHBReportingConsumer              → tms-ihb-{YYYY-MM}
  ReconciliationReportingConsumer   → tms-recon-breaks-{YYYY-MM}
  AuditReportingConsumer            → tms-audit-events-{YYYY-MM}    # 10-year retention
  ComplianceReportingConsumer       → tms-compliance-alerts-{YYYY-MM}
  ConfirmationReportingConsumer     → tms-confirmations-{YYYY-MM}
  FxRatesReportingConsumer          → tms-fx-rates-{YYYY-MM}        # compacted — no rollover
```

### Consumer Checkpoint (per consumer, stored in `tms-reporting` PostgreSQL schema)

```sql
INSERT INTO consumer_checkpoints
  (topic, partition_num, consumer_group, committed_offset, checkpoint_at)
VALUES (?, ?, ?, ?, now())
ON CONFLICT (topic, partition_num, consumer_group)
DO UPDATE SET committed_offset = EXCLUDED.committed_offset,
              checkpoint_at    = EXCLUDED.checkpoint_at;
```

### OpenSearch ILM Policy

```
Hot phase:     0–30 days  → stored on hot nodes (NVMe)
Warm phase:    30–90 days → warm nodes (SSD, reduced replicas to 1)
Cold phase:    90–365 days → compressed snapshots to S3
Delete phase:  > 7 years  → purge (audit indices: 10 years before purge)
```

---

## Replay Strategy

### When Replay Is Needed

1. New downstream consumer — needs to build its state from history (e.g., new risk reporting consumer)
2. Read model rebuild — OpenSearch index corrupted, schema changed, or new field added
3. Bug fix — consumer had a processing bug; events need reprocessing with corrected logic
4. Reporting consumer added for new service — e.g., `tms-ihb` goes live; IHB reporting needs to catch up
5. DR test — secondary cluster rebuilt from Kafka MirrorMaker replication

### Kafka Topic Replay

```bash
# Reset consumer group to replay from earliest offset
kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 \
  --group tms-reporting-payment-lifecycle-cg \
  --topic tms.payment.payments.lifecycle \
  --reset-offsets --to-earliest --execute
```

Consumer replay protections:
- All consumers are idempotent (upsert by `eventId` in OpenSearch; dedup via Redis key `kafka-idem:{eventId}`)
- `REPLAY_MODE=true` env var disables downstream notification publishing during replay
- Replay mode disables compliance alert re-emission (prevents duplicate alerts)

### Full Read Model Rebuild

```
1. Stop tms-reporting consumer pods for the affected domain
2. Delete / truncate the OpenSearch index (alias still points to new empty index)
3. Reset consumer group offset to --to-earliest
4. Deploy with REPLAY_MODE=true
5. Monitor consumer lag via Grafana until lag = 0
6. Set REPLAY_MODE=false; restart normally
```

### Payment Event Store Replay (tms-payment-hub)

```java
// Reconstruct full payment aggregate state from event store
List<PaymentEvent> events = paymentEventRepository
    .findByPaymentIdOrderBySequenceAsc(paymentId);

PaymentAggregate state = PaymentAggregate.empty();
for (PaymentEvent event : events) {
    state = state.apply(event);  // pure function; no side effects
}
return state.toSnapshot();
```

Snapshots are taken every 50 events; replay reads from nearest snapshot + subsequent events only (not full history).

### New-Service Catch-Up Pattern

When a new service (e.g., `tms-risk`, `tms-ihb`) starts for the first time:
1. Consumer group has no committed offset → Kafka default: `auto.offset.reset=earliest`
2. Service catches up from all historical events
3. During catch-up: service reports `readiness = DOWN` (not in load balancer rotation)
4. Once lag = 0: `readiness = UP`

---

## Health Checks and Kubernetes Integration

```yaml
# Per service — application.yml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
      group:
        readiness:
          include: db, kafka, redis, rabbit   # all must pass for readiness

# Kubernetes deployment
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8081    # management port (separate from 8080 app port)
  initialDelaySeconds: 45
  periodSeconds: 15
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8081
  initialDelaySeconds: 20
  periodSeconds: 10
  failureThreshold: 2
  # Failure removes pod from Service endpoints → no traffic during unhealthy state
```

---

## Disaster Recovery

### RPO and RTO Targets

| Component | RPO | RTO |
|-----------|-----|-----|
| Payment Hub (event store) | < 1 min (sync Patroni standby) | < 15 min |
| Cash Position (CQRS read model) | < 5 min (replay from Kafka) | < 30 min |
| Trade + Settlement | < 1 min | < 15 min |
| Accounting (journals) | < 1 min | < 20 min |
| Risk limits | < 1 min | < 10 min |
| OpenSearch (reporting) | < 30 min (replay from Kafka) | < 60 min |
| Kafka (MirrorMaker 2) | < 1 min lag to DR cluster | < 10 min |
| Reference Data | < 1 min (cache rebuild from events) | < 5 min |

### Data Redundancy

| Layer | Strategy |
|-------|----------|
| PostgreSQL (per service) | Patroni: 1 primary + 1 sync hot standby + 1 async read replica |
| PostgreSQL failover | Patroni auto-failover via etcd (< 30s) |
| Kafka | 3 brokers, replication factor = 3, min ISR = 2 |
| Kafka DR | MirrorMaker 2 → DR cluster in separate AZ/region (< 1 min lag) |
| Redis | Redis Sentinel (3 nodes) or Redis Cluster (6 nodes for Phase 3) |
| MinIO | Erasure coding across 4+ nodes; object lock on audit bucket |
| OpenSearch | 3 data nodes, 1 replica per primary shard |

### Backup Policy

```
PostgreSQL:
  Continuous WAL archiving → S3 (encrypted)
  Daily logical backup (pg_dump per service schema) → S3, 30-day retention
  PITR: point-in-time recovery within 7 days

Kafka:
  MirrorMaker 2 to DR cluster (< 1 min lag)
  Kafka Connect S3 Sink for audit and payment lifecycle topics → MinIO (7-year retention)
  Replay from Kafka within operational retention window (30 days)

OpenSearch:
  Daily snapshot → S3 (30-day retention)
  Full rebuild via Kafka replay if snapshot corrupt

MinIO (WORM audit):
  Cross-region replication enabled
  Object lock prevents deletion for retention period
```
