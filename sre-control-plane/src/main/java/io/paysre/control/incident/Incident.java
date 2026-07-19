package io.paysre.control.incident;

import io.paysre.control.alerting.AlertSignal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class Incident {

    private final String incidentId;
    private final String aggregateKey;
    private final Instant detectedAt;
    private final Set<String> alertIds = new LinkedHashSet<>();
    private IncidentStatus status;
    private Instant updatedAt;
    private long version;
    private boolean persisted;

    private Incident(String incidentId, String aggregateKey, Instant detectedAt) {
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.aggregateKey = Objects.requireNonNull(aggregateKey, "aggregateKey");
        this.detectedAt = Objects.requireNonNull(detectedAt, "detectedAt");
        this.updatedAt = detectedAt;
        this.status = IncidentStatus.DETECTED;
    }

    public static Incident detected(
            String incidentId, String aggregateKey, Instant detectedAt) {
        return new Incident(incidentId, aggregateKey, detectedAt);
    }

    public static Incident restore(
            String incidentId,
            String aggregateKey,
            IncidentStatus status,
            Instant detectedAt,
            Instant updatedAt,
            long version,
            List<String> alertIds) {
        var incident = new Incident(incidentId, aggregateKey, detectedAt);
        incident.status = status;
        incident.updatedAt = updatedAt;
        incident.version = version;
        incident.alertIds.addAll(alertIds);
        incident.persisted = true;
        return incident;
    }

    public void addAlert(AlertSignal alert) {
        if (!aggregateKey.equals(alert.aggregateKey())) {
            throw new IllegalArgumentException("alert aggregate key does not match incident");
        }
        if (alertIds.add(alert.alertId()) && alert.startsAt().isAfter(updatedAt)) {
            updatedAt = alert.startsAt();
        }
    }

    public void markInvestigating(Instant now) {
        transition(IncidentStatus.INVESTIGATING, now);
    }

    public void markMitigationProposed(Instant now) {
        transition(IncidentStatus.MITIGATION_PROPOSED, now);
    }

    public void markNeedsHuman(Instant now) {
        transition(IncidentStatus.NEEDS_HUMAN, now);
    }

    public void markMitigated(Instant now) {
        transition(IncidentStatus.MITIGATED, now);
    }

    public void markResolved(Instant now) {
        transition(IncidentStatus.RESOLVED, now);
    }

    private void transition(IncidentStatus target, Instant now) {
        status = target;
        updatedAt = now;
    }

    public boolean isOpen() {
        return status != IncidentStatus.RESOLVED;
    }

    public String incidentId() {
        return incidentId;
    }

    public String aggregateKey() {
        return aggregateKey;
    }

    public Instant detectedAt() {
        return detectedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public IncidentStatus status() {
        return status;
    }

    public List<String> alertIds() {
        return List.copyOf(alertIds);
    }

    public long version() {
        return version;
    }

    public boolean persisted() {
        return persisted;
    }

    public void markPersisted(long newVersion) {
        version = newVersion;
        persisted = true;
    }
}
