package io.paysre.control.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paysre.control.remediation.ActionDeniedException;
import io.paysre.control.remediation.ActionGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class IncidentLifecycleControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-17T05:00:00Z");

    @Test
    void resolvesAMitigatedIncidentThroughTheGuard() {
        var incident = Incident.detected("INC-1", "payment-unknown:CHANNEL_A", NOW);
        incident.markInvestigating(NOW.plusSeconds(1));
        incident.markMitigationProposed(NOW.plusSeconds(2));
        incident.markMitigated(NOW.plusSeconds(3));
        var guard = mock(ActionGuard.class);
        when(guard.authorizeResolution("INC-1", "operator-a")).thenReturn(incident);
        var repository = mock(IncidentRepository.class);
        when(repository.save(any(Incident.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var controller = new IncidentLifecycleController(
                guard, repository, Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));

        var view = controller.resolve(
                "INC-1", new IncidentLifecycleController.ResolutionRequest("operator-a"));

        assertThat(view.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.status()).isEqualTo(IncidentStatus.RESOLVED);
    }

    @Test
    void mapsGuardDenialsOntoHttpStatuses() {
        var controller = new IncidentLifecycleController(
                mock(ActionGuard.class),
                mock(IncidentRepository.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(controller.denied(new ActionDeniedException(
                        "INCIDENT_NOT_FOUND", "missing")).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(controller.denied(new ActionDeniedException(
                        "ACTOR_REQUIRED", "anonymous")).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(controller.denied(new ActionDeniedException(
                        "INCIDENT_NOT_RESOLVABLE", "in progress")).getStatus())
                .isEqualTo(HttpStatus.CONFLICT.value());
    }
}
