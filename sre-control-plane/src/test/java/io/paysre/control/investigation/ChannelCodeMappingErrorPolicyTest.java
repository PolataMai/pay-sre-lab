package io.paysre.control.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paysre.contracts.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelCodeMappingErrorPolicyTest {

    @Test
    void allowsNoRunbooksAndStillRequiresHumanReview() {
        var policy = new ChannelCodeMappingErrorPolicy();

        assertThat(policy.rootCause())
                .isEqualTo(RootCauseCode.CHANNEL_CODE_MAPPING_ERROR);
        assertThat(policy.allowedRunbooks()).isEmpty();
        assertThat(policy.requiresHumanReview()).isTrue();
    }

    @Test
    void rejectsAnyConclusionThatRecommendsARunbook() {
        var policy = new ChannelCodeMappingErrorPolicy();
        var conclusion = new InvestigationConclusion(
                "INC-1",
                RootCauseCode.CHANNEL_CODE_MAPPING_ERROR,
                new BigDecimal("0.50"),
                List.of("E1", "E2", "E3"),
                0,
                new Money(BigDecimal.ZERO, Currency.getInstance("CNY")),
                "any-runbook",
                true);

        assertThatThrownBy(() -> policy.validateConclusion(
                new RootCausePolicy.IncidentContext("INC-1"),
                conclusion,
                List.of()))
                .isInstanceOf(InvalidConclusionException.class)
                .hasMessageContaining("config issue");
    }

    @Test
    void acceptsAnEmptyRecommendedRunbook() {
        var policy = new ChannelCodeMappingErrorPolicy();
        var conclusion = new InvestigationConclusion(
                "INC-1",
                RootCauseCode.CHANNEL_CODE_MAPPING_ERROR,
                new BigDecimal("0.50"),
                List.of("E1", "E2", "E3"),
                0,
                new Money(BigDecimal.ZERO, Currency.getInstance("CNY")),
                "",
                true);

        policy.validateConclusion(
                new RootCausePolicy.IncidentContext("INC-1"),
                conclusion,
                List.of());
    }
}