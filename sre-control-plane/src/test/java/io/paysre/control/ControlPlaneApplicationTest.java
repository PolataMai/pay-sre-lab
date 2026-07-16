package io.paysre.control;

import static org.assertj.core.api.Assertions.assertThat;

import io.paysre.control.incident.IncidentApplicationService;
import io.paysre.control.incident.IncidentRepository;
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

    @Test
    void wiresIncidentIngestionAndRunsTheSchemaMigration() {
        assertThat(incidentService).isNotNull();
        assertThat(repository.findById("MISSING")).isEmpty();
    }
}
