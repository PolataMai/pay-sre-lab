package io.paysre.control.investigation;

import io.paysre.control.evidence.Evidence;
import io.paysre.control.evidence.EvidenceRepository;
import io.paysre.control.incident.Incident;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class ConclusionValidator {

    private final EvidenceRepository evidenceRepository;
    private final RootCausePolicyCatalog policies;

    /**
     * Backward-compatible constructor used by existing wiring and
     * tests. The built-in catalog covers every policy that ships with
     * the control plane.
     */
    public ConclusionValidator(EvidenceRepository evidenceRepository) {
        this(evidenceRepository, RootCausePolicyCatalog.defaults());
    }

    public ConclusionValidator(
            EvidenceRepository evidenceRepository, RootCausePolicyCatalog policies) {
        this.evidenceRepository = Objects.requireNonNull(
                evidenceRepository, "evidenceRepository");
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    public InvestigationConclusion validate(
            Incident incident, InvestigationConclusion conclusion) {
        require(incident.incidentId().equals(conclusion.incidentId()),
                "conclusion incident does not match current incident");
        require(conclusion.confidence().compareTo(BigDecimal.ZERO) >= 0
                        && conclusion.confidence().compareTo(BigDecimal.ONE) <= 0,
                "confidence must be between zero and one");
        require(conclusion.affectedPaymentCount() >= 0,
                "affected payment count must not be negative");

        var policy = policies.forRootCause(conclusion.rootCause());
        var uniqueEvidenceIds = new LinkedHashSet<>(conclusion.evidenceIds());
        require(uniqueEvidenceIds.size() >= 3,
                "at least three unique evidence references are required");
        require(uniqueEvidenceIds.size() == conclusion.evidenceIds().size(),
                "duplicate evidence references are not allowed");
        List<Evidence> referenced = uniqueEvidenceIds.stream()
                .map(id -> evidenceRepository.findById(id)
                        .orElseThrow(() -> invalid("evidence does not exist: " + id)))
                .toList();
        referenced.forEach(item -> require(
                item.incidentId().equals(incident.incidentId()),
                "evidence belongs to another incident: " + item.evidenceId()));

        policy.validateConclusion(
                new RootCausePolicy.IncidentContext(incident.incidentId()),
                conclusion, referenced);

        boolean impactMatches = referenced.stream()
                .filter(item -> item.evidenceType().equals("INCIDENT_IMPACT"))
                .anyMatch(item -> matchesImpact(item, conclusion));
        require(impactMatches, "conclusion impact does not match incident impact evidence");
        return conclusion;
    }

    private boolean matchesImpact(Evidence evidence, InvestigationConclusion conclusion) {
        var content = evidence.content();
        var amount = content.path("totalAmount");
        var currency = content.path("currency");
        return content.path("affectedPaymentCount").isIntegralNumber()
                && content.path("affectedPaymentCount").asLong()
                        == conclusion.affectedPaymentCount()
                && amount.isNumber()
                && amount.decimalValue().compareTo(conclusion.affectedAmount().amount()) == 0
                && currency.isTextual()
                && currency.asText().equals(
                        conclusion.affectedAmount().currency().getCurrencyCode());
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    private InvalidConclusionException invalid(String message) {
        return new InvalidConclusionException(message);
    }
}