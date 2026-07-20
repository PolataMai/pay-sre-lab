package io.paysre.control.adapter.persistence;

import io.paysre.control.remediation.ActionAuditRepository;
import io.paysre.control.remediation.ActionInvocation;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcActionAuditRepository implements ActionAuditRepository {

    private static final String COLUMNS = """
            audit_id, incident_id, execution_id, action, actor,
            allowed, reason_code, occurred_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcActionAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(ActionInvocation invocation) {
        jdbc.update("""
                        insert into action_audit (
                            audit_id, incident_id, execution_id, action, actor,
                            allowed, reason_code, occurred_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                invocation.auditId(),
                invocation.incidentId(),
                invocation.executionId(),
                invocation.action(),
                invocation.actor(),
                invocation.allowed(),
                invocation.reasonCode(),
                OffsetDateTime.ofInstant(invocation.occurredAt(), ZoneOffset.UTC));
    }

    @Override
    public List<ActionInvocation> findByIncidentId(String incidentId) {
        return jdbc.query(
                "select " + COLUMNS
                        + " from action_audit where incident_id = ?"
                        + " order by occurred_at, audit_id",
                this::mapRow,
                incidentId);
    }

    private ActionInvocation mapRow(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new ActionInvocation(
                resultSet.getString("audit_id"),
                resultSet.getString("incident_id"),
                resultSet.getString("execution_id"),
                resultSet.getString("action"),
                resultSet.getString("actor"),
                resultSet.getBoolean("allowed"),
                resultSet.getString("reason_code"),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant());
    }
}
