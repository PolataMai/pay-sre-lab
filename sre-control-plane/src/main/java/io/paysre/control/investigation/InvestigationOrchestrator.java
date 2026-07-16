package io.paysre.control.investigation;

import io.paysre.control.evidence.EvidenceRepository;
import io.paysre.control.incident.Incident;
import io.paysre.control.incident.IncidentRepository;
import io.paysre.control.tools.ToolGateway;
import io.paysre.control.tools.ToolResult;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InvestigationOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(InvestigationOrchestrator.class);

    private static final int MAX_TOOL_CALLS = 12;
    private static final int MAX_CONSECUTIVE_EMPTY_RESULTS = 3;
    private static final int MAX_INVALID_CONCLUSIONS = 2;
    private static final Duration TOTAL_DEADLINE = Duration.ofSeconds(120);

    private final IncidentRepository incidentRepository;
    private final EvidenceRepository evidenceRepository;
    private final InvestigationConclusionRepository conclusionRepository;
    private final InvestigationModel model;
    private final ToolGateway toolGateway;
    private final ConclusionValidator validator;
    private final Clock clock;
    private final ExecutorService modelExecutor;
    private final Duration modelTimeout;

    public InvestigationOrchestrator(
            IncidentRepository incidentRepository,
            EvidenceRepository evidenceRepository,
            InvestigationConclusionRepository conclusionRepository,
            InvestigationModel model,
            ToolGateway toolGateway,
            ConclusionValidator validator,
            Clock clock,
            ExecutorService modelExecutor,
            Duration modelTimeout) {
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository");
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository");
        this.conclusionRepository = Objects.requireNonNull(conclusionRepository, "conclusionRepository");
        this.model = Objects.requireNonNull(model, "model");
        this.toolGateway = Objects.requireNonNull(toolGateway, "toolGateway");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.modelExecutor = Objects.requireNonNull(modelExecutor, "modelExecutor");
        this.modelTimeout = Objects.requireNonNull(modelTimeout, "modelTimeout");
        if (modelTimeout.isZero() || modelTimeout.isNegative()) {
            throw new IllegalArgumentException("model timeout must be positive");
        }
    }

    public InvestigationConclusion investigate(String incidentId, InvestigationSeed seed) {
        var existing = conclusionRepository.findByIncidentId(incidentId);
        if (existing.isPresent()) {
            return existing.get();
        }
        var incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "incident does not exist: " + incidentId));
        incident.markInvestigating(clock.instant());
        incidentRepository.save(incident);

        var deadline = clock.instant().plus(TOTAL_DEADLINE);
        var results = new ArrayList<ToolResult>();
        int toolCalls = 0;
        int consecutiveEmptyResults = 0;
        int invalidConclusions = 0;
        String lastValidationError = null;

        while (toolCalls < MAX_TOOL_CALLS) {
            if (!clock.instant().isBefore(deadline)) {
                return escalate(incident, "investigation exceeded the 120 second deadline");
            }
            var context = new InvestigationContext(
                    incident,
                    seed,
                    evidenceRepository.findByIncidentId(incidentId),
                    results,
                    lastValidationError);
            InvestigationDecision decision;
            try {
                decision = decideWithOneTimeoutRetry(context);
            } catch (ModelDecisionException exception) {
                return escalate(incident, "investigation model failed: " + exception.getMessage());
            }
            if (decision == null) {
                return escalate(incident, "investigation model returned no structured decision");
            }

            if (decision instanceof InvestigationDecision.CallTool callTool) {
                toolCalls++;
                var result = toolGateway.execute(
                        incidentId,
                        "investigation-agent",
                        callTool.toolName(),
                        callTool.arguments());
                results.add(result);
                consecutiveEmptyResults = result.evidenceIds().isEmpty()
                        ? consecutiveEmptyResults + 1
                        : 0;
                if (consecutiveEmptyResults >= MAX_CONSECUTIVE_EMPTY_RESULTS) {
                    return escalate(incident, "three decisions without new evidence");
                }
                continue;
            }
            if (decision instanceof InvestigationDecision.Conclude conclude) {
                try {
                    var validated = validator.validate(incident, conclude.conclusion());
                    conclusionRepository.save(validated);
                    incident.markMitigationProposed(clock.instant());
                    incidentRepository.save(incident);
                    LOGGER.atInfo()
                            .addKeyValue("event", "INCIDENT_INVESTIGATION_COMPLETED")
                            .addKeyValue("incidentId", incident.incidentId())
                            .addKeyValue("rootCause", validated.rootCause().name())
                            .addKeyValue("confidence", validated.confidence())
                            .addKeyValue("evidenceCount", validated.evidenceIds().size())
                            .log("Incident investigation completed");
                    return validated;
                } catch (InvalidConclusionException exception) {
                    invalidConclusions++;
                    lastValidationError = exception.getMessage();
                    if (invalidConclusions >= MAX_INVALID_CONCLUSIONS) {
                        return escalate(
                                incident,
                                "conclusion validation failed twice: " + lastValidationError);
                    }
                    continue;
                }
            }
            var escalation = (InvestigationDecision.Escalate) decision;
            return escalate(incident, escalation.reason());
        }
        return escalate(incident, "investigation exceeded the 12 tool call limit");
    }

    private InvestigationDecision decideWithOneTimeoutRetry(InvestigationContext context) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            java.util.concurrent.Future<InvestigationDecision> future;
            try {
                future = modelExecutor.submit(() -> model.decide(context));
            } catch (RejectedExecutionException exception) {
                throw new ModelDecisionException("decision capacity is exhausted", exception);
            }
            try {
                return future.get(modelTimeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                if (attempt == 2) {
                    throw new ModelDecisionException("decision timed out twice", exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                throw new ModelDecisionException("decision was interrupted", exception);
            } catch (ExecutionException exception) {
                throw new ModelDecisionException(
                        "decision execution failed: " + rootMessage(exception), exception);
            }
        }
        throw new IllegalStateException("unreachable model retry state");
    }

    private String rootMessage(ExecutionException exception) {
        return exception.getCause() == null
                ? exception.getMessage()
                : exception.getCause().getMessage();
    }

    private InvestigationConclusion escalate(Incident incident, String reason) {
        incident.markNeedsHuman(clock.instant());
        incidentRepository.save(incident);
        LOGGER.atWarn()
                .addKeyValue("event", "INCIDENT_INVESTIGATION_ESCALATED")
                .addKeyValue("incidentId", incident.incidentId())
                .addKeyValue("reason", reason)
                .log("Incident investigation escalated");
        throw new InvestigationEscalatedException(reason);
    }

    private static final class ModelDecisionException extends RuntimeException {
        private ModelDecisionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
