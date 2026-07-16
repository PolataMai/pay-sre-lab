package io.paysre.control.alerting;

import io.paysre.control.incident.Incident;
import io.paysre.control.incident.IncidentApplicationService;
import io.paysre.control.incident.IncidentRepository;
import io.paysre.control.incident.IncidentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class AlertWebhookController {

    private final IncidentApplicationService incidentService;
    private final IncidentRepository repository;

    public AlertWebhookController(
            IncidentApplicationService incidentService, IncidentRepository repository) {
        this.incidentService = incidentService;
        this.repository = repository;
    }

    @PostMapping("/api/alerts/alertmanager")
    public List<IncidentView> ingest(@RequestBody AlertmanagerWebhook webhook) {
        if (webhook.alerts().isEmpty()) {
            throw new IllegalArgumentException("at least one alert is required");
        }
        return webhook.alerts().stream()
                .map(this::toSignal)
                .map(incidentService::ingest)
                .map(IncidentView::from)
                .toList();
    }

    @GetMapping("/api/incidents/{incidentId}")
    public IncidentView get(@PathVariable String incidentId) {
        return repository.findById(incidentId)
                .map(IncidentView::from)
                .orElseThrow(() -> new IncidentNotFoundException(incidentId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidWebhook(IllegalArgumentException exception) {
        var detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setProperty("code", "INVALID_ALERT_WEBHOOK");
        return detail;
    }

    @ExceptionHandler(IncidentNotFoundException.class)
    ProblemDetail incidentNotFound(IncidentNotFoundException exception) {
        var detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setProperty("code", "INCIDENT_NOT_FOUND");
        return detail;
    }

    private AlertSignal toSignal(AlertmanagerWebhook.Alert alert) {
        String service = requiredLabel(alert, "service");
        String alertName = requiredLabel(alert, "alertname");
        if (alert.startsAt() == null) {
            throw new IllegalArgumentException("alert startsAt is required");
        }
        var dimensions = new HashMap<String, String>();
        var channel = alert.labels().get("channel");
        if (channel != null && !channel.isBlank()) {
            dimensions.put("channel", channel);
        }
        String alertId = alert.fingerprint();
        if (alertId == null || alertId.isBlank()) {
            alertId = service + ":" + alertName + ":" + alert.startsAt();
        }
        return new AlertSignal(
                alertId,
                "ALERTMANAGER",
                service,
                alertName,
                severity(alert.labels().get("severity")),
                alert.startsAt(),
                dimensions,
                decimalOrNull(alert.annotations().get("observedValue")),
                decimalOrNull(alert.annotations().get("threshold")));
    }

    private String requiredLabel(AlertmanagerWebhook.Alert alert, String label) {
        var value = alert.labels().get(label);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("alert label " + label + " is required");
        }
        return value;
    }

    private Severity severity(String value) {
        if (value == null || value.isBlank()) {
            return Severity.WARNING;
        }
        try {
            return Severity.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported alert severity: " + value);
        }
    }

    private BigDecimal decimalOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid decimal value: " + value);
        }
    }

    public record IncidentView(
            String incidentId,
            String aggregateKey,
            IncidentStatus status,
            Instant detectedAt,
            Instant updatedAt,
            long version,
            List<String> alertIds) {

        static IncidentView from(Incident incident) {
            return new IncidentView(
                    incident.incidentId(),
                    incident.aggregateKey(),
                    incident.status(),
                    incident.detectedAt(),
                    incident.updatedAt(),
                    incident.version(),
                    incident.alertIds());
        }
    }

    private static final class IncidentNotFoundException extends RuntimeException {

        private IncidentNotFoundException(String incidentId) {
            super("incident not found: " + incidentId);
        }
    }
}
