package io.paysre.control.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paysre.contracts.Money;
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
        var controller = new InvestigationController(orchestrator, repository);
        var seed = new InvestigationSeed(
                "P10001", "CHANNEL_A", Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
        var conclusion = conclusion();
        when(orchestrator.investigate("INC-01", seed)).thenReturn(conclusion);
        when(repository.findByIncidentId("INC-01")).thenReturn(Optional.of(conclusion));

        assertThat(controller.investigate("INC-01", seed)).isEqualTo(conclusion);
        assertThat(controller.getConclusion("INC-01")).isEqualTo(conclusion);
        verify(orchestrator).investigate("INC-01", seed);
    }

    @Test
    void mapsEscalationToAnUnprocessableProblem() {
        var controller = new InvestigationController(
                mock(InvestigationOrchestrator.class),
                mock(InvestigationConclusionRepository.class));

        var problem = controller.escalated(
                new InvestigationEscalatedException("three decisions without new evidence"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
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
