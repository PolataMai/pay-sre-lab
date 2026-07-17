package io.paysre.control.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunbookExecutionTest {

    private static final Instant NOW = Instant.parse("2026-07-17T04:00:00Z");

    @Test
    void walksTheApprovedExecutionLifecycle() {
        var execution = proposed();
        assertThat(execution.status()).isEqualTo(RunbookExecutionStatus.PENDING_APPROVAL);

        execution.approve("operator-b", NOW.plusSeconds(10));
        assertThat(execution.status()).isEqualTo(RunbookExecutionStatus.RUNNING);
        assertThat(execution.approvedBy()).isEqualTo("operator-b");

        var result = new ObjectMapper().createObjectNode().put("synced", 5);
        execution.succeed(result, NOW.plusSeconds(20));
        assertThat(execution.status()).isEqualTo(RunbookExecutionStatus.SUCCEEDED);
        assertThat(execution.result().path("synced").asInt()).isEqualTo(5);
    }

    @Test
    void recordsFailureWithAnErrorInsteadOfAResult() {
        var execution = proposed();
        execution.approve("operator-b", NOW.plusSeconds(10));

        execution.fail("RUNBOOK_TIMEOUT", NOW.plusSeconds(20));

        assertThat(execution.status()).isEqualTo(RunbookExecutionStatus.FAILED);
        assertThat(execution.error()).isEqualTo("RUNBOOK_TIMEOUT");
        assertThat(execution.result()).isNull();
    }

    @Test
    void refusesApprovalByTheProposer() {
        var execution = proposed();

        assertThatThrownBy(() -> execution.approve("operator-a", NOW.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approver must differ");
    }

    @Test
    void refusesToRunWithoutApproval() {
        var execution = proposed();
        var result = new ObjectMapper().createObjectNode();

        assertThatThrownBy(() -> execution.succeed(result, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("expected RUNNING but was PENDING_APPROVAL");
    }

    private RunbookExecution proposed() {
        return RunbookExecution.proposed(
                "RUN-1",
                "INC-1",
                QueryAndSyncUnknownPaymentsRunbook.NAME,
                "operator-a",
                NOW);
    }
}
