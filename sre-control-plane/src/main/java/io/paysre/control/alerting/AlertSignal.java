package io.paysre.control.alerting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record AlertSignal(
        String alertId,
        String source,
        String service,
        String signalName,
        Severity severity,
        Instant startsAt,
        Map<String, String> dimensions,
        BigDecimal observedValue,
        BigDecimal threshold) {

    public AlertSignal {
        Objects.requireNonNull(alertId, "alertId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(signalName, "signalName");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(startsAt, "startsAt");
        dimensions = Map.copyOf(dimensions);
    }

    public String aggregateKey() {
        return service + ":" + signalName + ":"
                + dimensions.getOrDefault("channel", "ALL");
    }
}
