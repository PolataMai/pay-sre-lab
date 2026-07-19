package io.paysre.control.investigation;

import io.paysre.control.evidence.Evidence;
import java.util.List;
import java.util.Set;

/**
 * Advisory policy for {@link RootCauseCode#CHANNEL_CALLBACK_LOST}:
 * the channel is supposed to send an asynchronous callback after it
 * has finalised a payment, but those callbacks never arrive (or
 * arrive duplicated) — typically a downstream consumer / DLQ issue.
 * The fix is operational: reconcile the affected payments against
 * the channel's final state and replay if necessary. No automated
 * runbook can do this safely, so the policy recommends nothing and
 * keeps human review mandatory.
 */
public final class ChannelCallbackLostPolicy implements RootCausePolicy {

    @Override
    public RootCauseCode rootCause() {
        return RootCauseCode.CHANNEL_CALLBACK_LOST;
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
                "callback loss is an operational issue; no runbook should be recommended");
        require(conclusion.confidence().compareTo(java.math.BigDecimal.ZERO) >= 0,
                "confidence must not be negative");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidConclusionException(message);
        }
    }
}