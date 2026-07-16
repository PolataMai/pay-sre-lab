package io.paysre.control.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.contracts.Money;
import io.paysre.control.adapter.persistence.JdbcInvestigationConclusionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcInvestigationConclusionRepositoryTest {

    private JdbcInvestigationConclusionRepository repository;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:conclusion;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists investigation_conclusion");
        jdbc.execute("""
                create table investigation_conclusion (
                    incident_id varchar(40) primary key,
                    root_cause varchar(80) not null,
                    confidence numeric(5, 4) not null,
                    evidence_ids jsonb not null,
                    affected_payment_count bigint not null,
                    affected_amount numeric(20, 4) not null,
                    currency char(3) not null,
                    recommended_runbook varchar(120) not null,
                    requires_human_review boolean not null,
                    created_at timestamp with time zone not null
                )
                """);
        repository = new JdbcInvestigationConclusionRepository(
                jdbc,
                new ObjectMapper(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @Test
    void roundTripsStructuredConclusionWithoutChangingCurrencyScale() {
        var conclusion = new InvestigationConclusion(
                "INC-01",
                RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST,
                new BigDecimal("0.9500"),
                List.of("E1", "E2", "E3"),
                5,
                new Money(new BigDecimal("50.00"), Currency.getInstance("CNY")),
                "query-and-sync-unknown-payments",
                true);

        repository.save(conclusion);

        assertThat(repository.findByIncidentId("INC-01")).contains(conclusion);
        assertThat(repository.findByIncidentId("MISSING")).isEmpty();
    }
}
