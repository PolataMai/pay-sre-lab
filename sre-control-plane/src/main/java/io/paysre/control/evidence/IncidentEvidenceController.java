package io.paysre.control.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.control.tools.ToolAuditRepository;
import io.paysre.control.tools.ToolInvocation;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/incidents/{incidentId}")
public final class IncidentEvidenceController {

    private final EvidenceRepository evidenceRepository;
    private final ToolAuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    public IncidentEvidenceController(
            EvidenceRepository evidenceRepository, ToolAuditRepository auditRepository,
            ObjectMapper objectMapper) {
        this.evidenceRepository = evidenceRepository;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/evidence/{evidenceId}")
    public EvidenceDetails getEvidence(
            @PathVariable String incidentId, @PathVariable String evidenceId) {
        return evidenceRepository.findById(evidenceId)
                .filter(item -> item.incidentId().equals(incidentId))
                .map(item -> EvidenceDetails.from(item, objectMapper))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "incident evidence not found"));
    }

    @GetMapping("/tool-audits")
    public List<ToolInvocation> getToolAudits(@PathVariable String incidentId) {
        return auditRepository.findByIncidentId(incidentId);
    }

    public record EvidenceDetails(
            String evidenceId,
            String evidenceType,
            String sourceTool,
            int sourceToolVersion,
            Object content,
            String sha256,
            Instant collectedAt) {

        private static EvidenceDetails from(Evidence evidence, ObjectMapper objectMapper) {
            return new EvidenceDetails(
                    evidence.evidenceId(),
                    evidence.evidenceType(),
                    evidence.sourceTool(),
                    evidence.sourceToolVersion(),
                    objectMapper.convertValue(evidence.content(), Object.class),
                    evidence.sha256(),
                    evidence.collectedAt());
        }
    }
}
