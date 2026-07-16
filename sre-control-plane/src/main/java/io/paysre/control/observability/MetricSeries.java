package io.paysre.control.observability;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record MetricSeries(Map<String, String> labels, List<MetricSample> samples) {

    public MetricSeries {
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(samples, "samples");
        labels = Map.copyOf(new TreeMap<>(labels));
        samples = List.copyOf(samples);
    }
}
