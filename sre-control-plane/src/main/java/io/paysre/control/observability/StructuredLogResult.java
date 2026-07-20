package io.paysre.control.observability;

import java.util.List;
import java.util.Objects;

public record StructuredLogResult(List<StructuredLogRecord> records, boolean truncated) {

    public StructuredLogResult {
        records = List.copyOf(Objects.requireNonNull(records, "records"));
    }
}
