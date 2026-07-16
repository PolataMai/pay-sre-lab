package io.paysre.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.paysre.control.incident.IncidentApplicationService;
import io.paysre.control.incident.IncidentRepository;
import io.paysre.control.investigation.InvestigationConclusionRepository;
import io.paysre.control.investigation.InvestigationOrchestrator;
import io.paysre.control.tools.ToolGateway;
import io.paysre.control.tools.ToolHandler;
import java.util.List;
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

    @Test
    void wiresIncidentIngestionAndRunsTheSchemaMigration() {
        assertThat(incidentService).isNotNull();
        assertThat(repository.findById("MISSING")).isEmpty();
        assertThat(toolGateway).isNotNull();
        assertThat(investigationOrchestrator).isNotNull();
        assertThat(conclusionRepository.findByIncidentId("MISSING")).isEmpty();
        assertThat(toolHandlers)
                .extracting(handler -> handler.definition().name())
                .containsExactlyInAnyOrder(
                        "get_payment_timeline",
                        "query_channel_final_state",
                        "calculate_incident_impact");
    }
}
