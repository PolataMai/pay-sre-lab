package io.paysre.control.investigation;

import io.paysre.control.evidence.Evidence;
import java.util.List;
import java.util.Set;

/**
 * Advisory-only policy for {@link RootCauseCode#CHANNEL_DECLINE_SPIKE}:
 * a sudden surge of channel declines (response code 05 / generic decline)
 * is a business-side signal, not a runbook-worthy write action. The
 * conclusion can recommend no runbook; human review stays mandatory so
 * an on-call can decide whether to keep accepting traffic from the
 * channel or route around it.
 */
public final class ChannelDeclineSpikePolicy implements RootCausePolicy {

    @Override
    public RootCauseCode rootCause() {
        return RootCauseCode.CHANNEL_DECLINE_SPIKE;
    }

    @Override
    public Set<String> allowedRunbooks() {
        // Advisory root cause: nothing in the allow-list. The validator
        // will reject any conclusion that tries to name a runbook.
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
                "advisory root cause cannot recommend a runbook");
        require(conclusion.confidence().compareTo(java.math.BigDecimal.ZERO) >= 0,
                "confidence must not be negative");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidConclusionException(message);
        }
    }
}