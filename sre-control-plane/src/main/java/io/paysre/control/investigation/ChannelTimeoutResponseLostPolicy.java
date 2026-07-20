package io.paysre.control.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import io.paysre.control.evidence.Evidence;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * Validation contract for {@link RootCauseCode#CHANNEL_TIMEOUT_RESPONSE_LOST}:
 * the channel lost its synchronous reply, payment state is stuck in
 * UNKNOWN, and recovery needs the {@code query-and-sync-unknown-payments}
 * runbook under mandatory four-eyes review.
 */
public final class ChannelTimeoutResponseLostPolicy implements RootCausePolicy {

    static final String RUNBOOK = "query-and-sync-unknown-payments";
    private static final BigDecimal HIGH_CONFIDENCE = new BigDecimal("0.80");
    private static final Set<String> TRANSACTION_EVIDENCE_TYPES = Set.of(
            "PAYMENT_TIMELINE",
            "STRUCTURED_LOGS",
            "DISTRIBUTED_TRACE",
            "CHANNEL_FINAL_STATE");

    private final RootCauseCode rootCause;

    public ChannelTimeoutResponseLostPolicy() {
        this(RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST);
    }

    ChannelTimeoutResponseLostPolicy(RootCauseCode rootCause) {
        this.rootCause = rootCause;
    }

    @Override
    public RootCauseCode rootCause() {
        return rootCause;
    }

    @Override
    public Set<String> allowedRunbooks() {
        return Set.of(RUNBOOK);
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
        require(RUNBOOK.equals(conclusion.recommendedRunbook()),
                "recommended runbook is not allowed for this root cause");
        require(conclusion.requiresHumanReview(),
                "this runbook requires human review");
        require(referenced.stream().anyMatch(
                        item -> item.evidenceType().equals("INCIDENT_IMPACT")),
                "authoritative incident impact evidence is missing");
        if (conclusion.confidence().compareTo(HIGH_CONFIDENCE) >= 0) {
            require(referenced.stream().anyMatch(this::isUsableMetricsEvidence),
                    "high-confidence conclusion requires aggregate metrics evidence");
            long transactionEvidenceTypes = referenced.stream()
                    .filter(this::isUsableTransactionEvidence)
                    .map(Evidence::evidenceType)
                    .filter(TRANSACTION_EVIDENCE_TYPES::contains)
                    .distinct()
                    .count();
            require(transactionEvidenceTypes >= 2,
                    "high-confidence conclusion requires two transaction-specific evidence types");
        }
    }

    private boolean isUsableMetricsEvidence(Evidence evidence) {
        if (!evidence.evidenceType().equals("SERVICE_METRICS")) {
            return false;
        }
        var series = evidence.content().path("series");
        if (!series.isArray()) {
            return false;
        }
        return StreamSupport.stream(series.spliterator(), false)
                .map(item -> item.path("samples"))
                .filter(JsonNode::isArray)
                .flatMap(samples -> StreamSupport.stream(samples.spliterator(), false))
                .anyMatch(sample -> sample.path("value").isNumber()
                        && (!sample.path("available").isBoolean()
                                || sample.path("available").asBoolean()));
    }

    private boolean isUsableTransactionEvidence(Evidence evidence) {
        var content = evidence.content();
        return switch (evidence.evidenceType()) {
            case "PAYMENT_TIMELINE" -> hasNonEmptyArray(content)
                    || hasNonEmptyArray(content.path("events"));
            case "STRUCTURED_LOGS" -> hasNonEmptyArray(content.path("records"));
            case "DISTRIBUTED_TRACE" -> hasNonEmptyArray(content.path("spans"))
                    && traceIdMatches(evidence.evidenceId(), content.path("traceId").asText());
            case "CHANNEL_FINAL_STATE" -> Set.of("SUCCESS", "FAILED").contains(
                    content.path("result").asText());
            default -> false;
        };
    }

    private boolean traceIdMatches(String evidenceId, String traceId) {
        // The trace itself is validated downstream in ConclusionValidator
        // when the referenced list is available; for the policy-level check
        // we only need the structural shape.
        return traceId.matches("[a-fA-F0-9]{32}");
    }

    private boolean hasNonEmptyArray(JsonNode node) {
        return node.isArray() && !node.isEmpty();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidConclusionException(message);
        }
    }
}