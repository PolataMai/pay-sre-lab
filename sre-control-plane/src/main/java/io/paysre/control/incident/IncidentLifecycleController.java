package io.paysre.control.incident;

import io.paysre.control.remediation.ActionDeniedException;
import io.paysre.control.remediation.ActionGuard;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Human-only lifecycle transitions. Closing an incident goes through the
 * same ActionGuard audit trail as every other write-path action.
 */
@RestController
@RequestMapping("/api/incidents/{incidentId}")
public final class IncidentLifecycleController {

    private final ActionGuard guard;
    private final IncidentRepository repository;
    private final Clock clock;

    public IncidentLifecycleController(
            ActionGuard guard, IncidentRepository repository, Clock clock) {
        this.guard = guard;
        this.repository = repository;
        this.clock = clock;
    }

    @PostMapping("/resolution")
    public IncidentView resolve(
            @PathVariable String incidentId,
            @RequestBody ResolutionRequest request) {
        var incident = guard.authorizeResolution(incidentId, request.resolvedBy());
        incident.markResolved(clock.instant());
        var saved = repository.save(incident);
        return new IncidentView(saved.incidentId(), saved.status(), saved.updatedAt());
    }

    @ExceptionHandler(ActionDeniedException.class)
    ProblemDetail denied(ActionDeniedException exception) {
        var status = switch (exception.code()) {
            case "INCIDENT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ACTOR_REQUIRED" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.CONFLICT;
        };
        var detail = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        detail.setProperty("code", exception.code());
        return detail;
    }

    public record ResolutionRequest(String resolvedBy) {
    }

    public record IncidentView(String incidentId, IncidentStatus status, Instant updatedAt) {
    }
}
