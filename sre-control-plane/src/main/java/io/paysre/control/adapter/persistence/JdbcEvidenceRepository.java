package io.paysre.control.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.evidence.Evidence;
import io.paysre.control.evidence.EvidenceRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEvidenceRepository implements EvidenceRepository {

    private static final String COLUMNS = """
            evidence_id, incident_id, evidence_type, source_tool,
            source_tool_version, content, sha256, collected_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcEvidenceRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Evidence save(Evidence evidence) {
        jdbc.update("""
                        insert into evidence (
                            evidence_id, incident_id, evidence_type, source_tool,
                            source_tool_version, content, sha256, collected_at)
                        values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                        """,
                evidence.evidenceId(),
                evidence.incidentId(),
                evidence.evidenceType(),
                evidence.sourceTool(),
                evidence.sourceToolVersion(),
                evidence.content().toString(),
                evidence.sha256(),
                OffsetDateTime.ofInstant(evidence.collectedAt(), ZoneOffset.UTC));
        return evidence;
    }

    @Override
    public Optional<Evidence> findById(String evidenceId) {
        return jdbc.query(
                        "select " + COLUMNS + " from evidence where evidence_id = ?",
                        this::mapRow,
                        evidenceId)
                .stream()
                .findFirst();
    }

    @Override
    public List<Evidence> findByIncidentId(String incidentId) {
        return jdbc.query(
                "select " + COLUMNS
                        + " from evidence where incident_id = ? order by collected_at, evidence_id",
                this::mapRow,
                incidentId);
    }

    private Evidence mapRow(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new Evidence(
                resultSet.getString("evidence_id"),
                resultSet.getString("incident_id"),
                resultSet.getString("evidence_type"),
                resultSet.getString("source_tool"),
                resultSet.getInt("source_tool_version"),
                parseJson(resultSet.getString("content")),
                resultSet.getString("sha256").trim(),
                resultSet.getObject("collected_at", OffsetDateTime.class).toInstant());
    }

    private JsonNode parseJson(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return parsed.isTextual() ? objectMapper.readTree(parsed.asText()) : parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid evidence JSON", exception);
        }
    }
}
