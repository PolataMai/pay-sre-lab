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
                        "PAYMENT_TIMELINE", "CHANNEL_FINAL_STATE", "INCIDENT_IMPACT");
    }

    @Test
    void passesOnlyWhenRootCauseEvidenceRunbookAndReviewPolicyAllMatch() {
        var expected = ScenarioGroundTruth.load(
                "/fault-scenarios/channel-timeout-but-success-v1.yaml").expected();
        var evaluator = new ScenarioEvaluator();
        var actual = new ScenarioEvaluator.ActualInvestigation(
                "CHANNEL_TIMEOUT_RESPONSE_LOST",
                Set.of("PAYMENT_TIMELINE", "CHANNEL_FINAL_STATE", "INCIDENT_IMPACT"),
                3,
                "query-and-sync-unknown-payments",
                true);

        var score = evaluator.evaluate(expected, actual);

        assertThat(score.passed()).isTrue();
        assertThat(score.evidenceRecall()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void reportsPartialEvidenceRecallToFourDecimalPlaces() {
        var expected = ScenarioGroundTruth.load(
                "/fault-scenarios/channel-timeout-but-success-v1.yaml").expected();
        var actual = new ScenarioEvaluator.ActualInvestigation(
                "CHANNEL_TIMEOUT_RESPONSE_LOST",
                Set.of("PAYMENT_TIMELINE", "INCIDENT_IMPACT"),
                2,
                "query-and-sync-unknown-payments",
                true);

        var score = new ScenarioEvaluator().evaluate(expected, actual);

        assertThat(score.evidenceRecall()).isEqualByComparingTo("0.6667");
        assertThat(score.passed()).isFalse();
    }

    @Test
    void failsWhenEvidenceTypesMatchButTheMinimumCountDoesNot() {
        var expected = ScenarioGroundTruth.load(
                "/fault-scenarios/channel-timeout-but-success-v1.yaml").expected();
        var actual = new ScenarioEvaluator.ActualInvestigation(
                "CHANNEL_TIMEOUT_RESPONSE_LOST",
                Set.of("PAYMENT_TIMELINE", "CHANNEL_FINAL_STATE", "INCIDENT_IMPACT"),
                2,
                "query-and-sync-unknown-payments",
                true);

        var score = new ScenarioEvaluator().evaluate(expected, actual);

        assertThat(score.evidenceRecall()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(score.minimumEvidenceCountMet()).isFalse();
        assertThat(score.passed()).isFalse();
    }
}
