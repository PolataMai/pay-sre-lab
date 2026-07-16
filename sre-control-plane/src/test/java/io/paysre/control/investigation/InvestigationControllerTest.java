package io.paysre.control.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paysre.contracts.Money;
import io.paysre.control.evidence.Evidence;
import io.paysre.control.evidence.EvidenceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class InvestigationControllerTest {

    @Test
    void startsAndReadsAPersistedInvestigation() {
        var orchestrator = mock(InvestigationOrchestrator.class);
        var repository = mock(InvestigationConclusionRepository.class);
        var evidence = mock(EvidenceRepository.class);
        var controller = new InvestigationController(orchestrator, repository, evidence);
        var seed = new InvestigationSeed(
                "P10001", "CHANNEL_A", Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
        var conclusion = conclusion();
        when(orchestrator.investigate("INC-01", seed)).thenReturn(conclusion);
        when(repository.findByIncidentId("INC-01")).thenReturn(Optional.of(conclusion));
        when(evidence.findByIncidentId("INC-01")).thenReturn(List.of(new Evidence(
                "E1",
                "INC-01",
                "PAYMENT_TIMELINE",
                "get_payment_timeline",
                1,
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                "a".repeat(64),
                Instant.EPOCH)));

        assertThat(controller.investigate("INC-01", seed)).isEqualTo(conclusion);
        assertThat(controller.getConclusion("INC-01")).isEqualTo(conclusion);
        assertThat(controller.getEvidence("INC-01")).singleElement()
                .satisfies(item -> {
                    assertThat(item.evidenceId()).isEqualTo("E1");
                    assertThat(item.evidenceType()).isEqualTo("PAYMENT_TIMELINE");
                    assertThat(item.sha256()).isEqualTo("a".repeat(64));
                });
        verify(orchestrator).investigate("INC-01", seed);
    }

    @Test
    void mapsEscalationToAnUnprocessableProblem() {
        var controller = new InvestigationController(
                mock(InvestigationOrchestrator.class),
                mock(InvestigationConclusionRepository.class),
                mock(EvidenceRepository.class));

        var problem = controller.escalated(
                new InvestigationEscalatedException("three decisions without new evidence"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
        assertThat(problem.getProperties())
                .containsEntry("code", "INVESTIGATION_NEEDS_HUMAN");
    }

    private InvestigationConclusion conclusion() {
        return new InvestigationConclusion(
                "INC-01",
                RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST,
                new BigDecimal("0.95"),
                List.of("E1", "E2", "E3"),
                5,
                new Money(new BigDecimal("50.00"), Currency.getInstance("CNY")),
                "query-and-sync-unknown-payments",
                true);
    }
}
