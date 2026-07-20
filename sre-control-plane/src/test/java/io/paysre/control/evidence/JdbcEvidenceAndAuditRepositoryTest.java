package io.paysre.control.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.adapter.persistence.JdbcEvidenceRepository;
import io.paysre.control.adapter.persistence.JdbcToolAuditRepository;
import io.paysre.control.tools.ToolInvocation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcEvidenceAndAuditRepositoryTest {

    private JdbcTemplate jdbc;
    private JdbcEvidenceRepository evidenceRepository;
    private JdbcToolAuditRepository auditRepository;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:evidence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists tool_invocation");
        jdbc.execute("drop table if exists evidence");
        jdbc.execute("""
                create table evidence (
                    evidence_id varchar(40) primary key,
                    incident_id varchar(40) not null,
                    evidence_type varchar(64) not null,
                    source_tool varchar(80) not null,
                    source_tool_version integer not null,
                    content jsonb not null,
                    sha256 char(64) not null,
                    collected_at timestamp with time zone not null
                )
                """);
        jdbc.execute("""
                create table tool_invocation (
                    invocation_id varchar(40) primary key,
                    incident_id varchar(40) not null,
                    agent_id varchar(80) not null,
                    tool_name varchar(80) not null,
                    tool_version integer not null,
                    successful boolean not null,
                    evidence_ids jsonb not null,
                    duration_millis bigint not null,
                    error_code varchar(64),
                    invoked_at timestamp with time zone not null
                )
                """);
        evidenceRepository = new JdbcEvidenceRepository(jdbc, new ObjectMapper());
        auditRepository = new JdbcToolAuditRepository(jdbc, new ObjectMapper());
    }

    @Test
    void roundTripsEvidenceAndPersistsTheToolAudit() {
        var content = new ObjectMapper().createObjectNode().put("result", "SUCCESS");
        var evidence = new Evidence(
                "EVD-1",
                "INC-01",
                "CHANNEL_FINAL_STATE",
                "query_channel_final_state",
                1,
                content,
                "a".repeat(64),
                Instant.EPOCH);

        evidenceRepository.save(evidence);
        auditRepository.record(new ToolInvocation(
                "INV-1",
                "INC-01",
                "agent-1",
                "query_channel_final_state",
                1,
                true,
                List.of("EVD-1"),
                Duration.ofMillis(12),
                null,
                Instant.EPOCH));

        assertThat(evidenceRepository.findById("EVD-1")).contains(evidence);
        assertThat(evidenceRepository.findByIncidentId("INC-01"))
                .containsExactly(evidence);
        assertThat(auditRepository.findByIncidentId("INC-01"))
                .singleElement()
                .satisfies(invocation -> {
                    assertThat(invocation.toolName())
                            .isEqualTo("query_channel_final_state");
                    assertThat(invocation.evidenceIds()).containsExactly("EVD-1");
                    assertThat(invocation.duration()).isEqualTo(Duration.ofMillis(12));
                });
        assertThat(jdbc.queryForObject(
                        "select count(*) from tool_invocation where incident_id = 'INC-01'",
                        Integer.class))
                .isEqualTo(1);
    }
}
