# PaySRE Lab Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a testable vertical slice where a simulated payment channel times out but succeeds, the payment enters `UNKNOWN`, an alert creates an incident, and a read-only investigation produces an evidence-backed root-cause result.

**Architecture:** Use a Maven multi-module repository. `payment-service` and `channel-simulator` are independent Spring Boot applications representing the payment data plane; `sre-control-plane` is a modular Spring Boot application containing alert ingestion, incident state, read-only tools, investigation orchestration, and evaluation. Cross-process records live in a Spring-free `platform-contracts` module.

**Tech Stack:** Java 21, Maven, Spring Boot 4.1.0, Spring AI 2.0.0, Spring Web MVC, Spring Data JDBC, PostgreSQL 17, Flyway, Micrometer, OpenTelemetry, JUnit 5, AssertJ, Testcontainers, WireMock, Awaitility, ArchUnit.

## Global Constraints

- The first vertical slice implements only `TIMEOUT_BUT_SUCCESS`; other fault types remain design-only until separate plans are approved.
- All payment data is synthetic; no valid PAN, personal data, live credentials, or real payment endpoints are allowed.
- `platform-contracts` must not depend on Spring.
- The model never receives database, shell, SSH, or Kubernetes access.
- Investigation tools are read-only in this phase.
- A root-cause conclusion is valid only when every referenced evidence ID exists in the same incident.
- Money uses `BigDecimal` plus ISO 4217 `Currency`; `double` is forbidden.
- Fixed seed `20260716` makes fault selection reproducible.
- Every task follows test-first development and ends with a focused commit.
- Spring Boot is pinned to `4.1.0`; Spring AI is pinned through BOM `2.0.0`.
- Before Task 1 execution, run `git init`, create branch `feat/pay-sre-foundation`, and add a Java/Maven/IDE `.gitignore`; the current workspace is not yet a Git repository.

---

## Scope Decomposition

This foundation plan deliberately excludes four independent deliverables, each of which needs its own approved implementation plan after the foundation passes:

1. Full observability stack and custom Grafana dashboards.
2. Live LLM investigation through Spring AI and model comparison.
3. Action Guard, approval, and Runbook execution.
4. React Incident Console and public benchmark leaderboard.

## Locked File Structure

```text
pay-sre-lab/
├── pom.xml
├── platform-contracts/
│   ├── pom.xml
│   └── src/main/java/io/paysre/contracts/
├── payment-system/
│   ├── pom.xml
│   ├── channel-simulator/
│   │   ├── pom.xml
│   │   └── src/{main,test}/java/io/paysre/channel/
│   └── payment-service/
│       ├── pom.xml
│       └── src/{main,test}/java/io/paysre/payment/
├── sre-control-plane/
│   ├── pom.xml
│   └── src/{main,test}/java/io/paysre/control/
├── e2e-tests/
│   ├── pom.xml
│   └── src/test/java/io/paysre/e2e/
└── deploy/
    └── compose.yaml
```

## Task 1: Scaffold the Maven Reactor and Stable Contracts

**Files:**
- Create: `pom.xml`
- Create: `platform-contracts/pom.xml`
- Create: `payment-system/pom.xml`
- Create: `payment-system/channel-simulator/pom.xml`
- Create: `payment-system/payment-service/pom.xml`
- Create: `sre-control-plane/pom.xml`
- Create: `e2e-tests/pom.xml`
- Create: `platform-contracts/src/main/java/io/paysre/contracts/Money.java`
- Create: `platform-contracts/src/main/java/io/paysre/contracts/DomainEventEnvelope.java`
- Create: `platform-contracts/src/main/java/io/paysre/contracts/ChannelResult.java`
- Create: `platform-contracts/src/main/java/io/paysre/contracts/ChannelPaymentRequest.java`
- Create: `platform-contracts/src/main/java/io/paysre/contracts/ChannelPaymentResponse.java`
- Test: `platform-contracts/src/test/java/io/paysre/contracts/MoneyTest.java`

**Interfaces:**
- Produces: `Money`, `DomainEventEnvelope<T>`, `ChannelPaymentRequest`, `ChannelPaymentResponse`, and `ChannelResult` used by all later tasks.
- Consumes: nothing.

- [ ] **Step 1: Write the failing money test**

```java
package io.paysre.contracts;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void rejectsScaleBeyondCurrencyFractionDigits() {
        assertThatThrownBy(() -> new Money(new BigDecimal("1.001"), Currency.getInstance("CNY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount scale exceeds currency fraction digits");
    }
}
```

- [ ] **Step 2: Run the contract test and verify compilation fails**

Run: `mvn -pl platform-contracts -am test -Dtest=MoneyTest`

Expected: FAIL because `Money` does not exist.

- [ ] **Step 3: Create the root reactor**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
  </parent>
  <groupId>io.paysre</groupId>
  <artifactId>pay-sre-lab</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <properties>
    <java.version>21</java.version>
    <spring-ai.version>2.0.0</spring-ai.version>
    <testcontainers.version>1.21.3</testcontainers.version>
  </properties>
  <modules>
    <module>platform-contracts</module>
    <module>payment-system</module>
    <module>sre-control-plane</module>
    <module>e2e-tests</module>
  </modules>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-bom</artifactId>
        <version>${testcontainers.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

Each child POM inherits `io.paysre:pay-sre-lab:0.1.0-SNAPSHOT`. `payment-system/pom.xml` is packaging `pom` with modules `channel-simulator` and `payment-service`. Java modules depend on `platform-contracts`; only Boot applications apply `spring-boot-maven-plugin`.

- [ ] **Step 4: Implement the stable contract types**

```java
package io.paysre.contracts;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (amount.scale() > currency.getDefaultFractionDigits()) {
            throw new IllegalArgumentException("amount scale exceeds currency fraction digits");
        }
    }
}
```

```java
package io.paysre.contracts;

import java.time.Instant;
import java.util.UUID;

public record DomainEventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String aggregateType,
        String aggregateId,
        String traceId,
        String correlationId,
        T payload) {
}
```

```java
package io.paysre.contracts;

public enum ChannelResult {
    SUCCESS,
    FAILED,
    TIMEOUT
}
```

```java
package io.paysre.contracts;

public record ChannelPaymentRequest(
        String requestId,
        String paymentId,
        String channel,
        Money money) {
}
```

```java
package io.paysre.contracts;

import java.time.Instant;

public record ChannelPaymentResponse(
        String requestId,
        String paymentId,
        ChannelResult result,
        String channelCode,
        Instant channelTime) {
}
```

- [ ] **Step 5: Run all contract tests**

Run: `mvn -pl platform-contracts -am test`

Expected: BUILD SUCCESS with `MoneyTest` passing.

- [ ] **Step 6: Commit the scaffold**

```bash
git add pom.xml platform-contracts payment-system sre-control-plane e2e-tests
git commit -m "build: scaffold pay sre lab modules"
```

## Task 2: Implement the Deterministic Channel Simulator

**Files:**
- Create: `payment-system/channel-simulator/src/main/java/io/paysre/channel/ChannelSimulatorApplication.java`
- Create: `payment-system/channel-simulator/src/main/java/io/paysre/channel/FaultType.java`
- Create: `payment-system/channel-simulator/src/main/java/io/paysre/channel/FaultRule.java`
- Create: `payment-system/channel-simulator/src/main/java/io/paysre/channel/FaultRuleRepository.java`
- Create: `payment-system/channel-simulator/src/main/java/io/paysre/channel/InMemoryFaultRuleRepository.java`
- Create: `payment-system/channel-simulator/src/main/java/io/paysre/channel/FaultDecider.java`
- Create: `payment-system/channel-simulator/src/main/java/io/paysre/channel/ChannelTimeoutException.java`
- Create: `payment-system/channel-simulator/src/main/java/io/paysre/channel/ChannelPaymentNotFoundException.java`
- Create: `payment-system/channel-simulator/src/main/java/io/paysre/channel/ChannelSimulationService.java`
- Create: `payment-system/channel-simulator/src/main/java/io/paysre/channel/ChannelPaymentController.java`
- Create: `payment-system/channel-simulator/src/main/java/io/paysre/channel/FaultAdminController.java`
- Test: `payment-system/channel-simulator/src/test/java/io/paysre/channel/FaultDeciderTest.java`
- Test: `payment-system/channel-simulator/src/test/java/io/paysre/channel/ChannelSimulationServiceTest.java`

**Interfaces:**
- Consumes: `ChannelPaymentRequest` and produces `ChannelPaymentResponse` from Task 1.
- Produces: `POST /api/channel/payments`, `GET /api/channel/payments/{paymentId}`, and `PUT /api/admin/faults/{channel}`.

- [ ] **Step 1: Write a failing deterministic decision test**

```java
package io.paysre.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FaultDeciderTest {
    @Test
    void returnsTheSameDecisionForTheSamePaymentRuleAndSeed() {
        var rule = new FaultRule("CHANNEL_A", FaultType.TIMEOUT_BUT_SUCCESS,
                new BigDecimal("0.30"), Instant.EPOCH, Instant.parse("2099-01-01T00:00:00Z"), 20260716L);
        var decider = new FaultDecider();

        assertThat(decider.applies("P10001", rule))
                .isEqualTo(decider.applies("P10001", rule));
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -pl payment-system/channel-simulator -am test -Dtest=FaultDeciderTest`

Expected: FAIL because fault types do not exist.

- [ ] **Step 3: Implement the fault model and deterministic decider**

```java
package io.paysre.channel;

public enum FaultType {
    NONE,
    TIMEOUT_BUT_SUCCESS
}
```

```java
package io.paysre.channel;

import java.math.BigDecimal;
import java.time.Instant;

public record FaultRule(
        String channel,
        FaultType type,
        BigDecimal probability,
        Instant activeFrom,
        Instant activeUntil,
        long randomSeed) {
    public FaultRule {
        if (probability.signum() < 0 || probability.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("probability must be between zero and one");
        }
    }
}
```

```java
package io.paysre.channel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class FaultDecider {
    public boolean applies(String paymentId, FaultRule rule) {
        byte[] digest = sha256(paymentId + ":" + rule.channel() + ":" + rule.randomSeed());
        long positive = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(digest).getInt());
        BigDecimal sample = BigDecimal.valueOf(positive)
                .divide(BigDecimal.valueOf(1L << 32), 12, RoundingMode.HALF_UP);
        return sample.compareTo(rule.probability()) < 0;
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
```

```java
package io.paysre.channel;

import java.time.Instant;
import java.util.Optional;

public interface FaultRuleRepository {
    Optional<FaultRule> findActive(String channel, Instant now);
    void replace(FaultRule rule);
}
```

```java
package io.paysre.channel;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryFaultRuleRepository implements FaultRuleRepository {
    private final Map<String, FaultRule> rules = new ConcurrentHashMap<>();

    @Override
    public Optional<FaultRule> findActive(String channel, Instant now) {
        return Optional.ofNullable(rules.get(channel))
                .filter(rule -> !now.isBefore(rule.activeFrom()))
                .filter(rule -> now.isBefore(rule.activeUntil()));
    }

    @Override
    public void replace(FaultRule rule) {
        if (!rule.activeUntil().isAfter(rule.activeFrom())) {
            throw new IllegalArgumentException("activeUntil must be after activeFrom");
        }
        rules.put(rule.channel(), rule);
    }
}
```

- [ ] **Step 4: Implement final-state storage and timeout behavior**

`ChannelSimulationService.pay` must save `SUCCESS` before throwing a `ChannelTimeoutException` when the rule applies. `query` must then return the stored successful response.

```java
package io.paysre.channel;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelPaymentResponse;
import io.paysre.contracts.ChannelResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ChannelSimulationService {
    private final FaultRuleRepository rules;
    private final FaultDecider decider;
    private final Clock clock;
    private final Map<String, ChannelPaymentResponse> finalStates = new ConcurrentHashMap<>();

    public ChannelSimulationService(FaultRuleRepository rules, FaultDecider decider, Clock clock) {
        this.rules = rules;
        this.decider = decider;
        this.clock = clock;
    }

    public ChannelPaymentResponse pay(ChannelPaymentRequest request) {
        var response = new ChannelPaymentResponse(request.requestId(), request.paymentId(),
                ChannelResult.SUCCESS, "00", Instant.now(clock));
        var rule = rules.findActive(request.channel(), Instant.now(clock));
        if (rule.isPresent() && rule.get().type() == FaultType.TIMEOUT_BUT_SUCCESS
                && decider.applies(request.paymentId(), rule.get())) {
            finalStates.put(request.paymentId(), response);
            throw new ChannelTimeoutException(request.paymentId());
        }
        finalStates.put(request.paymentId(), response);
        return response;
    }

    public ChannelPaymentResponse query(String paymentId) {
        var response = finalStates.get(paymentId);
        if (response == null) {
            throw new ChannelPaymentNotFoundException(paymentId);
        }
        return response;
    }
}
```

```java
package io.paysre.channel;

public final class ChannelTimeoutException extends RuntimeException {
    public ChannelTimeoutException(String paymentId) {
        super("channel timeout for payment " + paymentId);
    }
}
```

```java
package io.paysre.channel;

public final class ChannelPaymentNotFoundException extends RuntimeException {
    public ChannelPaymentNotFoundException(String paymentId) {
        super("channel payment not found: " + paymentId);
    }
}
```

- [ ] **Step 5: Test that timeout still leaves a successful final state**

Create a test with an always-applying probability of `1.00`, assert `pay` throws `ChannelTimeoutException`, and assert `query(paymentId).result()` equals `SUCCESS`.

Run: `mvn -pl payment-system/channel-simulator -am test`

Expected: BUILD SUCCESS.

- [ ] **Step 6: Add HTTP endpoints and an exception mapping**

```java
package io.paysre.channel;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelPaymentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/channel/payments")
public final class ChannelPaymentController {
    private final ChannelSimulationService service;

    public ChannelPaymentController(ChannelSimulationService service) {
        this.service = service;
    }

    @PostMapping
    ChannelPaymentResponse pay(@RequestBody ChannelPaymentRequest request) {
        return service.pay(request);
    }

    @GetMapping("/{paymentId}")
    ChannelPaymentResponse query(@PathVariable String paymentId) {
        return service.query(paymentId);
    }

    @ExceptionHandler(ChannelTimeoutException.class)
    ProblemDetail timeout(ChannelTimeoutException exception) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT, exception.getMessage());
        detail.setProperty("code", "CHANNEL_TIMEOUT");
        return detail;
    }
}
```

```java
package io.paysre.channel;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/faults")
public final class FaultAdminController {
    private final FaultRuleRepository repository;

    public FaultAdminController(FaultRuleRepository repository) {
        this.repository = repository;
    }

    @PutMapping("/{channel}")
    void replace(@PathVariable String channel, @RequestBody FaultRule request) {
        if (!channel.equals(request.channel())) {
            throw new IllegalArgumentException("path channel must match rule channel");
        }
        repository.replace(request);
    }
}
```

- [ ] **Step 7: Commit the simulator**

```bash
git add payment-system/channel-simulator
git commit -m "feat: add deterministic payment channel simulator"
```

## Task 3: Implement Payment Acceptance and the UNKNOWN State

**Files:**
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/PaymentServiceApplication.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/domain/PaymentStatus.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/domain/PaymentOrder.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/domain/PaymentEvent.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/application/AcceptPaymentCommand.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/application/ChannelClient.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/application/ChannelCallTimeoutException.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/application/PaymentIdGenerator.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/application/PaymentRepository.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/application/PaymentApplicationService.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/adapter/http/HttpChannelClient.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/adapter/http/PaymentController.java`
- Create: `payment-system/payment-service/src/main/resources/db/migration/V1__payment_schema.sql`
- Test: `payment-system/payment-service/src/test/java/io/paysre/payment/domain/PaymentOrderTest.java`
- Test: `payment-system/payment-service/src/test/java/io/paysre/payment/application/PaymentApplicationServiceTest.java`

**Interfaces:**
- Consumes: channel endpoints from Task 2.
- Produces: `POST /api/payments`, `GET /api/payments/{paymentId}`, and `GET /api/payments/{paymentId}/timeline`.

- [ ] **Step 1: Write failing state transition tests**

```java
package io.paysre.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paysre.contracts.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class PaymentOrderTest {
    @Test
    void channelTimeoutMovesProcessingPaymentToUnknown() {
        var payment = PaymentOrder.create("P10001", "O10001", "M001",
                new Money(new BigDecimal("10.00"), Currency.getInstance("CNY")), Instant.EPOCH);
        payment.start("CHANNEL_A", "route-v1", Instant.EPOCH);
        payment.markUnknown("CHANNEL_TIMEOUT", Instant.EPOCH.plusSeconds(1));
        assertThat(payment.status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void cannotDirectlyMarkAnInitialPaymentSuccessful() {
        var payment = PaymentOrder.create("P10002", "O10002", "M001",
                new Money(new BigDecimal("10.00"), Currency.getInstance("CNY")), Instant.EPOCH);
        assertThatThrownBy(() -> payment.markSuccess("00", Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run the domain test and verify it fails**

Run: `mvn -pl payment-system/payment-service -am test -Dtest=PaymentOrderTest`

Expected: FAIL because the aggregate is missing.

- [ ] **Step 3: Implement the aggregate state machine**

```java
package io.paysre.payment.domain;

public enum PaymentStatus {
    INIT,
    PROCESSING,
    SUCCESS,
    FAILED,
    UNKNOWN
}
```

```java
package io.paysre.payment.domain;

import java.time.Instant;

public record PaymentEvent(
        Instant eventTime,
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        String source,
        String reasonCode) {
}
```

```java
package io.paysre.payment.domain;

import io.paysre.contracts.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class PaymentOrder {
    private final String paymentId;
    private final String orderId;
    private final String merchantId;
    private final Money money;
    private final List<PaymentEvent> events = new ArrayList<>();
    private PaymentStatus status;
    private String channel;
    private String routeVersion;

    private PaymentOrder(String paymentId, String orderId, String merchantId, Money money) {
        this.paymentId = paymentId;
        this.orderId = orderId;
        this.merchantId = merchantId;
        this.money = money;
        this.status = PaymentStatus.INIT;
    }

    public static PaymentOrder create(
            String paymentId, String orderId, String merchantId, Money money, Instant now) {
        var payment = new PaymentOrder(paymentId, orderId, merchantId, money);
        payment.events.add(new PaymentEvent(now, null, PaymentStatus.INIT, "PAYMENT_SERVICE", "CREATED"));
        return payment;
    }

    public void start(String channel, String routeVersion, Instant now) {
        require(PaymentStatus.INIT);
        this.channel = channel;
        this.routeVersion = routeVersion;
        transition(PaymentStatus.PROCESSING, "PAYMENT_SERVICE", "CHANNEL_SELECTED", now);
    }

    public void markSuccess(String channelCode, Instant now) {
        require(PaymentStatus.PROCESSING);
        transition(PaymentStatus.SUCCESS, "CHANNEL_RESPONSE", channelCode, now);
    }

    public void markFailed(String channelCode, Instant now) {
        require(PaymentStatus.PROCESSING);
        transition(PaymentStatus.FAILED, "CHANNEL_RESPONSE", channelCode, now);
    }

    public void markUnknown(String reasonCode, Instant now) {
        require(PaymentStatus.PROCESSING);
        transition(PaymentStatus.UNKNOWN, "CHANNEL_CALL", reasonCode, now);
    }

    public void confirmUnknownSuccess(String channelCode, Instant now) {
        require(PaymentStatus.UNKNOWN);
        transition(PaymentStatus.SUCCESS, "CHANNEL_QUERY", channelCode, now);
    }

    private void require(PaymentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + status);
        }
    }

    private void transition(PaymentStatus target, String source, String reason, Instant now) {
        var previous = status;
        status = target;
        events.add(new PaymentEvent(now, previous, target, source, reason));
    }

    public String paymentId() { return paymentId; }
    public String orderId() { return orderId; }
    public String merchantId() { return merchantId; }
    public Money money() { return money; }
    public PaymentStatus status() { return status; }
    public String channel() { return channel; }
    public String routeVersion() { return routeVersion; }
    public List<PaymentEvent> events() { return List.copyOf(events); }
}
```

- [ ] **Step 4: Write a failing application test for channel timeout**

Use an in-memory `PaymentRepository` and a `ChannelClient` lambda that throws `ChannelCallTimeoutException`. Assert the saved payment is `UNKNOWN` and its final timeline event has reason `CHANNEL_TIMEOUT`.

- [ ] **Step 5: Implement `PaymentApplicationService.accept`**

```java
package io.paysre.payment.application;

import io.paysre.contracts.Money;

public record AcceptPaymentCommand(
        String orderId,
        String merchantId,
        String idempotencyKey,
        Money money) {
}
```

```java
package io.paysre.payment.application;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelPaymentResponse;

public interface ChannelClient {
    ChannelPaymentResponse pay(ChannelPaymentRequest request);
}
```

```java
package io.paysre.payment.application;

public final class ChannelCallTimeoutException extends RuntimeException {
    public ChannelCallTimeoutException(String paymentId) {
        super("channel timeout for payment " + paymentId);
    }
}
```

```java
package io.paysre.payment.application;

public interface PaymentIdGenerator {
    String nextPaymentId();
}
```

```java
package io.paysre.payment.application;

import io.paysre.payment.domain.PaymentOrder;
import java.util.Optional;

public interface PaymentRepository {
    Optional<PaymentOrder> findByMerchantAndIdempotencyKey(String merchantId, String idempotencyKey);
    PaymentOrder save(PaymentOrder payment, String idempotencyKey);
}
```

```java
package io.paysre.payment.application;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelResult;
import io.paysre.payment.domain.PaymentOrder;
import java.time.Clock;
import java.util.UUID;

public final class PaymentApplicationService {
    private final PaymentRepository repository;
    private final ChannelClient channelClient;
    private final PaymentIdGenerator ids;
    private final Clock clock;

    public PaymentApplicationService(
            PaymentRepository repository,
            ChannelClient channelClient,
            PaymentIdGenerator ids,
            Clock clock) {
        this.repository = repository;
        this.channelClient = channelClient;
        this.ids = ids;
        this.clock = clock;
    }

    public PaymentOrder accept(AcceptPaymentCommand command) {
        return repository.findByMerchantAndIdempotencyKey(command.merchantId(), command.idempotencyKey())
                .orElseGet(() -> createAndInvoke(command));
    }

    private PaymentOrder createAndInvoke(AcceptPaymentCommand command) {
        var now = clock.instant();
        var payment = PaymentOrder.create(ids.nextPaymentId(), command.orderId(), command.merchantId(),
                command.money(), now);
        payment.start("CHANNEL_A", "route-v1", now);
        repository.save(payment, command.idempotencyKey());
        try {
            var response = channelClient.pay(toChannelRequest(payment));
            if (response.result() == ChannelResult.SUCCESS) {
                payment.markSuccess(response.channelCode(), clock.instant());
            } else {
                payment.markFailed(response.channelCode(), clock.instant());
            }
        } catch (ChannelCallTimeoutException exception) {
            payment.markUnknown("CHANNEL_TIMEOUT", clock.instant());
        }
        return repository.save(payment, command.idempotencyKey());
    }

    private ChannelPaymentRequest toChannelRequest(PaymentOrder payment) {
        return new ChannelPaymentRequest(
                UUID.randomUUID().toString(),
                payment.paymentId(),
                payment.channel(),
                payment.money());
    }
}
```

- [ ] **Step 6: Add Flyway schema and JDBC repository**

Use the `payment_order` and `payment_event` definitions from the design specification. Repository `save` updates the aggregate with optimistic version and appends only unpersisted events in the same transaction. Duplicate `(merchant_id, idempotency_key)` returns the existing payment.

- [ ] **Step 7: Add REST APIs and run tests**

Run: `mvn -pl payment-system/payment-service -am test`

Expected: BUILD SUCCESS; tests cover timeout, idempotent retry, illegal state transition, and timeline ordering.

- [ ] **Step 8: Commit payment acceptance**

```bash
git add payment-system/payment-service
git commit -m "feat: model unknown payment after channel timeout"
```

## Task 4: Add Business Telemetry and a Reconciliation Query

**Files:**
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/observability/PaymentMetrics.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/observability/PaymentTelemetry.java`
- Create: `payment-system/payment-service/src/main/java/io/paysre/payment/application/UnknownPaymentQueryService.java`
- Modify: `payment-system/payment-service/src/main/java/io/paysre/payment/application/PaymentApplicationService.java`
- Modify: `payment-system/payment-service/src/main/java/io/paysre/payment/adapter/http/PaymentController.java`
- Test: `payment-system/payment-service/src/test/java/io/paysre/payment/observability/PaymentMetricsTest.java`

**Interfaces:**
- Produces: `payment_attempt_outcome_total`, `payment_unknown_current`, and `GET /api/payments?status=UNKNOWN&from=...&to=...`.
- Consumes: payment transitions from Task 3.

- [ ] **Step 1: Write a failing metrics test**

Use `SimpleMeterRegistry`, call `recordTransition("CHANNEL_A", PROCESSING, UNKNOWN)`, and assert counter `payment_attempt_outcome_total` with tags `channel=CHANNEL_A,status=UNKNOWN` is `1.0`.

- [ ] **Step 2: Implement bounded-cardinality metrics**

```java
package io.paysre.payment.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.paysre.payment.domain.PaymentStatus;

public final class PaymentMetrics {
    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordTransition(String channel, PaymentStatus from, PaymentStatus to) {
        registry.counter("payment_state_transition_total",
                "channel", channel,
                "from", from.name(),
                "to", to.name()).increment();
        if (to == PaymentStatus.UNKNOWN || to == PaymentStatus.SUCCESS || to == PaymentStatus.FAILED) {
            registry.counter("payment_attempt_outcome_total",
                    "channel", channel,
                    "status", to.name()).increment();
        }
    }
}
```

Do not add `paymentId`, `orderId`, or `merchantId` as metric tags.

- [ ] **Step 3: Add a manual business span**

`PaymentTelemetry.inSpan("payment.channel.invoke", paymentId, channel, supplier)` uses OpenTelemetry API and adds `payment.id` and `payment.channel` as span attributes. It records exception and error status before rethrowing.

- [ ] **Step 4: Add the UNKNOWN query API**

Return only payment ID, order ID, amount, currency, channel, status, and update time. Enforce a maximum time range of 24 hours and a maximum page size of 200.

- [ ] **Step 5: Run telemetry tests**

Run: `mvn -pl payment-system/payment-service -am test`

Expected: BUILD SUCCESS and no meter contains a `paymentId` tag.

- [ ] **Step 6: Commit telemetry**

```bash
git add payment-system/payment-service
git commit -m "feat: expose payment business telemetry"
```

## Task 5: Create Alert Ingestion and Incident Persistence

**Files:**
- Create: `sre-control-plane/src/main/java/io/paysre/control/ControlPlaneApplication.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/alerting/AlertSignal.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/alerting/Severity.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/alerting/AlertmanagerWebhook.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/alerting/AlertWebhookController.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/incident/Incident.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/incident/IncidentStatus.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/incident/IncidentRepository.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/incident/IncidentApplicationService.java`
- Create: `sre-control-plane/src/main/resources/db/migration/V1__incident_schema.sql`
- Test: `sre-control-plane/src/test/java/io/paysre/control/incident/IncidentApplicationServiceTest.java`

**Interfaces:**
- Consumes: normalized alert webhook.
- Produces: `POST /api/alerts/alertmanager`, `GET /api/incidents/{incidentId}`, and incident status `DETECTED`.

- [ ] **Step 1: Write a failing alert aggregation test**

Given two alerts with the same service, signal, channel, and five-minute window, assert the service returns the same incident ID and stores two alert IDs.

- [ ] **Step 2: Implement alert and incident records**

```java
package io.paysre.control.alerting;

public enum Severity {
    INFO,
    WARNING,
    HIGH,
    CRITICAL
}
```

```java
package io.paysre.control.alerting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record AlertSignal(
        String alertId,
        String source,
        String service,
        String signalName,
        Severity severity,
        Instant startsAt,
        Map<String, String> dimensions,
        BigDecimal observedValue,
        BigDecimal threshold) {

    public String aggregateKey() {
        return service + ":" + signalName + ":" + dimensions.getOrDefault("channel", "ALL");
    }
}
```

`Incident` starts as `DETECTED`, has an optimistic version, and accepts new alerts without resetting `detectedAt`.

- [ ] **Step 3: Implement five-minute aggregation**

`IncidentApplicationService.ingest` queries an open incident by aggregate key with `detectedAt >= alert.startsAt() - PT5M`; otherwise it creates `INC-` plus a ULID. The transaction inserts the alert association and incident timeline entry.

- [ ] **Step 4: Add schema and webhook mapping**

Create `incident`, `incident_alert`, and `incident_timeline` tables. Reject webhooks missing `service`, `alertname`, or `startsAt` with HTTP 400.

- [ ] **Step 5: Run incident tests**

Run: `mvn -pl sre-control-plane -am test`

Expected: BUILD SUCCESS; aggregation, new-window creation, and malformed webhook tests pass.

- [ ] **Step 6: Commit incident ingestion**

```bash
git add sre-control-plane
git commit -m "feat: create incidents from payment alerts"
```

## Task 6: Implement the Read-Only Tool Gateway and Evidence Store

**Files:**
- Create: `sre-control-plane/src/main/java/io/paysre/control/tools/ToolDefinition.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/tools/ToolGateway.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/tools/ToolHandler.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/tools/ToolRisk.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/tools/ToolResult.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/tools/GetPaymentTimelineTool.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/tools/QueryChannelFinalStateTool.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/tools/CalculateIncidentImpactTool.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/evidence/Evidence.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/evidence/EvidenceRepository.java`
- Modify: `sre-control-plane/src/main/resources/db/migration/V1__incident_schema.sql`
- Test: `sre-control-plane/src/test/java/io/paysre/control/tools/ToolGatewayTest.java`

**Interfaces:**
- Consumes: payment and channel query endpoints from Tasks 2–4.
- Produces: `ToolResult` containing one or more persisted Evidence IDs.

- [ ] **Step 1: Write failing gateway tests**

Tests must prove that an unknown tool is rejected, a handler timeout is recorded, result content over 64 KiB is rejected, and successful content is stored with a SHA-256 hash.

- [ ] **Step 2: Define the internal tool contract**

```java
package io.paysre.control.tools;

public enum ToolRisk {
    READ_ONLY
}
```

```java
package io.paysre.control.tools;

import java.time.Duration;
import java.util.List;

public record ToolResult(
        String toolName,
        boolean successful,
        List<String> evidenceIds,
        Duration duration,
        String errorCode) {
}
```

```java
package io.paysre.control.tools;

import java.time.Duration;

public interface ToolHandler<I, O> {
    ToolDefinition definition();
    Class<I> inputType();
    O execute(I input);
}

public record ToolDefinition(
        String name,
        int version,
        String description,
        Duration timeout,
        int maximumResultBytes,
        ToolRisk risk) {
}
```

All foundation handlers use `ToolRisk.READ_ONLY`.

- [ ] **Step 3: Implement gateway validation and persistence**

`ToolGateway.execute(incidentId, agentId, toolName, JsonNode arguments)` validates input using Jackson, runs the handler on a bounded executor with its declared timeout, serializes the result, enforces the size limit, stores Evidence with SHA-256, and writes a `tool_invocation` audit row.

- [ ] **Step 4: Implement the three read-only tools**

- `get_payment_timeline(paymentId)` returns ordered payment events.
- `query_channel_final_state(paymentId)` returns channel result and channel timestamp.
- `calculate_incident_impact(channel, from, to)` returns count, total amount, and currency; it rejects mixed currencies instead of silently summing them.

- [ ] **Step 5: Run tool tests**

Run: `mvn -pl sre-control-plane -am test -Dtest=ToolGatewayTest`

Expected: BUILD SUCCESS; every successful invocation has Evidence and audit rows.

- [ ] **Step 6: Commit tools and evidence**

```bash
git add sre-control-plane
git commit -m "feat: add audited read only investigation tools"
```

## Task 7: Implement a Deterministic Investigation Orchestrator

**Files:**
- Create: `sre-control-plane/src/main/java/io/paysre/control/investigation/RootCauseCode.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/investigation/InvestigationDecision.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/investigation/InvestigationConclusion.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/investigation/InvestigationModel.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/investigation/StubInvestigationModel.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/investigation/InvestigationOrchestrator.java`
- Create: `sre-control-plane/src/main/java/io/paysre/control/investigation/ConclusionValidator.java`
- Test: `sre-control-plane/src/test/java/io/paysre/control/investigation/InvestigationOrchestratorTest.java`

**Interfaces:**
- Consumes: Incident Core and Tool Gateway.
- Produces: a persisted `InvestigationConclusion` with root cause `CHANNEL_TIMEOUT_RESPONSE_LOST`.

- [ ] **Step 1: Write a failing orchestration test**

The Stub model first requests `get_payment_timeline`, then `query_channel_final_state`, then `calculate_incident_impact`, and finally concludes. Assert the exact tool order, three or more Evidence IDs, and incident state `MITIGATION_PROPOSED`.

- [ ] **Step 2: Define decisions and conclusion**

```java
package io.paysre.control.investigation;

public enum RootCauseCode {
    CHANNEL_TIMEOUT_RESPONSE_LOST
}
```

```java
package io.paysre.control.investigation;

import com.fasterxml.jackson.databind.JsonNode;

public sealed interface InvestigationDecision {
    record CallTool(String toolName, JsonNode arguments) implements InvestigationDecision {}
    record Conclude(InvestigationConclusion conclusion) implements InvestigationDecision {}
    record Escalate(String reason) implements InvestigationDecision {}
}
```

```java
package io.paysre.control.investigation;

import io.paysre.contracts.Money;
import java.math.BigDecimal;
import java.util.List;

public record InvestigationConclusion(
        String incidentId,
        RootCauseCode rootCause,
        BigDecimal confidence,
        List<String> evidenceIds,
        long affectedPaymentCount,
        Money affectedAmount,
        String recommendedRunbook,
        boolean requiresHumanReview) {
}
```

- [ ] **Step 3: Implement the bounded loop**

The orchestrator performs at most 12 tool calls, stops after three decisions without new Evidence, uses a 120-second total deadline, and changes the incident to `NEEDS_HUMAN` on limit exhaustion.

- [ ] **Step 4: Implement deterministic conclusion validation**

Validator rules:

1. `incidentId` matches the current incident.
2. Every Evidence ID exists and belongs to that incident.
3. `affectedPaymentCount` and amount match an impact Evidence.
4. Confidence is between zero and one.
5. `recommendedRunbook` is exactly `query-and-sync-unknown-payments` for this root cause.
6. `requiresHumanReview` is true.

- [ ] **Step 5: Run orchestration tests**

Run: `mvn -pl sre-control-plane -am test -Dtest=InvestigationOrchestratorTest`

Expected: BUILD SUCCESS; invalid evidence references and fabricated impact values are rejected.

- [ ] **Step 6: Commit deterministic investigation**

```bash
git add sre-control-plane
git commit -m "feat: investigate incidents with evidence backed decisions"
```

## Task 8: Add the Ground-Truth Scenario and End-to-End Test

**Files:**
- Create: `fault-scenarios/channel-timeout-but-success-v1.yaml`
- Create: `e2e-tests/src/test/java/io/paysre/e2e/ChannelTimeoutButSuccessE2ETest.java`
- Create: `e2e-tests/src/test/java/io/paysre/e2e/ScenarioGroundTruth.java`
- Create: `e2e-tests/src/test/java/io/paysre/e2e/ScenarioEvaluator.java`
- Create: `deploy/compose.yaml`
- Create: `README.md`

**Interfaces:**
- Consumes: all services from Tasks 1–7.
- Produces: one repeatable, scored end-to-end scenario.

- [ ] **Step 1: Add the ground-truth scenario**

```yaml
id: channel-timeout-but-success-v1
seed: 20260716
fault:
  channel: CHANNEL_A
  type: TIMEOUT_BUT_SUCCESS
  probability: 1.00
  activeFor: PT2M
traffic:
  payments: 5
  amount: "10.00"
  currency: CNY
expected:
  rootCause: CHANNEL_TIMEOUT_RESPONSE_LOST
  minimumEvidenceCount: 3
  requiredEvidenceTypes:
    - PAYMENT_TIMELINE
    - CHANNEL_FINAL_STATE
    - INCIDENT_IMPACT
  recommendedRunbook: query-and-sync-unknown-payments
  requiresHumanReview: true
```

- [ ] **Step 2: Write the failing end-to-end test**

The test starts PostgreSQL and the three applications through Testcontainers, installs the fault, creates five payments, waits until all five are `UNKNOWN`, submits an alert, starts the investigation, and asserts the result against `ScenarioGroundTruth`.

- [ ] **Step 3: Implement the evaluator**

```java
public record ScenarioScore(
        boolean rootCauseCorrect,
        BigDecimal evidenceRecall,
        boolean runbookCorrect,
        boolean humanReviewCorrect) {

    public boolean passed() {
        return rootCauseCorrect
                && evidenceRecall.compareTo(BigDecimal.ONE) == 0
                && runbookCorrect
                && humanReviewCorrect;
    }
}
```

Evidence recall equals matched required evidence types divided by total required evidence types, using scale four and `HALF_UP` rounding.

- [ ] **Step 4: Add local Compose services**

`deploy/compose.yaml` starts PostgreSQL databases, channel simulator, payment service, and SRE control plane on a dedicated `pay-sre` network. Health checks use each Boot Actuator `/actuator/health/readiness` endpoint. No secrets are committed; synthetic defaults are passed through Compose environment values.

- [ ] **Step 5: Run the vertical slice**

Run: `mvn clean verify`

Expected: BUILD SUCCESS; `ChannelTimeoutButSuccessE2ETest` reports `ScenarioScore.passed() == true`.

- [ ] **Step 6: Run the local demonstration**

Run: `docker compose -f deploy/compose.yaml up --build`

Expected: all application health checks become healthy; five test payments become `UNKNOWN`; the created incident concludes `CHANNEL_TIMEOUT_RESPONSE_LOST` with at least three Evidence IDs.

- [ ] **Step 7: Document the demonstration and safety boundary**

README must include architecture, prerequisites, one-command start, scenario trigger, expected result, module boundaries, synthetic-data statement, and a warning that the project must not be connected directly to production payment systems.

- [ ] **Step 8: Commit the vertical slice**

```bash
git add fault-scenarios e2e-tests deploy README.md
git commit -m "test: verify timeout but success incident end to end"
```

## Final Verification

- [ ] Run `mvn clean verify` and confirm all unit, integration, architecture, and end-to-end tests pass.
- [ ] Run `docker compose -f deploy/compose.yaml config` and confirm the Compose file is valid.
- [ ] Run `docker compose -f deploy/compose.yaml up --build` and wait for all health checks.
- [ ] Trigger the scenario twice and confirm the same five payments receive the same fault decision for seed `20260716`.
- [ ] Confirm the investigation contains only read-only tool invocations.
- [ ] Confirm every conclusion Evidence ID resolves to Evidence in the same incident.
- [ ] Confirm no Prometheus meter uses payment ID, order ID, merchant ID, or trace ID as a label.
- [ ] Confirm repository search finds no valid payment credentials, private keys, or personal information.
- [ ] Stop the environment with `docker compose -f deploy/compose.yaml down -v`.

## Subsequent Plans

After this foundation is reviewed and verified, write separate plans in this order:

1. `pay-sre-observability-stack` — OpenTelemetry Collector, Prometheus, Loki, Tempo, Grafana, Alertmanager, and dashboards.
2. `pay-sre-live-investigation-model` — Spring AI model integration, structured outputs, replay, prompt versioning, and model evaluation.
3. `pay-sre-action-guard-runbooks` — approval hashes, risk policy, Runbook state machine, compensation, and audit.
4. `pay-sre-incident-console` — incident timeline, evidence, hypotheses, impact, approval, and live progress UI.
5. `pay-sre-scenario-library` — callback loss, duplicate messages, code mapping errors, routing faults, accounting lag, and notification storms.
