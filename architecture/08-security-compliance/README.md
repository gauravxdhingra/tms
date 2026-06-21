# 08 — Audit, Security, and Compliance Architecture (v2)

## Security Layers

```
┌────────────────────────────────────────────────────────────┐
│                   TMS Security Layers                       │
│                                                            │
│  Edge          TLS 1.3, WAF, DDoS protection               │
│  Gateway       JWT validation, rate limiting, CORS         │
│  Service mesh  mTLS between all services (Phase 3: Istio)  │
│  Service       Spring Security RBAC + ABAC                 │
│  Data          PostgreSQL RLS, column encryption (pgcrypto) │
│  Audit         Immutable event trail, WORM storage          │
└────────────────────────────────────────────────────────────┘
```

---

## Identity and Authentication

**IDP:** Keycloak 26.2

```
Keycloak realms:
  tms-realm          — treasury users (human operators)
  tms-system-realm   — service accounts (machine-to-machine)

OIDC flows:
  Authorization Code + PKCE     — browser SPA login (tms-ui)
  Client Credentials            — service-to-service (tms-payment-hub → tms-risk gRPC)
  Refresh Token Rotation        — access token in memory; refresh token in httpOnly cookie
```

**JWT token claims (custom mappers configured in Keycloak):**
```json
{
  "sub": "user-uuid",
  "iss": "https://auth.tms.internal/realms/tms-realm",
  "aud": ["tms-gateway"],
  "exp": 1720000000,
  "preferred_username": "jdoe",
  "email": "jdoe@treasury.corp",
  "legal_entity_id": "LE-001",
  "roles": ["PAYMENT_MAKER", "CASH_VIEWER"],
  "sod_group": "MAKER",
  "mfa_authenticated": true
}
```

**MFA requirements (enforced at Keycloak via authentication flows):**
- Required for: payment approval, payment release, period close, rule set publish, user admin, limit override approval
- Methods: TOTP (Authenticator app), WebAuthn (hardware security key)
- Spring Security validates `mfa_authenticated: true` claim before servicing MFA-gated endpoints

**Access token lifetime:** 5 minutes (short; BFF transparently refreshes using httpOnly refresh cookie).
**Refresh token lifetime:** 8 hours (business day session). Rotated on use.

---

## Authorization — RBAC + ABAC

### Roles (V2 — includes all 20 services)

| Role | Capabilities |
|------|-------------|
| `SYSTEM_ADMIN` | All access across all legal entities |
| `PAYMENT_MAKER` | Create, enrich, submit payments |
| `PAYMENT_CHECKER` | Approve or reject payments (SoD: ≠ maker) |
| `PAYMENT_RELEASER` | Release approved payments to network (SoD: ≠ maker, ≠ checker) |
| `PAYMENT_VIEWER` | Read-only payment access |
| `TRADE_CAPTURER` | Create and amend trades |
| `TRADE_APPROVER` | Approve trades above threshold |
| `TRADE_VIEWER` | Read-only trade access |
| `SETTLEMENT_OPERATOR` | Manage settlement instructions and repairs |
| `SSI_MANAGER` | Create and amend Standing Settlement Instructions |
| `CASH_MANAGER` | Read ECF/BAT/position; no posting (position service is event-driven) |
| `CASH_VIEWER` | Read-only cash position access |
| `ACCOUNTING_POSTER` | Post manual journals |
| `ACCOUNTING_APPROVER` | Approve reversals above threshold |
| `PERIOD_CLOSE` | Initiate and approve period close |
| `RISK_VIEWER` | View limits, exposures, FX NOP |
| `RISK_LIMIT_MANAGE` | Create/amend risk limits; approve limit overrides |
| `FX_RATE_MANAGER` | Enter and approve FX rates |
| `IHB_OPERATOR` | Process POBO/COBO; manage intercompany loans and netting |
| `RECON_OPERATOR` | Run reconciliation; resolve breaks |
| `COMPLIANCE_OFFICER` | Review and resolve sanctions alerts |
| `COMPLIANCE_SENIOR` | Escalated alerts; regulatory filing approval |
| `CONFIRMATION_MATCHER` | Override and dispute confirmation matches |
| `REFDATA_ADMIN` | Manage legal entities, counterparties, calendars |
| `USER_ADMIN` | Manage users and role assignments (cannot grant own role) |
| `REPORT_VIEWER` | Access reporting and audit trail read |
| `RULES_ADMIN` | Author rule sets |
| `RULES_PUBLISHER` | Publish/deprecate rule sets (SoD: ≠ author) |
| `AUDIT_VIEWER` | Read audit trail (immutable log access) |

### ABAC Policies

ABAC adds context-aware conditions on top of roles, enforced via Spring Security `@PreAuthorize` + `AbacPolicy` beans:

```
PAYMENT_MAKER creates payment for their legalEntityId only
  Condition: jwt.legal_entity_id == resource.legal_entity_id

PAYMENT_CHECKER cannot approve payments they created
  Condition: jwt.sub != resource.created_by_user_id     [SoD]

PAYMENT_RELEASER cannot release payments they made or approved
  Condition: jwt.sub NOT IN (resource.created_by, resource.approved_by)  [SoD]

Payments above threshold require dual approval
  Condition: resource.amount > threshold → resource.approval_count >= 2

CASH_VIEWER sees only accounts in their assigned portfolio
  Condition: jwt.portfolio_ids CONTAINS resource.portfolio_id

RISK_LIMIT_MANAGE approves overrides for their legalEntityId only
  Condition: jwt.legal_entity_id == resource.legal_entity_id

TRADE_APPROVER cannot approve trades they captured
  Condition: jwt.sub != resource.captured_by_user_id    [SoD]

IHB_OPERATOR processes POBO only for subsidiaries assigned to them
  Condition: jwt.subsidiary_ids CONTAINS resource.requesting_subsidiary_id

RULES_PUBLISHER cannot publish rule sets they authored
  Condition: jwt.sub != resource.authored_by_user_id    [SoD]

PERIOD_CLOSE approval requires a different person than the initiator
  Condition: jwt.sub != resource.initiated_by_user_id   [SoD]
```

```java
// Spring Security ABAC — example usage
@PreAuthorize("""
    hasRole('PAYMENT_CHECKER')
    and @abacPolicy.canApprovePayment(authentication, #paymentId)
""")
public ApprovalResult approvePayment(UUID paymentId, ApproveCmd cmd) { ... }
```

### Segregation of Duties (SoD) Summary

| Workflow | SoD Rule |
|----------|----------|
| Payment lifecycle | Maker ≠ Checker ≠ Releaser |
| Trade lifecycle | Capturer ≠ Approver |
| Journal reversal | Poster ≠ Approver (for reversals above threshold) |
| Period close | Initiator ≠ Approver |
| Rule set lifecycle | Author ≠ Publisher |
| User admin | Cannot grant roles to self |
| Limit override | Requester ≠ Override Approver |
| SSI amendment | Initiator ≠ Authoriser |

Every SoD check: (1) enforced at application layer via ABAC; (2) recorded in audit trail with actor, action, target, result; (3) SoD violation attempt emits `SoDViolationAttempted` event → compliance notification.

---

## Audit Trail Architecture

### Design Goals

- **Immutable** — records are never updated or deleted (insert-only table)
- **Attributable** — every record identifies who (`userId`), what (`action`), when (`occurredAt`), from where (`actorIp`, `sourceService`)
- **Complete** — covers every business action and state change (via `@Audited` AOP in `tms-common-audit`)
- **Tamper-evident** — SHA-256 hash chaining per entity for modification detection
- **Queryable** — OpenSearch index enables full-text and structured search
- **Durable** — 7–10 year retention in WORM MinIO bucket

### Audit Record Schema (per-service, identical DDL)

```sql
CREATE TABLE audit_log (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id        UUID NOT NULL UNIQUE,
  entity_type     VARCHAR(100) NOT NULL,
  entity_id       UUID NOT NULL,
  action          VARCHAR(200) NOT NULL,
  action_category VARCHAR(50) NOT NULL,    -- CREATE | UPDATE | APPROVE | REJECT | VIEW | RELEASE
  actor_id        VARCHAR(100) NOT NULL,
  actor_type      VARCHAR(50) NOT NULL,    -- USER | SYSTEM | SERVICE
  actor_ip        INET,
  legal_entity_id VARCHAR(50) NOT NULL,
  before_state    JSONB,                   -- null for CREATE actions
  after_state     JSONB,                   -- null for DELETE-equivalent actions
  correlation_id  UUID NOT NULL,
  trace_id        VARCHAR(100),
  source_service  VARCHAR(100) NOT NULL,
  occurred_at     TIMESTAMPTZ NOT NULL,
  hash            VARCHAR(64) NOT NULL,    -- SHA-256 of record content
  previous_hash   VARCHAR(64)             -- SHA-256 of prior record for same entity
) PARTITION BY RANGE (occurred_at);
```

Hash chaining:
```java
String hash = sha256(
    record.entityType + "|" + record.entityId + "|" +
    record.action + "|" + record.actorId + "|" +
    record.occurredAt.toString() + "|" + record.afterState +
    "|" + Objects.toString(record.previousHash, "GENESIS")
);
```

### Audit Event Flow

```
Business action in service
    ├─ 1. Write business record to DB
    ├─ 2. Write audit_log entry (same transaction via @Audited AOP)
    └─ 3. Write to outbox_events (same transaction)
           │
           ▼ (Debezium CDC)
    Kafka: tms.audit.entries (compacted, 10-year retention)
           │
           ├─► tms-reporting consumer → OpenSearch tms-audit-events-{YYYY-MM}
           └─► Kafka Connect S3 Sink → MinIO WORM bucket (object lock, 7-year minimum)
```

### New Audit Actions for V2 Services

| Service | Actions audited |
|---------|----------------|
| `tms-risk` | `LIMIT_CREATED`, `LIMIT_AMENDED`, `LIMIT_BREACH_DETECTED`, `LIMIT_OVERRIDE_REQUESTED`, `LIMIT_OVERRIDE_APPROVED`, `LIMIT_OVERRIDE_REJECTED` |
| `tms-fx-rates` | `FX_RATE_SUBMITTED`, `FX_RATE_APPROVED`, `FX_RATE_REJECTED`, `REVALUATION_RUN_TRIGGERED` |
| `tms-ihb` | `POBO_REQUEST_SUBMITTED`, `POBO_EXECUTED`, `COBO_RECEIVED`, `INTERCOMPANY_LOAN_CREATED`, `NETTING_RUN_TRIGGERED`, `NETTING_SETTLED` |
| `tms-confirmation-matching` | `MATCH_OVERRIDDEN`, `DISPUTE_RAISED`, `DISPUTE_RESOLVED` |
| `tms-accounting` | `JOURNAL_POSTED`, `REVERSAL_APPROVED`, `PERIOD_CLOSED`, `ACCRUAL_RUN_COMPLETED`, `HEDGE_DESIGNATED`, `HEDGE_DEDESIGNATED` |
| `tms-settlement` | `SSI_CREATED`, `SSI_AMENDED`, `NETTING_APPLIED`, `CLS_SUBMITTED` |

### Audit Accessibility

```
OpenSearch query patterns:
  → All actions by a user in date range
  → All actions on a specific payment, trade, or journal
  → All SoD violation attempts
  → All compliance alert resolutions
  → All limit overrides by senior approver
  → All period close events

REST API:
  GET /api/v1/reports/audit-trail?entityType=&entityId=&actorId=&from=&to=&action=
  (via tms-bff → tms-reporting → OpenSearch)
```

---

## Compliance Architecture

### Sanctions Screening

**Service:** `tms-compliance`

```
Screening modes:
  Synchronous pre-release  — called by Payment Processing Saga Step 3 (10s timeout)
  Async batch re-screen    — nightly: all active counterparties re-screened against updated lists

Lists screened:
  OFAC SDN List
  EU Consolidated Sanctions List
  UN Security Council Sanctions List
  HMT UK Financial Sanctions List
  Custom internal watchlists

Watchlist update process:
  External provider → SFTP drop
  → tms-integration ingests file
  → tms-compliance processes and indexes to OpenSearch tms-watchlist-*
  → WatchlistUpdated event → triggers async re-screening of active counterparties
```

**Alert severity levels:**
| Level | Criteria | Action |
|-------|----------|--------|
| `EXACT` | Name + DOB/ID exact match | Auto-hold; escalate immediately to senior compliance |
| `FUZZY_HIGH` | Fuzzy match ≥ 85% confidence | Hold; compliance review required within 1h |
| `FUZZY_LOW` | Fuzzy match < 85% | Notify; review within 4h; auto-resolve if no action |
| `PEP` | Politically Exposed Person | Enhanced due diligence flag; payment not held by default |

**Circuit breaker policy (when compliance service unavailable):**
- After 5 failures in 10s: circuit opens
- Open circuit action: route payment to `COMPLIANCE_HOLD` status; notify compliance ops
- Never auto-clear a payment when screening is unavailable

### IHB Compliance Considerations (new in V2)

POBO payments are screened as if the treasury entity were the originator (master account). The subsidiary's beneficiary name is still screened. Intercompany transactions are excluded from external sanctions screening but included in internal AML pattern detection.

### Confirmation Matching Compliance (new in V2)

Disputed confirmations (`ConfirmationDisputeRaised`) trigger a compliance notification if the dispute remains unresolved beyond 2 business days (ISDA best practice). All match override actions require `CONFIRMATION_MATCHER` role and are audit-logged with full before/after state.

---

## Data Security

### Encryption

| Data | Approach |
|------|----------|
| Data at rest | PostgreSQL disk-level encryption (cloud KMS or dm-crypt) |
| Data in transit | TLS 1.3 (all HTTP); mTLS between services (Phase 3: Istio; Phase 1-2: mutual TLS via service identity certs) |
| Sensitive DB columns | `pgcrypto` column-level encryption for IBAN, BIC, counterparty name |
| Kafka messages | TLS between producers/consumers and broker; SASL/SCRAM authentication |
| S3/MinIO objects | SSE-S3 (server-side encryption); WORM object lock |
| Backups | Encrypted before write; KMS key rotation every 90 days |

### PII Handling

| Rule | Detail |
|------|--------|
| Masked in logs | `IBAN****1234`, `[MASKED-NAME]` — enforced via Logback masking filter in `tms-common-audit` |
| Not in Kafka payloads | Events reference counterparty by ID; name resolved at query time |
| Column encrypted in DB | Only decrypted for users with `PII_VIEW` permission |
| Audit trail | PII in before/after state is masked for `AUDIT_VIEWER`; full PII visible only to `AUDIT_PII_VIEW` |
| GDPR erasure | Marks personal data fields as `[ANONYMISED]`; transaction IDs and amounts preserved for financial integrity |

### Secret Management

```
HashiCorp Vault:
  Dynamic PostgreSQL credentials (TTL = 1h, auto-rotated)
  Kafka SASL/SCRAM credentials
  SWIFT Service Bureau certificates and private keys
  Sanctions screening API keys
  MinIO access credentials
  Keycloak client secrets (tms-system-realm service accounts)

Kubernetes integration:
  Vault Agent Sidecar → injects secrets as environment variables into pod at startup
  Spring profiles: 'local' uses plaintext from docker-compose env; 'prod' uses Vault Agent
```

---

## API Security Controls

### Gateway Level (Spring Cloud Gateway)

```yaml
filters:
  - JwtValidationFilter:
      issuer: https://auth.tms.internal/realms/tms-realm
      algorithms: [RS256]
      required-claims:
        legal_entity_id: required
        mfa_authenticated: required-for:
          - /api/v1/payments/*/approve
          - /api/v1/payments/*/release
          - /api/v1/accounting/*/periods/*/close
          - /api/v1/risk/limits/*/override/approve
          - /api/v1/admin/users

  - RateLimitFilter:
      key: ${jwt.sub}
      limits:
        default: 1000/min
        POST /api/v1/payments: 200/min
        POST /api/v1/trades: 100/min
        POST /bff/v1/ihb/pobo-requests: 100/min

  - RequestCorrelationFilter:
      inject-if-absent: true   # generates UUID if X-Correlation-ID not present

  - IpAllowlistFilter:
      allowed-cidrs: ${ALLOWED_NETWORK_CIDRS:0.0.0.0/0}  # tighten per environment
```

### Service Level (Spring Security)

```java
// In every service — configured by tms-common-security auto-configuration
@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
        .csrf(AbstractHttpConfigurer::disable)  // stateless API; CSRF not applicable
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health/**").permitAll()
            .anyRequest().authenticated()
        )
        .build();
}

// PostgreSQL RLS: set session variable from JWT claim on every request
@Component
public class RlsContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String legalEntityId = extractLegalEntityId(SecurityContextHolder.getContext());
        jdbcTemplate.execute("SET LOCAL app.legal_entity_id = '" + legalEntityId + "'");
        chain.doFilter(req, res);
    }
}
```

### Input Validation

- All request bodies validated via Bean Validation 3.0 (`@NotNull`, `@Size`, `@DecimalMin`)
- Monetary amounts: accepted as string in JSON; parsed to `MonetaryAmount` — rejects non-numeric or negative values at boundary
- SQL injection: parameterized queries only (JPA/JDBC; no string concatenation in queries)
- XSS: API returns structured JSON only; no HTML rendering in API layer; `Content-Type: application/json` enforced
- File uploads: 100MB size limit; magic-byte type validation (not extension only); ClamAV virus scan before processing
- Path traversal: all file paths resolved via `Paths.get(...).normalize()` with prefix check

---

## Regulatory Compliance Controls

| Requirement | Implementation |
|-------------|---------------|
| Payment audit trail | 7-year WORM MinIO storage + OpenSearch queryable index |
| SoD enforcement | ABAC policies + audit log of every SoD check + attempt |
| Sanctions screening | Real-time pre-release (Phase 2+) + nightly batch re-screen |
| 4-eyes principle | Maker-checker enforced at API layer + ABAC + DB trigger |
| Multi-GAAP | IFRS / US GAAP / LOCAL / MGMT ledgers in `tms-accounting` |
| Data residency | K8s namespace + network policies per legal entity region |
| SOC 2 / ISO 27001 | Audit trail completeness, access controls, incident response runbooks |
| GDPR | PII masking, right-to-erasure workflow (anonymise preserving financial integrity) |
| MiFID II / EMIR | `TradeReportableEvent` emission hooks (Phase 3) |
| CSDR | Settlement fail monitoring; buy-in workflow trigger on D+2 fails |

---

## Security Incident Response

### Automated Detection Triggers

```
> 5 failed login attempts in 5 minutes   → account lock + compliance notification
SoD violation attempt                    → compliance notification + P2 audit alert
Unusual payment amount (> 10× entity avg)→ manual review flag in payment UI
Payment released outside business hours  → P2 alert to ops
Audit log hash chain broken              → P1 page (immediate)
Watchlist update failed                  → compliance alert (cannot screen = cannot release)
DLT depth > 0 for payment queues         → P1 page
Compliance circuit breaker open          → P1 page + auto-hold new payments
Limit breach with no override within 30m → P2 alert to risk manager
IHB netting failure                      → P2 alert; gross instructions reverted
```

### Breach Response

```
1. Detection   → automated alert to security team (Alertmanager → PagerDuty)
2. Isolation   → Kubernetes NetworkPolicy can isolate a namespace in < 1 min;
                  Phase 3: Istio traffic policy cuts compromised service from mesh
3. Investigation → full OTel distributed trace + immutable audit trail
4. Recovery    → event replay from Kafka (within retention) reconstructs read models
5. Notification → regulatory notification workflow if PII or payment data exposed (GDPR 72h)
```
