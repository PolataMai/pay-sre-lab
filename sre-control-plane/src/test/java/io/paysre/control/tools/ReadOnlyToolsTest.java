package io.paysre.control.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadOnlyToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void paymentTimelineAndChannelFinalStateAreReadOnlyTools() {
        var paymentClient = mock(PaymentReadClient.class);
        var channelClient = mock(ChannelReadClient.class);
        var timeline = objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("reasonCode", "CHANNEL_TIMEOUT"));
        var finalState = objectMapper.createObjectNode()
                .put("paymentId", "P10001")
                .put("result", "SUCCESS");
        when(paymentClient.timeline("P10001")).thenReturn(timeline);
        when(channelClient.finalState("P10001")).thenReturn(finalState);

        var timelineTool = new GetPaymentTimelineTool(paymentClient);
        var channelTool = new QueryChannelFinalStateTool(channelClient);

        assertThat(timelineTool.execute(new GetPaymentTimelineTool.Input("P10001")))
                .isEqualTo(timeline);
        assertThat(channelTool.execute(new QueryChannelFinalStateTool.Input("P10001")))
                .isEqualTo(finalState);
        assertThat(timelineTool.definition().risk()).isEqualTo(ToolRisk.READ_ONLY);
        assertThat(channelTool.definition().risk()).isEqualTo(ToolRisk.READ_ONLY);
    }

    @Test
    void impactToolCountsAndSumsOnlyTheRequestedChannel() {
        var paymentClient = mock(PaymentReadClient.class);
        var from = Instant.EPOCH;
        var to = from.plusSeconds(3_600);
        when(paymentClient.unknownPayments(from, to, 200)).thenReturn(List.of(
                payment("P1", "CHANNEL_A", "10.00", "CNY"),
                payment("P2", "CHANNEL_A", "20.00", "CNY"),
                payment("P3", "CHANNEL_B", "99.00", "CNY")));
        var tool = new CalculateIncidentImpactTool(paymentClient);

        var impact = tool.execute(
                new CalculateIncidentImpactTool.Input("CHANNEL_A", from, to));

        assertThat(impact.affectedPaymentCount()).isEqualTo(2);
        assertThat(impact.totalAmount()).isEqualByComparingTo("30.00");
        assertThat(impact.currency()).isEqualTo("CNY");
        assertThat(impact.paymentIds()).containsExactly("P1", "P2");
    }

    @Test
    void impactToolRejectsMixedCurrencies() {
        var paymentClient = mock(PaymentReadClient.class);
        var from = Instant.EPOCH;
        var to = from.plusSeconds(3_600);
        when(paymentClient.unknownPayments(from, to, 200)).thenReturn(List.of(
                payment("P1", "CHANNEL_A", "10.00", "CNY"),
                payment("P2", "CHANNEL_A", "10.00", "USD")));
        var tool = new CalculateIncidentImpactTool(paymentClient);

        assertThatThrownBy(() -> tool.execute(
                        new CalculateIncidentImpactTool.Input("CHANNEL_A", from, to)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("cannot aggregate mixed currencies");
    }

    @Test
    void impactToolRefusesToClaimAnExactTotalAtTheCandidateLimit() {
        var paymentClient = mock(PaymentReadClient.class);
        var from = Instant.EPOCH;
        var to = from.plusSeconds(3_600);
        var candidates = java.util.stream.IntStream.range(0, 200)
                .mapToObj(index -> payment("P" + index, "CHANNEL_A", "1.00", "CNY"))
                .toList();
        when(paymentClient.unknownPayments(from, to, 200)).thenReturn(candidates);
        var tool = new CalculateIncidentImpactTool(paymentClient);

        assertThatThrownBy(() -> tool.execute(
                        new CalculateIncidentImpactTool.Input("CHANNEL_A", from, to)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("impact candidate limit reached; exact total is unknown");
    }

    private UnknownPaymentRecord payment(
            String paymentId, String channel, String amount, String currency) {
        return new UnknownPaymentRecord(
                paymentId,
                "O-" + paymentId,
                new BigDecimal(amount),
                currency,
                channel,
                "UNKNOWN",
                Instant.EPOCH);
    }
}
