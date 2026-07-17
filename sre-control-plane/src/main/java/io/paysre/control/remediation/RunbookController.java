package io.paysre.control.remediation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents/{incidentId}/runbook-executions")
public final class RunbookController {

    private static final Set<String> NOT_FOUND_CODES =
            Set.of("INCIDENT_NOT_FOUND", "EXECUTION_NOT_FOUND");
    private static final Set<String> FORBIDDEN_CODES =
            Set.of("FOUR_EYES_REQUIRED", "ACTOR_REQUIRED");

    private final RunbookExecutionService service;

    public RunbookController(RunbookExecutionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunbookExecutionView propose(
            @PathVariable String incidentId,
            @RequestBody ProposalRequest request) {
        return RunbookExecutionView.from(service.propose(
                incidentId, request.runbook(), request.requestedBy()));
    }

    @PostMapping("/{executionId}/approval")
    public RunbookExecutionView approve(
            @PathVariable String incidentId,
            @PathVariable String executionId,
            @RequestBody ApprovalRequest request) {
        return RunbookExecutionView.from(service.approveAndRun(
                incidentId, executionId, request.approver()));
    }

    @GetMapping
    public List<RunbookExecutionView> list(@PathVariable String incidentId) {
        return service.findByIncidentId(incidentId).stream()
                .map(RunbookExecutionView::from)
                .toList();
    }

    @ExceptionHandler(ActionDeniedException.class)
    ProblemDetail denied(ActionDeniedException exception) {
        HttpStatus status;
        if (NOT_FOUND_CODES.contains(exception.code())) {
            status = HttpStatus.NOT_FOUND;
        } else if (FORBIDDEN_CODES.contains(exception.code())) {
            status = HttpStatus.FORBIDDEN;
        } else {
            status = HttpStatus.CONFLICT;
        }
        var detail = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        detail.setProperty("code", exception.code());
        return detail;
    }

    public record ProposalRequest(String runbook, String requestedBy) {
        public ProposalRequest {
            Objects.requireNonNull(runbook, "runbook");
        }
    }

    public record ApprovalRequest(String approver) {
    }

    public record RunbookExecutionView(
            String executionId,
            String incidentId,
            String runbook,
            RunbookExecutionStatus status,
            String requestedBy,
            String approvedBy,
            JsonNode result,
            String error,
            Instant createdAt,
            Instant updatedAt) {

        static RunbookExecutionView from(RunbookExecution execution) {
            return new RunbookExecutionView(
                    execution.executionId(),
                    execution.incidentId(),
                    execution.runbook(),
                    execution.status(),
                    execution.requestedBy(),
                    execution.approvedBy(),
                    execution.result(),
                    execution.error(),
                    execution.createdAt(),
                    execution.updatedAt());
        }
    }
}
