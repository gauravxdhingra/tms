# 02 — Technical Stack (v2)

## What Changed from v1
- Added UI stack (Angular 19, TypeScript, AG Grid, BFF pattern, SSE/WebSocket)
- Added SWIFT connectivity model (Bureau vs Alliance options)
- Added local development story (Docker Compose, service stubs, Spring profiles)
- Added `Clock` injection pattern as a mandatory cross-cutting standard
- Added `MonetaryAmount` / JSR-354 as the financial arithmetic standard
- Added Camunda DMN as the FEEL/decision table runtime for Rules Engine
- Added ISO 20022 MX message parsing libraries
- Added data migration tooling
- Reinforced API versioning as a first-class concern

---

## Backend Runtime and Framework

| Layer | Choice | Version | Justification |
|-------|--------|---------|---------------|
| Language | Java | 21 LTS | Virtual threads (Loom), records, sealed classes, pattern matching. LTS support horizon matches enterprise finance lifecycle. |
| Runtime | Spring Boot | 3.4.1 | Virtual thread support (Loom), Jakarta EE 10. |
| Web layer | Spring WebMVC (blocking) | aligned with Boot 3.4 | Virtual threads make blocking I/O equivalent in throughput to reactive. Simpler debugging. Matches Spring Batch needs. **Exception:** `tms-bff` uses WebFlux for SSE streaming endpoints only. |
| Cloud | Spring Cloud | 2024.0.0 | Config Server, Gateway, Circuit Breaker (Resilience4j), Contract. |
| Security | Spring Security | 6.x | OAuth2 resource server, OIDC, RBAC/ABAC, method security, JWT validation. |
| Data (JPA) | Spring Data JPA + Hibernate | 6.x | Entity mapping, optimistic locking, auditing. |
| Data (JDBC) | Spring Data JDBC / JdbcTemplate | 3.x | Bulk insert paths, batch processing, raw SQL where JPA adds overhead. |
| Batch | Spring Batch | 5.x | Bank statement ingestion, bulk payment files, reconciliation jobs, accrual posting runs. Restartable, partitioned, chunk-oriented. |
| Messaging (Kafka) | Spring Kafka | 3.x | `@KafkaListener`, consumer groups, exactly-once, DLT routing. |
| Messaging (RabbitMQ) | Spring AMQP | 3.x | Command queues, delayed retries, approval workflow, DLX. |
| Cache | Spring Data Redis + Redisson | 3.x | `@Cacheable`, distributed locks, idempotency store. |
| gRPC | `grpc-spring-boot-starter` | — | Rules Engine, Identity token check, Risk pre-trade limit check — high-frequency internal calls. |
| API docs | SpringDoc OpenAPI 3.1 | — | Auto-generated, versioned. |
| Serialization (REST) | Jackson | 2.x | JSON for REST. Custom `MonetaryAmountSerializer`. |
| Serialization (Kafka) | Avro + Confluent Schema Registry | — | Versioned schemas, backward-compatibility enforcement. |
| Validation | Spring Validation + Bean Validation 3.0 | — | Custom constraint validators per domain (IBAN, BIC, LEI, value date). |
| Rules Engine DSL | Camunda DMN Engine (embedded) | 7.x | Apache-licensed, Java-native FEEL/DMN evaluation. `.dmn` files stored and versioned in DB. No custom DSL parser needed. |
| MT/MX parsing | WIFE (open source) + custom ISO 20022 parser | — | WIFE for SWIFT MT message parsing. Custom XSD-validated parser for ISO 20022 MX (pain, camt, pacs, sese namespaces). |
| Financial arithmetic | JSR-354 (`javax.money` / Moneta RI) | — | `MonetaryAmount` for all financial values. Never `double`. Currency-aware rounding (HALF_EVEN). |
| Clock injection | `java.time.Clock` (Spring bean) | — | **Mandatory pattern.** Every service accepts a `Clock` bean. Never call `LocalDate.now()` or `Instant.now()` directly. |

---

## Frontend Stack

| Layer | Choice | Justification |
|-------|--------|---------------|
| Language | TypeScript 5.x | Type safety for financial data models (amount, currency, date fields must be explicit) |
| Framework | Angular 19 | Opinionated, batteries-included framework well-suited to enterprise LOB apps; strong DI, reactive forms, robust HTTP client, signals for fine-grained reactivity |
| Build | Angular CLI + esbuild | Default Angular toolchain; esbuild for fast builds and HMR |
| Data grid | AG Grid (Community or Enterprise) | Handles 100K+ row treasury data tables with virtual scrolling, column grouping, frozen columns, Excel export. Industry standard for financial UIs. |
| Charts | Apache ECharts via ngx-echarts | Waterfall charts (cash ladder), heatmaps (FX exposure), candlestick (rate charts), bar/line KPI charts |
| Server state | Angular HttpClient + RxJS | Native HTTP client; BehaviorSubject / signals for derived state; no extra library needed |
| UI state | NgRx SignalStore (or RxJS services) | Lightweight signal-based state management for treasury UI state (active entity, filters, open panels) |
| Component library | PrimeNG or Angular Material | PrimeNG preferred for data-dense treasury UIs (table, tree, dialog, form field components); Angular Material as alternative |
| Real-time | Server-Sent Events (SSE) via BFF | `EventSource` wrapped in an Angular service; updates piped to RxJS `Subject` consumed by components |
| Routing | Angular Router | Lazy-loaded feature modules per domain (Payments, Cash, Trades, Settlement, etc.) |
| Forms | Angular Reactive Forms | `FormGroup` / `FormControl` with custom validators for IBAN, BIC, monetary amounts, value dates |
| Date handling | date-fns + date-fns-tz | Lightweight, tree-shakeable, immutable date utilities for business day and timezone-aware display |
| Internationalisation | Angular i18n / ngx-translate | Multi-language support (English, German, French, Spanish minimum for European treasury) |
| Testing | Jest + Angular Testing Library + Playwright | Jest for unit/component tests, Playwright for E2E |
| Accessibility | WCAG 2.1 AA | Required for enterprise software in EU/UK jurisdictions |

### BFF Technology
- **Framework:** Spring Boot (WebMVC for REST aggregation, WebFlux for SSE streaming)
- **SSE implementation:** Kafka consumer → `SseEmitter` → browser. One SSE connection per user session, scoped to their legal entity.
- **Caching:** Redis for aggregated view cache (TTL: 10s for real-time views, 60s for summary views)
- **Auth:** JWT pass-through — validates token but does not re-issue; extracts claims for downstream calls

---

## Persistence

| Store | Product | Version | Usage |
|-------|---------|---------|-------|
| Primary RDBMS | PostgreSQL | 17 | All transactional domains. Schema per service. RLS for entity isolation. |
| Event Store | PostgreSQL (append-only tables) | 17 | Payment Hub, Settlement, Accounting event logs. Custom implementation — no Axon. |
| Cache / Lock | Redis | 7 (Cluster) | Idempotency keys, session state, BFF view cache, rate reference cache, distributed locks. |
| Search / Reporting | OpenSearch | 2.x | Audit trail, payment search, reporting projections, compliance alert search. |
| Streaming | Apache Kafka | 3.8 | Domain event backbone, exactly-once delivery, log compaction for reference/rate data. |
| Command Queues | RabbitMQ | 3.13 | Approval workflows, payment release commands, retry/DLX infrastructure. |
| Object Storage | MinIO (S3-compatible) | — | File ingestion staging, bank statement archives, bulk payment files, audit exports, report artifacts, DMN rule files. |
| Schema Registry | Confluent Schema Registry (OSS) | — | Avro schema storage, compatibility enforcement for all Kafka topics. |
| Batch Job Metadata | PostgreSQL (Spring Batch schema) | — | Job instances, job executions, step executions — in the same DB as the owning service. |
| Rate Store | PostgreSQL (tms-fx-rates) | — | Historical FX rates, forward curves. Compacted Kafka topic for live rate distribution. |

---

## SWIFT Connectivity Architecture

SWIFT connectivity is a significant infrastructure decision. Options in order of complexity:

### Recommended for Phase 1–2: SWIFT Service Bureau
- A **SWIFT Service Bureau** manages the Alliance Gateway and SWIFT infrastructure on behalf of the TMS.
- The TMS connects to the bureau via a secure API (REST or file-based SFTP).
- The bureau handles: BIC registration, SWIFT PKI certificates, message routing, network monitoring.
- Cost: per-message + monthly service fee. Higher unit cost than own infrastructure; zero SWIFT infrastructure overhead.
- Examples: Bottomline Technologies, SunGard/FIS bureaus, CGI.

### For Phase 3 / High-Volume: SWIFT Alliance Lite2 or Alliance Cloud
- **Alliance Lite2:** Web-based SWIFTNet connectivity, suitable for low-to-medium volume (< 10,000 messages/day).
- **Alliance Cloud:** Cloud-hosted Alliance infrastructure managed by SWIFT; intermediate option.
- **Alliance Access + Alliance Gateway:** On-premise, for high-volume banks (> 100K messages/day). Only justified if the platform serves banks or large financial institutions.

### SWIFT message standards supported
- **FIN (MT):** MT1xx, MT2xx, MT3xx, MT5xx, MT9xx — via bureau or Alliance
- **ISO 20022 MX:** pain.001, pain.002, pain.008, pacs.008, pacs.002, pacs.003, pacs.004, pacs.009, camt.053, camt.054, camt.056, camt.086, sese.023, sese.024 — mandatory from 2025 for TARGET2/CHAPS, progressive migration for SWIFT CBPR+
- **FileAct:** SWIFT bulk file transfer (pain.001 batches, NACHA ACH files)
- **SWIFT Accord:** External confirmation matching service integration (Phase 3)

### SWIFT test environment
- Mandatory for all payment network adapters before production
- SWIFT provides test BICs and a test FIN environment
- All network adapters must have integration tests against SWIFT test environment as part of CI for Phase 2+

---

## Local Development Story

Developers cannot and should not run all 20 services locally. The local development setup is layered:

### Layer 1: Infrastructure (Docker Compose — always running locally)
```yaml
# docker-compose.infra.yml
services:
  postgres:        # PostgreSQL 17 — one instance, multiple databases per service
  kafka:           # Kafka 3.8 (KRaft mode — no ZooKeeper)
  schema-registry: # Confluent Schema Registry OSS
  rabbitmq:        # RabbitMQ 3.13 with management plugin + delayed-message plugin
  redis:           # Redis 7 standalone (sentinel not needed locally)
  keycloak:        # Keycloak with pre-loaded tms-realm and test users
  minio:           # MinIO for object storage
  opensearch:      # OpenSearch 2.x (single node)
  opensearch-dashboards:
  mailpit:         # Local SMTP server for email notification testing
```

Single command: `docker compose -f docker-compose.infra.yml up -d`

### Layer 2: Service Stubs (WireMock — replace unavailable services)
```
tms-stubs/
  swift-network-stub/     # Returns configurable ACK/NACK for payment releases
  compliance-stub/        # Returns CLEARED for all names except a configurable blocklist
  fx-rate-stub/           # Returns static rates or file-driven rates
  external-custodian-stub/ # Simulates settlement confirmation from custodian
```

WireMock stubs are loaded from JSON mapping files. Each stub has a `__admin` API for dynamic scenario switching during development.

### Layer 3: Selective Service Startup (Maven)
```bash
# Run a single service:
mvn -pl tms-payment-hub spring-boot:run -Dspring-boot.run.profiles=local

# Run multiple services in parallel (separate terminals or via process-compose):
mvn -pl tms-payment-hub,tms-cash-ecf,tms-reference-data spring-boot:run \
    -Dspring-boot.run.profiles=local

# Build all modules (skip tests for speed):
mvn install -DskipTests --threads 4
```

### Spring Profiles per Environment

| Profile | DB | Kafka | External Services | Data |
|---------|----|----|---|---|
| `local` | Docker Compose PostgreSQL | Docker Compose Kafka | All stubbed (WireMock) | Seed data auto-loaded |
| `test` | Testcontainers (per test) | Embedded / Testcontainers | All stubbed | Test fixtures |
| `staging` | Shared staging cluster | Shared staging Kafka | Network adapters stubbed | Masked prod-like data |
| `production` | Real cluster (Patroni) | Real cluster (MSK/self-managed) | Real SWIFT bureau, real compliance | Real data |

### Database Initialisation
Each service's `local` profile runs Flyway migrations + a `data.sql` seed script with:
- A default legal entity, 2 portfolios, 4 books
- Sample counterparties (10), currencies (majors), calendars (TARGET, UK, US)
- A test payment maker user and a test payment checker user (pre-configured in Keycloak)
- Sample bank accounts (EUR, USD, GBP)

---

## API Versioning Strategy

Treasury integrations with banks, ERPs, and downstream systems are long-lived. Breaking API changes cannot be deployed without a deprecation notice and a parallel support window.

### Rules
1. **URL versioning:** `/api/v1/` → `/api/v2/` (explicit, unambiguous for clients)
2. **Non-breaking changes within a version (allowed without version bump):**
   - Adding optional response fields
   - Adding optional request parameters with documented defaults
   - Adding new endpoints
3. **Breaking changes require a new version:**
   - Removing or renaming fields
   - Changing field types
   - Changing response codes for existing conditions
   - Changing authentication requirements
4. **Parallel support window:** v1 and v2 coexist for **minimum 12 months** after v2 GA
5. **Deprecation signalling:** HTTP headers on deprecated endpoints:
   ```
   Deprecation: true
   Sunset: Sat, 01 Jan 2027 00:00:00 GMT
   Link: <https://docs.tms.internal/api/v2/payments>; rel="successor-version"
   ```
6. **Version lifecycle documented** in a public API changelog (Markdown, in repo)
7. **Spring Cloud Contract tests** include version compatibility checks (v1 consumer tests run against v2 producer to verify backward compatibility before decommission)

---

## Financial Arithmetic Standards

Every service that handles monetary amounts **must** follow these rules:

### MonetaryAmount (JSR-354)
```java
// Never:
double amount = 1234.56;
float amount = 1234.56f;

// Always:
MonetaryAmount amount = Money.of(1234.56, "EUR");  // using Moneta RI

// Arithmetic:
MonetaryAmount total = amount.add(fee);
MonetaryAmount net   = amount.subtract(withholding);

// Rounding — always use currency's default fraction digits:
MonetaryRounding rounding = Monetary.getRounding(
    RoundingQueryBuilder.of()
        .setRoundingName("default")
        .set(RoundingMode.HALF_EVEN)  // Banker's rounding
        .build()
);
MonetaryAmount rounded = amount.with(rounding);
```

### Database storage
```sql
-- Amount column: always NUMERIC(24, 8)
-- 8 decimal places covers all currencies (max 3 in KWD, 8 in crypto if needed)
-- Stored in the instrument's native currency, never pre-converted
amount NUMERIC(24, 8) NOT NULL
```

### Currency decimal rules (enforced at presentation layer)
| Decimals | Currencies |
|----------|-----------|
| 0 | JPY, KRW, VND, CLP |
| 2 | USD, EUR, GBP, CHF, CAD, AUD, SEK, NOK, DKK |
| 3 | KWD, BHD, OMR, JOD |

### FX conversion
- Always record: original amount, original currency, converted amount, converted currency, FX rate used, rate source, rate timestamp
- Never silently convert without recording the rate applied

---

## Clock Injection — Mandatory Pattern

**Every service that deals with financial dates uses an injected `Clock`.**

```java
// Configuration (per service — different clocks per profile):
@Configuration
class ClockConfig {

    // Production: wall clock
    @Bean
    @Profile("!test")
    Clock systemClock() {
        return Clock.systemUTC();
    }

    // Test: fixed clock, controllable by tests
    @Bean
    @Profile("test")
    Clock testClock() {
        return Clock.fixed(Instant.parse("2025-06-15T09:00:00Z"), ZoneOffset.UTC);
    }
}

// Usage in any service component:
@Service
class PaymentService {
    private final Clock clock;

    LocalDate today()     { return LocalDate.now(clock); }
    Instant  nowInstant() { return Instant.now(clock); }
    LocalDateTime nowDt() { return LocalDateTime.now(clock); }
}

// In tests — advance the clock for cutoff / settlement date tests:
@Autowired Clock clock;
// ReflectionTestUtils.setField(clock, ...) or use a TestClock wrapper:
((MutableClock) clock).setInstant(Instant.parse("2025-06-15T17:01:00Z"));
// Now re-run payment release → should fail cutoff check
```

**Where this matters most:**
- Settlement cutoff validation (is it past cutoff for this network and value date?)
- Payment value date validation (cannot submit a payment with value date in the past)
- Accrual calculation (daily interest must use the correct calendar date)
- Period close guard (cannot post to a closed period)
- Maturity processing (trade matures exactly on its maturity date)
- Holiday calendar checks (is today a business day?)

---

## Infrastructure

| Layer | Choice | Justification |
|-------|--------|---------------|
| Container orchestration | Kubernetes (EKS / GKE / on-prem) | HPA for dynamic scaling, PDB for HA, namespace-level isolation per environment |
| Service mesh | None in Phase 1–2; Istio in Phase 3 | mTLS between services at mesh level; circuit breaking at mesh layer. Istio adds significant operational complexity; Resilience4j + TLS covers Phase 1–2 adequately. |
| API Gateway | Spring Cloud Gateway | JWT validation, rate limiting, routing, CORS, correlation ID injection, response caching for reference data |
| Config management | Spring Cloud Config Server + HashiCorp Vault | Externalized config; secrets via Vault dynamic credentials |
| Secrets | HashiCorp Vault | DB credentials (1h TTL), API keys, SWIFT certificates, Kafka SASL, MinIO keys |
| Secret rotation in K8s | Vault Agent Sidecar + `SIGHUP` reload | Running pods pick up rotated credentials via sidecar file refresh without restart |
| Certificate management | cert-manager (K8s) | Automatic TLS rotation for ingress and inter-service certs |
| DR database | PostgreSQL Patroni (automatic failover) | etcd-based leader election, streaming replication, RTO < 60s |
| DR Kafka | MirrorMaker 2 to DR cluster | < 1 minute replication lag to separate AZ/region cluster |

### Kubernetes Resource Sizing (baseline — tune after Phase 1 load test)

| Service | CPU Request/Limit | Memory Request/Limit | Min Replicas | HPA Target |
|---------|------------------|---------------------|--------------|-----------|
| tms-payment-hub | 500m / 2000m | 1Gi / 2Gi | 2 | CPU 60% |
| tms-cash-ecf | 250m / 1000m | 512Mi / 1Gi | 2 | CPU 60% |
| tms-cash-position | 250m / 1000m | 512Mi / 1Gi | 2 | CPU 60% |
| tms-settlement | 250m / 1000m | 512Mi / 1Gi | 2 | CPU 60% |
| tms-accounting | 250m / 1000m | 512Mi / 1Gi | 2 | CPU 60% |
| tms-message-manager | 500m / 2000m | 1Gi / 2Gi | 2 | CPU 60% |
| tms-reporting | 500m / 2000m | 2Gi / 4Gi | 2 | CPU 70% |
| tms-rules-engine | 500m / 1000m | 1Gi / 2Gi | 2 | CPU 50% |
| tms-compliance | 250m / 1000m | 512Mi / 1Gi | 2 | CPU 60% |
| tms-bff | 250m / 1000m | 512Mi / 1Gi | 2 | CPU 60% |
| tms-reference-data | 100m / 500m | 256Mi / 512Mi | 2 | CPU 70% |
| tms-identity | 250m / 500m | 512Mi / 1Gi | 2 | CPU 60% |

### Kubernetes Network Policies
```yaml
# Example: tms-payment-hub can only talk to:
#   tms-compliance, tms-rules-engine, tms-reference-data, tms-settlement,
#   tms-cash-ecf, tms-accounting, Kafka, RabbitMQ, Redis
# It CANNOT talk to: tms-reporting DB, other services' databases directly
```

### Zero-Downtime Deployment
- **Rolling updates** for all stateless services: 1 pod down at a time, readiness probe gates traffic
- **Flyway migrations** must be backward-compatible within a release window:
  - Phase 1: add column with default → Phase 2: add NOT NULL constraint → Phase 3: remove old column
  - Never drop a column in the same release that removes code using it
- **Kafka consumer rebalancing during deployment:** `@KafkaListener` with `concurrency` set; graceful shutdown commits current offset before pod stops (`spring.kafka.listener.ack-mode=MANUAL_IMMEDIATE` on critical consumers)

---

## Build and CI/CD

| Tool | Usage |
|------|-------|
| Build (backend) | Maven 3.9.x, multi-module POM, one module per service + shared libraries |
| Avro codegen | `avro-maven-plugin` — generates Java classes from `.avsc` files in `tms-events-schema` |
| DMN bundling | DMN files from DB for runtime; Git-tracked `.dmn` files as the authoritative source |
| Containers | `jib-maven-plugin` (no Dockerfile required per service) |
| Build (frontend) | Angular CLI (`ng build`) — invoked from Maven via `frontend-maven-plugin` |
| CI | GitHub Actions (or GitLab CI / Jenkins): build → test → contract-test → scan → push image |
| CD | ArgoCD (GitOps): PR-based promotion dev → staging → UAT → prod |
| Contract testing | Spring Cloud Contract (REST), Avro Schema Registry compatibility checks (Kafka) |
| Load testing | Gatling (payment throughput, bulk file processing, cash position update rate) |
| Dependency scanning | OWASP Dependency-Check + Snyk |
| SAST | SpotBugs + SonarQube (financial code quality gates: no floating-point money, no direct `LocalDate.now()`) |
| Secret scanning | truffleHog or GitGuardian in CI pre-merge |
| Image signing | Cosign (Sigstore) — all images signed before push; admission webhook verifies signature |

### Maven Multi-Module Structure
```
tms/                              (root POM — parent for all modules)
├── pom.xml
├── tms-events-schema/pom.xml
├── tms-common-audit/pom.xml
├── tms-common-outbox/pom.xml
├── tms-common-idempotency/pom.xml
├── tms-common-security/pom.xml
├── tms-common-money/pom.xml
├── tms-common-validation/pom.xml
├── tms-common-messaging/pom.xml
├── tms-common-test/pom.xml
├── tms-payment-hub/pom.xml
├── tms-trade/pom.xml
...                               (all 20 services)
└── tms-ui/                       (Angular SPA — built via frontend-maven-plugin)
    ├── package.json
    ├── angular.json
    └── src/
```

Root POM manages all dependency versions via `<dependencyManagement>`. Spring Boot parent POM is imported as a BOM:
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>${spring-boot.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-dependencies</artifactId>
      <version>${spring-cloud.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Common build plugins declared once in root POM `<pluginManagement>`:
- `maven-compiler-plugin` (Java 21, `--enable-preview` for virtual threads)
- `spring-boot-maven-plugin` (repackage for service modules)
- `jib-maven-plugin` (container images, configured per service module)
- `avro-maven-plugin` (Avro codegen in `tms-events-schema`)
- `flyway-maven-plugin` (for local DB migration tasks)
- `jacoco-maven-plugin` (coverage reporting)
- `frontend-maven-plugin` (Node/npm/Angular CLI for `tms-ui`)

### SonarQube Quality Gates (financial-specific)
Custom rules added to standard quality gate:
- **No `double`/`float` for amount fields:** pattern-matched on field names containing `amount`, `price`, `rate`, `balance`
- **No `LocalDate.now()` or `Instant.now()` direct calls:** must use injected `Clock`
- **No cross-service DB access:** no `@Entity` or `JdbcTemplate` queries outside own schema
- **All Kafka consumers must have `@Idempotent` annotation or documented justification**

---

## Data Migration and Cutover Tooling

### Import APIs (for seed data on go-live)
Each service exposes an `/api/v1/import/*` endpoint (disabled in production after cutover):
```
POST /api/v1/import/counterparties      (bulk JSON or CSV)
POST /api/v1/import/legal-entities
POST /api/v1/import/bank-accounts
POST /api/v1/import/ssi                 (SSI master data)
POST /api/v1/import/open-trades         (open positions at cutover date)
POST /api/v1/import/cash-balances       (starting positions)
POST /api/v1/import/users               (user accounts and roles)
```

### Parallel Run Support
- Old system and new TMS run simultaneously during parallel run (minimum 4 weeks)
- Daily comparison reports generated by `tms-reporting` (position comparison, settlement comparison, cash balance comparison)
- Discrepancy report highlights differences between systems
- Parallel run completion criteria: zero discrepancies for 5 consecutive business days

### Data Masking for Non-Production
- Production data never copied to dev/staging without masking
- Masking tool (e.g., Faker-based Spring Batch job or commercial tool) replaces:
  - Account numbers with synthetic equivalents preserving format and checksum (valid IBAN structure)
  - Counterparty names with fictional names
  - Amounts scaled by a random factor (structure preserved, values changed)
- Synthetic data generator available for Phase 1 testing (generates realistic but fictional treasury data)

---

## Why These Choices Over Alternatives

| Alternative | Rejected Because |
|-------------|-----------------|
| Quarkus | Spring Batch ecosystem coverage is critical for reconciliation and bulk processing |
| Kotlin | Java 21 records + sealed classes cover ergonomics; Java is the standard in enterprise finance teams |
| MongoDB for event store | No strict transaction guarantees; period-close DB trigger constraints require SQL |
| Apache Pulsar | Kafka is more mature in financial services; Confluent Schema Registry ecosystem is well-established |
| Oracle DB | PostgreSQL with proper partitioning is sufficient; licence costs are significant |
| Axon Framework | Framework lock-in; limits portability of event model; poor SQL access for analytics |
| Spring WebFlux everywhere | Virtual threads close the throughput gap; blocking is simpler to debug; Batch needs blocking |
| Drools (Red Hat) | Camunda DMN is Apache-licensed, lighter, and standard-compliant (DMN 1.3); Drools requires commercial licence for enterprise features |
| Temporal (workflow engine) | Adds an external dependency for saga orchestration; Spring + PostgreSQL + Kafka provides sufficient saga primitives without the operational overhead |
| React (UI) | Angular 19's opinionated DI, reactive forms, signals, and strong TypeScript integration make it better suited to large enterprise LOB apps with many developers; AG Grid Angular integration is equally best-in-class |
| WebSocket for real-time | SSE is simpler (HTTP/1.1 compatible, no upgrade handshake, natural backpressure); treasury updates are server-push only |
