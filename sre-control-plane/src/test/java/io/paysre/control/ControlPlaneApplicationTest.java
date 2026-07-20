package io.paysre.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.paysre.control.incident.IncidentApplicationService;
import io.paysre.control.incident.IncidentRepository;
import io.paysre.control.investigation.InvestigationConclusionRepository;
import io.paysre.control.investigation.InvestigationOrchestrator;
import io.paysre.control.tools.ToolGateway;
import io.paysre.control.tools.ToolHandler;
import java.util.List;
import io.paysre.control.alerting.AlertSignal;
import io.paysre.control.alerting.Severity;
import java.time.Instant;
import java.util.Map;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = ControlPlaneApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:control-app;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.datasource.username=sa",
            "spring.datasource.password="
        })
class ControlPlaneApplicationTest {

    @Autowired
    private IncidentApplicationService incidentService;

    @Autowired
    private IncidentRepository repository;

    @Autowired
    private ToolGateway toolGateway;

    @Autowired
    private List<ToolHandler<?, ?>> toolHandlers;

    @Autowired
    private InvestigationOrchestrator investigationOrchestrator;

    @Autowired
    private InvestigationConclusionRepository conclusionRepository;

    @Autowired
    private PrometheusMeterRegistry prometheusRegistry;

    @Autowired
    private OpenTelemetry openTelemetry;

    @Test
    void wiresIncidentIngestionAndRunsTheSchemaMigration() {
        assertThat(incidentService).isNotNull();
        assertThat(repository.findById("MISSING")).isEmpty();
        assertThat(toolGateway).isNotNull();
        assertThat(investigationOrchestrator).isNotNull();
        assertThat(conclusionRepository.findByIncidentId("MISSING")).isEmpty();
        assertThat(prometheusRegistry).isNotNull();
        assertThat(openTelemetry).isNotNull();
        assertThat(toolHandlers)
                .extracting(handler -> handler.definition().name())
                .containsExactlyInAnyOrder(
                        "get_payment_timeline",
                        "query_channel_final_state",
                        "calculate_incident_impact",
                        "query_service_metrics",
                        "search_structured_logs",
                        "get_distributed_trace");
    }

    @Test
    void incidentBusinessFieldsDoNotCollideWithReservedEcsMembers() {
        var loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        long errorsBefore = loggerContext.getStatusManager().getCopyOfStatusList().stream()
                .filter(status -> status.getLevel() == Status.ERROR)
                .count();

        incidentService.ingest(new AlertSignal(
                "ALERT-STRUCTURED-LOG",
                "TEST",
                "payment-service",
                "PaymentUnknownHigh",
                Severity.HIGH,
                Instant.parse("2026-07-16T10:00:00Z"),
                Map.of("channel", "CHANNEL_A"),
                null,
                null));

        assertThat(loggerContext.getStatusManager().getCopyOfStatusList().stream()
                        .filter(status -> status.getLevel() == Status.ERROR)
                        .count())
                .isEqualTo(errorsBefore);
    }
}
