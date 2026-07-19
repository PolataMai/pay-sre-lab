package io.paysre.control.remediation;

import io.paysre.control.incident.Incident;
import io.paysre.control.incident.IncidentRepository;
import io.paysre.control.incident.IncidentStatus;
import io.paysre.control.investigation.InvestigationConclusionRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The single gate in front of every write-path action. Each authorization
 * attempt, allowed or denied, leaves an action audit record. The
 * investigation model has no path to this class: actors are human
 * identities supplied through the REST API.
 */
public final class ActionGuard {

    private final Set<String> allowedRunbooks;
    private final IncidentRepository incidentRepository;
    private final InvestigationConclusionRepository conclusionRepository;
    private final RunbookExecutionRepository executionRepository;
    private final ActionAuditRepository auditRepository;
    private final Clock clock;
    private final Supplier<String> auditIds;

    public ActionGuard(
            Set<String> allowedRunbooks,
            IncidentRepository incidentRepository,
            InvestigationConclusionRepository conclusionRepository,
            RunbookExecutionRepository executionRepository,
            ActionAuditRepository auditRepository,
            Clock clock,
            Supplier<String> auditIds) {
        this.allowedRunbooks = Set.copyOf(allowedRunbooks);
        this.incidentRepository = Objects.requireNonNull(incidentRepository, "incidentRepository");
        this.conclusionRepository =
                Objects.requireNonNull(conclusionRepository, "conclusionRepository");
        this.executionRepository =
                Objects.requireNonNull(executionRepository, "executionRepository");
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.auditIds = Objects.requireNonNull(auditIds, "auditIds");
    }

    public Incident authorizeProposal(String incidentId, String runbook, String requestedBy) {
        var action = "RUNBOOK_PROPOSED";
        if (requestedBy == null || requestedBy.isBlank()) {
            throw denied(incidentId, null, action, "anonymous", "ACTOR_REQUIRED",
                    "a runbook proposal requires a named human proposer");
        }
        if (!allowedRunbooks.contains(runbook)) {
            throw denied(incidentId, null, action, requestedBy, "RUNBOOK_NOT_ALLOWED",
                    "runbook is not in the allowlist: " + runbook);
        }
        var incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null) {
            throw denied(incidentId, null, action, requestedBy, "INCIDENT_NOT_FOUND",
                    "incident does not exist: " + incidentId);
        }
        if (incident.status() != IncidentStatus.MITIGATION_PROPOSED) {
            throw denied(incidentId, null, action, requestedBy, "INCIDENT_NOT_ACTIONABLE",
                    "incident is not awaiting mitigation: " + incident.status());
        }
        var conclusion = conclusionRepository.findByIncidentId(incidentId).orElse(null);
        if (conclusion == null) {
            throw denied(incidentId, null, action, requestedBy, "CONCLUSION_MISSING",
                    "no validated conclusion exists for incident " + incidentId);
        }
        if (!conclusion.recommendedRunbook().equals(runbook)) {
            throw denied(incidentId, null, action, requestedBy, "RUNBOOK_MISMATCH",
                    "conclusion recommends " + conclusion.recommendedRunbook());
        }
        if (!conclusion.requiresHumanReview()) {
            throw denied(incidentId, null, action, requestedBy, "HUMAN_REVIEW_FLAG_MISSING",
                    "conclusion does not require human review");
        }
        if (executionRepository.findActiveByIncidentId(incidentId).isPresent()) {
            throw denied(incidentId, null, action, requestedBy, "EXECUTION_ALREADY_ACTIVE",
                    "another runbook execution is already pending or running");
        }
        audit(incidentId, null, action, requestedBy, true, null);
        return incident;
    }

    public RunbookExecution authorizeApproval(
            String incidentId, String executionId, String approver) {
        var action = "RUNBOOK_APPROVED";
        if (approver == null || approver.isBlank()) {
            throw denied(incidentId, executionId, action, "anonymous", "ACTOR_REQUIRED",
                    "a runbook approval requires a named human approver");
        }
        var execution = executionRepository.findById(executionId)
                .filter(item -> item.incidentId().equals(incidentId))
                .orElse(null);
        if (execution == null) {
            throw denied(incidentId, executionId, action, approver, "EXECUTION_NOT_FOUND",
                    "runbook execution does not exist for this incident: " + executionId);
        }
        if (execution.status() != RunbookExecutionStatus.PENDING_APPROVAL) {
            throw denied(incidentId, executionId, action, approver, "EXECUTION_NOT_APPROVABLE",
                    "runbook execution is " + execution.status());
        }
        if (approver.equals(execution.requestedBy())) {
            throw denied(incidentId, executionId, action, approver, "FOUR_EYES_REQUIRED",
                    "the approver must differ from the proposer");
        }
        audit(incidentId, executionId, action, approver, true, null);
        return execution;
    }

    public Incident authorizeResolution(String incidentId, String resolvedBy) {
        var action = "INCIDENT_RESOLVED";
        if (resolvedBy == null || resolvedBy.isBlank()) {
            throw denied(incidentId, null, action, "anonymous", "ACTOR_REQUIRED",
                    "an incident resolution requires a named human");
        }
        var incident = incidentRepository.findById(incidentId).orElse(null);
        if (incident == null) {
            throw denied(incidentId, null, action, resolvedBy, "INCIDENT_NOT_FOUND",
                    "incident does not exist: " + incidentId);
        }
        if (incident.status() != IncidentStatus.MITIGATED
                && incident.status() != IncidentStatus.NEEDS_HUMAN) {
            throw denied(incidentId, null, action, resolvedBy, "INCIDENT_NOT_RESOLVABLE",
                    "only mitigated or needs-human incidents can be resolved, was "
                            + incident.status());
        }
        audit(incidentId, null, action, resolvedBy, true, null);
        return incident;
    }

    public void recordExecutionOutcome(
            RunbookExecution execution, boolean successful, String reasonCode) {
        audit(
                execution.incidentId(),
                execution.executionId(),
                "RUNBOOK_EXECUTED",
                execution.approvedBy() == null ? "system" : execution.approvedBy(),
                successful,
                reasonCode);
    }

    private ActionDeniedException denied(
            String incidentId,
            String executionId,
            String action,
            String actor,
            String code,
            String message) {
        audit(incidentId, executionId, action, actor, false, code);
        return new ActionDeniedException(code, message);
    }

    private void audit(
            String incidentId,
            String executionId,
            String action,
            String actor,
            boolean allowed,
            String reasonCode) {
        auditRepository.record(new ActionInvocation(
                auditIds.get(),
                incidentId,
                executionId,
                action,
                actor,
                allowed,
                reasonCode,
                clock.instant()));
    }
}
