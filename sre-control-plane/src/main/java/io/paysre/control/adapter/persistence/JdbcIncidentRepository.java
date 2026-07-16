package io.paysre.control.adapter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.alerting.AlertSignal;
import io.paysre.control.incident.Incident;
import io.paysre.control.incident.IncidentRepository;
import io.paysre.control.incident.IncidentStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcIncidentRepository implements IncidentRepository {

    private static final String INCIDENT_COLUMNS = """
            incident_id, aggregate_key, status, detected_at, updated_at, version
            """;
    private static final String POSTGRES_ALERT_INSERT = """
            insert into incident_alert (
                incident_id, alert_id, source, service, signal_name, severity,
                starts_at, dimensions, observed_value, threshold)
            values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
            on conflict (alert_id) do nothing
            """;
    private static final String H2_ALERT_INSERT = """
            merge into incident_alert as target
            using (values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)) as source(
                incident_id, alert_id, source_name, service_name, signal_name, severity,
                starts_at, dimensions, observed_value, threshold)
               on target.alert_id = source.alert_id
            when not matched then
                insert (
                    incident_id, alert_id, source, service, signal_name, severity,
                    starts_at, dimensions, observed_value, threshold)
                values (
                    source.incident_id, source.alert_id, source.source_name,
                    source.service_name, source.signal_name, source.severity,
                    source.starts_at, source.dimensions,
                    source.observed_value, source.threshold)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String alertInsertSql;

    public JdbcIncidentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.alertInsertSql = jdbc.execute((ConnectionCallback<String>) connection ->
                "H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())
                        ? H2_ALERT_INSERT
                        : POSTGRES_ALERT_INSERT);
    }

    @Override
    public Optional<Incident> findOpenByAggregateKeySince(
            String aggregateKey, java.time.Instant detectedSince) {
        return findOne(
                "select " + INCIDENT_COLUMNS + " from incident "
                        + "where aggregate_key = ? and status <> 'RESOLVED' and detected_at >= ? "
                        + "order by detected_at desc limit 1",
                aggregateKey,
                OffsetDateTime.ofInstant(detectedSince, ZoneOffset.UTC));
    }

    @Override
    public Optional<Incident> findById(String incidentId) {
        return findOne(
                "select " + INCIDENT_COLUMNS + " from incident where incident_id = ?",
                incidentId);
    }

    @Override
    @Transactional
    public Incident save(Incident incident, AlertSignal alert) {
        if (!incident.persisted()) {
            insertIncident(incident);
        } else {
            updateIncident(incident);
        }
        int attached = insertAlert(incident.incidentId(), alert);
        if (attached == 1) {
            appendTimeline(
                    incident.incidentId(),
                    "ALERT_ATTACHED",
                    alert.startsAt(),
                    objectMapper.createObjectNode().put("alertId", alert.alertId()).toString());
        }
        return incident;
    }

    @Override
    @Transactional
    public Incident save(Incident incident) {
        if (!incident.persisted()) {
            insertIncident(incident);
        } else {
            updateIncident(incident);
        }
        appendTimeline(
                incident.incidentId(),
                "STATUS_CHANGED",
                incident.updatedAt(),
                objectMapper.createObjectNode().put("status", incident.status().name()).toString());
        return incident;
    }

    private void insertIncident(Incident incident) {
        jdbc.update("""
                        insert into incident (
                            incident_id, aggregate_key, status, detected_at, updated_at, version)
                        values (?, ?, ?, ?, ?, ?)
                        """,
                incident.incidentId(),
                incident.aggregateKey(),
                incident.status().name(),
                OffsetDateTime.ofInstant(incident.detectedAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(incident.updatedAt(), ZoneOffset.UTC),
                0L);
        incident.markPersisted(0L);
        appendTimeline(
                incident.incidentId(),
                "INCIDENT_DETECTED",
                incident.detectedAt(),
                objectMapper.createObjectNode()
                        .put("aggregateKey", incident.aggregateKey())
                        .toString());
    }

    private void updateIncident(Incident incident) {
        long nextVersion = incident.version() + 1;
        int updated = jdbc.update("""
                        update incident
                           set status = ?, updated_at = ?, version = ?
                         where incident_id = ? and version = ?
                        """,
                incident.status().name(),
                OffsetDateTime.ofInstant(incident.updatedAt(), ZoneOffset.UTC),
                nextVersion,
                incident.incidentId(),
                incident.version());
        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                    "incident version changed: " + incident.incidentId());
        }
        incident.markPersisted(nextVersion);
    }

    private int insertAlert(String incidentId, AlertSignal alert) {
        return jdbc.update(
                alertInsertSql,
                incidentId,
                alert.alertId(),
                alert.source(),
                alert.service(),
                alert.signalName(),
                alert.severity().name(),
                OffsetDateTime.ofInstant(alert.startsAt(), ZoneOffset.UTC),
                serializeDimensions(alert),
                alert.observedValue(),
                alert.threshold());
    }

    private Optional<Incident> findOne(String sql, Object... arguments) {
        var rows = jdbc.query(sql, (resultSet, rowNumber) -> Incident.restore(
                        resultSet.getString("incident_id"),
                        resultSet.getString("aggregate_key"),
                        IncidentStatus.valueOf(resultSet.getString("status")),
                        resultSet.getObject("detected_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                        resultSet.getLong("version"),
                        loadAlertIds(resultSet.getString("incident_id"))),
                arguments);
        return rows.stream().findFirst();
    }

    private List<String> loadAlertIds(String incidentId) {
        return jdbc.queryForList("""
                select alert_id
                  from incident_alert
                 where incident_id = ?
                 order by starts_at, alert_id
                """, String.class, incidentId);
    }

    private String serializeDimensions(AlertSignal alert) {
        try {
            return objectMapper.writeValueAsString(alert.dimensions());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize alert dimensions", exception);
        }
    }

    private void appendTimeline(
            String incidentId,
            String eventType,
            java.time.Instant eventTime,
            String details) {
        jdbc.update("""
                        insert into incident_timeline (
                            incident_id, event_type, event_time, details)
                        values (?, ?, ?, cast(? as jsonb))
                        """,
                incidentId,
                eventType,
                OffsetDateTime.ofInstant(eventTime, ZoneOffset.UTC),
                details);
    }
}
