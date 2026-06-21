# 13 — Testing Strategy (v2)

## Test Pyramid

```
                    ┌──────────────┐
                    │   E2E / UI   │  ~20 tests  (Playwright — critical paths only)
                  ┌─┴──────────────┴─┐
                  │ Contract Tests    │  ~50 pairs (Spring Cloud Contract + Avro compat)
                ┌─┴───────────────────┴─┐
                │  Integration Tests     │  ~200/service  (Testcontainers)
              ┌─┴─────────────────────────┴─┐
              │       Unit Tests             │  ~500+/service  (JUnit 5 + Mockito)
              └───────────────────────────────┘
```

Rule: The higher the pyramid tier, the fewer tests, but the more confidence in cross-service integration.

---

## Mandatory Patterns (enforced by ArchUnit / CI quality gate)

| Pattern | Rule |
|---------|------|
| Clock injection | All services must inject `Clock` bean; `LocalDate.now()` is banned (ArchUnit) |
| MonetaryAmount | `double`/`float` for monetary fields banned (ArchUnit) |
| Testcontainers | All integration tests must extend `TmsIntegrationTest` base class |
| No production DB in CI | CI never connects to a real database; always Testcontainers |
| Avro compatibility | Schema Registry plugin verifies BACKWARD compatibility on every PR |
| Contract tests | Every REST API and Kafka topic must have at least one contract |

---

## Unit Tests

**Framework:** JUnit 5 + Mockito + AssertJ

### Domain Logic
```java
// Example: payment service unit test
class PaymentServiceTest {

    private final Clock clock = Clock.fixed(
        LocalDate.of(2025, 6, 15).atStartOfDay(ZoneOffset.UTC).toInstant(),
        ZoneOffset.UTC
    );

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentService service = new PaymentService(paymentRepository, clock);

    @Test
    void shouldRejectPaymentWithValueDateInThePast() {
        CreatePaymentCommand cmd = CreatePaymentCommand.builder()
            .valueDate(LocalDate.of(2025, 6, 14))  // yesterday relative to fixed clock
            .amount(Money.of("100.00", "EUR"))
            .build();

        assertThatThrownBy(() -> service.create(cmd))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Value date must not be in the past");
    }
}
```

### Monetary Arithmetic
```java
// Verify HALF_EVEN rounding for interest calculations
class AccrualCalculatorTest {

    @Test
    void shouldRoundInterestWithHalfEvenMode() {
        MonetaryAmount principal = Money.of("1000000.00", "USD");
        BigDecimal rate = new BigDecimal("0.03500");
        // ACT/360: 181 days
        MonetaryAmount accrual = calculator.calculate(principal, rate, 181, DayCountConvention.ACT_360);

        MoneyAssertions.assertAmount(accrual, "17569.44", "USD");
    }

    @Test
    void shouldNeverProduceFloatingPointRoundingError() {
        MonetaryAmount a = Money.of("0.1", "EUR");
        MonetaryAmount b = Money.of("0.2", "EUR");
        MonetaryAmount sum = a.add(b);
        // Must be exactly 0.3, not 0.30000000000000004
        MoneyAssertions.assertAmount(sum, "0.3", "EUR");
    }
}
```

### Day-Count Convention Tests (date-sensitive — use fixed clock)
```java
@TestFactory
Stream<DynamicTest> dayCountConventionTests() {
    return Stream.of(
        new DayCountCase(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 7, 1), ACT_360, 181, 360),
        new DayCountCase(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 7, 1), ACT_365, 181, 365),
        new DayCountCase(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 7, 1), THIRTY_360, 180, 360),
        new DayCountCase(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 7, 1), ACT_ACT_ICMA, 182, 366)
    ).map(tc -> dynamicTest(tc.toString(), () -> {
        BigDecimal factor = calculator.dayCountFraction(tc.start(), tc.end(), tc.convention());
        assertThat(factor).isEqualByComparingTo(
            new BigDecimal(tc.numerator()).divide(new BigDecimal(tc.denominator()), 10, HALF_EVEN)
        );
    }));
}
```

---

## Integration Tests (Testcontainers)

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class PaymentProcessingIntegrationTest extends TmsIntegrationTest {
    // TmsIntegrationTest provides: PostgreSQL, Kafka, RabbitMQ, Redis containers

    @Autowired PaymentRepository paymentRepository;
    @Autowired KafkaTestConsumer kafkaConsumer;

    @Test
    void shouldPublishPaymentCreatedEventAfterCreation() {
        CreatePaymentCommand cmd = PaymentTestFixtures.validPaymentCommand();

        paymentService.create(cmd);

        PaymentCreated event = kafkaConsumer
            .awaitEvent(PaymentCreated.class, "tms.payment.payments.lifecycle", Duration.ofSeconds(5));
        assertThat(event.getPaymentId()).isNotNull();
        assertThat(event.getAmount().getAmount()).isEqualTo("1000.00");
        assertThat(event.getAmount().getCurrency()).isEqualTo("EUR");
    }

    @Test
    void shouldBeIdempotentOnDuplicateIdempotencyKey() {
        CreatePaymentCommand cmd = PaymentTestFixtures.validPaymentCommand();
        String idempotencyKey = UUID.randomUUID().toString();

        String id1 = paymentService.create(cmd, idempotencyKey);
        String id2 = paymentService.create(cmd, idempotencyKey);

        assertThat(id1).isEqualTo(id2);
        assertThat(paymentRepository.countByCorrelationId(idempotencyKey)).isEqualTo(1);
    }
}
```

### Saga Integration Test
```java
class PaymentSagaIntegrationTest extends TmsIntegrationTest {

    @Test
    void paymentSagaShouldCompleteHappyPath() throws InterruptedException {
        // Given: WireMock stubs for compliance screening (cleared)
        wiremock.stubFor(post("/screen").willReturn(okJson("""
            { "result": "CLEARED", "confidence": 100 }
        """)));

        // When: create and submit payment
        UUID paymentId = createPayment();
        submitPayment(paymentId);

        // Then: await saga completion
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ACKNOWLEDGED);

            // Verify ECF flow was created
            List<ExpectedCashFlow> flows = ecfClient.findBySourceId(paymentId);
            assertThat(flows).hasSize(1);
            assertThat(flows.get(0).getFlowStatus()).isEqualTo("CONFIRMED");
        });
    }

    @Test
    void paymentSagaShouldHoldOnSanctionsHit() {
        wiremock.stubFor(post("/screen").willReturn(okJson("""
            { "result": "HELD", "confidence": 95, "matchedList": "OFAC-SDN" }
        """)));

        UUID paymentId = createPayment();
        submitPayment(paymentId);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Payment payment = paymentRepository.findById(paymentId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SANCTIONS_HELD);
        });
    }
}
```

### Resilience Test (Toxiproxy)
```java
@Testcontainers
class NetworkResilienceTest extends TmsIntegrationTest {

    @Container
    static final ToxiproxyContainer toxiproxy = new ToxiproxyContainer(
        DockerImageName.parse("ghazal/toxiproxy:2.1.7"));

    @Test
    void kafkaPublisherShouldRetryOnTransientFailure() {
        ToxiproxyClient.Proxy kafkaProxy = toxiproxy.getProxy(kafkaContainer, 9092);

        // Simulate 5 seconds of kafka unavailability
        kafkaProxy.toxics().latency("kafka-latency", ToxicDirection.DOWNSTREAM, 5000);

        // Action: publish event (should succeed after retry, not throw)
        assertThatCode(() -> publisher.publishPaymentCreated(fixture))
            .doesNotThrowAnyException();

        kafkaProxy.toxics().get("kafka-latency").remove();
    }

    @Test
    void complianceSagaStepShouldActivateCircuitBreaker() {
        // Bring compliance service down
        WireMock.stubFor(post("/screen").willReturn(serverError()));

        // First failure
        paymentService.submitForCompliance(payment1);
        assertThat(complianceCircuitBreaker.getState()).isEqualTo(CLOSED);

        // Fail 5 times to open circuit
        IntStream.range(0, 5).forEach(i -> paymentService.submitForCompliance(newPayment()));
        assertThat(complianceCircuitBreaker.getState()).isEqualTo(OPEN);

        // New payments should be held immediately without calling compliance
        paymentService.submitForCompliance(newPayment());
        WireMock.verify(exactly(0), postRequestedFor(urlEqualTo("/screen")));
    }
}
```

---

## Contract Tests (Spring Cloud Contract)

### REST Contract (Provider-side)

```groovy
// tms-payment-hub/src/test/resources/contracts/payment/shouldCreatePayment.groovy
Contract.make {
    request {
        method POST()
        url '/api/v1/payments'
        headers { contentType applicationJson() }
        body([
            debitAccountId : $(anyUuid()),
            creditAccountId: $(anyUuid()),
            amount         : "1000.00",
            currency       : "EUR",
            valueDate      : "2025-06-20"
        ])
    }
    response {
        status 201
        body([
            paymentId: $(anyUuid()),
            status   : "PENDING"
        ])
        headers { contentType applicationJson() }
    }
}
```

Contract tests are generated stubs for downstream consumers. The BFF's integration test suite runs against the generated WireMock stub from `tms-payment-hub`. This guarantees that if `tms-payment-hub` changes its API, the BFF's consumer-driven contract test fails immediately in CI.

### Avro Schema Compatibility (enforced by CI)
```bash
# In CI — run against tms-events-schema module
# Uses exec:java to call the Schema Registry compatibility check API
mvn exec:java -pl tms-events-schema \
  -Dexec.mainClass=com.tms.schema.CompatibilityCheck
# Fails if any .avsc change would break BACKWARD compatibility
```

Full test in producer:
```java
@Test
void paymentCreatedSchemaShouldBeBackwardCompatible() {
    SchemaRegistryClient client = new MockSchemaRegistryClient();
    String subject = "tms.payment.payments.lifecycle-value";

    // Register current schema
    client.register(subject, new Schema.Parser().parse(PaymentCreated.SCHEMA$));

    // New schema (from modified .avsc) must be backward compatible
    ParsedSchema newSchema = new AvroSchema(NEW_PAYMENT_CREATED_SCHEMA_JSON);
    CompatibilityLevel level = client.testCompatibility(subject, newSchema);
    assertThat(level).isEqualTo(CompatibilityLevel.BACKWARD);
}
```

---

## Domain-Specific Test Concerns

### Settlement Cutoff Tests (date + time sensitive)
```java
@Test
void shouldDeferSettlementAfterCutoff() {
    // Fixed clock: 2025-06-15 15:30 UTC (after SWIFT cutoff 14:00)
    Clock afterCutoffClock = Clock.fixed(
        LocalDateTime.of(2025, 6, 15, 15, 30).toInstant(ZoneOffset.UTC),
        ZoneOffset.UTC
    );
    SettlementService service = new SettlementService(repo, afterCutoffClock);

    SettlementInstruction instruction = createInstructionForToday();
    Result result = service.processInstruction(instruction);

    assertThat(result.getSettlementDate()).isEqualTo(LocalDate.of(2025, 6, 16));
    assertThat(result.getReason()).isEqualTo("CUTOFF_MISSED");
}
```

### Audit Trail Completeness
```java
@Test
void everyStateTransitionShouldProduceAuditEntry() {
    UUID paymentId = createAndSubmitPayment();
    approvePayment(paymentId);
    releasePayment(paymentId);

    List<AuditEntry> trail = auditRepository.findByEntityId(paymentId);
    assertThat(trail).extracting(AuditEntry::getAction)
        .containsExactlyInAnyOrder(
            "PAYMENT_CREATED",
            "PAYMENT_SUBMITTED",
            "PAYMENT_APPROVED",
            "PAYMENT_RELEASED"
        );
    trail.forEach(entry -> {
        assertThat(entry.getUserId()).isNotBlank();
        assertThat(entry.getCorrelationId()).isNotNull();
        assertThat(entry.getChangedAt()).isNotNull();
    });
}
```

### Balanced Journal Assertion
```java
@Test
void journalEntriesMustBalance() {
    TradeSettledEvent event = buildSettledTradeEvent();
    accountingService.processTradeSettlement(event);

    Journal journal = journalRepository.findByCorrelationId(event.getCorrelationId());
    BigDecimal totalDebits = journal.getEntries().stream()
        .filter(e -> e.getDirection() == DEBIT)
        .map(e -> Money.toBigDecimal(e.getAmount()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal totalCredits = journal.getEntries().stream()
        .filter(e -> e.getDirection() == CREDIT)
        .map(e -> Money.toBigDecimal(e.getAmount()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    assertThat(totalDebits).isEqualByComparingTo(totalCredits);
}
```

### Accrual Idempotency Test
```java
@Test
void nightlyAccrualRunShouldBeIdempotent() {
    LocalDate accrualDate = LocalDate.of(2025, 6, 15);

    accrualService.runForDate(accrualDate);
    accrualService.runForDate(accrualDate); // run twice (e.g., retry after failure)

    List<AccrualEntry> entries = accrualRepository.findByDate(accrualDate);
    // Must produce exactly one entry per instrument per day per ledger — not two
    Map<String, Long> countByKey = entries.stream()
        .collect(groupingBy(e -> e.getInstrumentId() + ":" + e.getLedger(), counting()));
    assertThat(countByKey.values()).allMatch(count -> count == 1L);
}
```

---

## Chaos / Resilience Tests (separate suite, opt-in)

**Tool:** Chaos Monkey for Spring Boot (`de.codecentric:chaos-monkey-spring-boot`)
**Activation:** Spring profile `chaos` — never active in CI default, only in dedicated chaos runs

```yaml
# application-chaos.yml
chaos:
  monkey:
    enabled: true
    watcher:
      service: true
      repository: true
    assaults:
      level: 3
      latency-active: true
      latency-range-start: 1000
      latency-range-end: 5000
      exceptions-active: true
```

Chaos tests to run periodically (not every build):
1. Kill `tms-compliance` container mid-saga → verify payment held (not lost)
2. Kill `tms-accounting` during accrual run → verify re-run produces correct idempotent result
3. PostgreSQL primary failover (Patroni) → verify services reconnect within 30s
4. Kafka broker failure (1 of 3) → verify producers reconnect; no event loss
5. Redis eviction → verify idempotency keys regenerated (at-least-once processing, not exactly-once)

---

## Angular Component Tests (Jest + Angular Testing Library)

```typescript
// payment-queue.component.spec.ts
describe('PaymentQueueComponent', () => {
  let fixture: ComponentFixture<PaymentQueueComponent>;
  let paymentService: jest.Mocked<PaymentService>;

  beforeEach(async () => {
    paymentService = { list: jest.fn() } as any;
    await TestBed.configureTestingModule({
      imports: [PaymentQueueComponent],
      providers: [{ provide: PaymentService, useValue: paymentService }],
    }).compileComponents();

    fixture = TestBed.createComponent(PaymentQueueComponent);
  });

  it('should display PENDING_APPROVAL badge for pending payments', () => {
    paymentService.list.mockReturnValue(of({ data: [mockPayment('PENDING_APPROVAL')], totalCount: 1 }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="status-badge"]').textContent)
      .toContain('PENDING_APPROVAL');
  });

  it('should show Approve button only for PAYMENT_CHECKER role', () => {
    TestBed.inject(SessionStore).patchState({ roles: ['PAYMENT_VIEWER'] });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="approve-btn"]')).toBeNull();

    TestBed.inject(SessionStore).patchState({ roles: ['PAYMENT_CHECKER'] });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-testid="approve-btn"]')).not.toBeNull();
  });
});
```

## E2E Tests (Playwright)

**Scope:** Critical golden paths only. Not every screen.

| Test | Path |
|------|------|
| Create and approve a payment | Login → New Payment → Submit → Switch to Approver → Approve → Verify RELEASED status |
| Cash ladder is updated after bank statement | Upload MT940 → Verify BAT appears → Verify position updated |
| Trade capture to settlement | Capture FX trade → Confirm → Verify settlement instruction created |
| Compliance hold and release | Create payment with blocked name → Verify HELD → Login as Compliance → Clear → Verify RELEASED |
| Risk limit breach alert | Create trade exceeding limit → Verify breach in risk dashboard → Approve override |

```typescript
// playwright.config.ts
export default defineConfig({
  testDir: './e2e',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:4200',  // Angular dev server
    trace: 'on-first-retry',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});

// e2e/payment.spec.ts
test('create and approve payment', async ({ page }) => {
  await loginAs(page, 'payment-initiator');
  await page.goto('/payments/new');
  await page.getByLabel('Amount').fill('50000');
  await page.getByLabel('Currency').selectOption('EUR');
  await page.getByRole('button', { name: 'Submit' }).click();
  await expect(page.getByTestId('payment-status')).toHaveText('PENDING_APPROVAL');

  await loginAs(page, 'payment-approver');
  await page.goto('/payments/approvals');
  await page.getByTestId('approve-btn').first().click();
  await expect(page.getByTestId('payment-status')).toHaveText('APPROVED');
});
```

---

## Performance Tests (Gatling)

**Location:** `tms-performance/src/gatling/`

Baseline targets (must pass in CI's load test stage):

| Scenario | RPS | p95 Latency |
|----------|-----|-------------|
| Payment creation | 50 | < 500ms |
| Cash ladder query (100 accounts) | 200 | < 300ms |
| Trade capture | 20 | < 800ms |
| Accrual batch (10,000 instruments) | — | < 5min total |
| BFF cash dashboard aggregation | 100 | < 200ms |

```scala
// PaymentLoadSimulation.scala
class PaymentLoadSimulation extends Simulation {
  val createPaymentScenario = scenario("Create Payment")
    .exec(http("POST /api/v1/payments")
      .post("/api/v1/payments")
      .header("Authorization", "Bearer #{token}")
      .header("Idempotency-Key", "#{idempotencyKey}")
      .body(ElFileBody("payment_request.json"))
      .check(status.is(201)))

  setUp(
    createPaymentScenario.inject(
      rampUsers(50).during(60.seconds),
      constantUsersPerSec(50).during(5.minutes)
    )
  ).assertions(
    global.responseTime.percentile3.lt(500),
    global.successfulRequests.percent.gt(99)
  )
}
```

---

## Test Data Management

- Each integration test runs in a transaction rolled back at test end (or truncates target tables)
- `TmsIntegrationTest` base class provides `@Sql("/sql/truncate-all.sql")` cleanup before each test
- `PaymentTestFixtures`, `TradeTestFixtures`, `AccrualTestFixtures` etc. live in `tms-common-test`
- No shared test data between tests — each test creates its own fixtures inline
- Fixed `Clock` (2025-01-15 00:00 UTC) used in all tests that touch dates; override per-test when testing specific date logic
