package io.paysre.control.observability;

import java.util.List;
import java.util.Objects;

public record ServiceMetricsResult(
        MetricSignal signal,
        List<MetricSeries> series,
        List<String> warnings,
        boolean truncated) {

    public ServiceMetricsResult {
        Objects.requireNonNull(signal, "signal");
        series = List.copyOf(Objects.requireNonNull(series, "series"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}
