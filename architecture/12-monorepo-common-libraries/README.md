# 12 — Monorepo & Common Libraries (v2)

## Repository Structure

```
tms/                                    # Gradle monorepo root
├── settings.gradle.kts                 # all subprojects registered here
├── build.gradle.kts                    # root conventions applied to all modules
├── gradle/
│   ├── libs.versions.toml              # single version catalog
│   └── conventions/                    # convention plugins (shared build logic)
│       ├── java-library.gradle.kts
│       ├── spring-service.gradle.kts
│       └── avro-codegen.gradle.kts
│
├── tms-events-schema/                  # Avro schemas + generated Java classes
├── tms-common-audit/                   # Audit logging cross-cutting library
├── tms-common-outbox/                  # Transactional outbox + Debezium CDC
├── tms-common-idempotency/             # Idempotency-Key dedup (Redis-backed)
├── tms-common-security/                # JWT extraction, RBAC/ABAC helpers
├── tms-common-money/                   # MonetaryAmount wrappers, JSR-354 config
├── tms-common-validation/              # Shared Bean Validation annotations
├── tms-common-messaging/               # Kafka/RabbitMQ template wrappers
├── tms-common-test/                    # Testcontainers base, fixtures, fakes
│
├── tms-gateway/                        # Spring Cloud Gateway
├── tms-config-server/                  # Spring Cloud Config Server
├── tms-bff/                            # Backend For Frontend
├── tms-payment-hub/
├── tms-trade/
├── tms-settlement/
├── tms-cash-ecf/
├── tms-cash-bat/
├── tms-cash-position/
├── tms-accounting/
├── tms-risk/
├── tms-fx-rates/
├── tms-ihb/
├── tms-liquidity/
├── tms-reconciliation/
├── tms-bank-accounts/
├── tms-reference-data/
├── tms-compliance/
├── tms-confirmation-matching/
├── tms-rules-engine/
├── tms-notifications/
│
└── tms-ui/                             # React SPA (separate Vite build, not Gradle)
    ├── package.json
    ├── vite.config.ts
    └── src/
```

---

## Gradle Configuration

### `settings.gradle.kts` (excerpt)
```kotlin
rootProject.name = "tms"

// Shared libraries
include(
    "tms-events-schema",
    "tms-common-audit",
    "tms-common-outbox",
    "tms-common-idempotency",
    "tms-common-security",
    "tms-common-money",
    "tms-common-validation",
    "tms-common-messaging",
    "tms-common-test",
)

// Infrastructure services
include("tms-gateway", "tms-config-server", "tms-bff")

// Domain services
include(
    "tms-payment-hub", "tms-trade", "tms-settlement",
    "tms-cash-ecf", "tms-cash-bat", "tms-cash-position",
    "tms-accounting", "tms-risk", "tms-fx-rates",
    "tms-ihb", "tms-liquidity", "tms-reconciliation",
    "tms-bank-accounts", "tms-reference-data", "tms-compliance",
    "tms-confirmation-matching", "tms-rules-engine", "tms-notifications",
)
```

### Version Catalog — `gradle/libs.versions.toml` (key entries)
```toml
[versions]
java                    = "21"
spring-boot             = "4.0.0"
spring-cloud            = "2025.0.0"
spring-kafka            = "4.0.0"
spring-amqp             = "4.0.0"
spring-security         = "7.0.0"
hibernate               = "7.0.0"
flyway                  = "10.0.0"
kafka                   = "3.8.0"
avro                    = "1.12.0"
confluent-sr            = "7.8.0"
moneta                  = "1.4.4"
jackson                 = "2.18.0"
grpc                    = "1.68.0"
testcontainers          = "1.20.0"
gatling                 = "3.12.0"
wiremock                = "3.9.0"
toxiproxy               = "2.1.7"
jib                     = "3.4.3"

[libraries]
spring-boot-starter-web     = { module = "org.springframework.boot:spring-boot-starter-web" }
spring-boot-starter-data-jpa = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
spring-kafka                = { module = "org.springframework.kafka:spring-kafka" }
spring-amqp                 = { module = "org.springframework.amqp:spring-rabbit" }
moneta                      = { module = "org.javamoney:moneta", version.ref = "moneta" }
avro                        = { module = "org.apache.avro:avro", version.ref = "avro" }
confluent-avro-serializer   = { module = "io.confluent:kafka-avro-serializer", version.ref = "confluent-sr" }
testcontainers-postgresql   = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
testcontainers-kafka        = { module = "org.testcontainers:kafka", version.ref = "testcontainers" }
testcontainers-rabbitmq     = { module = "org.testcontainers:rabbitmq", version.ref = "testcontainers" }
```

### Convention Plugins

**`gradle/conventions/spring-service.gradle.kts`** — applied by every deployable service:
```kotlin
plugins {
    id("java")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.cloud.tools.jib")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    implementation(project(":tms-common-audit"))
    implementation(project(":tms-common-outbox"))
    implementation(project(":tms-common-idempotency"))
    implementation(project(":tms-common-security"))
    implementation(project(":tms-common-money"))
    implementation(project(":tms-common-validation"))
    implementation(project(":tms-common-messaging"))
    implementation(project(":tms-events-schema"))
    testImplementation(project(":tms-common-test"))
}

jib {
    from { image = "eclipse-temurin:21-jre-alpine" }
    to { image = "ghcr.io/org/tms/${project.name}:${project.version}" }
    container {
        jvmFlags = listOf("-XX:+UseZGC", "-XX:+ZGenerational")
        ports = listOf("8080", "8081") // app + management
    }
}

tasks.test { useJUnitPlatform() }
```

---

## `tms-events-schema`

Owns all Avro `.avsc` files and publishes generated Java classes as a JAR.

```
tms-events-schema/
├── build.gradle.kts
└── src/main/avro/
    ├── common/
    │   ├── MonetaryAmount.avsc
    │   ├── AuditMetadata.avsc
    │   └── LegalEntityContext.avsc
    ├── payment/
    │   ├── PaymentCreated.avsc
    │   ├── PaymentValidated.avsc
    │   ├── PaymentReleased.avsc
    │   ├── PaymentSettled.avsc
    │   └── PaymentFailed.avsc
    ├── cash/
    │   ├── ecf/
    │   │   ├── ExpectedCashFlowCreated.avsc
    │   │   ├── ExpectedCashFlowAmended.avsc
    │   │   └── ExpectedCashFlowSettled.avsc
    │   ├── bat/
    │   │   ├── BankStatementReceived.avsc
    │   │   └── BankTransactionPosted.avsc
    │   └── position/
    │       └── CashPositionUpdated.avsc
    ├── trade/
    ├── settlement/
    ├── accounting/
    ├── risk/
    ├── fx/
    ├── ihb/
    ├── reconciliation/
    └── confirmation/
```

**`MonetaryAmount.avsc`** (canonical money type, reused by all schemas):
```json
{
  "type": "record",
  "name": "MonetaryAmount",
  "namespace": "com.tms.events.common",
  "fields": [
    { "name": "amount", "type": "string" },
    { "name": "currency", "type": "string" }
  ]
}
```

**`build.gradle.kts`**:
```kotlin
plugins {
    id("java-library")
    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1"
}

avro { isCreateSetters.set(false) }  // generate immutable records

dependencies {
    api(libs.avro)
    api(libs.confluent.avro.serializer)
}
```

Schema compatibility rule: ALL schemas registered with `BACKWARD` compatibility in Confluent Schema Registry. CI enforces this via `schema-registry-gradle-plugin` check on every PR.

---

## `tms-common-audit`

```java
// Annotation for automatic audit logging via AOP
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    String action();
    String entityType();
}

// AOP aspect (applied via spring-service convention plugin's auto-configuration)
@Aspect
@Component
public class AuditAspect {
    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        Object result = pjp.proceed();
        AuditEntry entry = AuditEntry.builder()
            .action(audited.action())
            .entityType(audited.entityType())
            .entityId(extractEntityId(result))
            .userId(SecurityContextHolder.getContext().userId())
            .correlationId(MDC.get("correlationId"))
            .changedAt(Instant.now(clock))
            .build();
        auditPublisher.publish(entry);  // publishes to tms.audit.entries topic
        return result;
    }
}
```

Audit entries go to Kafka `tms.audit.entries` (keyed by `entityType:entityId`) → OpenSearch index for search.

---

## `tms-common-outbox`

```java
// Outbox table is per-service (each service has its own outbox in its schema)
// This library provides the publisher and the Debezium configuration bootstrap

@Service
public class OutboxEventPublisher {
    private final JdbcTemplate jdbc;

    public void publish(String aggregateType, UUID aggregateId,
                        String eventType, byte[] avroPayload, int schemaId) {
        jdbc.update("""
            INSERT INTO outbox_events
              (aggregate_type, aggregate_id, event_type, payload, avro_schema_id)
            VALUES (?, ?, ?, ?, ?)
            """,
            aggregateType, aggregateId, eventType, avroPayload, schemaId);
        // Debezium CDC reads this insert and publishes to Kafka
        // Never call KafkaTemplate directly — always go through outbox
    }
}
```

DDL is identical per-service; managed by each service's Flyway migrations (not this library). This library ships the `OutboxEventPublisher` bean + the Debezium connector configuration class only.

---

## `tms-common-idempotency`

```java
@Component
public class IdempotencyFilter extends OncePerRequestFilter {
    private final StringRedisTemplate redis;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String key = req.getHeader("Idempotency-Key");
        if (key == null) { chain.doFilter(req, res); return; }

        String cacheKey = "idem:" + key;
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            res.setStatus(HttpServletResponse.SC_OK);
            res.getWriter().write(cached);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(res);
        chain.doFilter(req, wrapped);
        redis.opsForValue().set(cacheKey, new String(wrapped.getContentAsByteArray()),
            Duration.ofHours(24));
        wrapped.copyBodyToResponse();
    }
}

// Consumer-side idempotency (Kafka listener decorator)
public abstract class IdempotentKafkaListener<T> {
    private final StringRedisTemplate redis;

    protected abstract void processOnce(T message, String messageId);

    @SuppressWarnings("unused")
    public void onMessage(T message, @Header(KafkaHeaders.RECEIVED_KEY) String messageId) {
        String key = "kafka-idem:" + messageId;
        Boolean isNew = redis.opsForValue().setIfAbsent(key, "1", Duration.ofDays(7));
        if (Boolean.TRUE.equals(isNew)) processOnce(message, messageId);
    }
}
```

---

## `tms-common-security`

```java
// Extracts typed principal from JWT — used in all services
public record TmsPrincipal(
    String userId,
    String username,
    String email,
    List<String> roles,
    String legalEntityId   // from "entity" custom claim set by Keycloak mapper
) {}

@Component
public class TmsPrincipalExtractor {
    public TmsPrincipal extract(Authentication auth) {
        Jwt jwt = (Jwt) auth.getPrincipal();
        return new TmsPrincipal(
            jwt.getSubject(),
            jwt.getClaimAsString("preferred_username"),
            jwt.getClaimAsString("email"),
            jwt.getClaimAsStringList("roles"),
            jwt.getClaimAsString("entity")
        );
    }
}

// Convenience annotation
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("hasRole(#role)")
public @interface RequiresRole {
    String value();
}
```

---

## `tms-common-money`

```java
// Canonical money operations — wraps JSR-354 Moneta
public final class Money {
    private Money() {}

    public static MonetaryAmount of(String amount, String currency) {
        return Monetary.getDefaultAmountFactory()
            .setCurrency(currency)
            .setNumber(new BigDecimal(amount))
            .create();
    }

    public static MonetaryAmount of(BigDecimal amount, String currency) {
        return Monetary.getDefaultAmountFactory()
            .setCurrency(currency)
            .setNumber(amount)
            .create();
    }

    public static BigDecimal toBigDecimal(MonetaryAmount m) {
        return m.getNumber().numberValueExact(BigDecimal.class);
    }

    public static String toApiString(MonetaryAmount m) {
        return toBigDecimal(m).toPlainString();
    }
}

// Jackson module: serialize MonetaryAmount as { "amount": "123.45", "currency": "EUR" }
public class MonetaryAmountModule extends SimpleModule {
    public MonetaryAmountModule() {
        addSerializer(MonetaryAmount.class, new MonetaryAmountSerializer());
        addDeserializer(MonetaryAmount.class, new MonetaryAmountDeserializer());
    }
}
```

This module is auto-configured via Spring Boot auto-configuration in `tms-common-money`. All services pick it up automatically.

---

## `tms-common-messaging`

```java
// Typed Kafka publisher: handles serialization, correlation ID propagation
@Component
public class TmsKafkaPublisher {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public <T> void publish(String topic, String key, T payload) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, payload);
        record.headers().add("correlationId",
            MDC.get("correlationId").getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record);
    }
}

// RabbitMQ command publisher with reply correlation
@Component
public class TmsRabbitPublisher {
    private final RabbitTemplate rabbitTemplate;

    public void publishCommand(String exchange, String routingKey, Object command) {
        rabbitTemplate.convertAndSend(exchange, routingKey, command, msg -> {
            msg.getMessageProperties().setCorrelationId(MDC.get("correlationId"));
            msg.getMessageProperties().setMessageId(UUID.randomUUID().toString());
            return msg;
        });
    }
}
```

---

## `tms-common-test`

```java
// Base class for all integration tests
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class TmsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
        .withDatabaseName("tms_test")
        .withUsername("tms")
        .withPassword("tms");

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.8.0"));

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer(
        DockerImageName.parse("rabbitmq:3.13-management"));

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
        DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}

// Fixed clock for deterministic date tests
@TestConfiguration
public class TestClockConfig {
    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(
            LocalDate.of(2025, 1, 15).atStartOfDay(ZoneOffset.UTC).toInstant(),
            ZoneOffset.UTC
        );
    }
}

// Money assertion helpers
public final class MoneyAssertions {
    public static void assertAmount(MonetaryAmount actual, String expectedAmount, String currency) {
        assertThat(Money.toApiString(actual)).isEqualTo(expectedAmount);
        assertThat(actual.getCurrency().getCurrencyCode()).isEqualTo(currency);
    }
}
```

---

## CI Pipeline — GitHub Actions

```yaml
# .github/workflows/ci.yml (excerpt)
jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - uses: gradle/gradle-build-action@v3

      - name: Build all modules
        run: ./gradlew build -x test --parallel

      - name: Unit tests (all modules)
        run: ./gradlew test --parallel

      - name: Integration tests (requires Docker)
        run: ./gradlew integrationTest

      - name: Avro schema compatibility check
        run: ./gradlew checkSchemaCompatibility
        # Fails if any schema change breaks BACKWARD compatibility

      - name: Spring Cloud Contract verification
        run: ./gradlew contractTest

      - name: SonarQube quality gate
        run: ./gradlew sonar
        # Fails on: double/float for amounts, LocalDate.now(), cross-service DB queries

  publish-images:
    needs: build-and-test
    if: github.ref == 'refs/heads/main'
    steps:
      - name: Publish all service images
        run: ./gradlew jib --parallel
        # Jib builds and pushes without Docker daemon
```

---

## Dependency Rules (enforced by ArchUnit)

```java
// tms-common-test ArchUnit enforcement (runs in each service's test suite)
@AnalyzeClasses(packages = "com.tms")
public class DependencyRulesTest {

    @ArchTest
    static final ArchRule noServiceToServiceDbAccess =
        noClasses().that().resideInAPackage("com.tms..*")
            .should().dependOnClassesThat()
            .resideInAPackage("com.tms..*.repository..")
            .andShould().haveSimpleNameNotContaining("Own");
    // Each service may only import its own repository interfaces.

    @ArchTest
    static final ArchRule noDoubleOrFloatForMoney =
        noFields().that().areDeclaredInClassesThat()
            .resideInAPackage("com.tms..*")
            .should().haveRawType(double.class)
            .orShould().haveRawType(float.class)
            .orShould().haveRawType(Double.class)
            .orShould().haveRawType(Float.class);

    @ArchTest
    static final ArchRule noDirectLocalDateNow =
        noClasses().should().callMethod(LocalDate.class, "now");
    // Must inject Clock and call LocalDate.now(clock) instead.
}
```

---

## Local Development

**Start all infrastructure:**
```bash
docker compose -f docker-compose.infra.yml up -d
```

**`docker-compose.infra.yml` services:**
| Service | Port | Notes |
|---------|------|-------|
| PostgreSQL 17 | 5432 | Single instance; per-service schemas created by Flyway |
| Kafka + KRaft | 9092, 9093 | No ZooKeeper; Schema Registry on 8081 |
| RabbitMQ 3.13 | 5672, 15672 | Management UI; x-delayed-message plugin enabled |
| Redis 7 | 6379 | Single node for dev |
| Keycloak 25 | 8080 | Dev realm pre-configured with test users |
| MinIO | 9000, 9001 | S3-compatible; Console on 9001 |
| OpenSearch 2 | 9200 | Single node for dev |
| Mailpit | 8025, 1025 | SMTP catch-all for notification emails |
| WireMock | 8090 | External service stubs (SWIFT bureau, sanctions screening) |

**Run a single service:**
```bash
./gradlew :tms-payment-hub:bootRun --args='--spring.profiles.active=local'
```

Spring `local` profile overrides: connects to Docker Compose infrastructure, disables Vault Agent (uses plaintext secrets), enables H2 console, enables `/actuator` without auth.
