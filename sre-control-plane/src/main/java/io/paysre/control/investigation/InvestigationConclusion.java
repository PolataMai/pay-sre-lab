package io.paysre.control.investigation;

import io.paysre.contracts.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record InvestigationConclusion(
        String incidentId,
        RootCauseCode rootCause,
        BigDecimal confidence,
        List<String> evidenceIds,
        long affectedPaymentCount,
        Money affectedAmount,
        String recommendedRunbook,
        boolean requiresHumanReview) {

    public InvestigationConclusion {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(rootCause, "rootCause");
        Objects.requireNonNull(confidence, "confidence");
        evidenceIds = List.copyOf(evidenceIds);
        Objects.requireNonNull(affectedAmount, "affectedAmount");
        Objects.requireNonNull(recommendedRunbook, "recommendedRunbook");
    }
}
