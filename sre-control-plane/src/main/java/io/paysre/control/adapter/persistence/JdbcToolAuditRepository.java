package io.paysre.control.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.tools.ToolAuditRepository;
import io.paysre.control.tools.ToolInvocation;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcToolAuditRepository implements ToolAuditRepository {

    private static final String COLUMNS = """
            invocation_id, incident_id, agent_id, tool_name, tool_version,
            successful, evidence_ids, duration_millis, error_code, invoked_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcToolAuditRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(ToolInvocation invocation) {
        jdbc.update("""
                        insert into tool_invocation (
                            invocation_id, incident_id, agent_id, tool_name, tool_version,
                            successful, evidence_ids, duration_millis, error_code, invoked_at)
                        values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
                        """,
                invocation.invocationId(),
                invocation.incidentId(),
                invocation.agentId(),
                invocation.toolName(),
                invocation.toolVersion(),
                invocation.successful(),
                serialize(invocation),
                invocation.duration().toMillis(),
                invocation.errorCode(),
                OffsetDateTime.ofInstant(invocation.invokedAt(), ZoneOffset.UTC));
    }

    @Override
    public List<ToolInvocation> findByIncidentId(String incidentId) {
        return jdbc.query(
                "select " + COLUMNS
                        + " from tool_invocation where incident_id = ?"
                        + " order by invoked_at, invocation_id",
                this::mapRow,
                incidentId);
    }

    private ToolInvocation mapRow(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new ToolInvocation(
                resultSet.getString("invocation_id"),
                resultSet.getString("incident_id"),
                resultSet.getString("agent_id"),
                resultSet.getString("tool_name"),
                resultSet.getInt("tool_version"),
                resultSet.getBoolean("successful"),
                parseEvidenceIds(resultSet.getString("evidence_ids")),
                Duration.ofMillis(resultSet.getLong("duration_millis")),
                resultSet.getString("error_code"),
                resultSet.getObject("invoked_at", OffsetDateTime.class).toInstant());
    }

    private List<String> parseEvidenceIds(String value) {
        try {
            var parsed = objectMapper.readTree(value);
            if (parsed.isTextual()) {
                parsed = objectMapper.readTree(parsed.asText());
            }
            var result = new ArrayList<String>();
            parsed.forEach(item -> result.add(item.asText()));
            return List.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid tool audit evidence IDs", exception);
        }
    }

    private String serialize(ToolInvocation invocation) {
        try {
            return objectMapper.writeValueAsString(invocation.evidenceIds());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize evidence IDs", exception);
        }
    }
}
