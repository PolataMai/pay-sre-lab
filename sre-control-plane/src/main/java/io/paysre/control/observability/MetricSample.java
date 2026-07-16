package io.paysre.control.observability;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record MetricSample(Instant timestamp, BigDecimal value, boolean available) {

    public MetricSample {
        Objects.requireNonNull(timestamp, "timestamp");
        if (available) {
            Objects.requireNonNull(value, "value");
        } else if (value != null) {
            throw new IllegalArgumentException("unavailable sample must not contain a value");
        }
    }
}
