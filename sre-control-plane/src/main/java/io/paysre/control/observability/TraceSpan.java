package io.paysre.control.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record TraceSpan(
        String spanId,
        String parentSpanId,
        String service,
        String name,
        Instant startTime,
        Duration duration,
        TraceSpanStatus status,
        Map<String, String> attributes) {

    public TraceSpan {
        Objects.requireNonNull(spanId, "spanId");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(status, "status");
        attributes = Map.copyOf(new TreeMap<>(
                Objects.requireNonNull(attributes, "attributes")));
    }
}
