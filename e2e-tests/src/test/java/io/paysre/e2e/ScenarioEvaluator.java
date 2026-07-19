package io.paysre.e2e;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

final class ScenarioEvaluator {

    ScenarioScore evaluate(
            ScenarioGroundTruth.Expected expected, ActualInvestigation actual) {
        long matchedEvidence = expected.requiredEvidenceTypes().stream()
                .filter(actual.evidenceTypes()::contains)
                .count();
        var recall = BigDecimal.valueOf(matchedEvidence)
                .divide(
                        BigDecimal.valueOf(expected.requiredEvidenceTypes().size()),
                        4,
                        RoundingMode.HALF_UP);
        return new ScenarioScore(
                expected.rootCause().equals(actual.rootCause()),
                recall,
                actual.evidenceCount() >= expected.minimumEvidenceCount(),
                expected.recommendedRunbook().equals(actual.recommendedRunbook()),
                expected.requiresHumanReview() == actual.requiresHumanReview());
    }

    RemediationScore evaluateRemediation(
            ScenarioGroundTruth.Remediation expected, ActualRemediation actual) {
        return new RemediationScore(
                expected.runbookExecutionStatus().equals(actual.runbookExecutionStatus()),
                actual.paymentStatuses().stream()
                        .allMatch(expected.finalPaymentStatus()::equals));
    }

    record ActualInvestigation(
            String rootCause,
            Set<String> evidenceTypes,
            int evidenceCount,
            String recommendedRunbook,
            boolean requiresHumanReview) {

        ActualInvestigation {
            evidenceTypes = Set.copyOf(evidenceTypes);
        }
    }

    record ActualRemediation(
            String runbookExecutionStatus,
            java.util.List<String> paymentStatuses) {

        ActualRemediation {
            paymentStatuses = java.util.List.copyOf(paymentStatuses);
        }
    }
}

record ScenarioScore(
        boolean rootCauseCorrect,
        BigDecimal evidenceRecall,
        boolean minimumEvidenceCountMet,
        boolean runbookCorrect,
        boolean humanReviewCorrect) {

    boolean passed() {
        return rootCauseCorrect
                && evidenceRecall.compareTo(BigDecimal.ONE) == 0
                && minimumEvidenceCountMet
                && runbookCorrect
                && humanReviewCorrect;
    }
}

record RemediationScore(
        boolean executionStatusCorrect,
        boolean allPaymentsConverged) {

    boolean passed() {
        return executionStatusCorrect && allPaymentsConverged;
    }
}
