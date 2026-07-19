package io.paysre.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScenarioEvaluatorTest {

    @Test
    void loadsTheVersionedGroundTruthFromYaml() {
        var groundTruth = ScenarioGroundTruth.load(
                "/fault-scenarios/channel-timeout-but-success-v1.yaml");

        assertThat(groundTruth.id()).isEqualTo("channel-timeout-but-success-v1");
        assertThat(groundTruth.fault().probability()).isEqualByComparingTo("1.00");
        assertThat(groundTruth.traffic().payments()).isEqualTo(5);
        assertThat(groundTruth.expected().requiredEvidenceTypes())
                .containsExactly(
                        "SERVICE_METRICS",
                        "STRUCTURED_LOGS",
                        "DISTRIBUTED_TRACE",
                        "PAYMENT_TIMELINE", "CHANNEL_FINAL_STATE", "INCIDENT_IMPACT");
    }

    @Test
    void passesOnlyWhenRootCauseEvidenceRunbookAndReviewPolicyAllMatch() {
        var expected = ScenarioGroundTruth.load(
                "/fault-scenarios/channel-timeout-but-success-v1.yaml").expected();
        var evaluator = new ScenarioEvaluator();
        var actual = new ScenarioEvaluator.ActualInvestigation(
                "CHANNEL_TIMEOUT_RESPONSE_LOST",
                Set.of(
                        "SERVICE_METRICS",
                        "STRUCTURED_LOGS",
                        "DISTRIBUTED_TRACE",
                        "PAYMENT_TIMELINE",
                        "CHANNEL_FINAL_STATE",
                        "INCIDENT_IMPACT"),
                6,
                "query-and-sync-unknown-payments",
                true);

        var score = evaluator.evaluate(expected, actual);

        assertThat(score.passed()).isTrue();
        assertThat(score.evidenceRecall()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void loadsTheTimeoutButFailedScenarioWithItsRemediationGroundTruth() {
        var groundTruth = ScenarioGroundTruth.load(
                "/fault-scenarios/channel-timeout-but-failed-v1.yaml");

        assertThat(groundTruth.id()).isEqualTo("channel-timeout-but-failed-v1");
        assertThat(groundTruth.fault().type()).isEqualTo("TIMEOUT_BUT_FAILED");
        assertThat(groundTruth.expected().rootCause())
                .isEqualTo("CHANNEL_TIMEOUT_RESPONSE_LOST");
        assertThat(groundTruth.expected().remediation().runbookExecutionStatus())
                .isEqualTo("SUCCEEDED");
        assertThat(groundTruth.expected().remediation().finalPaymentStatus())
                .isEqualTo("FAILED");
    }

    @Test
    void remediationPassesOnlyWhenExecutionAndEveryPaymentConverge() {
        var expected = ScenarioGroundTruth.load(
                "/fault-scenarios/channel-timeout-but-failed-v1.yaml")
                .expected().remediation();
        var evaluator = new ScenarioEvaluator();

        var pass = evaluator.evaluateRemediation(expected,
                new ScenarioEvaluator.ActualRemediation(
                        "SUCCEEDED", java.util.List.of("FAILED", "FAILED")));
        assertThat(pass.passed()).isTrue();

        var wrongStatus = evaluator.evaluateRemediation(expected,
                new ScenarioEvaluator.ActualRemediation(
                        "SUCCEEDED", java.util.List.of("FAILED", "UNKNOWN")));
        assertThat(wrongStatus.allPaymentsConverged()).isFalse();
        assertThat(wrongStatus.passed()).isFalse();

        var failedExecution = evaluator.evaluateRemediation(expected,
                new ScenarioEvaluator.ActualRemediation(
                        "FAILED", java.util.List.of("FAILED")));
        assertThat(failedExecution.executionStatusCorrect()).isFalse();
        assertThat(failedExecution.passed()).isFalse();
    }

    @Test
    void reportsPartialEvidenceRecallToFourDecimalPlaces() {
        var expected = ScenarioGroundTruth.load(
                "/fault-scenarios/channel-timeout-but-success-v1.yaml").expected();
        var actual = new ScenarioEvaluator.ActualInvestigation(
                "CHANNEL_TIMEOUT_RESPONSE_LOST",
                Set.of(
                        "SERVICE_METRICS",
                        "STRUCTURED_LOGS",
                        "DISTRIBUTED_TRACE",
                        "PAYMENT_TIMELINE",
                        "INCIDENT_IMPACT"),
                2,
                "query-and-sync-unknown-payments",
                true);

        var score = new ScenarioEvaluator().evaluate(expected, actual);

        assertThat(score.evidenceRecall()).isEqualByComparingTo("0.8333");
        assertThat(score.passed()).isFalse();
    }

    @Test
    void failsWhenEvidenceTypesMatchButTheMinimumCountDoesNot() {
        var expected = ScenarioGroundTruth.load(
                "/fault-scenarios/channel-timeout-but-success-v1.yaml").expected();
        var actual = new ScenarioEvaluator.ActualInvestigation(
                "CHANNEL_TIMEOUT_RESPONSE_LOST",
                Set.of(
                        "SERVICE_METRICS",
                        "STRUCTURED_LOGS",
                        "DISTRIBUTED_TRACE",
                        "PAYMENT_TIMELINE",
                        "CHANNEL_FINAL_STATE",
                        "INCIDENT_IMPACT"),
                5,
                "query-and-sync-unknown-payments",
                true);

        var score = new ScenarioEvaluator().evaluate(expected, actual);

        assertThat(score.evidenceRecall()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(score.minimumEvidenceCountMet()).isFalse();
        assertThat(score.passed()).isFalse();
    }
}
