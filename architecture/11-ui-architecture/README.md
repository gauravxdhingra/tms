# 11 — UI Architecture (v2)

## Overview

The TMS frontend is a single-page application (SPA) built with React 19 and TypeScript, communicating with backend services exclusively via the BFF (`tms-bff`). The BFF aggregates microservice responses into view-optimised payloads and streams real-time updates over Server-Sent Events (SSE). The UI never calls individual microservices directly.

```
Browser (React SPA)
    │
    ├── REST/JSON (TanStack Query) ──────► Spring Cloud Gateway ──► tms-bff ──► microservices
    └── SSE (EventSource)  ─────────────► tms-bff (WebFlux SSE endpoint)
```

---

## Technology Stack

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Framework | React 19 + TypeScript 5.x | Server Components (future-ready), concurrent features, strong types |
| Build | Vite 6.x | Sub-second HMR, native ESM, minimal config |
| Routing | React Router v7 (framework mode) | Loader/Action pattern, nested routes, code splitting |
| Server state | TanStack Query v5 | Normalised cache, background refetch, optimistic updates |
| UI state | Zustand | Lightweight; scoped slices per domain (no global store sprawl) |
| Components | Ant Design 5.x (AntD) | Enterprise-grade; date pickers, form fields, layout; fully typed |
| Data grids | AG Grid Community → Enterprise | 100K+ row virtual scroll, server-side row model, column pinning, grouping |
| Charts | Recharts (simple) + Apache ECharts (complex) | Recharts for KPIs; ECharts for waterfall, heatmap, candlestick |
| Forms | React Hook Form 7 + Zod | Schema validation mirrors backend Zod/Bean Validation DTOs |
| Dates | date-fns 3 + date-fns-tz | Business day calculations, timezone-aware display |
| Real-time | Native `EventSource` via custom hook | SSE over HTTP/2; no extra library needed |
| Styles | CSS Modules + AntD design tokens | No Tailwind (conflicts with AntD theme); tokens for brand consistency |
| i18n | react-i18next | Number/date formatting via `Intl.NumberFormat`/`Intl.DateTimeFormat` |
| Testing | Vitest + React Testing Library + Playwright | Unit/component + e2e |

---

## Screen Inventory

### Public / Auth
| Screen | Route | Notes |
|--------|-------|-------|
| Login | `/login` | Redirects to Keycloak OIDC; PKCE flow |
| Callback | `/callback` | Handles OIDC redirect; stores tokens in memory |
| Unauthorized | `/403` | Role check failed after token exchange |

### Cash Management
| Screen | Route | Key Data |
|--------|-------|----------|
| Cash Dashboard | `/cash` | Cash ladder aggregate; multi-entity switcher; BFF SSE for live updates |
| Cash Ladder | `/cash/ladder` | AG Grid: rows = accounts, cols = dates D to D+30 |
| ECF Browser | `/cash/ecf` | AG Grid server-side: filter by source, status, currency, value date |
| BAT Browser | `/cash/bat` | AG Grid: bank transactions; MT940/CAMT.053 ingestion status |
| Bank Statement Upload | `/cash/bat/upload` | File upload + progress; validation errors inline |
| Cash Position Detail | `/cash/position/:accountId` | Single account; confirmed vs anticipated splits; chart |

### Payments
| Screen | Route | Key Data |
|--------|-------|----------|
| Payment Queue | `/payments` | AG Grid: real-time status (SSE); quick filters by status/currency |
| Create Payment | `/payments/new` | Multi-step wizard (Beneficiary → Amount → Review → Submit) |
| Payment Detail | `/payments/:id` | BFF aggregated: status, audit trail, saga steps, SWIFT messages |
| Approval Inbox | `/payments/approvals` | Payments awaiting current user's approval; bulk approve |
| Return/Repair | `/payments/repair` | Exception queue; edit and resubmit |

### Trades
| Screen | Route | Key Data |
|--------|-------|----------|
| Trade Blotter | `/trades` | AG Grid: all active trades; filter by type/counterparty/currency/date |
| Trade Capture | `/trades/new/:type` | Context-sensitive form per instrument type (FX, IRS, Deposit, etc.) |
| Trade Detail | `/trades/:id` | Full lifecycle; linked settlement; accounting entries tab |
| Confirmations | `/trades/confirmations` | AG Grid: pending/matched/disputed; match/override actions |
| Maturity Dashboard | `/trades/maturities` | Calendar view + grid; maturing in 7/30/90 days |

### Settlement
| Screen | Route | Key Data |
|--------|-------|----------|
| Settlement Queue | `/settlement` | BFF aggregated queue: grouped by settle_date + currency; SSE status |
| Settlement Detail | `/settlement/:id` | Instruction detail; netting group if applicable; SWIFT ref |
| NOSTRO Reconciliation | `/settlement/nostro` | AG Grid: breaks, matches, aged items; resolve/escalate inline |
| SSI Manager | `/settlement/ssi` | SSI CRUD; amendment history; effective date timeline |

### Accounting
| Screen | Route | Key Data |
|--------|-------|----------|
| General Ledger | `/accounting/gl` | AG Grid: journal entries; filter by ledger/CoA/period/entity |
| Chart of Accounts | `/accounting/coa` | Tree view (class → category → subcategory → account); edit inline |
| Period Management | `/accounting/periods` | Open/Close period controls; accrual run status |
| P&L Report | `/accounting/pnl` | Filterable by ledger (IFRS/US_GAAP/LOCAL/MGMT), entity, period |
| Balance Sheet | `/accounting/balance-sheet` | Same filters as P&L; drill-down by CoA node |
| Accrual Monitor | `/accounting/accruals` | Nightly run status; per-instrument accrual amounts; error log |

### Risk
| Screen | Route | Key Data |
|--------|-------|----------|
| Risk Dashboard | `/risk` | Gauge charts: limit utilisations; BFF SSE for live breach alerts |
| Limit Manager | `/risk/limits` | AG Grid: all limits; create/edit/suspend; utilisation bars |
| Exposure Browser | `/risk/exposure` | Per-counterparty/currency/maturity breakdown |
| FX NOP Monitor | `/risk/fx-nop` | Net open position by currency pair; limit overlay |
| Limit Override | `/risk/overrides` | Pending overrides for approval (if user has override-approver role) |

### In-House Bank
| Screen | Route | Key Data |
|--------|-------|----------|
| IHB Dashboard | `/ihb` | Virtual account balances; pending POBO/COBO; netting status |
| POBO Requests | `/ihb/pobo` | Subsidiary-view (via role): submit request; track status |
| Intercompany Loans | `/ihb/loans` | Active loans; interest accruals; maturity schedule |
| Netting Run | `/ihb/netting` | Manual trigger; net amounts preview; confirm to execute |
| Intercompany Statement | `/ihb/statements/:subsidiaryId` | Statement per subsidiary virtual account |

### Liquidity
| Screen | Route | Key Data |
|--------|-------|----------|
| Liquidity Overview | `/liquidity` | Short/medium/strategic horizon tabs; funding gap alerts |
| Counterbalancing Capacity | `/liquidity/cbc` | Available liquid assets; discount haircut breakdown |

### Reference Data
| Screen | Route | Key Data |
|--------|-------|----------|
| Counterparties | `/reference/counterparties` | AG Grid; inline edit; SIC/BIC/LEI fields |
| Bank Accounts | `/reference/bank-accounts` | Tree: legal entity → account; IBAN validation inline |
| Calendars | `/reference/calendars` | Business day calendar management |
| FX Rates | `/reference/fx-rates` | Current spot + forward; manual rate entry; approval workflow |

### Reports & Compliance
| Screen | Route | Key Data |
|--------|-------|----------|
| Report Builder | `/reports` | OpenSearch-backed; filter builder; CSV/Excel export |
| Audit Trail | `/reports/audit` | Immutable log; filter by user/action/entity |
| Compliance Alerts | `/compliance/alerts` | Sanctions + suspicious activity alerts; resolution workflow |

### Administration
| Screen | Route | Key Data |
|--------|-------|----------|
| User Management | `/admin/users` | Keycloak-backed; role assignment per legal entity |
| Legal Entities | `/admin/entities` | Entity hierarchy; base currency; calendar assignment |

---

## State Management Architecture

```
┌────────────────────────────────────────────────────────────────┐
│  TanStack Query Cache (server state)                           │
│  Keys: ['cash', 'ladder', entityId, date]                      │
│        ['payments', 'list', filters]                           │
│        ['trades', tradeId]                                     │
│  TTL: staleTime=30s for position data; Infinity for ref data   │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│  Zustand Slices (UI state — NOT server state)                  │
│  ├── useSessionStore: { userId, entityId, roles, token }       │
│  ├── useLayoutStore: { sidebarCollapsed, activeTab }           │
│  ├── usePaymentStore: { selectedPaymentIds, bulkMode }         │
│  └── useRiskStore: { activeLimitBreachIds (SSE-fed) }          │
└────────────────────────────────────────────────────────────────┘
```

Rules:
- **Never store server data in Zustand.** Only TanStack Query owns server data.
- **Never store UI state in TanStack Query.** Only Zustand (or local component state) owns UI decisions.
- `useSessionStore` is initialised once at startup from Keycloak token claims. Roles drive RBAC in the UI (display/hide actions; server enforces all authorization).

---

## Real-Time Updates (SSE)

```typescript
// Reusable hook
function useSseStream<T>(endpoint: string, onMessage: (event: T) => void) {
  const { getAccessToken } = useAuth();

  useEffect(() => {
    const es = new EventSource(endpoint, { withCredentials: true });
    // Note: native EventSource does not support Authorization header.
    // BFF reads token from secure httpOnly cookie set at login.
    es.onmessage = (e) => onMessage(JSON.parse(e.data));
    es.onerror = () => { es.close(); /* reconnect handled by retry hook */ };
    return () => es.close();
  }, [endpoint]);
}

// Usage: cash position live update
function CashDashboard() {
  const queryClient = useQueryClient();

  useSseStream<CashPositionUpdatedEvent>(
    '/bff/v1/stream/cash-position',
    (event) => {
      queryClient.setQueryData(
        ['cash', 'position', event.accountId, event.currency],
        (old) => ({ ...old, ...event })
      );
    }
  );
  // ...
}
```

SSE endpoints (all behind Spring Cloud Gateway → tms-bff WebFlux):
| Endpoint | Emits | Consumer |
|----------|-------|----------|
| `/bff/v1/stream/cash-position` | `CashPositionUpdated` | Cash Dashboard, Cash Ladder |
| `/bff/v1/stream/payments` | `PaymentStatusChanged` | Payment Queue |
| `/bff/v1/stream/risk-alerts` | `LimitBreachDetected` | Risk Dashboard, header bell |
| `/bff/v1/stream/notifications` | Generic `Notification` | Header bell (all screens) |

---

## AG Grid Configuration Patterns

### Server-Side Row Model (for large datasets)
```typescript
// Used for: Payment Queue, Trade Blotter, ECF Browser, GL entries
const gridOptions: GridOptions = {
  rowModelType: 'serverSide',
  serverSideDatasource: {
    getRows: async (params) => {
      const { data, totalCount } = await api.payments.list({
        startRow: params.request.startRow,
        endRow: params.request.endRow,
        filterModel: params.request.filterModel,
        sortModel: params.request.sortModel,
      });
      params.success({ rowData: data, rowCount: totalCount });
    },
  },
  cacheBlockSize: 100,
  maxBlocksInCache: 10,
};
```

### Monetary amount cell renderer
```typescript
// All amount columns use this renderer — never raw number display
const MonetaryAmountRenderer: React.FC<ICellRendererParams> = ({ value, data }) => {
  const formatted = new Intl.NumberFormat(locale, {
    style: 'currency',
    currency: data.currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 8,
  }).format(parseFloat(value)); // value is string from API (never float)
  return <span style={{ textAlign: 'right' }}>{formatted}</span>;
};
```

---

## BFF Aggregated View Contracts (UI → BFF)

### Payment Detail (all-in-one)
```
GET /bff/v1/payments/:id/detail
Response:
{
  payment: { ...PaymentDTO },
  auditTrail: AuditEntry[],
  sagaSteps: SagaStepDTO[],
  swiftMessages: SwiftMessageDTO[],
  accountingEntries: JournalEntryDTO[],
  ecfFlows: EcfFlowDTO[]
}
```

### Cash Dashboard
```
GET /bff/v1/cash/dashboard?entityId=&date=
Response:
{
  totalPositions: { currency, netPosition, confirmedInflows, anticipatedInflows }[],
  cashLadder: { accountId, accountName, currency, dailyPositions: { date, amount }[] }[],
  recentBAT: BankTransactionDTO[],
  pendingECF: ExpectedCashFlowDTO[]
}
```

### Risk Dashboard
```
GET /bff/v1/risk/dashboard?entityId=
Response:
{
  limitUtilisations: { limitId, limitType, utilisationPct, current, max, status }[],
  activeBreach: LimitBreachDTO[],
  fxNOP: { currencyPair, netPosition, limit, utilisationPct }[],
  recentAlerts: RiskAlertDTO[]
}
```

---

## Authentication Flow

1. User navigates to `/` → React Router loader checks for valid token in memory
2. No token → redirect to `/login` → redirect to Keycloak OIDC with PKCE
3. Keycloak returns to `/callback?code=...` → exchange code for tokens
4. Access token stored **in memory only** (never localStorage/sessionStorage — XSS risk)
5. Refresh token stored in **secure httpOnly cookie** (BFF issues this on token exchange)
6. BFF refreshes access token transparently using refresh token cookie
7. All BFF calls: `Authorization: Bearer <access_token>` header
8. SSE calls: BFF reads token from httpOnly cookie (EventSource cannot set headers)

```typescript
// Token refresh (TanStack Query integration)
const authClient = {
  async getAccessToken(): Promise<string> {
    if (tokenIsExpiringSoon(memoryStore.token)) {
      const fresh = await axios.post('/bff/v1/auth/refresh'); // sends cookie automatically
      memoryStore.token = fresh.data.accessToken;
    }
    return memoryStore.token;
  }
};
```

---

## Role-Based UI Rendering

```typescript
// Roles come from Keycloak token claims, scoped per legal entity
type Role = 'TRADE_CAPTURE' | 'TRADE_APPROVE' | 'PAYMENT_INITIATE' | 'PAYMENT_APPROVE'
          | 'RISK_VIEW' | 'RISK_LIMIT_MANAGE' | 'COMPLIANCE_VIEW' | 'COMPLIANCE_RESOLVE'
          | 'ACCOUNTING_VIEW' | 'ACCOUNTING_POST' | 'PERIOD_CLOSE' | 'ADMIN';

function useHasRole(role: Role): boolean {
  const { roles } = useSessionStore();
  return roles.includes(role);
}

// Usage: hide action buttons for unauthorised users
function PaymentActions({ payment }: { payment: Payment }) {
  const canApprove = useHasRole('PAYMENT_APPROVE');
  return (
    <Space>
      {canApprove && payment.status === 'PENDING_APPROVAL' && (
        <Button onClick={handleApprove}>Approve</Button>
      )}
    </Space>
  );
}
```

Frontend RBAC is a UX convenience only. All authorization is enforced server-side.

---

## Error Handling

```typescript
// Global error boundary + TanStack Query error handling
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        if (error.status === 401) return false; // trigger re-auth, not retry
        if (error.status === 403) return false;
        return failureCount < 3;
      },
      staleTime: 30_000,
    },
    mutations: {
      onError: (error) => notification.error({ message: error.message }),
    },
  },
});
```

HTTP 422 (validation errors from backend) are surfaced inline on form fields via React Hook Form's `setError`.

---

## Performance Patterns

- **Code splitting:** Every top-level route lazy-loaded via `React.lazy` + `Suspense`
- **AG Grid virtual scroll:** Only DOM nodes for visible rows (100K rows no problem)
- **TanStack Query prefetch:** Prefetch detail views on list row hover (300ms debounce)
- **Memoization:** `useMemo` on derived chart data only; avoid `memo()` by default (profile first)
- **Date formatting:** Single `Intl.DateTimeFormat` instance per locale per render cycle, not recreated per cell
- **Monetary display:** All amounts arrive as strings from API; parsed to `number` only for display, never stored as `number`

---

## Accessibility

- All interactive elements keyboard-navigable
- AntD components ship with ARIA attributes; custom components must follow suit
- AG Grid: `aria-label` on all action buttons within grid cells
- Colour is never the only indicator (e.g., breach status = colour + icon + text)
- Focus trap in modal dialogs (AntD `Modal` handles this)
- Minimum contrast ratio 4.5:1 (WCAG AA)
