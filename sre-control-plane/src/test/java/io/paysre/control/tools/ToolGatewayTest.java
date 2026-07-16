package io.paysre.control.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.evidence.Evidence;
import io.paysre.control.evidence.EvidenceRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ToolGatewayTest {

    private static final Instant NOW = Instant.parse("2026-07-16T10:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
    private final InMemoryToolAuditRepository audit = new InMemoryToolAuditRepository();
    private final AtomicInteger ids = new AtomicInteger();

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void rejectsAnUnknownToolAndAuditsTheAttempt() {
        var result = gateway(List.of()).execute(
                "INC-01", "agent-1", "shell", objectMapper.createObjectNode());

        assertThat(result.successful()).isFalse();
        assertThat(result.errorCode()).isEqualTo("UNKNOWN_TOOL");
        assertThat(result.evidenceIds()).isEmpty();
        assertThat(audit.invocations).singleElement()
                .satisfies(invocation -> assertThat(invocation.errorCode())
                        .isEqualTo("UNKNOWN_TOOL"));
    }

    @Test
    void recordsAHandlerTimeoutWithoutCreatingEvidence() {
        var release = new CountDownLatch(1);
        var handler = handler(
                "slow_tool",
                Duration.ofMillis(20),
                1_024,
                input -> {
                    try {
                        release.await();
                        return Map.of("status", "released");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted", exception);
                    }
                });

        var result = gateway(List.of(handler)).execute(
                "INC-01", "agent-1", "slow_tool", objectMapper.createObjectNode());

        assertThat(result.successful()).isFalse();
        assertThat(result.errorCode()).isEqualTo("TOOL_TIMEOUT");
        assertThat(evidence.items).isEmpty();
        assertThat(audit.invocations).hasSize(1);
    }

    @Test
    void rejectsSerializedContentAboveTheDeclaredLimit() {
        var handler = handler(
                "large_tool",
                Duration.ofSeconds(1),
                16,
                input -> Map.of("content", "x".repeat(100)));

        var result = gateway(List.of(handler)).execute(
                "INC-01", "agent-1", "large_tool", objectMapper.createObjectNode());

        assertThat(result.successful()).isFalse();
        assertThat(result.errorCode()).isEqualTo("RESULT_TOO_LARGE");
        assertThat(evidence.items).isEmpty();
    }

    @Test
    void persistsSuccessfulContentWithASha256HashAndReturnsItsEvidenceId() {
        var handler = handler(
                "query_channel_final_state",
                Duration.ofSeconds(1),
                65_536,
                input -> Map.of("paymentId", "P10001", "result", "SUCCESS"));

        var result = gateway(List.of(handler)).execute(
                "INC-01", "agent-1", handler.definition().name(), objectMapper.createObjectNode());

        assertThat(result.successful()).isTrue();
        assertThat(result.evidenceIds()).containsExactly("EVD-1");
        assertThat(evidence.items.values()).singleElement()
                .satisfies(item -> {
                    assertThat(item.incidentId()).isEqualTo("INC-01");
                    assertThat(item.evidenceType()).isEqualTo("CHANNEL_FINAL_STATE");
                    assertThat(item.sha256()).matches("[0-9a-f]{64}");
                    assertThat(item.content().path("result").asText()).isEqualTo("SUCCESS");
                });
        assertThat(audit.invocations).singleElement()
                .satisfies(invocation -> assertThat(invocation.evidenceIds())
                        .containsExactly("EVD-1"));
    }

    @Test
    void rejectsImmediatelyAndAuditsWhenTheWorkerPoolIsExhausted() throws Exception {
        var saturated = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>());
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        saturated.submit(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(occupied.await(1, TimeUnit.SECONDS)).isTrue();
        var handler = handler(
                "read_tool",
                Duration.ofSeconds(1),
                1_024,
                input -> Map.of("status", "ok"));

        try {
            var result = gateway(List.of(handler), saturated).execute(
                    "INC-01", "agent-1", "read_tool", objectMapper.createObjectNode());

            assertThat(result.successful()).isFalse();
            assertThat(result.errorCode()).isEqualTo("TOOL_CAPACITY_EXHAUSTED");
            assertThat(audit.invocations.get(audit.invocations.size() - 1).errorCode())
                    .isEqualTo("TOOL_CAPACITY_EXHAUSTED");
        } finally {
            release.countDown();
            saturated.shutdownNow();
        }
    }

    private ToolGateway gateway(List<ToolHandler<?, ?>> handlers) {
        return gateway(handlers, executor);
    }

    private ToolGateway gateway(List<ToolHandler<?, ?>> handlers, ExecutorService workerPool) {
        return new ToolGateway(
                handlers,
                objectMapper,
                workerPool,
                evidence,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "EVD-" + ids.incrementAndGet());
    }

    private ToolHandler<Map, Map> handler(
            String name,
            Duration timeout,
            int maximumBytes,
            Function<Map, Map> operation) {
        return new ToolHandler<>() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        name,
                        1,
                        "test handler",
                        timeout,
                        maximumBytes,
                        ToolRisk.READ_ONLY);
            }

            @Override
            public Class<Map> inputType() {
                return Map.class;
            }

            @Override
            public Map execute(Map input) {
                return operation.apply(input);
            }
        };
    }

    private static final class InMemoryEvidenceRepository implements EvidenceRepository {

        private final Map<String, Evidence> items = new LinkedHashMap<>();

        @Override
        public Evidence save(Evidence item) {
            items.put(item.evidenceId(), item);
            return item;
        }

        @Override
        public Optional<Evidence> findById(String evidenceId) {
            return Optional.ofNullable(items.get(evidenceId));
        }

        @Override
        public List<Evidence> findByIncidentId(String incidentId) {
            return items.values().stream()
                    .filter(item -> item.incidentId().equals(incidentId))
                    .toList();
        }
    }

    private static final class InMemoryToolAuditRepository implements ToolAuditRepository {

        private final List<ToolInvocation> invocations = new ArrayList<>();

        @Override
        public void record(ToolInvocation invocation) {
            invocations.add(invocation);
        }
    }
}
