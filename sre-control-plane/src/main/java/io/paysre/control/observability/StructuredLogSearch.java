package io.paysre.control.observability;

import java.time.Instant;
import java.util.Objects;

public record StructuredLogSearch(
        String service,
        LogEvent event,
        LogLevel minimumLevel,
        String paymentId,
        String traceId,
        Instant from,
        Instant to,
        int limit) {

    public StructuredLogSearch {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(minimumLevel, "minimumLevel");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
