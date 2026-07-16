package io.paysre.control.incident;

import io.paysre.control.alerting.AlertSignal;
import java.time.Instant;
import java.util.Optional;

public interface IncidentRepository {

    Optional<Incident> findOpenByAggregateKeySince(
            String aggregateKey, Instant detectedSince);

    Optional<Incident> findById(String incidentId);

    Incident save(Incident incident, AlertSignal alert);

    Incident save(Incident incident);
}
