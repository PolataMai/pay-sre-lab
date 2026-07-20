package io.paysre.control.observability;

import java.time.Instant;
import java.util.Objects;

public record StructuredLogRecord(
        Instant timestamp,
        String service,
        LogLevel level,
        String traceId,
        String spanId,
        LogEvent event,
        String paymentId,
        String reasonCode,
        String message) {

    public StructuredLogRecord {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(message, "message");
    }
}
