package io.paysre.control.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paysre.contracts.Money;
import io.paysre.control.incident.Incident;
import io.paysre.control.incident.IncidentRepository;
import io.paysre.control.investigation.InvestigationConclusion;
import io.paysre.control.investigation.InvestigationConclusionRepository;
import io.paysre.control.investigation.RootCauseCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ActionGuardTest {

    private static final Instant NOW = Instant.parse("2026-07-17T04:00:00Z");

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final InvestigationConclusionRepository conclusions =
            mock(InvestigationConclusionRepository.class);
    private final RunbookExecutionRepository executions =
            mock(RunbookExecutionRepository.class);
    private final RecordingAuditRepository audits = new RecordingAuditRepository();
    private final ActionGuard guard = new ActionGuard(
            Set.of(QueryAndSyncUnknownPaymentsRunbook.NAME),
            incidents,
            conclusions,
            executions,
            audits,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new IdSequence());

    @Test
    void authorizesAProposalAgainstAMatchingConclusion() {
        when(incidents.findById("INC-1")).thenReturn(Optional.of(actionableIncident()));
        when(conclusions.findByIncidentId("INC-1")).thenReturn(Optional.of(conclusion()));
        when(executions.findActiveByIncidentId("INC-1")).thenReturn(Optional.empty());

        var incident = guard.authorizeProposal(
                "INC-1", QueryAndSyncUnknownPaymentsRunbook.NAME, "operator-a");

        assertThat(incident.incidentId()).isEqualTo("INC-1");
        assertThat(audits.records).hasSize(1);
        assertThat(audits.records.get(0).allowed()).isTrue();
        assertThat(audits.records.get(0).action()).isEqualTo("RUNBOOK_PROPOSED");
    }

    @Test
    void deniesRunbooksOutsideTheAllowlist() {
        assertDenied(
                () -> guard.authorizeProposal("INC-1", "drop-database", "operator-a"),
                "RUNBOOK_NOT_ALLOWED");
    }

    @Test
    void deniesAnonymousProposals() {
        assertDenied(
                () -> guard.authorizeProposal(
                        "INC-1", QueryAndSyncUnknownPaymentsRunbook.NAME, " "),
                "ACTOR_REQUIRED");
    }

    @Test
    void deniesProposalsForIncidentsNotAwaitingMitigation() {
        var incident = Incident.detected("INC-1", "payment-unknown:CHANNEL_A", NOW);
        when(incidents.findById("INC-1")).thenReturn(Optional.of(incident));

        assertDenied(
                () -> guard.authorizeProposal(
                        "INC-1", QueryAndSyncUnknownPaymentsRunbook.NAME, "operator-a"),
                "INCIDENT_NOT_ACTIONABLE");
    }

    @Test
    void deniesProposalsWhenTheConclusionRecommendsAnotherRunbook() {
        when(incidents.findById("INC-1")).thenReturn(Optional.of(actionableIncident()));
        when(conclusions.findByIncidentId("INC-1")).thenReturn(Optional.empty());

        assertDenied(
                () -> guard.authorizeProposal(
                        "INC-1", QueryAndSyncUnknownPaymentsRunbook.NAME, "operator-a"),
                "CONCLUSION_MISSING");
    }

    @Test
    void deniesConcurrentExecutionsForTheSameIncident() {
        when(incidents.findById("INC-1")).thenReturn(Optional.of(actionableIncident()));
        when(conclusions.findByIncidentId("INC-1")).thenReturn(Optional.of(conclusion()));
        when(executions.findActiveByIncidentId("INC-1"))
                .thenReturn(Optional.of(pendingExecution("operator-a")));

        assertDenied(
                () -> guard.authorizeProposal(
                        "INC-1", QueryAndSyncUnknownPaymentsRunbook.NAME, "operator-a"),
                "EXECUTION_ALREADY_ACTIVE");
    }

    @Test
    void enforcesFourEyesOnApproval() {
        when(executions.findById("RUN-1"))
                .thenReturn(Optional.of(pendingExecution("operator-a")));

        assertDenied(
                () -> guard.authorizeApproval("INC-1", "RUN-1", "operator-a"),
                "FOUR_EYES_REQUIRED");
    }

    @Test
    void approvesAPendingExecutionForADifferentHuman() {
        when(executions.findById("RUN-1"))
                .thenReturn(Optional.of(pendingExecution("operator-a")));

        var execution = guard.authorizeApproval("INC-1", "RUN-1", "operator-b");

        assertThat(execution.executionId()).isEqualTo("RUN-1");
        assertThat(audits.records.get(audits.records.size() - 1).allowed()).isTrue();
    }

    @Test
    void resolutionIsAllowedForMitigatedAndNeedsHumanIncidents() {
        var mitigated = actionableIncident();
        mitigated.markMitigated(NOW.plusSeconds(3));
        when(incidents.findById("INC-1")).thenReturn(Optional.of(mitigated));

        assertThat(guard.authorizeResolution("INC-1", "operator-a").incidentId())
                .isEqualTo("INC-1");

        var needsHuman = actionableIncident();
        needsHuman.markNeedsHuman(NOW.plusSeconds(3));
        when(incidents.findById("INC-1")).thenReturn(Optional.of(needsHuman));

        assertThat(guard.authorizeResolution("INC-1", "operator-a").status())
                .isEqualTo(io.paysre.control.incident.IncidentStatus.NEEDS_HUMAN);
    }

    @Test
    void resolutionIsDeniedWhileTheIncidentIsStillBeingWorked() {
        when(incidents.findById("INC-1")).thenReturn(Optional.of(actionableIncident()));

        assertDenied(
                () -> guard.authorizeResolution("INC-1", "operator-a"),
                "INCIDENT_NOT_RESOLVABLE");
    }

    @Test
    void resolutionRequiresANamedHuman() {
        assertDenied(
                () -> guard.authorizeResolution("INC-1", ""),
                "ACTOR_REQUIRED");
    }

    private void assertDenied(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ActionDeniedException.class)
                .satisfies(exception -> assertThat(
                        ((ActionDeniedException) exception).code()).isEqualTo(code));
        var last = audits.records.get(audits.records.size() - 1);
        assertThat(last.allowed()).isFalse();
        assertThat(last.reasonCode()).isEqualTo(code);
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

    private RunbookExecution pendingExecution(String requestedBy) {
        return RunbookExecution.proposed(
                "RUN-1", "INC-1", QueryAndSyncUnknownPaymentsRunbook.NAME, requestedBy, NOW);
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

    private static final class IdSequence implements java.util.function.Supplier<String> {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public String get() {
            return "ACT-" + sequence.incrementAndGet();
        }
    }
}
