package io.paysre.control.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.contracts.Money;
import io.paysre.control.incident.Incident;
import io.paysre.control.incident.IncidentRepository;
import io.paysre.control.incident.IncidentStatus;
import io.paysre.control.investigation.InvestigationConclusion;
import io.paysre.control.investigation.InvestigationConclusionRepository;
import io.paysre.control.investigation.RootCauseCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RunbookExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T04:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final InvestigationConclusionRepository conclusions =
            mock(InvestigationConclusionRepository.class);
    private final InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
    private final RecordingAuditRepository audits = new RecordingAuditRepository();
    private final java.util.concurrent.ExecutorService executor =
            Executors.newSingleThreadExecutor();
    private final StubRunbook runbook = new StubRunbook();

    @AfterEach
    void shutDown() {
        executor.shutdownNow();
    }

    @Test
    void proposesApprovesRunsAndMitigatesTheIncident() {
        var incident = actionableIncident();
        when(incidents.findById("INC-1")).thenReturn(Optional.of(incident));
        when(conclusions.findByIncidentId("INC-1")).thenReturn(Optional.of(conclusion()));
        runbook.result = MAPPER.createObjectNode().put("synced", 5);
        var service = service();

        var proposed = service.propose(
                "INC-1", QueryAndSyncUnknownPaymentsRunbook.NAME, "operator-a");
        assertThat(proposed.status()).isEqualTo(RunbookExecutionStatus.PENDING_APPROVAL);

        var finished = service.approveAndRun("INC-1", proposed.executionId(), "operator-b");

        assertThat(finished.status()).isEqualTo(RunbookExecutionStatus.SUCCEEDED);
        assertThat(finished.result().path("synced").asInt()).isEqualTo(5);
        assertThat(incident.status()).isEqualTo(IncidentStatus.MITIGATED);
        assertThat(audits.records)
                .extracting(ActionInvocation::action)
                .contains("RUNBOOK_PROPOSED", "RUNBOOK_APPROVED", "RUNBOOK_EXECUTED");
    }

    @Test
    void failedRunbooksHandTheIncidentBackToAHuman() {
        var incident = actionableIncident();
        when(incidents.findById("INC-1")).thenReturn(Optional.of(incident));
        when(conclusions.findByIncidentId("INC-1")).thenReturn(Optional.of(conclusion()));
        runbook.failure = new RemediationBackendException(
                "PAYMENT_SERVICE_UNREACHABLE", "down");
        var service = service();
        var proposed = service.propose(
                "INC-1", QueryAndSyncUnknownPaymentsRunbook.NAME, "operator-a");

        var finished = service.approveAndRun("INC-1", proposed.executionId(), "operator-b");

        assertThat(finished.status()).isEqualTo(RunbookExecutionStatus.FAILED);
        assertThat(finished.error()).contains("PAYMENT_SERVICE_UNREACHABLE");
        assertThat(incident.status()).isEqualTo(IncidentStatus.NEEDS_HUMAN);
        var lastAudit = audits.records.get(audits.records.size() - 1);
        assertThat(lastAudit.action()).isEqualTo("RUNBOOK_EXECUTED");
        assertThat(lastAudit.allowed()).isFalse();
    }

    @Test
    void refusesApprovalByTheProposerBeforeTouchingAnything() {
        var incident = actionableIncident();
        when(incidents.findById("INC-1")).thenReturn(Optional.of(incident));
        when(conclusions.findByIncidentId("INC-1")).thenReturn(Optional.of(conclusion()));
        var service = service();
        var proposed = service.propose(
                "INC-1", QueryAndSyncUnknownPaymentsRunbook.NAME, "operator-a");

        assertThatThrownBy(() -> service.approveAndRun(
                "INC-1", proposed.executionId(), "operator-a"))
                .isInstanceOf(ActionDeniedException.class);
        assertThat(executions.findById(proposed.executionId()).orElseThrow().status())
                .isEqualTo(RunbookExecutionStatus.PENDING_APPROVAL);
        assertThat(incident.status()).isEqualTo(IncidentStatus.MITIGATION_PROPOSED);
    }

    private RunbookExecutionService service() {
        var guard = new ActionGuard(
                java.util.Set.of(QueryAndSyncUnknownPaymentsRunbook.NAME),
                incidents,
                conclusions,
                executions,
                audits,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new IdSequence("ACT"));
        return new RunbookExecutionService(
                guard,
                List.of(runbook),
                executions,
                incidents,
                conclusions,
                Clock.fixed(NOW, ZoneOffset.UTC),
                executor,
                Duration.ofSeconds(5),
                new IdSequence("RUN"));
    }

    private Incident actionableIncident() {
        var incident = Incident.detected("INC-1", "payment-unknown:CHANNEL_A", NOW);
        incident.markInvestigating(NOW.plusSeconds(1));
        incident.markMitigationProposed(NOW.plusSeconds(2));
        return incident;
    }

    private InvestigationConclusion conclusion() {
        return new InvestigationConclusion(
                "INC-1",
                RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST,
                new BigDecimal("0.95"),
                List.of("EVD-1", "EVD-2", "EVD-3"),
                5,
                new Money(new BigDecimal("50.00"), Currency.getInstance("CNY")),
                QueryAndSyncUnknownPaymentsRunbook.NAME,
                true);
    }

    private static final class StubRunbook implements Runbook {
        private JsonNode result;
        private RuntimeException failure;

        @Override
        public String name() {
            return QueryAndSyncUnknownPaymentsRunbook.NAME;
        }

        @Override
        public JsonNode execute(Incident incident, InvestigationConclusion conclusion) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static final class InMemoryExecutionRepository
            implements RunbookExecutionRepository {
        private final java.util.Map<String, RunbookExecution> store =
                new java.util.LinkedHashMap<>();

        @Override
        public RunbookExecution save(RunbookExecution execution) {
            execution.markPersisted(execution.version() + (execution.persisted() ? 1 : 0));
            store.put(execution.executionId(), execution);
            return execution;
        }

        @Override
        public Optional<RunbookExecution> findById(String executionId) {
            return Optional.ofNullable(store.get(executionId));
        }

        @Override
        public List<RunbookExecution> findByIncidentId(String incidentId) {
            return store.values().stream()
                    .filter(item -> item.incidentId().equals(incidentId))
                    .toList();
        }

        @Override
        public Optional<RunbookExecution> findActiveByIncidentId(String incidentId) {
            return store.values().stream()
                    .filter(item -> item.incidentId().equals(incidentId))
                    .filter(item -> item.status() == RunbookExecutionStatus.PENDING_APPROVAL
                            || item.status() == RunbookExecutionStatus.RUNNING)
                    .findFirst();
        }
    }

    private static final class RecordingAuditRepository implements ActionAuditRepository {
        private final List<ActionInvocation> records = new ArrayList<>();

        @Override
        public void record(ActionInvocation invocation) {
            records.add(invocation);
        }

        @Override
        public List<ActionInvocation> findByIncidentId(String incidentId) {
            return List.copyOf(records);
        }
    }

    private record IdSequence(String prefix, AtomicInteger sequence)
            implements java.util.function.Supplier<String> {
        private IdSequence(String prefix) {
            this(prefix, new AtomicInteger());
        }

        @Override
        public String get() {
            return prefix + "-" + sequence.incrementAndGet();
        }
    }
}
