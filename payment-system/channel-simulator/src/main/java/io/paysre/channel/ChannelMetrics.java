package io.paysre.channel;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;

public final class ChannelMetrics {

    private final MeterRegistry registry;

    public ChannelMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void recordRequest(String channel, String result, Duration duration) {
        registry.counter(
                        "channel_request_total",
                        "channel", channel,
                        "result", result)
                .increment();
        Timer.builder("channel_request_duration_seconds")
                .description("Channel payment request duration")
                .tag("channel", channel)
                .register(registry)
                .record(duration);
    }
}
