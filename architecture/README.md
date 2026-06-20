# TMS — Treasury Management Platform
## Architecture Design Suite (v2)

Production-grade, event-driven Treasury Management Platform built on a modern Spring ecosystem.
Inspired by the functional breadth of Wallstreet Suite and Kyriba, designed for enterprise-scale
treasury operations: payment processing, cash visibility, settlement, accounting, reconciliation,
risk, in-house banking, and reporting across multiple legal entities, ledgers, and currencies.

---

## Document Index

| # | Document | Description |
|---|----------|-------------|
| 01 | [Bounded Context Map](01-bounded-contexts/README.md) | 20 deployable services, context map, ownership, multi-tenancy |
| 02 | [Technical Stack](02-technical-stack/README.md) | Technology choices with justifications; frontend, SWIFT, local dev |
| 03 | [Event Model](03-event-model/README.md) | 17 event domains, Avro schemas, outbox pattern, idempotency |
| 04 | [Messaging Topology](04-messaging-topology/README.md) | 50+ Kafka topics, RabbitMQ exchanges/queues, BFF SSE subscriptions |
| 05 | [API Contracts](05-api-contracts/README.md) | REST/gRPC per service; BFF aggregated views; SSE endpoints |
| 06 | [Database Strategy](06-database-strategy/README.md) | DDL per service; partitioning; event store; archival; multi-GAAP |
| 07 | [Saga Workflows](07-saga-workflows/README.md) | 9 sagas: payment, settlement, netting, accrual, maturity, POBO, risk |
| 08 | [Security & Compliance](08-security-compliance/README.md) | Auth (Keycloak/OIDC), RBAC+ABAC, audit, sanctions, SoD |
| 09 | [Observability](09-observability/README.md) | OTel tracing, Prometheus metrics, event replay, OpenSearch reporting |
| 10 | [Roadmap](10-roadmap/README.md) | MVP → Enterprise phased plan; risks; Phase 1 exit criteria |
| 11 | [UI Architecture](11-ui-architecture/README.md) | React 19 SPA; screen inventory; AG Grid; real-time SSE; BFF views |
| 12 | [Monorepo & Common Libraries](12-monorepo-common-libraries/README.md) | Gradle monorepo; 9 shared modules; CI pipeline; local dev |
| 13 | [Testing Strategy](13-testing-strategy/README.md) | Test pyramid; Testcontainers; saga tests; contract tests; Gatling |

---

## Platform Tenets

1. **Event-first** — every meaningful business action emits a versioned, Avro-encoded domain event
2. **Audit by default** — every event is immutable, attributed, and queryable in OpenSearch
3. **Idempotency everywhere** — all APIs (Idempotency-Key) and Kafka consumers tolerate safe replay
4. **Bounded contexts own their data** — no shared databases across service boundaries (enforced by ArchUnit)
5. **CQRS where it earns its cost** — applied to cash position and liquidity views; not mandated globally
6. **Event sourcing selectively** — payments, audit trail, settlement lifecycle only
7. **Saga-orchestrated workflows** — long-running processes are explicit, compensable, timeout-aware, and observable
8. **Multi-entity by design** — legal entity, portfolio, book, currency, and GAAP ledger are first-class dimensions
9. **Clock injection mandatory** — `java.time.Clock` injected everywhere; `LocalDate.now()` banned (ArchUnit + SonarQube)
10. **Money is never a float** — `MonetaryAmount` (JSR-354) everywhere; `double`/`float` banned (ArchUnit)

---

## Architecture at a Glance

```
Browser (React 19 SPA)
    │
    ├── REST  ──► Spring Cloud Gateway ──► tms-bff ──┬──► tms-payment-hub
    └── SSE   ──► tms-bff (WebFlux)                  ├──► tms-trade
                                                      ├──► tms-cash-position
                                                      ├──► tms-risk
                                                      └──► (all other services)

Domain Services (20 total):
  Cash:        tms-cash-ecf | tms-cash-bat | tms-cash-position
  Payments:    tms-payment-hub
  Trades:      tms-trade | tms-confirmation-matching
  Settlement:  tms-settlement
  Accounting:  tms-accounting
  Risk:        tms-risk | tms-fx-rates
  IHB:         tms-ihb
  Liquidity:   tms-liquidity
  Recon:       tms-reconciliation
  Reference:   tms-bank-accounts | tms-reference-data
  Compliance:  tms-compliance | tms-rules-engine | tms-notifications

Shared Libraries (9 modules):
  tms-events-schema | tms-common-audit | tms-common-outbox | tms-common-idempotency
  tms-common-security | tms-common-money | tms-common-validation
  tms-common-messaging | tms-common-test
```
