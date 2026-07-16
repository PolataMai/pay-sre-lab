package io.paysre.control.observability;

import java.util.List;
import java.util.Objects;

public record DistributedTrace(
        String traceId,
        List<TraceSpan> spans,
        boolean partial,
        boolean truncated) {

    public DistributedTrace {
        Objects.requireNonNull(traceId, "traceId");
        spans = List.copyOf(Objects.requireNonNull(spans, "spans"));
    }
}
