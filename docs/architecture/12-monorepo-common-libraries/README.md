# 12 — Monorepo & Common Libraries (v2)

## Repository Structure

```
tms/                                    # Maven multi-module root
├── pom.xml                             # root POM: versions, pluginManagement, profiles
│
├── tms-events-schema/                  # Avro schemas + generated Java classes
├── tms-common-audit/
├── tms-common-outbox/
├── tms-common-idempotency/
├── tms-common-security/
├── tms-common-money/
├── tms-common-validation/
├── tms-common-messaging/
├── tms-common-test/
│
├── tms-gateway/
├── tms-config-server/
├── tms-bff/
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
└── tms-ui/                             # Angular SPA — built via frontend-maven-plugin
    ├── package.json
    ├── angular.json
    ├── tsconfig.json
    └── src/
```

---

## Maven Configuration

### Root `pom.xml`

```xml
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.tms</groupId>
  <artifactId>tms-parent</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <modules>
    <!-- Shared libraries — must be first (services depend on them) -->
    <module>tms-events-schema</module>
    <module>tms-common-audit</module>
    <module>tms-common-outbox</module>
    <module>tms-common-idempotency</module>
    <module>tms-common-security</module>
    <module>tms-common-money</module>
    <module>tms-common-validation</module>
    <module>tms-common-messaging</module>
    <module>tms-common-test</module>
    <!-- Infrastructure services -->
    <module>tms-gateway</module>
    <module>tms-config-server</module>
    <module>tms-bff</module>
    <!-- Domain services -->
    <module>tms-payment-hub</module>
    <module>tms-trade</module>
    <module>tms-settlement</module>
    <module>tms-cash-ecf</module>
    <module>tms-cash-bat</module>
    <module>tms-cash-position</module>
    <module>tms-accounting</module>
    <module>tms-risk</module>
    <module>tms-fx-rates</module>
    <module>tms-ihb</module>
    <module>tms-liquidity</module>
    <module>tms-reconciliation</module>
    <module>tms-bank-accounts</module>
    <module>tms-reference-data</module>
    <module>tms-compliance</module>
    <module>tms-confirmation-matching</module>
    <module>tms-rules-engine</module>
    <module>tms-notifications</module>
    <!-- Frontend -->
    <module>tms-ui</module>
  </modules>

  <properties>
    <java.version>21</java.version>
    <spring-boot.version>4.1.0</spring-boot.version>
    <spring-cloud.version>2025.1.2</spring-cloud.version>
    <spring-kafka.version>4.1.0</spring-kafka.version>
    <spring-amqp.version>4.1.0</spring-amqp.version>
    <avro.version>1.12.0</avro.version>
    <confluent.version>7.8.0</confluent.version>
    <moneta.version>1.4.4</moneta.version>
    <testcontainers.version>1.20.0</testcontainers.version>
    <gatling.version>3.12.0</gatling.version>
    <grpc.version>1.68.0</grpc.version>
    <mapstruct.version>1.6.0</mapstruct.version>
    <archunit.version>1.3.0</archunit.version>
  </properties>

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
      <!-- Shared library versions -->
      <dependency>
        <groupId>com.tms</groupId>
        <artifactId>tms-events-schema</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>com.tms</groupId>
        <artifactId>tms-common-audit</artifactId>
        <version>${project.version}</version>
      </dependency>
      <!-- ... repeat for all tms-common-* -->
      <dependency>
        <groupId>org.javamoney</groupId>
        <artifactId>moneta</artifactId>
        <version>${moneta.version}</version>
      </dependency>
      <dependency>
        <groupId>org.apache.avro</groupId>
        <artifactId>avro</artifactId>
        <version>${avro.version}</version>
      </dependency>
      <dependency>
        <groupId>io.confluent</groupId>
        <artifactId>kafka-avro-serializer</artifactId>
        <version>${confluent.version}</version>
      </dependency>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-bom</artifactId>
        <version>${testcontainers.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>com.tngtech.archunit</groupId>
        <artifactId>archunit-junit5</artifactId>
        <version>${archunit.version}</version>
        <scope>test</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <version>3.13.0</version>
          <configuration>
            <release>21</release>
            <compilerArgs>
              <arg>--enable-preview</arg>
            </compilerArgs>
          </configuration>
        </plugin>
        <plugin>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-maven-plugin</artifactId>
          <version>${spring-boot.version}</version>
          <executions>
            <execution>
              <goals><goal>repackage</goal></goals>
            </execution>
          </executions>
        </plugin>
        <plugin>
          <groupId>com.google.cloud.tools</groupId>
          <artifactId>jib-maven-plugin</artifactId>
          <version>3.4.3</version>
          <configuration>
            <from>
              <image>eclipse-temurin:21-jre-alpine</image>
            </from>
            <to>
              <image>ghcr.io/org/tms/${project.artifactId}:${project.version}</image>
            </to>
            <container>
              <jvmFlags>
                <jvmFlag>-XX:+UseZGC</jvmFlag>
                <jvmFlag>-XX:+ZGenerational</jvmFlag>
                <jvmFlag>--enable-preview</jvmFlag>
              </jvmFlags>
              <ports><port>8080</port><port>8081</port></ports>
            </container>
          </configuration>
        </plugin>
        <plugin>
          <groupId>org.apache.avro</groupId>
          <artifactId>avro-maven-plugin</artifactId>
          <version>${avro.version}</version>
          <executions>
            <execution>
              <phase>generate-sources</phase>
              <goals><goal>schema</goal></goals>
              <configuration>
                <sourceDirectory>${project.basedir}/src/main/avro</sourceDirectory>
                <outputDirectory>${project.build.directory}/generated-sources/avro</outputDirectory>
                <createSetters>false</createSetters>   <!-- immutable records -->
              </configuration>
            </execution>
          </executions>
        </plugin>
        <plugin>
          <groupId>org.jacoco</groupId>
          <artifactId>jacoco-maven-plugin</artifactId>
          <version>0.8.12</version>
          <executions>
            <execution>
              <goals><goal>prepare-agent</goal></goals>
            </execution>
            <execution>
              <id>report</id>
              <phase>verify</phase>
              <goals><goal>report</goal></goals>
            </execution>
          </executions>
        </plugin>
        <plugin>
          <groupId>org.flywaydb</groupId>
          <artifactId>flyway-maven-plugin</artifactId>
          <version>10.0.0</version>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

### Service Module `pom.xml` (template — every deployable service)

```xml
<project>
  <parent>
    <groupId>com.tms</groupId>
    <artifactId>tms-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <artifactId>tms-payment-hub</artifactId>
  <packaging>jar</packaging>

  <dependencies>
    <!-- Spring Boot -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.kafka</groupId>
      <artifactId>spring-kafka</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.amqp</groupId>
      <artifactId>spring-rabbit</artifactId>
    </dependency>

    <!-- TMS common libraries (all services get these) -->
    <dependency>
      <groupId>com.tms</groupId>
      <artifactId>tms-events-schema</artifactId>
    </dependency>
    <dependency>
      <groupId>com.tms</groupId>
      <artifactId>tms-common-audit</artifactId>
    </dependency>
    <dependency>
      <groupId>com.tms</groupId>
      <artifactId>tms-common-outbox</artifactId>
    </dependency>
    <dependency>
      <groupId>com.tms</groupId>
      <artifactId>tms-common-idempotency</artifactId>
    </dependency>
    <dependency>
      <groupId>com.tms</groupId>
      <artifactId>tms-common-security</artifactId>
    </dependency>
    <dependency>
      <groupId>com.tms</groupId>
      <artifactId>tms-common-money</artifactId>
    </dependency>
    <dependency>
      <groupId>com.tms</groupId>
      <artifactId>tms-common-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>com.tms</groupId>
      <artifactId>tms-common-messaging</artifactId>
    </dependency>

    <!-- Test -->
    <dependency>
      <groupId>com.tms</groupId>
      <artifactId>tms-common-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>com.google.cloud.tools</groupId>
        <artifactId>jib-maven-plugin</artifactId>
      </plugin>
      <plugin>
        <groupId>org.jacoco</groupId>
        <artifactId>jacoco-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

### `tms-ui` Module `pom.xml` (Angular via `frontend-maven-plugin`)

```xml
<project>
  <parent>
    <groupId>com.tms</groupId>
    <artifactId>tms-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>
  <artifactId>tms-ui</artifactId>
  <packaging>jar</packaging>

  <build>
    <plugins>
      <plugin>
        <groupId>com.github.eirslett</groupId>
        <artifactId>frontend-maven-plugin</artifactId>
        <version>1.15.0</version>
        <configuration>
          <workingDirectory>${project.basedir}</workingDirectory>
          <nodeVersion>v22.0.0</nodeVersion>
          <npmVersion>10.5.0</npmVersion>
        </configuration>
        <executions>
          <execution>
            <id>install-node-and-npm</id>
            <goals><goal>install-node-and-npm</goal></goals>
            <phase>initialize</phase>
          </execution>
          <execution>
            <id>npm-install</id>
            <goals><goal>npm</goal></goals>
            <phase>initialize</phase>
            <configuration><arguments>install</arguments></configuration>
          </execution>
          <execution>
            <id>angular-build</id>
            <goals><goal>npm</goal></goals>
            <phase>compile</phase>
            <configuration>
              <arguments>run build -- --configuration production</arguments>
            </configuration>
          </execution>
          <execution>
            <id>angular-test</id>
            <goals><goal>npm</goal></goals>
            <phase>test</phase>
            <configuration>
              <arguments>run test -- --watch=false --browsers=ChromeHeadless</arguments>
            </configuration>
          </execution>
        </executions>
      </plugin>
      <!-- Package built Angular dist into a JAR for deployment -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-resources-plugin</artifactId>
        <executions>
          <execution>
            <id>copy-angular-dist</id>
            <phase>prepare-package</phase>
            <goals><goal>copy-resources</goal></goals>
            <configuration>
              <outputDirectory>${project.build.outputDirectory}/static</outputDirectory>
              <resources>
                <resource>
                  <directory>${project.basedir}/dist/tms-ui/browser</directory>
                </resource>
              </resources>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

The built Angular `dist/` is packaged into a JAR and served as static resources by the BFF (`tms-bff`), or deployed to a CDN (recommended for production).

---

## `tms-events-schema`

```
tms-events-schema/
├── pom.xml               (applies avro-maven-plugin)
└── src/main/avro/
    ├── common/
    │   ├── MonetaryAmount.avsc
    │   ├── AuditMetadata.avsc
    │   └── LegalEntityContext.avsc
    ├── payment/
    │   ├── PaymentCreated.avsc
    │   ├── PaymentSettled.avsc
    │   └── PaymentFailed.avsc
    ├── cash/
    │   ├── ecf/ExpectedCashFlowCreated.avsc
    │   ├── bat/BankTransactionPosted.avsc
    │   └── position/CashPositionUpdated.avsc
    ├── trade/
    ├── settlement/
    ├── accounting/
    ├── risk/
    ├── fx/
    ├── ihb/
    ├── reconciliation/
    └── confirmation/
```

**`MonetaryAmount.avsc`:**
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

Schema compatibility rule: all schemas registered with `BACKWARD` compatibility in Confluent Schema Registry. CI enforces this via a `mvn exec:java` call to the Schema Registry compatibility check API on every PR.

---

## `tms-common-audit`

```java
// Annotation for automatic audit logging via AOP
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    String action();
    String entityType();
}

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
        auditPublisher.publish(entry);
        return result;
    }
}
```

---

## `tms-common-outbox`

```java
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
        // Debezium CDC reads this insert and publishes to Kafka.
        // Never call KafkaTemplate directly — always go through outbox.
    }
}
```

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

        String cached = redis.opsForValue().get("idem:" + key);
        if (cached != null) {
            res.setStatus(HttpServletResponse.SC_OK);
            res.getWriter().write(cached);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(res);
        chain.doFilter(req, wrapped);
        redis.opsForValue().set("idem:" + key,
            new String(wrapped.getContentAsByteArray()), Duration.ofHours(24));
        wrapped.copyBodyToResponse();
    }
}

public abstract class IdempotentKafkaListener<T> {
    private final StringRedisTemplate redis;

    protected abstract void processOnce(T message, String messageId);

    public void onMessage(T message, @Header(KafkaHeaders.RECEIVED_KEY) String messageId) {
        Boolean isNew = redis.opsForValue()
            .setIfAbsent("kafka-idem:" + messageId, "1", Duration.ofDays(7));
        if (Boolean.TRUE.equals(isNew)) processOnce(message, messageId);
    }
}
```

---

## `tms-common-security`

```java
public record TmsPrincipal(
    String userId,
    String username,
    String email,
    List<String> roles,
    String legalEntityId
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
            jwt.getClaimAsString("legal_entity_id")
        );
    }
}
```

---

## `tms-common-money`

```java
public final class Money {
    private Money() {}

    public static MonetaryAmount of(String amount, String currency) {
        return Monetary.getDefaultAmountFactory()
            .setCurrency(currency)
            .setNumber(new BigDecimal(amount))
            .create();
    }

    public static String toApiString(MonetaryAmount m) {
        return m.getNumber().numberValueExact(BigDecimal.class).toPlainString();
    }
}

// Jackson module — auto-configured by tms-common-money Spring Boot auto-configuration
public class MonetaryAmountModule extends SimpleModule {
    public MonetaryAmountModule() {
        addSerializer(MonetaryAmount.class, new MonetaryAmountSerializer());
        addDeserializer(MonetaryAmount.class, new MonetaryAmountDeserializer());
    }
}
```

---

## `tms-common-test`

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
public abstract class TmsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17").withDatabaseName("tms_test");

    @Container
    static final KafkaContainer kafka =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"));

    @Container
    static final RabbitMQContainer rabbit =
        new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

    @Container
    static final GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

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
# .github/workflows/ci.yml
jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }

      - name: Cache Maven packages
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}

      - name: Build all modules (skip tests)
        run: mvn install -DskipTests --threads 4

      - name: Unit tests
        run: mvn test --threads 4

      - name: Integration tests
        run: mvn verify -P integration-test

      - name: Avro schema compatibility check
        run: mvn exec:java -pl tms-events-schema -Dexec.mainClass=com.tms.schema.CompatibilityCheck

      - name: Spring Cloud Contract verification
        run: mvn spring-cloud-contract:generateStubs spring-cloud-contract:generateTests verify -pl tms-payment-hub

      - name: SonarQube analysis
        run: mvn sonar:sonar -Dsonar.projectKey=tms

  publish-images:
    needs: build-and-test
    if: github.ref == 'refs/heads/main'
    steps:
      - name: Publish all service images via Jib
        run: mvn jib:build --threads 4 -DskipTests
        # Jib builds and pushes without a Docker daemon
```

---

## Dependency Rules (ArchUnit — in `tms-common-test`, runs in every service)

```java
@AnalyzeClasses(packages = "com.tms")
public class DependencyRulesTest {

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

    @ArchTest
    static final ArchRule noServiceToServiceDbAccess =
        noClasses().that().resideInAPackage("com.tms..*")
            .should().dependOnClassesThat()
            .resideInAPackage("com.tms..*.repository..")
            .andShould().haveSimpleNameNotContaining("Own");
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
| PostgreSQL 17 | 5432 | Single instance; per-service schemas created by Flyway on startup |
| Kafka + KRaft | 9092 | No ZooKeeper; Schema Registry on 8081 |
| RabbitMQ 3.13 | 5672, 15672 | Management UI; x-delayed-message plugin enabled |
| Redis 7 | 6379 | Single node for dev |
| Keycloak 26.2 | 8180 | Dev realm pre-configured with test users and roles |
| MinIO | 9000, 9001 | S3-compatible; Console on 9001 |
| OpenSearch 2 | 9200 | Single node for dev |
| Mailpit | 8025, 1025 | SMTP catch-all for notification email testing |
| WireMock | 8090 | External service stubs (SWIFT bureau, sanctions screening) |

**Run a single service:**
```bash
mvn -pl tms-payment-hub spring-boot:run -Dspring-boot.run.profiles=local
```

**Run Angular UI in dev mode:**
```bash
cd tms-ui
npm install
npm start         # ng serve — proxies API calls to local BFF at :8095
```

Spring `local` profile: connects to Docker Compose infrastructure, disables Vault Agent, enables H2 console, enables `/actuator` without auth.
