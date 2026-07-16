package io.paysre.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelResult;
import io.paysre.contracts.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class ChannelSimulationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T10:00:00Z");

    @Test
    void timeoutStillLeavesASuccessfulFinalStateForLaterQuery() {
        var rules = new InMemoryFaultRuleRepository();
        rules.replace(new FaultRule(
                "CHANNEL_A",
                FaultType.TIMEOUT_BUT_SUCCESS,
                new BigDecimal("1.00"),
                NOW.minusSeconds(60),
                NOW.plusSeconds(60),
                20260716L));
        var service = new ChannelSimulationService(
                rules,
                new FaultDecider(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ChannelMetrics(new SimpleMeterRegistry()),
                new ChannelTelemetry(OpenTelemetry.noop().getTracer("test")));
        var request = request("PAY-1");

        assertThatThrownBy(() -> service.pay(request))
                .isInstanceOf(ChannelTimeoutException.class)
                .hasMessage("channel timeout for payment PAY-1");

        var finalState = service.query("PAY-1");
        assertThat(finalState.result()).isEqualTo(ChannelResult.SUCCESS);
        assertThat(finalState.channelCode()).isEqualTo("00");
        assertThat(finalState.channelTime()).isEqualTo(NOW);
    }

    @Test
    void paymentSucceedsNormallyWhenNoFaultRuleIsActive() {
        var service = new ChannelSimulationService(
                new InMemoryFaultRuleRepository(),
                new FaultDecider(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ChannelMetrics(new SimpleMeterRegistry()),
                new ChannelTelemetry(OpenTelemetry.noop().getTracer("test")));

        assertThat(service.pay(request("PAY-2")).result()).isEqualTo(ChannelResult.SUCCESS);
    }

    @Test
    void queryingAnUnknownPaymentFailsExplicitly() {
        var service = new ChannelSimulationService(
                new InMemoryFaultRuleRepository(),
                new FaultDecider(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ChannelMetrics(new SimpleMeterRegistry()),
                new ChannelTelemetry(OpenTelemetry.noop().getTracer("test")));

        assertThatThrownBy(() -> service.query("MISSING"))
                .isInstanceOf(ChannelPaymentNotFoundException.class)
                .hasMessage("channel payment not found: MISSING");
    }

    private ChannelPaymentRequest request(String paymentId) {
        return new ChannelPaymentRequest(
                "REQ-" + paymentId,
                paymentId,
                "CHANNEL_A",
                new Money(new BigDecimal("10.00"), Currency.getInstance("CNY")));
    }
}
