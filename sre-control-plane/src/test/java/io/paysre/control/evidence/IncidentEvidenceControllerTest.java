package io.paysre.control.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.tools.ToolAuditRepository;
import io.paysre.control.tools.ToolInvocation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class IncidentEvidenceControllerTest {

    @Test
    void exposesIncidentOwnedEvidenceContentAndToolAudit() {
        var evidenceRepository = mock(EvidenceRepository.class);
        var auditRepository = mock(ToolAuditRepository.class);
        var evidence = evidence("EVD-1", "INC-01");
        var invocation = new ToolInvocation(
                "INV-1",
                "INC-01",
                "deterministic-investigator",
                "query_service_metrics",
                1,
                true,
                List.of("EVD-1"),
                Duration.ofMillis(12),
                null,
                Instant.EPOCH);
        when(evidenceRepository.findById("EVD-1")).thenReturn(Optional.of(evidence));
        when(auditRepository.findByIncidentId("INC-01")).thenReturn(List.of(invocation));
        var controller = new IncidentEvidenceController(
                evidenceRepository, auditRepository, new ObjectMapper());

        assertThat(controller.getEvidence("INC-01", "EVD-1"))
                .satisfies(view -> {
                    assertThat(view.content()).isEqualTo(Map.of(
                            "signal", "PAYMENT_UNKNOWN_CURRENT"));
                    assertThat(view.sha256()).isEqualTo("a".repeat(64));
                });
        assertThat(controller.getToolAudits("INC-01"))
                .containsExactly(invocation);
    }

    @Test
    void refusesEvidenceOwnedByAnotherIncident() {
        var evidenceRepository = mock(EvidenceRepository.class);
        when(evidenceRepository.findById("EVD-1"))
                .thenReturn(Optional.of(evidence("EVD-1", "INC-02")));
        var controller = new IncidentEvidenceController(
                evidenceRepository, mock(ToolAuditRepository.class), new ObjectMapper());

        assertThatThrownBy(() -> controller.getEvidence("INC-01", "EVD-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    private Evidence evidence(String evidenceId, String incidentId) {
        var content = new ObjectMapper().createObjectNode()
                .put("signal", "PAYMENT_UNKNOWN_CURRENT");
        return new Evidence(
                evidenceId,
                incidentId,
                "SERVICE_METRICS",
                "query_service_metrics",
                1,
                content,
                "a".repeat(64),
                Instant.EPOCH);
    }
}
