package io.paysre.control.investigation;

import io.paysre.control.evidence.Evidence;
import java.util.List;
import java.util.Set;

/**
 * Advisory policy for {@link RootCauseCode#ROUTING_MISCONFIGURED}:
 * the merchant-to-channel mapping in payment-service (and any
 * Nacos-side overlay) was edited so that one or more merchants are
 * routed to the wrong channel. The fix is a config rollback plus a
 * state reconciliation; no automated runbook can safely replay the
 * affected payments. The policy recommends nothing and keeps human
 * review mandatory.
 */
public final class RoutingMisconfiguredPolicy implements RootCausePolicy {

    @Override
    public RootCauseCode rootCause() {
        return RootCauseCode.ROUTING_MISCONFIGURED;
    }

    @Override
    public Set<String> allowedRunbooks() {
        return Set.of();
    }

    @Override
    public boolean requiresHumanReview() {
        return true;
    }

    @Override
    public void validateConclusion(
            RootCausePolicy.IncidentContext context,
            InvestigationConclusion conclusion,
            List<Evidence> referenced) {
        require(conclusion.recommendedRunbook() == null
                        || conclusion.recommendedRunbook().isEmpty(),
                "routing misconfiguration is a config issue; no runbook should be recommended");
        require(conclusion.confidence().compareTo(java.math.BigDecimal.ZERO) >= 0,
                "confidence must not be negative");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidConclusionException(message);
        }
    }
}