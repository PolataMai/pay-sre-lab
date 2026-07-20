package io.paysre.control.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RunbookControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-17T04:00:00Z");

    @Test
    void exposesTheProposalAndApprovalWorkflow() {
        var service = mock(RunbookExecutionService.class);
        var execution = RunbookExecution.proposed(
                "RUN-1", "INC-1", QueryAndSyncUnknownPaymentsRunbook.NAME, "operator-a", NOW);
        when(service.propose(
                "INC-1", QueryAndSyncUnknownPaymentsRunbook.NAME, "operator-a"))
                .thenReturn(execution);
        var controller = new RunbookController(service);

        var view = controller.propose("INC-1", new RunbookController.ProposalRequest(
                QueryAndSyncUnknownPaymentsRunbook.NAME, "operator-a"));

        assertThat(view.executionId()).isEqualTo("RUN-1");
        assertThat(view.status()).isEqualTo(RunbookExecutionStatus.PENDING_APPROVAL);
        assertThat(view.requestedBy()).isEqualTo("operator-a");
    }

    @Test
    void mapsDenialCodesOntoMeaningfulHttpStatuses() {
        var controller = new RunbookController(mock(RunbookExecutionService.class));

        assertThat(controller.denied(new ActionDeniedException(
                        "INCIDENT_NOT_FOUND", "missing")).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(controller.denied(new ActionDeniedException(
                        "FOUR_EYES_REQUIRED", "same person")).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(controller.denied(new ActionDeniedException(
                        "EXECUTION_ALREADY_ACTIVE", "busy")).getStatus())
                .isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(controller.denied(new ActionDeniedException(
                        "RUNBOOK_NOT_ALLOWED", "nope")).getProperties())
                .containsEntry("code", "RUNBOOK_NOT_ALLOWED");
    }
}
