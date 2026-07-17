package io.paysre.control.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.remediation.RunbookExecution;
import io.paysre.control.remediation.RunbookExecutionRepository;
import io.paysre.control.remediation.RunbookExecutionStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRunbookExecutionRepository implements RunbookExecutionRepository {

    private static final String COLUMNS = """
            execution_id, incident_id, runbook, status, requested_by, approved_by,
            result, error, version, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcRunbookExecutionRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public RunbookExecution save(RunbookExecution execution) {
        if (!execution.persisted()) {
            jdbc.update("""
                            insert into runbook_execution (
                                execution_id, incident_id, runbook, status, requested_by,
                                approved_by, result, error, version, created_at, updated_at)
                            values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?)
                            """,
                    execution.executionId(),
                    execution.incidentId(),
                    execution.runbook(),
                    execution.status().name(),
                    execution.requestedBy(),
                    execution.approvedBy(),
                    serialize(execution.result()),
                    execution.error(),
                    0L,
                    OffsetDateTime.ofInstant(execution.createdAt(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(execution.updatedAt(), ZoneOffset.UTC));
            execution.markPersisted(0L);
            return execution;
        }
        long nextVersion = execution.version() + 1;
        int updated = jdbc.update("""
                        update runbook_execution
                           set status = ?, approved_by = ?, result = cast(? as jsonb),
                               error = ?, version = ?, updated_at = ?
                         where execution_id = ? and version = ?
                        """,
                execution.status().name(),
                execution.approvedBy(),
                serialize(execution.result()),
                execution.error(),
                nextVersion,
                OffsetDateTime.ofInstant(execution.updatedAt(), ZoneOffset.UTC),
                execution.executionId(),
                execution.version());
        if (updated != 1) {
            throw new OptimisticLockingFailureException(
                    "runbook execution version changed: " + execution.executionId());
        }
        execution.markPersisted(nextVersion);
        return execution;
    }

    @Override
    public Optional<RunbookExecution> findById(String executionId) {
        return findOne(
                "select " + COLUMNS + " from runbook_execution where execution_id = ?",
                executionId);
    }

    @Override
    public List<RunbookExecution> findByIncidentId(String incidentId) {
        return jdbc.query(
                "select " + COLUMNS
                        + " from runbook_execution where incident_id = ?"
                        + " order by created_at, execution_id",
                this::mapRow,
                incidentId);
    }

    @Override
    public Optional<RunbookExecution> findActiveByIncidentId(String incidentId) {
        var rows = jdbc.query(
                "select " + COLUMNS
                        + " from runbook_execution where incident_id = ?"
                        + " and status in ('PENDING_APPROVAL', 'RUNNING')"
                        + " order by created_at, execution_id",
                this::mapRow,
                incidentId);
        return rows.stream().findFirst();
    }

    private Optional<RunbookExecution> findOne(String sql, Object... arguments) {
        return jdbc.query(sql, this::mapRow, arguments).stream().findFirst();
    }

    private RunbookExecution mapRow(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return RunbookExecution.restore(
                resultSet.getString("execution_id"),
                resultSet.getString("incident_id"),
                resultSet.getString("runbook"),
                RunbookExecutionStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("requested_by"),
                resultSet.getString("approved_by"),
                parse(resultSet.getString("result")),
                resultSet.getString("error"),
                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                resultSet.getObject("updated_at", OffsetDateTime.class).toInstant(),
                resultSet.getLong("version"));
    }

    private String serialize(JsonNode result) {
        if (result == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize runbook result", exception);
        }
    }

    private JsonNode parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            var parsed = objectMapper.readTree(value);
            if (parsed.isTextual()) {
                parsed = objectMapper.readTree(parsed.asText());
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid runbook result JSON", exception);
        }
    }
}
