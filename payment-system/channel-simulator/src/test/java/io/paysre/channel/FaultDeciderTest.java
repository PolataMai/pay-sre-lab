package io.paysre.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FaultDeciderTest {

    @Test
    void returnsTheSameDecisionForTheSamePaymentRuleAndSeed() {
        var rule = ruleWithProbability("0.30");
        var decider = new FaultDecider();

        assertThat(decider.applies("P10001", rule))
                .isEqualTo(decider.applies("P10001", rule));
    }

    @Test
    void probabilityOneAlwaysAppliesAndProbabilityZeroNeverApplies() {
        var decider = new FaultDecider();

        assertThat(decider.applies("P10001", ruleWithProbability("1.00"))).isTrue();
        assertThat(decider.applies("P10001", ruleWithProbability("0.00"))).isFalse();
    }

    @Test
    void rejectsProbabilityOutsideZeroAndOne() {
        assertThatThrownBy(() -> ruleWithProbability("1.01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("probability must be between zero and one");
    }

    private FaultRule ruleWithProbability(String probability) {
        return new FaultRule(
                "CHANNEL_A",
                FaultType.TIMEOUT_BUT_SUCCESS,
                new BigDecimal(probability),
                Instant.EPOCH,
                Instant.parse("2099-01-01T00:00:00Z"),
                20260716L);
    }
}
