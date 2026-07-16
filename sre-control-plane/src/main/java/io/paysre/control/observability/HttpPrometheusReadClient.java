package io.paysre.control.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class HttpPrometheusReadClient implements PrometheusReadClient {

    static final int MAX_SERIES = 20;
    static final int MAX_SAMPLES = 240;
    private static final Duration MAX_RANGE = Duration.ofHours(2);
    private static final Duration MIN_STEP = Duration.ofSeconds(15);
    private static final Set<String> CHANNELS = Set.of("CHANNEL_A");
    private static final Set<String> OUTPUT_LABELS =
            Set.of("service", "channel", "status", "result");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HttpPrometheusReadClient(
            RestClient restClient, ObjectMapper objectMapper, Clock clock) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ServiceMetricsResult query(ServiceMetricsQuery query) {
        validate(query);
        var promQl = query.signal().promQl(query.channel());
        String body;
        try {
            body = restClient.get()
                    .uri(builder -> builder
                            .path("/api/v1/query_range")
                            .queryParam("query", "{promql}")
                            .queryParam("start", query.from())
                            .queryParam("end", query.to())
                            .queryParam("step", formatStep(query.step()))
                            .queryParam("limit", MAX_SERIES)
                            .queryParam("timeout", "3s")
                            .build(Map.of("promql", promQl)))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ignored) {
            throw new ObservabilityBackendException(
                    ObservabilityBackendException.Code.BACKEND_UNAVAILABLE,
                    "Prometheus query failed");
        }
        if (body == null) {
            throw malformed("Prometheus returned an empty response", null);
        }
        return normalize(query.signal(), body);
    }

    private void validate(ServiceMetricsQuery query) {
        Objects.requireNonNull(query, "query");
        if (!query.signal().service().equals(query.service())) {
            throw new IllegalArgumentException("signal is not available for requested service");
        }
        if (!CHANNELS.contains(query.channel())) {
            throw new IllegalArgumentException("unsupported payment channel");
        }
        if (!query.from().isBefore(query.to())) {
            throw new IllegalArgumentException("metrics range must have from before to");
        }
        if (query.to().isAfter(clock.instant())) {
            throw new IllegalArgumentException("metrics range cannot end in the future");
        }
        var range = Duration.between(query.from(), query.to());
        if (range.compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("metrics range cannot exceed two hours");
        }
        if (query.step().compareTo(MIN_STEP) < 0) {
            throw new IllegalArgumentException("metrics step must be at least 15 seconds");
        }
        if (query.step().compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("metrics step cannot exceed two hours");
        }
        if (query.step().toNanosPart() % 1_000_000 != 0) {
            throw new IllegalArgumentException("metrics step must use millisecond precision");
        }
        long sampleCount = range.toNanos() / query.step().toNanos() + 1;
        if (sampleCount > MAX_SAMPLES) {
            throw new IllegalArgumentException("metrics query cannot request more than 240 samples");
        }
    }

    private ServiceMetricsResult normalize(MetricSignal signal, String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw malformed("Prometheus returned malformed JSON", exception);
        }
        var data = root.path("data");
        var backendSeries = data.path("result");
        if (!"success".equals(root.path("status").asText())
                || !"matrix".equals(data.path("resultType").asText())
                || !backendSeries.isArray()) {
            throw malformed("Prometheus returned an invalid matrix response", null);
        }

        var warnings = normalizeWarnings(root.path("warnings"));
        var series = new ArrayList<MetricSeries>();
        int remainingSamples = MAX_SAMPLES;
        boolean truncated = backendSeries.size() > MAX_SERIES;
        for (int index = 0;
                index < backendSeries.size()
                        && index < MAX_SERIES
                        && remainingSamples > 0;
                index++) {
            var source = backendSeries.get(index);
            var values = source.path("values");
            if (!source.path("metric").isObject() || !values.isArray()) {
                throw malformed("Prometheus series is malformed", null);
            }
            var normalizedSamples = new ArrayList<MetricSample>();
            int accepted = Math.min(values.size(), remainingSamples);
            for (int sampleIndex = 0; sampleIndex < accepted; sampleIndex++) {
                normalizedSamples.add(normalizeSample(values.get(sampleIndex)));
            }
            if (accepted < values.size()) {
                truncated = true;
            }
            remainingSamples -= accepted;
            series.add(new MetricSeries(
                    normalizeLabels(source.path("metric")), normalizedSamples));
            if (remainingSamples == 0 && index + 1 < backendSeries.size()) {
                truncated = true;
            }
        }
        return new ServiceMetricsResult(signal, series, warnings, truncated);
    }

    private LinkedHashMap<String, String> normalizeLabels(JsonNode labels) {
        var normalized = new LinkedHashMap<String, String>();
        labels.properties().forEach(entry -> {
            if (OUTPUT_LABELS.contains(entry.getKey()) && entry.getValue().isTextual()) {
                normalized.put(entry.getKey(), entry.getValue().asText());
            }
        });
        return normalized;
    }

    private MetricSample normalizeSample(JsonNode sample) {
        if (!sample.isArray() || sample.size() != 2 || !sample.get(1).isTextual()) {
            throw malformed("Prometheus sample is malformed", null);
        }
        var timestamp = parseTimestamp(sample.get(0));
        var rawValue = sample.get(1).asText();
        if (rawValue.equals("NaN")
                || rawValue.equals("Inf")
                || rawValue.equals("+Inf")
                || rawValue.equals("-Inf")) {
            return new MetricSample(timestamp, null, false);
        }
        try {
            return new MetricSample(timestamp, new BigDecimal(rawValue), true);
        } catch (NumberFormatException exception) {
            throw malformed("Prometheus sample value is malformed", exception);
        }
    }

    private Instant parseTimestamp(JsonNode value) {
        if (!value.isNumber()) {
            throw malformed("Prometheus sample timestamp is malformed", null);
        }
        try {
            var decimal = new BigDecimal(value.asText());
            long seconds = decimal.setScale(0, RoundingMode.FLOOR).longValueExact();
            int nanos = decimal.subtract(BigDecimal.valueOf(seconds))
                    .movePointRight(9)
                    .intValueExact();
            return Instant.ofEpochSecond(seconds, nanos);
        } catch (ArithmeticException | NumberFormatException exception) {
            throw malformed("Prometheus sample timestamp is malformed", exception);
        }
    }

    private List<String> normalizeWarnings(JsonNode warnings) {
        if (warnings.isMissingNode() || warnings.isNull()) {
            return List.of();
        }
        if (!warnings.isArray()) {
            throw malformed("Prometheus warnings are malformed", null);
        }
        var normalized = new ArrayList<String>();
        warnings.forEach(warning -> {
            if (!warning.isTextual()) {
                throw malformed("Prometheus warning is malformed", null);
            }
            normalized.add(warning.asText());
        });
        return List.copyOf(normalized);
    }

    private String formatStep(Duration step) {
        if (step.toNanosPart() == 0) {
            return step.toSeconds() + "s";
        }
        return step.toMillis() + "ms";
    }

    private ObservabilityBackendException malformed(String message, Throwable cause) {
        return new ObservabilityBackendException(
                ObservabilityBackendException.Code.MALFORMED_RESPONSE,
                message,
                cause);
    }
}
