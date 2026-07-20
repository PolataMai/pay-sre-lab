package io.paysre.control.investigation;

import io.paysre.control.evidence.Evidence;
import java.util.List;
import java.util.Set;

/**
 * Advisory policy for {@link RootCauseCode#CHANNEL_CODE_MAPPING_ERROR}:
 * payment-service's channel-return-code-to-ChannelResult mapping has
 * been misconfigured (e.g. code 00 routed to FAILED when it should be
 * SUCCESS). The fix is a config change in
 * {@code paysre.channel.code-mapping.*} — typically via Nacos — not a
 * write-path runbook. The conclusion can therefore recommend no
 * runbook; human review stays mandatory so an on-call can verify the
 * config diff and roll it back.
 */
public final class ChannelCodeMappingErrorPolicy implements RootCausePolicy {

    @Override
    public RootCauseCode rootCause() {
        return RootCauseCode.CHANNEL_CODE_MAPPING_ERROR;
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
                "code mapping error is a config issue; no runbook should be recommended");
        require(conclusion.confidence().compareTo(java.math.BigDecimal.ZERO) >= 0,
                "confidence must not be negative");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidConclusionException(message);
        }
    }
}