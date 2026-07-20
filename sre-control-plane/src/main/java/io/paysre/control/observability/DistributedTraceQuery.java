package io.paysre.control.observability;

import java.time.Instant;
import java.util.Objects;

public record DistributedTraceQuery(String traceId, Instant from, Instant to) {

    public DistributedTraceQuery {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }
}
