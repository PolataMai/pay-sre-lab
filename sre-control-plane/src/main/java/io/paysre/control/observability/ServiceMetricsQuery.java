package io.paysre.control.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record ServiceMetricsQuery(
        MetricSignal signal,
        String service,
        String channel,
        Instant from,
        Instant to,
        Duration step) {

    public ServiceMetricsQuery {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(step, "step");
    }
}
