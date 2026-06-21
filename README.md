# TMS — Treasury Management System

An event-driven corporate treasury platform. The product goal is multi-entity cash visibility, controlled payment execution, short-term forecasting, bank connectivity, and operational reconciliation. Advanced capabilities (trade lifecycle, risk, IHB, hedge accounting) are planned for later phases.

**Implementation status: early foundation.** Shared libraries and the Payment Hub domain slice are in active development. Most other service modules are scaffolds. The Angular UI does not yet exist as an Angular project.

---

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| Java | 21 (Temurin) | `sdk install java 21.0.7-tem` |
| Maven | 3.9+ | `sdk install maven 3.9.9` |
| Docker + Compose v2 | any recent | Docker Desktop (Mac) / Docker Engine + plugin (Linux) |
| Node.js | 20+ | Required only when UI work starts |

[SDKMAN](https://sdkman.io) is the only supported Java/Maven installer. Homebrew paths are not tested and will not be supported.

---

## Quick Start

```bash
# 1. Start all local infrastructure (Postgres, Kafka, Redis, Keycloak, …)
./scripts/start-dev.sh --infra-only

# 2. Build everything (skips tests for speed)
mvn clean package -DskipTests --threads 4

# 3. Install shared libraries into local .m2, then run Payment Operations
mvn install -DskipTests
mvn -pl modules/payment-operations spring-boot:run -Dspring-boot.run.profiles=local
```

Payment Operations listens on `http://localhost:8081`. Health check: `curl http://localhost:8081/actuator/health`

To stop infrastructure: `docker compose -f infrastructure/compose/docker-compose.infra.yml down`

---

## Local Infrastructure

| Service | Host address | Credentials |
|---|---|---|
| PostgreSQL 17 | `localhost:5432` db `tms` | `tms` / `tms` |
| Kafka 3.8 (KRaft) | `localhost:9092` | — |
| Redis 7 | `localhost:6379` | — |
| RabbitMQ 3.13 | `localhost:5672` | `guest` / `guest` |
| RabbitMQ UI | `http://localhost:15672` | `guest` / `guest` |
| Keycloak 26.2 | `http://localhost:8180` | `admin` / `admin` |
| MinIO | `http://localhost:9001` (console) | `minioadmin` / `minioadmin` |
| OpenSearch 2.18 | `http://localhost:9200` | security disabled |
| Mailpit | `http://localhost:8025` | — |
| WireMock | `http://localhost:8089/__admin/` | — |

All credentials are development-only defaults. See the security notice at the bottom.

---

## Running Tests

```bash
# Unit tests only
mvn test

# Full verify (unit + architecture tests)
mvn clean verify

# Single module and its dependencies
mvn -pl modules/payment-operations -am test

# Integration-test profile (requires Docker for Testcontainers)
mvn clean verify -P integration-test
```

All 5 ArchUnit architectural rules pass in `modules/payment-operations`. Integration tests are not yet written.

---

## Repository Layout

```
tms/
├── docs/
│   ├── architecture/       Architecture suite (target-state design; see status notes inside)
│   └── adr/
│       └── DECISIONS.md    Architecture decision log
├── contracts/
│   └── event-contracts/    Avro schemas and generated event classes (com.tms.events.*)
├── libraries/
│   ├── treasury-kernel/    JSR-354 monetary arithmetic + IBAN validators (com.tms.kernel.*)
│   ├── platform-events/    Outbox, audit, idempotency, Kafka/AMQP messaging (com.tms.platform.*)
│   ├── platform-security/  JWT/Keycloak context, RLS filter, method security (com.tms.platform.security)
│   └── test-support/       Testcontainers base classes, WireMock, AssertJ helpers (test scope only)
├── modules/
│   └── payment-operations/ Payment domain — domain + application + infrastructure layers
│       ├── domain/         Payment aggregate, state machine, repository port
│       ├── application/    PaymentApplicationService (orchestration + outbox)
│       ├── infrastructure/ JPA repository, Flyway migration, REST controller
│       └── arch/           ArchUnit rules (5 rules, all green)
├── future-modules/         Empty Maven scaffolds — inactive unless -P future-modules
├── infrastructure/
│   ├── compose/            docker-compose.infra.yml
│   ├── keycloak/           Realm export and realm-creation scripts
│   ├── postgres/           DB init scripts
│   └── wiremock/           WireMock stub mappings
├── scripts/
│   └── start-dev.sh        Prerequisite checks + infra startup (Mac and Linux)
└── pom.xml                 Maven reactor and centralized version catalogue
```

---

## Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Money arithmetic | JSR-354 / Moneta | `double`/`float` banned; enforced by ArchUnit |
| Clock | Injected `java.time.Clock` | `LocalDate.now()` banned; deterministic tests |
| Persistence boundary | Transactional Outbox | Atomic Payment + event in one DB transaction |
| Idempotency | Redis `SET NX` + TTL | IN_FLIGHT → COMPLETED state machine |
| Auth | Keycloak JWT + method security | `ROLE_` prefix, legal-entity RLS per transaction |
| Avro | Apache Avro 1.12 + Confluent SR | Typed codegen, backward-compatible evolution |
| Architecture tests | ArchUnit 1.3 | Layer rules + no-Spring-in-domain + no-float enforced in CI |

Full rationale is in [DECISIONS.md](DECISIONS.md).

---

## Mac → EC2 Linux Migration Notes

The codebase is platform-neutral Java/Maven. These rules keep it that way:

- **No `host.docker.internal`** in shared config — it is Docker Desktop-only; unmapped on Linux Docker Engine.
- **Kafka advertises `localhost:9092`** for host clients. Inter-container access needs a separate internal listener (`kafka:29092`) — tracked as a known gap.
- **OpenSearch** needs `vm.max_map_count=262144` on Linux hosts (`sysctl -w vm.max_map_count=262144`). Docker Desktop applies this silently; on EC2 you must set it explicitly.
- **Image tags are pinned** — no `latest`. Every image has a tested digest-stable version.
- **Shell scripts use `/usr/bin/env bash`** and avoid macOS-only utilities.
- **Line endings** are enforced LF via `.gitattributes` — prevents CRLF failures on Linux.
- The Compose stack is a **local dev environment**, not a production topology. For EC2 deployment, see [`docs/architecture/IMPROVEMENTS.md § 3`](docs/architecture/IMPROVEMENTS.md).

---

## Architecture and Documentation

| Document | What it covers |
|---|---|
| [Architecture Index](docs/architecture/README.md) | Entry point for all design documents |

Architecture documents describe the **target state**. Where docs and code disagree, the code is authoritative for what is implemented today.

---

## Known Gaps (Foundation)

- Schema Registry is not in the Compose stack yet — Kafka/Avro flows are not end-to-end runnable.
- No integration tests exist yet; Testcontainers base classes in `tms-common-test` are stubs.
- `tms-ui` is a Maven POM placeholder — no Angular project has been initialized.

---

## Security Notice

Every credential in `docker-compose.infra.yml` is an insecure local-dev default. Do not reuse them on any shared host. Do not expose PostgreSQL, Kafka, Redis, RabbitMQ, Keycloak admin, MinIO admin, or OpenSearch ports to the internet. Never commit real bank credentials, certificates, API keys, customer data, or production payment files to this repository.
