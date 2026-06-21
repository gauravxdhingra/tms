# 11 — UI Architecture (v2)

## Overview

The TMS frontend is an Angular SPA communicating with backend services exclusively via the BFF (`tms-bff`). The BFF aggregates microservice responses into view-optimised payloads and streams real-time updates over Server-Sent Events (SSE). The Angular app never calls individual microservices directly.

```
Browser (Angular SPA)
    │
    ├── REST/JSON (Angular HttpClient) ──► Spring Cloud Gateway ──► tms-bff ──► microservices
    └── SSE (EventSource service) ────────► tms-bff (WebFlux SSE endpoint)
```

---

## Technology Stack

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Framework | Angular 19 + TypeScript 5.x | Opinionated DI, reactive forms, signals for fine-grained reactivity; strong fit for large enterprise LOB apps with many contributors |
| Build | Angular CLI + esbuild | Fast builds; HMR; production bundle with differential loading |
| Routing | Angular Router | Lazy-loaded feature modules per domain; route guards for RBAC |
| Server state | Angular HttpClient + RxJS | Native HTTP client; `BehaviorSubject` / signals for caching and derived state |
| UI state | NgRx SignalStore | Lightweight signal-based store scoped per feature (session, layout, domain state) |
| Components | PrimeNG | Data-dense enterprise library (table, tree, dialog, calendar, form fields); well-suited to treasury workflows |
| Data grids | AG Grid Community → Enterprise | 100K+ row virtual scroll, server-side row model, column pinning, grouping, Excel export |
| Charts | Apache ECharts via `ngx-echarts` | Waterfall (cash ladder), heatmap (FX exposure), candlestick (rate charts), bar/line for KPIs |
| Forms | Angular Reactive Forms | `FormGroup`/`FormControl`; custom validators for IBAN, BIC, monetary amounts, value dates |
| Date handling | date-fns 3 + date-fns-tz | Business day calculations, timezone-aware display |
| Real-time | Native `EventSource` wrapped in Angular service | SSE over HTTP/2; injected as a singleton service; events piped to RxJS `Subject` |
| Styles | SCSS + PrimeNG theming | PrimeNG CSS variables for brand tokens; SCSS modules for component-level styles |
| i18n | `@ngx-translate/core` | Runtime language switching; `Intl.NumberFormat` / `Intl.DateTimeFormat` for numbers and dates |
| Testing | Jest + Angular Testing Library + Playwright | Unit/component + E2E |
| Packaging | `frontend-maven-plugin` + `maven-resources-plugin` | Built by Maven as part of monorepo; `dist/` copied into BFF JAR or served from CDN |

---

## Angular Project Structure

```
tms-ui/
├── angular.json
├── package.json
├── tsconfig.json
└── src/
    ├── main.ts
    ├── app/
    │   ├── app.config.ts           # standalone bootstrap, provideRouter, provideHttpClient
    │   ├── app.routes.ts           # top-level lazy routes
    │   ├── core/
    │   │   ├── auth/               # Keycloak OIDC integration, token refresh, auth guard
    │   │   ├── http/               # HttpInterceptor: JWT header, correlation ID, error handling
    │   │   ├── sse/                # SseService: EventSource wrapper → RxJS Observable
    │   │   └── session/            # SessionStore (NgRx signal store): userId, roles, entityId
    │   ├── shared/
    │   │   ├── components/         # TmsMoneyPipe, TmsDatePipe, AmountCellRenderer, StatusBadge
    │   │   ├── validators/         # ibanValidator, bicValidator, futureDateValidator
    │   │   └── ag-grid/            # AG Grid column definitions, cell renderers, common grid options
    │   └── features/
    │       ├── cash/               # Cash Dashboard, Cash Ladder, ECF Browser, BAT Browser
    │       ├── payments/           # Payment Queue, Create Payment, Payment Detail, Approval Inbox
    │       ├── trades/             # Trade Blotter, Trade Capture, Trade Detail, Confirmations
    │       ├── settlement/         # Settlement Queue, NOSTRO Recon, SSI Manager
    │       ├── accounting/         # GL, CoA Tree, Period Management, P&L, Balance Sheet
    │       ├── risk/               # Risk Dashboard, Limit Manager, Exposure Browser, FX NOP
    │       ├── ihb/                # IHB Dashboard, POBO, Intercompany Loans, Netting, Statements
    │       ├── liquidity/          # Liquidity Overview, Counterbalancing Capacity
    │       ├── reference/          # Counterparties, Bank Accounts, Calendars, FX Rates
    │       ├── reports/            # Report Builder, Audit Trail
    │       ├── compliance/         # Compliance Alerts
    │       └── admin/              # User Management, Legal Entities
    └── environments/
        ├── environment.ts          # local dev (points to localhost BFF)
        └── environment.prod.ts     # production (points to gateway URL)
```

---

## Screen Inventory

### Public / Auth
| Screen | Route |
|--------|-------|
| Login | `/login` → redirect to Keycloak OIDC |
| Callback | `/callback` — PKCE code exchange |
| Unauthorized | `/403` |

### Cash Management
| Screen | Route | Key Data |
|--------|-------|----------|
| Cash Dashboard | `/cash` | Cash ladder aggregate; entity switcher; SSE live updates |
| Cash Ladder | `/cash/ladder` | AG Grid: accounts × date columns D to D+30 |
| ECF Browser | `/cash/ecf` | AG Grid server-side: filter by source, status, currency, value date |
| BAT Browser | `/cash/bat` | AG Grid: bank transactions; ingestion status |
| Statement Upload | `/cash/bat/upload` | File upload + progress; validation errors inline |
| Position Detail | `/cash/position/:accountId` | Single account; confirmed vs anticipated; ECharts bar |

### Payments
| Screen | Route | Key Data |
|--------|-------|----------|
| Payment Queue | `/payments` | AG Grid; SSE status updates; quick filter bar |
| Create Payment | `/payments/new` | Reactive Form wizard: Beneficiary → Amount → Review → Submit |
| Payment Detail | `/payments/:id` | BFF aggregated: status, saga steps, SWIFT messages, accounting entries |
| Approval Inbox | `/payments/approvals` | Payments pending current user; bulk approve |
| Return/Repair | `/payments/repair` | Exception queue; edit and resubmit |

### Trades
| Screen | Route |
|--------|-------|
| Trade Blotter | `/trades` |
| Trade Capture | `/trades/new/:type` |
| Trade Detail | `/trades/:id` |
| Confirmations | `/trades/confirmations` |
| Maturity Dashboard | `/trades/maturities` |

### Settlement
| Screen | Route |
|--------|-------|
| Settlement Queue | `/settlement` |
| Settlement Detail | `/settlement/:id` |
| NOSTRO Reconciliation | `/settlement/nostro` |
| SSI Manager | `/settlement/ssi` |

### Accounting
| Screen | Route |
|--------|-------|
| General Ledger | `/accounting/gl` |
| Chart of Accounts | `/accounting/coa` |
| Period Management | `/accounting/periods` |
| P&L Report | `/accounting/pnl` |
| Balance Sheet | `/accounting/balance-sheet` |
| Accrual Monitor | `/accounting/accruals` |

### Risk
| Screen | Route |
|--------|-------|
| Risk Dashboard | `/risk` |
| Limit Manager | `/risk/limits` |
| Exposure Browser | `/risk/exposure` |
| FX NOP Monitor | `/risk/fx-nop` |
| Limit Override | `/risk/overrides` |

### In-House Bank
| Screen | Route |
|--------|-------|
| IHB Dashboard | `/ihb` |
| POBO Requests | `/ihb/pobo` |
| Intercompany Loans | `/ihb/loans` |
| Netting Run | `/ihb/netting` |
| Intercompany Statement | `/ihb/statements/:subsidiaryId` |

*(Liquidity, Reference Data, Reports, Compliance, Admin — same pattern)*

---

## State Management Architecture

```
┌────────────────────────────────────────────────────────────┐
│  Angular HttpClient + RxJS (server state)                  │
│  Each feature service (PaymentService, CashService, etc.)  │
│  owns a BehaviorSubject / signal for its domain data.      │
│  HTTP responses update the subject; components read it.    │
└────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────┐
│  NgRx SignalStore slices (UI / session state)              │
│  SessionStore: { userId, legalEntityId, roles, token }     │
│  LayoutStore:  { sidebarCollapsed, activeTheme }           │
│  PaymentStore: { selectedIds, bulkMode }                   │
│  RiskStore:    { activeLimitBreachIds }  ← fed by SSE      │
└────────────────────────────────────────────────────────────┘
```

Rule: Angular services own server data (HTTP + cache). NgRx SignalStore owns UI decisions. The two never overlap.

---

## Real-Time Updates (SSE)

```typescript
// core/sse/sse.service.ts
@Injectable({ providedIn: 'root' })
export class SseService {
  // BFF reads the auth token from the httpOnly cookie for SSE connections
  // (EventSource cannot set Authorization headers)
  stream<T>(endpoint: string): Observable<T> {
    return new Observable(observer => {
      const es = new EventSource(endpoint, { withCredentials: true });
      es.onmessage = (e) => observer.next(JSON.parse(e.data) as T);
      es.onerror   = ()  => { es.close(); observer.error(new Error('SSE disconnected')); };
      return () => es.close();
    }).pipe(
      retry({ delay: 5000 }),  // auto-reconnect after 5s
    );
  }
}

// Usage in Cash Dashboard component
@Component({ ... })
export class CashDashboardComponent implements OnInit {
  private sseService = inject(SseService);
  private cashService = inject(CashService);

  ngOnInit() {
    this.sseService.stream<CashPositionUpdatedEvent>('/bff/v1/stream/cash-position')
      .pipe(takeUntilDestroyed())
      .subscribe(event => this.cashService.applyPositionUpdate(event));
  }
}
```

SSE endpoints:
| Endpoint | Emits | Consumers |
|----------|-------|-----------|
| `/bff/v1/stream/cash-position` | `CashPositionUpdated` | Cash Dashboard, Cash Ladder |
| `/bff/v1/stream/payments` | `PaymentStatusChanged` | Payment Queue |
| `/bff/v1/stream/risk-alerts` | `LimitBreachDetected` | Risk Dashboard, header bell |
| `/bff/v1/stream/notifications` | `Notification` | Header bell (all screens) |

---

## AG Grid Configuration Patterns

### Server-Side Row Model (for large datasets)
```typescript
// Used for: Payment Queue, Trade Blotter, ECF Browser, GL entries
@Component({ ... })
export class PaymentQueueComponent {
  gridOptions: GridOptions = {
    rowModelType: 'serverSide',
    serverSideDatasource: {
      getRows: (params: IServerSideGetRowsParams) => {
        this.paymentService.list({
          startRow: params.request.startRow ?? 0,
          endRow:   params.request.endRow   ?? 100,
          filterModel: params.request.filterModel,
          sortModel:   params.request.sortModel,
        }).subscribe({
          next: ({ data, totalCount }) =>
            params.success({ rowData: data, rowCount: totalCount }),
          error: () => params.fail(),
        });
      },
    },
    cacheBlockSize: 100,
    maxBlocksInCache: 10,
  };
}
```

### Monetary Amount Cell Renderer
```typescript
@Component({
  template: `<span class="amount-cell">{{ formatted }}</span>`,
})
export class MonetaryAmountRendererComponent implements ICellRendererAngularComp {
  formatted = '';

  agInit(params: ICellRendererParams): void {
    this.refresh(params);
  }

  refresh(params: ICellRendererParams): boolean {
    // value is always a string from the API — never a float
    this.formatted = new Intl.NumberFormat(this.locale, {
      style: 'currency',
      currency: params.data?.currency ?? 'EUR',
      minimumFractionDigits: 2,
      maximumFractionDigits: 8,
    }).format(parseFloat(params.value));
    return true;
  }
}
```

---

## Angular HTTP Interceptors

```typescript
// core/http/auth.interceptor.ts
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const session = inject(SessionStore);
  const token   = session.accessToken();
  return next(req.clone({
    setHeaders: { Authorization: `Bearer ${token}` },
  }));
};

// core/http/correlation.interceptor.ts
export const correlationInterceptor: HttpInterceptorFn = (req, next) => {
  return next(req.clone({
    setHeaders: { 'X-Correlation-ID': crypto.randomUUID() },
  }));
};

// core/http/error.interceptor.ts
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401) inject(AuthService).redirectToLogin();
      if (err.status === 422) return throwError(() => err); // let form handle validation errors
      inject(MessageService).add({ severity: 'error', summary: err.error?.message ?? 'Error' });
      return throwError(() => err);
    }),
  );
};
```

---

## Authentication Flow

1. User navigates to `/` → `AuthGuard` checks for valid access token in `SessionStore`
2. No token → redirect to `/login` → redirect to Keycloak with PKCE (`code_challenge`)
3. Keycloak returns to `/callback?code=...` → Angular exchanges code for tokens via BFF `/auth/token`
4. BFF stores refresh token in **httpOnly secure cookie**; returns access token in response body
5. Access token stored **in memory only** (never `localStorage` — XSS risk)
6. `SessionStore` holds token as a signal; `authInterceptor` reads it on every request
7. Token refresh: interceptor checks expiry before each request; calls `/bff/v1/auth/refresh` (uses cookie automatically)
8. SSE connections: BFF reads token from cookie (EventSource cannot send headers)

---

## Role-Based Rendering

```typescript
// core/session/session.store.ts (NgRx SignalStore)
export const SessionStore = signalStore(
  { providedIn: 'root' },
  withState<SessionState>({
    userId: '',
    legalEntityId: '',
    roles: [] as string[],
    accessToken: '',
  }),
  withComputed(state => ({
    hasRole: computed(() => (role: string) => state.roles().includes(role)),
  })),
);

// Usage in component template
@Component({
  template: `
    @if (session.hasRole()('PAYMENT_CHECKER') && payment.status === 'PENDING_APPROVAL') {
      <button (click)="approve()">Approve</button>
    }
  `,
})
export class PaymentActionsComponent {
  session = inject(SessionStore);
}
```

Frontend RBAC is a UX convenience. All authorization is enforced server-side.

---

## BFF Aggregated View Contracts

### Payment Detail
```
GET /bff/v1/payments/:id/detail
→ { payment, auditTrail, sagaSteps, swiftMessages, accountingEntries, ecfFlows }
```

### Cash Dashboard
```
GET /bff/v1/cash/dashboard?entityId=&date=
→ { totalPositions, cashLadder, recentBAT, pendingECF }
```

### Risk Dashboard
```
GET /bff/v1/risk/dashboard?entityId=
→ { limitUtilisations, activeBreach, fxNOP, recentAlerts }
```

---

## Custom Pipes (shared)

```typescript
// shared/components/money.pipe.ts
@Pipe({ name: 'tmsMoney', pure: true, standalone: true })
export class TmsMoneyPipe implements PipeTransform {
  transform(value: string, currency: string, locale = 'en-GB'): string {
    return new Intl.NumberFormat(locale, {
      style: 'currency', currency,
      minimumFractionDigits: 2, maximumFractionDigits: 8,
    }).format(parseFloat(value)); // value is always a string from API
  }
}

// Usage in template: {{ amount | tmsMoney:currency }}
```

---

## Performance Patterns

- **Lazy loading:** every feature module loaded on demand via `loadChildren` (reduces initial bundle by ~70%)
- **AG Grid virtual scroll:** only DOM rows for the visible viewport — 100K rows, no problem
- **OnPush change detection:** all components use `ChangeDetectionStrategy.OnPush`; signals drive re-renders
- **Memoised pipes:** `pure: true` (default) — re-runs only when inputs change
- **Date formatting:** single `Intl.DateTimeFormat` instance per locale in `TmsDatePipe`
- **Amount parsing:** amounts arrive as strings from API; parsed to `number` only for display, never stored as `number`

---

## Accessibility

- All interactive elements keyboard-navigable (Tab, Enter, Space, Escape)
- PrimeNG components ship with ARIA attributes; custom components follow the same pattern
- AG Grid: `ariaLabel` on all action buttons within cells
- Colour is never the only indicator (breach status = colour + icon + text label)
- Focus trap in modal dialogs (PrimeNG `Dialog` handles this)
- Minimum contrast ratio 4.5:1 (WCAG AA)
