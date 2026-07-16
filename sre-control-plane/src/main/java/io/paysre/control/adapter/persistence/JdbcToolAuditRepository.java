package io.paysre.control.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.tools.ToolAuditRepository;
import io.paysre.control.tools.ToolInvocation;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcToolAuditRepository implements ToolAuditRepository {

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

    private String serialize(ToolInvocation invocation) {
        try {
            return objectMapper.writeValueAsString(invocation.evidenceIds());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize evidence IDs", exception);
        }
    }
}
