package io.paysre.control.alerting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paysre.control.incident.Incident;
import io.paysre.control.incident.IncidentApplicationService;
import io.paysre.control.incident.IncidentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class AlertWebhookControllerTest {

    @Test
    void rejectsAnAlertWithoutAServiceLabel() {
        var controller = controller();
        var alert = new AlertmanagerWebhook.Alert(
                "firing",
                Map.of("alertname", "PaymentUnknownHigh"),
                Map.of(),
                Instant.EPOCH,
                "fingerprint-1");

        assertThatThrownBy(() -> controller.ingest(
                        new AlertmanagerWebhook("firing", List.of(alert))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("alert label service is required");
    }

    @Test
    void mapsAValidWebhookToANormalizedAlertSignal() {
        var service = mock(IncidentApplicationService.class);
        var repository = mock(IncidentRepository.class);
        var incident = Incident.detected(
                "INC-01", "payment-service:PaymentUnknownHigh:CHANNEL_A", Instant.EPOCH);
        when(service.ingest(any())).thenReturn(incident);
        var controller = new AlertWebhookController(service, repository);
        var alert = new AlertmanagerWebhook.Alert(
                "firing",
                Map.of(
                        "service", "payment-service",
                        "alertname", "PaymentUnknownHigh",
                        "channel", "CHANNEL_A",
                        "severity", "high"),
                Map.of("observedValue", "5", "threshold", "1"),
                Instant.EPOCH,
                "fingerprint-1");

        var result = controller.ingest(new AlertmanagerWebhook("firing", List.of(alert)));

        var signal = ArgumentCaptor.forClass(AlertSignal.class);
        verify(service).ingest(signal.capture());
        assertThat(signal.getValue().aggregateKey())
                .isEqualTo("payment-service:PaymentUnknownHigh:CHANNEL_A");
        assertThat(signal.getValue().severity()).isEqualTo(Severity.HIGH);
        assertThat(result).singleElement()
                .satisfies(view -> assertThat(view.incidentId()).isEqualTo("INC-01"));
    }

    @Test
    void mapsInvalidWebhookToABadRequestProblem() {
        var controller = controller();

        var problem = controller.invalidWebhook(
                new IllegalArgumentException("alert startsAt is required"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_ALERT_WEBHOOK");
    }

    private AlertWebhookController controller() {
        return new AlertWebhookController(
                mock(IncidentApplicationService.class), mock(IncidentRepository.class));
    }
}
