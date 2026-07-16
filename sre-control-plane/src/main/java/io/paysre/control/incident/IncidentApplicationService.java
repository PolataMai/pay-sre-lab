package io.paysre.control.incident;

import io.paysre.control.alerting.AlertSignal;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class IncidentApplicationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncidentApplicationService.class);

    private static final Duration AGGREGATION_WINDOW = Duration.ofMinutes(5);

    private final IncidentRepository repository;
    private final IncidentIdGenerator ids;

    public IncidentApplicationService(
            IncidentRepository repository, IncidentIdGenerator ids) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public Incident ingest(AlertSignal alert) {
        var existing = repository.findOpenByAggregateKeySince(
                alert.aggregateKey(), alert.startsAt().minus(AGGREGATION_WINDOW));
        boolean created = existing.isEmpty();
        var incident = existing.orElseGet(() -> Incident.detected(
                ids.nextIncidentId(), alert.aggregateKey(), alert.startsAt()));
        incident.addAlert(alert);
        var saved = repository.save(incident, alert);
        LOGGER.atInfo()
                .addKeyValue("event", created ? "INCIDENT_CREATED" : "INCIDENT_ALERT_AGGREGATED")
                .addKeyValue("incidentId", saved.incidentId())
                .addKeyValue("affectedService", alert.service())
                .addKeyValue("signalName", alert.signalName())
                .addKeyValue("severity", alert.severity().name())
                .log(created ? "Incident created" : "Alert aggregated into incident");
        return saved;
    }
}
