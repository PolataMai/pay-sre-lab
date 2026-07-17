package io.paysre.control.remediation;

import java.util.List;
import java.util.Optional;

public interface RunbookExecutionRepository {

    RunbookExecution save(RunbookExecution execution);

    Optional<RunbookExecution> findById(String executionId);

    List<RunbookExecution> findByIncidentId(String incidentId);

    Optional<RunbookExecution> findActiveByIncidentId(String incidentId);
}
