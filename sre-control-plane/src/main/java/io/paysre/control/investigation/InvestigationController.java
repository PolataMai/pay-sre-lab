package io.paysre.control.investigation;

import io.paysre.control.evidence.Evidence;
import io.paysre.control.evidence.EvidenceRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents/{incidentId}")
public final class InvestigationController {

    private final InvestigationOrchestrator orchestrator;
    private final InvestigationConclusionRepository repository;
    private final EvidenceRepository evidenceRepository;

    public InvestigationController(
            InvestigationOrchestrator orchestrator,
            InvestigationConclusionRepository repository,
            EvidenceRepository evidenceRepository) {
        this.orchestrator = orchestrator;
        this.repository = repository;
        this.evidenceRepository = evidenceRepository;
    }

    @PostMapping("/investigations")
    public InvestigationConclusion investigate(
            @PathVariable String incidentId,
            @RequestBody InvestigationSeed seed) {
        return orchestrator.investigate(incidentId, seed);
    }

    @GetMapping("/conclusion")
    public InvestigationConclusion getConclusion(@PathVariable String incidentId) {
        return repository.findByIncidentId(incidentId)
                .orElseThrow(() -> new ConclusionNotFoundException(incidentId));
    }

    @GetMapping("/evidence")
    public List<EvidenceView> getEvidence(@PathVariable String incidentId) {
        return evidenceRepository.findByIncidentId(incidentId).stream()
                .map(EvidenceView::from)
                .toList();
    }

    @ExceptionHandler(InvestigationEscalatedException.class)
    ProblemDetail escalated(InvestigationEscalatedException exception) {
        var detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, exception.getMessage());
        detail.setProperty("code", "INVESTIGATION_NEEDS_HUMAN");
        return detail;
    }

    @ExceptionHandler(ConclusionNotFoundException.class)
    ProblemDetail conclusionNotFound(ConclusionNotFoundException exception) {
        var detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setProperty("code", "CONCLUSION_NOT_FOUND");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(IllegalArgumentException exception) {
        var detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setProperty("code", "INVALID_INVESTIGATION_REQUEST");
        return detail;
    }

    private static final class ConclusionNotFoundException extends RuntimeException {
        private ConclusionNotFoundException(String incidentId) {
            super("investigation conclusion not found: " + incidentId);
        }
    }

    public record EvidenceView(
            String evidenceId,
            String evidenceType,
            String sourceTool,
            int sourceToolVersion,
            String sha256,
            Instant collectedAt) {

        private static EvidenceView from(Evidence evidence) {
            return new EvidenceView(
                    evidence.evidenceId(),
                    evidence.evidenceType(),
                    evidence.sourceTool(),
                    evidence.sourceToolVersion(),
                    evidence.sha256(),
                    evidence.collectedAt());
        }
    }
}
