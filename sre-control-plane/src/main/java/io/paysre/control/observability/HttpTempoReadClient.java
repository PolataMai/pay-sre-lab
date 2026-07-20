package io.paysre.control.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public final class HttpTempoReadClient implements TempoReadClient {

    static final int MAX_SPANS = 200;
    static final int MAX_RESPONSE_CHARS = 2_097_152;
    private static final Duration MAX_RANGE = Duration.ofHours(2);
    private static final Pattern TRACE_ID = Pattern.compile("[a-fA-F0-9]{32}");
    private static final Set<String> SERVICES =
            Set.of("payment-service", "channel-simulator", "sre-control-plane");
    private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
            "payment.id",
            "order.id",
            "merchant.id",
            "payment.channel",
            "http.route",
            "http.request.method",
            "http.response.status_code",
            "http.method",
            "http.status_code",
            "messaging.system",
            "messaging.destination.name",
            "messaging.destination",
            "exception.type");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HttpTempoReadClient(RestClient restClient, ObjectMapper objectMapper, Clock clock) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DistributedTrace get(DistributedTraceQuery query) {
        validate(query);
        var traceId = query.traceId().toLowerCase(Locale.ROOT);
        String body;
        try {
            body = restClient.get()
                    .uri(builder -> builder
                            .path("/api/v2/traces/")
                            .pathSegment(traceId)
                            .queryParam("start", query.from().getEpochSecond())
                            .queryParam("end", query.to().getEpochSecond())
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new ObservabilityBackendException(
                        ObservabilityBackendException.Code.NOT_FOUND,
                        "Tempo trace was not found");
            }
            throw unavailable();
        } catch (RestClientException ignored) {
            throw unavailable();
        }
        if (body == null) {
            throw malformed("Tempo returned an empty response", null);
        }
        if (body.length() > MAX_RESPONSE_CHARS) {
            throw new ObservabilityBackendException(
                    ObservabilityBackendException.Code.LIMIT_EXCEEDED,
                    "Tempo response exceeded the safe limit");
        }
        return normalize(traceId, body);
    }

    private void validate(DistributedTraceQuery query) {
        Objects.requireNonNull(query, "query");
        if (!TRACE_ID.matcher(query.traceId()).matches()) {
            throw new IllegalArgumentException("trace ID must contain 32 hexadecimal characters");
        }
        if (!query.from().isBefore(query.to())) {
            throw new IllegalArgumentException("trace range must have from before to");
        }
        if (query.to().isAfter(clock.instant())) {
            throw new IllegalArgumentException("trace range cannot end in the future");
        }
        if (Duration.between(query.from(), query.to()).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("trace range cannot exceed two hours");
        }
    }

    private DistributedTrace normalize(String expectedTraceId, String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw malformed("Tempo returned malformed JSON", exception);
        }
        var trace = root.has("trace") ? root.path("trace") : root;
        var resourceSpans = trace.path("resourceSpans");
        if (!resourceSpans.isArray() || resourceSpans.isEmpty()) {
            throw malformed("Tempo returned an invalid OTLP trace", null);
        }

        var spans = new ArrayList<TraceSpan>();
        boolean truncated = false;
        outer:
        for (var resourceSpan : resourceSpans) {
            var resourceAttributes = attributes(resourceSpan.path("resource").path("attributes"));
            var service = resourceAttributes.get("service.name");
            if (!SERVICES.contains(service)) {
                throw malformed("Tempo span resource has an unsupported service name", null);
            }
            var scopeSpans = resourceSpan.path("scopeSpans");
            if (!scopeSpans.isArray()) {
                throw malformed("Tempo scope spans are malformed", null);
            }
            for (var scopeSpan : scopeSpans) {
                var sourceSpans = scopeSpan.path("spans");
                if (!sourceSpans.isArray()) {
                    throw malformed("Tempo spans are malformed", null);
                }
                for (var sourceSpan : sourceSpans) {
                    if (spans.size() == MAX_SPANS) {
                        truncated = true;
                        break outer;
                    }
                    spans.add(normalizeSpan(expectedTraceId, service, resourceAttributes, sourceSpan));
                }
            }
        }
        if (spans.isEmpty()) {
            throw malformed("Tempo trace contains no spans", null);
        }
        spans.sort(Comparator.comparing(TraceSpan::startTime));
        var responseStatus = root.path("status").asText();
        boolean partial = !responseStatus.isBlank()
                && !"COMPLETE".equalsIgnoreCase(responseStatus);
        return new DistributedTrace(expectedTraceId, spans, partial, truncated);
    }

    private TraceSpan normalizeSpan(
            String expectedTraceId,
            String service,
            Map<String, String> resourceAttributes,
            JsonNode span) {
        var actualTraceId = decodeId(span.path("traceId").asText(), 16, "trace ID");
        if (!expectedTraceId.equals(actualTraceId)) {
            throw malformed("Tempo returned a span for another trace", null);
        }
        var spanId = decodeId(span.path("spanId").asText(), 8, "span ID");
        var parentValue = span.path("parentSpanId").asText();
        var parentSpanId = parentValue.isBlank()
                ? null
                : decodeId(parentValue, 8, "parent span ID");
        var name = span.path("name").asText();
        if (name.isBlank()) {
            throw malformed("Tempo span name is missing", null);
        }
        if (name.length() > 256) {
            throw malformed("Tempo span name exceeds the safe limit", null);
        }
        long startNanos = parseNanos(span.path("startTimeUnixNano"), "start time");
        long endNanos = parseNanos(span.path("endTimeUnixNano"), "end time");
        if (endNanos < startNanos) {
            throw malformed("Tempo span has a negative duration", null);
        }
        var projected = new LinkedHashMap<String, String>();
        resourceAttributes.forEach((key, value) -> {
            if (ALLOWED_ATTRIBUTES.contains(key)) {
                projected.put(key, value);
            }
        });
        attributes(span.path("attributes")).forEach((key, value) -> {
            if (ALLOWED_ATTRIBUTES.contains(key)) {
                projected.put(key, value);
            }
        });
        long durationNanos;
        try {
            durationNanos = Math.subtractExact(endNanos, startNanos);
        } catch (ArithmeticException exception) {
            throw malformed("Tempo span duration is malformed", exception);
        }
        return new TraceSpan(
                spanId,
                parentSpanId,
                service,
                name,
                instantFromNanos(startNanos),
                Duration.ofNanos(durationNanos),
                status(span.path("status").path("code")),
                projected);
    }

    private Map<String, String> attributes(JsonNode source) {
        if (source.isMissingNode() || source.isNull()) {
            return Map.of();
        }
        if (!source.isArray()) {
            throw malformed("Tempo attributes are malformed", null);
        }
        var attributes = new LinkedHashMap<String, String>();
        for (var attribute : source) {
            var key = attribute.path("key").asText();
            var value = attributeValue(attribute.path("value"));
            if (!key.isBlank() && value != null) {
                attributes.put(key, value);
            }
        }
        return attributes;
    }

    private String attributeValue(JsonNode value) {
        for (var field : new String[] {
            "stringValue", "intValue", "doubleValue", "boolValue"
        }) {
            var candidate = value.get(field);
            if (candidate != null && candidate.isValueNode()) {
                var text = candidate.asText();
                if (text.length() > 256
                        || text.chars().anyMatch(Character::isISOControl)) {
                    throw malformed("Tempo attribute exceeds the safe limit", null);
                }
                return text;
            }
        }
        return null;
    }

    private TraceSpanStatus status(JsonNode code) {
        var value = code.asText();
        if ("2".equals(value) || "STATUS_CODE_ERROR".equals(value)) {
            return TraceSpanStatus.ERROR;
        }
        if ("1".equals(value) || "STATUS_CODE_OK".equals(value)) {
            return TraceSpanStatus.OK;
        }
        return TraceSpanStatus.UNSET;
    }

    private String decodeId(String value, int expectedBytes, String field) {
        if (value.matches("[a-fA-F0-9]{" + (expectedBytes * 2) + "}")) {
            return value.toLowerCase(Locale.ROOT);
        }
        try {
            var decoded = Base64.getDecoder().decode(value);
            if (decoded.length != expectedBytes) {
                throw malformed("Tempo " + field + " has the wrong length", null);
            }
            return HexFormat.of().formatHex(decoded);
        } catch (IllegalArgumentException exception) {
            throw malformed("Tempo " + field + " is malformed", exception);
        }
    }

    private long parseNanos(JsonNode value, String field) {
        if (!value.isTextual() && !value.isIntegralNumber()) {
            throw malformed("Tempo span " + field + " is malformed", null);
        }
        try {
            return Long.parseLong(value.asText());
        } catch (NumberFormatException exception) {
            throw malformed("Tempo span " + field + " is malformed", exception);
        }
    }

    private Instant instantFromNanos(long nanos) {
        return Instant.ofEpochSecond(
                Math.floorDiv(nanos, 1_000_000_000L),
                Math.floorMod(nanos, 1_000_000_000L));
    }

    private ObservabilityBackendException unavailable() {
        return new ObservabilityBackendException(
                ObservabilityBackendException.Code.BACKEND_UNAVAILABLE,
                "Tempo query failed");
    }

    private ObservabilityBackendException malformed(String message, Throwable cause) {
        return new ObservabilityBackendException(
                ObservabilityBackendException.Code.MALFORMED_RESPONSE,
                message,
                cause);
    }
}
