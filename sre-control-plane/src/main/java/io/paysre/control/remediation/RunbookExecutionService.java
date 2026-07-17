package io.paysre.control.remediation;

import com.fasterxml.jackson.databind.JsonNode;
import io.paysre.control.incident.IncidentRepository;
import io.paysre.control.investigation.InvestigationConclusionRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the approval workflow: propose, approve (four-eyes) and run the
 * approved runbook inside a bounded executor. Success mitigates the
 * incident; any failure hands the incident back to a human.
 */
public final class RunbookExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RunbookExecutionService.class);

    private final ActionGuard guard;
    private final Map<String, Runbook> runbooks;
    private final RunbookExecutionRepository executionRepository;
    private final IncidentRepository incidentRepository;
    private final InvestigationConclusionRepository conclusionRepository;
    private final Clock clock;
    private final ExecutorService executor;
    private final Duration executionTimeout;
    private final Supplier<String> executionIds;

    public RunbookExecutionService(
            ActionGuard guard,
            List<Runbook> runbooks,
            RunbookExecutionRepository executionRepository,
            IncidentRepository incidentRepository,
            InvestigationConclusionRepository conclusionRepository,
            Clock clock,
            ExecutorService executor,
            Duration executionTimeout,
            Supplier<String> executionIds) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.runbooks = runbooks.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Runbook::name, runbook -> runbook));
        this.executionRepository =
                Objects.requireNonNull(executionRepository, "executionRepository");
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository");
        this.conclusionRepository =
                Objects.requireNonNull(conclusionRepository, "conclusionRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.executionTimeout = Objects.requireNonNull(executionTimeout, "executionTimeout");
        this.executionIds = Objects.requireNonNull(executionIds, "executionIds");
        if (executionTimeout.isZero() || executionTimeout.isNegative()) {
            throw new IllegalArgumentException("execution timeout must be positive");
        }
    }

    public RunbookExecution propose(String incidentId, String runbook, String requestedBy) {
        guard.authorizeProposal(incidentId, runbook, requestedBy);
        var execution = RunbookExecution.proposed(
                executionIds.get(), incidentId, runbook, requestedBy, clock.instant());
        var saved = executionRepository.save(execution);
        LOGGER.atInfo()
                .addKeyValue("event", "RUNBOOK_PROPOSED")
                .addKeyValue("incidentId", incidentId)
                .addKeyValue("executionId", saved.executionId())
                .addKeyValue("runbook", runbook)
                .addKeyValue("requestedBy", requestedBy)
                .log("Runbook execution proposed");
        return saved;
    }

    public RunbookExecution approveAndRun(
            String incidentId, String executionId, String approver) {
        var execution = guard.authorizeApproval(incidentId, executionId, approver);
        var incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ActionDeniedException(
                        "INCIDENT_NOT_FOUND", "incident does not exist: " + incidentId));
        var conclusion = conclusionRepository.findByIncidentId(incidentId)
                .orElseThrow(() -> new ActionDeniedException(
                        "CONCLUSION_MISSING", "no conclusion exists for " + incidentId));
        var runbook = runbooks.get(execution.runbook());
        if (runbook == null) {
            throw new ActionDeniedException(
                    "RUNBOOK_NOT_ALLOWED",
                    "no implementation is registered for " + execution.runbook());
        }

        execution.approve(approver, clock.instant());
        executionRepository.save(execution);

        try {
            var future = executor.submit(() -> runbook.execute(incident, conclusion));
            JsonNode result;
            try {
                result = future.get(executionTimeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                return failed(execution, incident, "RUNBOOK_TIMEOUT");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                return failed(execution, incident, "RUNBOOK_INTERRUPTED");
            } catch (ExecutionException exception) {
                return failed(execution, incident, rootMessage(exception));
            }
            execution.succeed(result, clock.instant());
            executionRepository.save(execution);
            incident.markMitigated(clock.instant());
            incidentRepository.save(incident);
            guard.recordExecutionOutcome(execution, true, null);
            LOGGER.atInfo()
                    .addKeyValue("event", "RUNBOOK_EXECUTION_SUCCEEDED")
                    .addKeyValue("incidentId", incidentId)
                    .addKeyValue("executionId", execution.executionId())
                    .addKeyValue("runbook", execution.runbook())
                    .addKeyValue("approvedBy", approver)
                    .log("Runbook execution succeeded");
            return execution;
        } catch (RejectedExecutionException exception) {
            return failed(execution, incident, "RUNBOOK_CAPACITY_EXHAUSTED");
        }
    }

    public List<RunbookExecution> findByIncidentId(String incidentId) {
        return executionRepository.findByIncidentId(incidentId);
    }

    private RunbookExecution failed(
            RunbookExecution execution,
            io.paysre.control.incident.Incident incident,
            String error) {
        execution.fail(error, clock.instant());
        executionRepository.save(execution);
        incident.markNeedsHuman(clock.instant());
        incidentRepository.save(incident);
        guard.recordExecutionOutcome(execution, false, error);
        LOGGER.atWarn()
                .addKeyValue("event", "RUNBOOK_EXECUTION_FAILED")
                .addKeyValue("incidentId", execution.incidentId())
                .addKeyValue("executionId", execution.executionId())
                .addKeyValue("runbook", execution.runbook())
                .addKeyValue("error", error)
                .log("Runbook execution failed");
        return execution;
    }

    private String rootMessage(ExecutionException exception) {
        return exception.getCause() == null
                ? exception.getMessage()
                : exception.getCause().getMessage();
    }
}
