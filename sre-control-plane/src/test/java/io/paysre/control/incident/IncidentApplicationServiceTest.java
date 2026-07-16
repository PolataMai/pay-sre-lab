package io.paysre.control.incident;

import static org.assertj.core.api.Assertions.assertThat;

import io.paysre.control.alerting.AlertSignal;
import io.paysre.control.alerting.Severity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IncidentApplicationServiceTest {

    private static final Instant START = Instant.parse("2026-07-16T10:00:00Z");

    @Test
    void aggregatesAlertsWithTheSameKeyInsideFiveMinutes() {
        var repository = new InMemoryIncidentRepository();
        var sequence = new AtomicInteger();
        var service = new IncidentApplicationService(
                repository, () -> "INC-" + sequence.incrementAndGet());

        var first = service.ingest(alert("A-1", START));
        var second = service.ingest(alert("A-2", START.plusSeconds(240)));

        assertThat(second.incidentId()).isEqualTo(first.incidentId());
        assertThat(second.detectedAt()).isEqualTo(START);
        assertThat(second.alertIds()).containsExactly("A-1", "A-2");
        assertThat(second.status()).isEqualTo(IncidentStatus.DETECTED);
    }

    @Test
    void createsANewIncidentOutsideTheAggregationWindow() {
        var repository = new InMemoryIncidentRepository();
        var sequence = new AtomicInteger();
        var service = new IncidentApplicationService(
                repository, () -> "INC-" + sequence.incrementAndGet());

        var first = service.ingest(alert("A-1", START));
        var second = service.ingest(alert("A-2", START.plusSeconds(301)));

        assertThat(second.incidentId()).isNotEqualTo(first.incidentId());
    }

    private AlertSignal alert(String alertId, Instant startsAt) {
        return new AlertSignal(
                alertId,
                "ALERTMANAGER",
                "payment-service",
                "payment_unknown_current",
                Severity.HIGH,
                startsAt,
                Map.of("channel", "CHANNEL_A"),
                new BigDecimal("5"),
                new BigDecimal("1"));
    }

    private static final class InMemoryIncidentRepository implements IncidentRepository {

        private final Map<String, Incident> incidents = new LinkedHashMap<>();

        @Override
        public Optional<Incident> findOpenByAggregateKeySince(
                String aggregateKey, Instant detectedSince) {
            return incidents.values().stream()
                    .filter(incident -> incident.aggregateKey().equals(aggregateKey))
                    .filter(Incident::isOpen)
                    .filter(incident -> !incident.detectedAt().isBefore(detectedSince))
                    .findFirst();
        }

        @Override
        public Optional<Incident> findById(String incidentId) {
            return Optional.ofNullable(incidents.get(incidentId));
        }

        @Override
        public Incident save(Incident incident, AlertSignal alert) {
            incidents.put(incident.incidentId(), incident);
            return incident;
        }

        @Override
        public Incident save(Incident incident) {
            incidents.put(incident.incidentId(), incident);
            return incident;
        }
    }
}
