package io.paysre.control.incident;

import io.paysre.control.alerting.AlertSignal;
import java.time.Duration;
import java.util.Objects;

public final class IncidentApplicationService {

    private static final Duration AGGREGATION_WINDOW = Duration.ofMinutes(5);

    private final IncidentRepository repository;
    private final IncidentIdGenerator ids;

    public IncidentApplicationService(
            IncidentRepository repository, IncidentIdGenerator ids) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public Incident ingest(AlertSignal alert) {
        var incident = repository.findOpenByAggregateKeySince(
                        alert.aggregateKey(), alert.startsAt().minus(AGGREGATION_WINDOW))
                .orElseGet(() -> Incident.detected(
                        ids.nextIncidentId(), alert.aggregateKey(), alert.startsAt()));
        incident.addAlert(alert);
        return repository.save(incident, alert);
    }
}
