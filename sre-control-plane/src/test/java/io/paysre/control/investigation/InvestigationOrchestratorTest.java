package io.paysre.control.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.contracts.Money;
import io.paysre.control.alerting.AlertSignal;
import io.paysre.control.evidence.Evidence;
import io.paysre.control.evidence.EvidenceRepository;
import io.paysre.control.incident.Incident;
import io.paysre.control.incident.IncidentRepository;
import io.paysre.control.incident.IncidentStatus;
import io.paysre.control.tools.ToolAuditRepository;
import io.paysre.control.tools.ToolDefinition;
import io.paysre.control.tools.ToolGateway;
import io.paysre.control.tools.ToolHandler;
import io.paysre.control.tools.ToolInvocation;
import io.paysre.control.tools.ToolRisk;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvestigationOrchestratorTest {

    private static final Instant NOW = Instant.parse("2026-07-16T10:00:00Z");
    private static final String INCIDENT_ID = "INC-01";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final InMemoryEvidenceRepository evidence = new InMemoryEvidenceRepository();
    private final InMemoryIncidentRepository incidents = new InMemoryIncidentRepository();
    private final InMemoryConclusionRepository conclusions = new InMemoryConclusionRepository();
    private final InMemoryAuditRepository audit = new InMemoryAuditRepository();
    private final AtomicInteger evidenceIds = new AtomicInteger();

    @BeforeEach
    void createIncident() {
        incidents.add(Incident.detected(
                INCIDENT_ID,
                "payment-service:payment_unknown_current:CHANNEL_A",
                NOW));
    }

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void investigatesInDeterministicOrderAndPersistsAnEvidenceBackedConclusion() {
        var toolOrder = new ArrayList<String>();
        var gateway = gateway(List.of(
                handler("get_payment_timeline", toolOrder, Map.of(
                        "paymentId", "P10001",
                        "events", List.of(Map.of("reasonCode", "CHANNEL_TIMEOUT")))),
                handler("query_channel_final_state", toolOrder, Map.of(
                        "paymentId", "P10001", "result", "SUCCESS")),
                handler("calculate_incident_impact", toolOrder, Map.of(
                        "affectedPaymentCount", 5,
                        "totalAmount", new BigDecimal("50.00"),
                        "currency", "CNY",
                        "paymentIds", List.of("P1", "P2", "P3", "P4", "P5")))));
        var orchestrator = orchestrator(new StubInvestigationModel(objectMapper), gateway);

        var conclusion = orchestrator.investigate(INCIDENT_ID, seed());

        assertThat(toolOrder).containsExactly(
                "get_payment_timeline",
                "query_channel_final_state",
                "calculate_incident_impact");
        assertThat(conclusion.rootCause())
                .isEqualTo(RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST);
        assertThat(conclusion.evidenceIds()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(conclusion.affectedPaymentCount()).isEqualTo(5);
        assertThat(conclusion.affectedAmount().amount()).isEqualByComparingTo("50.00");
        assertThat(conclusions.findByIncidentId(INCIDENT_ID)).contains(conclusion);
        assertThat(incidents.findById(INCIDENT_ID).orElseThrow().status())
                .isEqualTo(IncidentStatus.MITIGATION_PROPOSED);

        assertThat(orchestrator.investigate(INCIDENT_ID, seed())).isEqualTo(conclusion);
        assertThat(toolOrder).hasSize(3);
    }

    @Test
    void stopsAfterThreeConsecutiveCallsWithoutNewEvidence() {
        InvestigationModel unavailableToolModel = context ->
                new InvestigationDecision.CallTool(
                        "unavailable_tool", objectMapper.createObjectNode());
        var orchestrator = orchestrator(unavailableToolModel, gateway(List.of()));

        assertThatThrownBy(() -> orchestrator.investigate(INCIDENT_ID, seed()))
                .isInstanceOf(InvestigationEscalatedException.class)
                .hasMessageContaining("three decisions without new evidence");

        assertThat(audit.invocations).hasSize(3);
        assertThat(incidents.findById(INCIDENT_ID).orElseThrow().status())
                .isEqualTo(IncidentStatus.NEEDS_HUMAN);
        assertThat(conclusions.findByIncidentId(INCIDENT_ID)).isEmpty();
    }

    @Test
    void validatorRejectsMissingEvidenceAndFabricatedImpact() {
        evidence.save(evidence("E1", "PAYMENT_TIMELINE", Map.of("paymentId", "P10001")));
        evidence.save(evidence("E2", "CHANNEL_FINAL_STATE", Map.of("result", "SUCCESS")));
        evidence.save(evidence("E3", "INCIDENT_IMPACT", Map.of(
                "affectedPaymentCount", 5,
                "totalAmount", new BigDecimal("50.00"),
                "currency", "CNY")));
        var validator = new ConclusionValidator(evidence);
        var valid = conclusion(List.of("E1", "E2", "E3"), 5, "50.00");

        assertThatThrownBy(() -> validator.validate(
                        incidents.findById(INCIDENT_ID).orElseThrow(),
                        conclusion(List.of("E1", "E2", "MISSING"), 5, "50.00")))
                .isInstanceOf(InvalidConclusionException.class)
                .hasMessageContaining("MISSING");
        assertThatThrownBy(() -> validator.validate(
                        incidents.findById(INCIDENT_ID).orElseThrow(),
                        conclusion(List.of("E1", "E2", "E3"), 6, "60.00")))
                .isInstanceOf(InvalidConclusionException.class)
                .hasMessageContaining("impact");
        assertThat(validator.validate(
                        incidents.findById(INCIDENT_ID).orElseThrow(), valid))
                .isSameAs(valid);
    }

    @Test
    void stopsAtTwelveToolCallsEvenWhenEveryCallCreatesEvidence() {
        var toolOrder = new ArrayList<String>();
        var handler = handler(
                "get_payment_timeline",
                toolOrder,
                Map.of("paymentId", "P10001"));
        InvestigationModel repetitiveModel = context ->
                new InvestigationDecision.CallTool(
                        "get_payment_timeline",
                        objectMapper.createObjectNode().put("paymentId", "P10001"));

        assertThatThrownBy(() -> orchestrator(
                        repetitiveModel, gateway(List.of(handler)))
                .investigate(INCIDENT_ID, seed()))
                .isInstanceOf(InvestigationEscalatedException.class)
                .hasMessageContaining("12 tool call limit");

        assertThat(toolOrder).hasSize(12);
        assertThat(incidents.findById(INCIDENT_ID).orElseThrow().status())
                .isEqualTo(IncidentStatus.NEEDS_HUMAN);
    }

    @Test
    void stopsWhenTheTotalDeadlineHasElapsed() {
        var decisions = new AtomicInteger();
        InvestigationModel model = context -> {
            decisions.incrementAndGet();
            return new InvestigationDecision.Escalate("should not be reached");
        };
        var clock = new SequenceClock(
                NOW,
                NOW,
                NOW.plusSeconds(121),
                NOW.plusSeconds(121));

        assertThatThrownBy(() -> orchestrator(model, gateway(List.of()), clock)
                .investigate(INCIDENT_ID, seed()))
                .isInstanceOf(InvestigationEscalatedException.class)
                .hasMessageContaining("120 second deadline");
        assertThat(decisions).hasValue(0);
    }

    @Test
    void allowsOneConclusionRepairBeforeEscalating() {
        var attempts = new AtomicInteger();
        InvestigationModel fabricatingModel = context -> {
            attempts.incrementAndGet();
            return new InvestigationDecision.Conclude(
                    conclusion(List.of("MISSING-1", "MISSING-2", "MISSING-3"), 99, "99.00"));
        };

        assertThatThrownBy(() -> orchestrator(fabricatingModel, gateway(List.of()))
                .investigate(INCIDENT_ID, seed()))
                .isInstanceOf(InvestigationEscalatedException.class)
                .hasMessageContaining("validation failed twice");

        assertThat(attempts).hasValue(2);
        assertThat(conclusions.findByIncidentId(INCIDENT_ID)).isEmpty();
        assertThat(incidents.findById(INCIDENT_ID).orElseThrow().status())
                .isEqualTo(IncidentStatus.NEEDS_HUMAN);
    }

    private InvestigationOrchestrator orchestrator(
            InvestigationModel model, ToolGateway gateway) {
        return orchestrator(model, gateway, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private InvestigationOrchestrator orchestrator(
            InvestigationModel model, ToolGateway gateway, Clock clock) {
        return new InvestigationOrchestrator(
                incidents,
                evidence,
                conclusions,
                model,
                gateway,
                new ConclusionValidator(evidence),
                clock);
    }

    private ToolGateway gateway(List<ToolHandler<?, ?>> handlers) {
        return new ToolGateway(
                handlers,
                objectMapper,
                executor,
                evidence,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "E" + evidenceIds.incrementAndGet());
    }

    private ToolHandler<Map, Map> handler(
            String name, List<String> toolOrder, Map<String, Object> output) {
        return new ToolHandler<>() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        name,
                        1,
                        "test tool",
                        Duration.ofSeconds(1),
                        65_536,
                        ToolRisk.READ_ONLY);
            }

            @Override
            public Class<Map> inputType() {
                return Map.class;
            }

            @Override
            public Map execute(Map input) {
                toolOrder.add(name);
                return output;
            }
        };
    }

    private InvestigationSeed seed() {
        return new InvestigationSeed(
                "P10001", "CHANNEL_A", NOW.minusSeconds(300), NOW.plusSeconds(300));
    }

    private InvestigationConclusion conclusion(
            List<String> evidenceIds, long count, String amount) {
        return new InvestigationConclusion(
                INCIDENT_ID,
                RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST,
                new BigDecimal("0.95"),
                evidenceIds,
                count,
                new Money(new BigDecimal(amount), Currency.getInstance("CNY")),
                "query-and-sync-unknown-payments",
                true);
    }

    private Evidence evidence(String id, String type, Map<String, Object> content) {
        return new Evidence(
                id,
                INCIDENT_ID,
                type,
                "test",
                1,
                objectMapper.valueToTree(content),
                "a".repeat(64),
                NOW);
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

    private static final class InMemoryIncidentRepository implements IncidentRepository {
        private final Map<String, Incident> items = new LinkedHashMap<>();

        void add(Incident incident) {
            items.put(incident.incidentId(), incident);
        }

        @Override
        public Optional<Incident> findOpenByAggregateKeySince(
                String aggregateKey, Instant detectedSince) {
            return Optional.empty();
        }

        @Override
        public Optional<Incident> findById(String incidentId) {
            return Optional.ofNullable(items.get(incidentId));
        }

        @Override
        public Incident save(Incident incident, AlertSignal alert) {
            return save(incident);
        }

        @Override
        public Incident save(Incident incident) {
            items.put(incident.incidentId(), incident);
            return incident;
        }
    }

    private static final class InMemoryConclusionRepository
            implements InvestigationConclusionRepository {
        private final Map<String, InvestigationConclusion> items = new LinkedHashMap<>();

        @Override
        public InvestigationConclusion save(InvestigationConclusion conclusion) {
            items.put(conclusion.incidentId(), conclusion);
            return conclusion;
        }

        @Override
        public Optional<InvestigationConclusion> findByIncidentId(String incidentId) {
            return Optional.ofNullable(items.get(incidentId));
        }
    }

    private static final class InMemoryAuditRepository implements ToolAuditRepository {
        private final List<ToolInvocation> invocations = new ArrayList<>();

        @Override
        public void record(ToolInvocation invocation) {
            invocations.add(invocation);
        }
    }

    private static final class SequenceClock extends Clock {
        private final List<Instant> values;
        private int index;

        private SequenceClock(Instant... values) {
            this.values = List.of(values);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            var value = values.get(Math.min(index, values.size() - 1));
            index++;
            return value;
        }
    }
}
