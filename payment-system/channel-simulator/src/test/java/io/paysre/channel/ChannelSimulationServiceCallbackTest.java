package io.paysre.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.paysre.contracts.ChannelCallback;
import io.paysre.contracts.ChannelResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChannelSimulationServiceCallbackTest {

    private static final Instant NOW = Instant.parse("2026-07-20T10:00:00Z");

    @Test
    void callbackLostFaultSilentlyDropsIncomingCallbacks() {
        var rules = new InMemoryFaultRuleRepository();
        rules.replace(new FaultRule(
                "CHANNEL_A",
                FaultType.CALLBACK_LOST,
                new BigDecimal("1.00"),
                NOW.minusSeconds(60),
                NOW.plusSeconds(60),
                20260720L));
        var service = new ChannelSimulationService(
                rules, new FaultDecider(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ChannelMetrics(new SimpleMeterRegistry()),
                new ChannelTelemetry(OpenTelemetry.noop().getTracer("test")));

        Optional<ChannelCallback> recorded = service.ingestCallback(callback("P1", 1));

        assertThat(recorded).isEmpty();
    }

    @Test
    void callbackDuplicatedFaultLetsEveryDuplicateThrough() {
        var rules = new InMemoryFaultRuleRepository();
        rules.replace(new FaultRule(
                "CHANNEL_A",
                FaultType.CALLBACK_DUPLICATED,
                new BigDecimal("1.00"),
                NOW.minusSeconds(60),
                NOW.plusSeconds(60),
                20260721L));
        var service = new ChannelSimulationService(
                rules, new FaultDecider(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ChannelMetrics(new SimpleMeterRegistry()),
                new ChannelTelemetry(OpenTelemetry.noop().getTracer("test")));

        var first = service.ingestCallback(callback("P1", 1));
        var second = service.ingestCallback(callback("P1", 1));
        var third = service.ingestCallback(callback("P1", 2));

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(third).isPresent();
    }

    @Test
    void noFaultAcceptsEveryCallbackOnce() {
        var service = new ChannelSimulationService(
                new InMemoryFaultRuleRepository(), new FaultDecider(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ChannelMetrics(new SimpleMeterRegistry()),
                new ChannelTelemetry(OpenTelemetry.noop().getTracer("test")));

        var first = service.ingestCallback(callback("P1", 1));
        var second = service.ingestCallback(callback("P1", 1));
        var third = service.ingestCallback(callback("P1", 2));

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(third).isPresent();
    }

    private ChannelCallback callback(String paymentId, long sequence) {
        return new ChannelCallback(
                UUID.randomUUID().toString(),
                paymentId,
                "CHANNEL_A",
                ChannelResult.SUCCESS,
                "00",
                sequence,
                NOW);
    }
}