package io.paysre.control.remediation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.adapter.persistence.JdbcActionAuditRepository;
import io.paysre.control.adapter.persistence.JdbcRunbookExecutionRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcRunbookRepositoriesTest {

    private static final Instant NOW = Instant.parse("2026-07-17T04:00:00Z");

    private JdbcRunbookExecutionRepository executions;
    private JdbcActionAuditRepository audits;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:runbooks;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists runbook_execution");
        jdbc.execute("drop table if exists action_audit");
        jdbc.execute("""
                create table runbook_execution (
                    execution_id varchar(60) primary key,
                    incident_id varchar(40) not null,
                    runbook varchar(120) not null,
                    status varchar(30) not null,
                    requested_by varchar(120) not null,
                    approved_by varchar(120),
                    result jsonb,
                    error varchar(1000),
                    version bigint not null,
                    created_at timestamp with time zone not null,
                    updated_at timestamp with time zone not null
                )
                """);
        jdbc.execute("""
                create table action_audit (
                    audit_id varchar(60) primary key,
                    incident_id varchar(40) not null,
                    execution_id varchar(60),
                    action varchar(60) not null,
                    actor varchar(120) not null,
                    allowed boolean not null,
                    reason_code varchar(120),
                    occurred_at timestamp with time zone not null
                )
                """);
        var objectMapper = new ObjectMapper();
        executions = new JdbcRunbookExecutionRepository(jdbc, objectMapper);
        audits = new JdbcActionAuditRepository(jdbc);
    }

    @Test
    void persistsTheFullExecutionLifecycleWithOptimisticLocking() {
        var execution = RunbookExecution.proposed(
                "RUN-1", "INC-1", QueryAndSyncUnknownPaymentsRunbook.NAME, "operator-a", NOW);
        executions.save(execution);

        execution.approve("operator-b", NOW.plusSeconds(10));
        executions.save(execution);
        execution.succeed(
                new ObjectMapper().createObjectNode().put("synced", 5),
                NOW.plusSeconds(20));
        executions.save(execution);

        var reloaded = executions.findById("RUN-1").orElseThrow();
        assertThat(reloaded.status()).isEqualTo(RunbookExecutionStatus.SUCCEEDED);
        assertThat(reloaded.approvedBy()).isEqualTo("operator-b");
        assertThat(reloaded.result().path("synced").asInt()).isEqualTo(5);
        assertThat(reloaded.version()).isEqualTo(2);
        assertThat(executions.findByIncidentId("INC-1")).hasSize(1);
        assertThat(executions.findActiveByIncidentId("INC-1")).isEmpty();
    }

    @Test
    void findsActiveExecutionsWhilePendingOrRunning() {
        var execution = RunbookExecution.proposed(
                "RUN-2", "INC-2", QueryAndSyncUnknownPaymentsRunbook.NAME, "operator-a", NOW);
        executions.save(execution);

        assertThat(executions.findActiveByIncidentId("INC-2")).isPresent();
    }

    @Test
    void recordsAndReadsActionAuditsInOrder() {
        audits.record(new ActionInvocation(
                "ACT-1", "INC-1", null, "RUNBOOK_PROPOSED", "operator-a",
                true, null, NOW));
        audits.record(new ActionInvocation(
                "ACT-2", "INC-1", "RUN-1", "RUNBOOK_APPROVED", "operator-b",
                false, "FOUR_EYES_REQUIRED", NOW.plusSeconds(1)));

        var records = audits.findByIncidentId("INC-1");

        assertThat(records).hasSize(2);
        assertThat(records.get(0).action()).isEqualTo("RUNBOOK_PROPOSED");
        assertThat(records.get(1).allowed()).isFalse();
        assertThat(records.get(1).reasonCode()).isEqualTo("FOUR_EYES_REQUIRED");
    }
}
