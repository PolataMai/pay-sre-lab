package io.paysre.control.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public final class HttpLokiReadClient implements LokiReadClient {

    static final int MAX_LIMIT = 200;
    static final int MAX_RESPONSE_CHARS = 1_048_576;
    private static final int MAX_MESSAGE_LENGTH = 2_048;
    private static final Duration MAX_RANGE = Duration.ofHours(2);
    private static final Set<String> SERVICES =
            Set.of("payment-service", "channel-simulator", "sre-control-plane");
    private static final Pattern TRACE_ID = Pattern.compile("[a-fA-F0-9]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[a-fA-F0-9]{16}");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public HttpLokiReadClient(RestClient restClient, ObjectMapper objectMapper, Clock clock) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public StructuredLogResult search(StructuredLogSearch search) {
        validate(search);
        var logQl = buildLogQl(search);
        String body;
        try {
            body = restClient.get()
                    .uri(builder -> builder
                            .path("/loki/api/v1/query_range")
                            .queryParam("query", "{logql}")
                            .queryParam("start", epochNanos(search.from()))
                            .queryParam("end", epochNanos(search.to()))
                            .queryParam("limit", search.limit())
                            .queryParam("direction", "backward")
                            .build(Map.of("logql", logQl)))
                    .header("X-Loki-Response-Encoding-Flags", "categorize-labels")
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException ignored) {
            throw new ObservabilityBackendException(
                    ObservabilityBackendException.Code.BACKEND_UNAVAILABLE,
                    "Loki query failed");
        }
        if (body == null) {
            throw malformed("Loki returned an empty response", null);
        }
        if (body.length() > MAX_RESPONSE_CHARS) {
            throw new ObservabilityBackendException(
                    ObservabilityBackendException.Code.LIMIT_EXCEEDED,
                    "Loki response exceeded the safe limit");
        }
        return normalize(body, search);
    }

    private void validate(StructuredLogSearch search) {
        Objects.requireNonNull(search, "search");
        if (!SERVICES.contains(search.service())) {
            throw new IllegalArgumentException("unsupported log service");
        }
        search.minimumLevel().minimumRegex();
        if (search.event() == null
                && isBlank(search.paymentId())
                && isBlank(search.traceId())) {
            throw new IllegalArgumentException("at least one log filter is required");
        }
        validatePaymentId(search.paymentId());
        if (!isBlank(search.traceId()) && !TRACE_ID.matcher(search.traceId()).matches()) {
            throw new IllegalArgumentException("trace ID must contain 32 hexadecimal characters");
        }
        if (!search.from().isBefore(search.to())) {
            throw new IllegalArgumentException("log range must have from before to");
        }
        if (search.to().isAfter(clock.instant())) {
            throw new IllegalArgumentException("log range cannot end in the future");
        }
        if (Duration.between(search.from(), search.to()).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("log range cannot exceed two hours");
        }
        if (search.limit() < 1 || search.limit() > MAX_LIMIT) {
            throw new IllegalArgumentException("log limit must be between 1 and 200");
        }
    }

    private void validatePaymentId(String paymentId) {
        if (paymentId == null) {
            return;
        }
        if (paymentId.isBlank() || paymentId.length() > 128) {
            throw new IllegalArgumentException("payment ID must contain 1 to 128 characters");
        }
        if (paymentId.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("payment ID cannot contain control characters");
        }
    }

    private String buildLogQl(StructuredLogSearch search) {
        var query = new StringBuilder("{service_name=\"")
                .append(escape(search.service()))
                .append("\"} | severity_text=~\"")
                .append(search.minimumLevel().minimumRegex())
                .append('"');
        if (search.event() != null) {
            query.append(" | event=\"").append(search.event().name()).append('"');
        }
        if (!isBlank(search.paymentId())) {
            query.append(" | paymentId=\"").append(escape(search.paymentId())).append('"');
        }
        if (!isBlank(search.traceId())) {
            query.append(" | traceId=\"")
                    .append(search.traceId().toLowerCase(java.util.Locale.ROOT))
                    .append('"');
        }
        return query.toString();
    }

    private StructuredLogResult normalize(String body, StructuredLogSearch search) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw malformed("Loki returned malformed JSON", exception);
        }
        var data = root.path("data");
        var streams = data.path("result");
        if (!"success".equals(root.path("status").asText())
                || !"streams".equals(data.path("resultType").asText())
                || !streams.isArray()) {
            throw malformed("Loki returned an invalid streams response", null);
        }

        var entries = new ArrayList<NormalizedLog>();
        for (var stream : streams) {
            var labels = stream.path("stream");
            var values = stream.path("values");
            if (!labels.isObject() || !values.isArray()) {
                throw malformed("Loki stream is malformed", null);
            }
            for (var value : values) {
                entries.add(normalizeEntry(search.service(), labels, value));
            }
        }
        entries.sort(Comparator.comparing(
                        (NormalizedLog item) -> item.record().timestamp())
                .reversed());
        boolean truncated = entries.size() > search.limit()
                || entries.stream().anyMatch(NormalizedLog::contentTruncated);
        var records = entries.stream()
                .limit(search.limit())
                .map(NormalizedLog::record)
                .toList();
        return new StructuredLogResult(records, truncated);
    }

    private NormalizedLog normalizeEntry(
            String expectedService, JsonNode labels, JsonNode value) {
        if (!value.isArray()
                || value.size() < 2
                || !value.get(0).isTextual()
                || !value.get(1).isTextual()) {
            throw malformed("Loki log entry is malformed", null);
        }
        var service = labels.path("service_name").asText();
        if (!expectedService.equals(service)) {
            throw malformed("Loki returned a log from another service", null);
        }
        var metadata = new LinkedHashMap<String, String>();
        copyTextProperties(labels, metadata);
        if (value.size() >= 3 && value.get(2).isObject()) {
            copyResponseMetadata(value.get(2), metadata);
        }
        var rawMessage = value.get(1).asText();
        boolean truncated = rawMessage.length() > MAX_MESSAGE_LENGTH;
        var message = truncated ? rawMessage.substring(0, MAX_MESSAGE_LENGTH) : rawMessage;
        var traceId = validateBackendId(
                first(metadata, "traceId", "trace_id"), TRACE_ID, "trace ID");
        var spanId = validateBackendId(
                first(metadata, "spanId", "span_id"), SPAN_ID, "span ID");
        var paymentId = boundedMetadata(metadata.get("paymentId"), 128, "payment ID");
        var reasonCode = boundedMetadata(metadata.get("reasonCode"), 128, "reason code");
        var record = new StructuredLogRecord(
                parseEpochNanos(value.get(0).asText()),
                service,
                LogLevel.fromValue(first(metadata, "severity_text", "level", "log_level")),
                traceId,
                spanId,
                LogEvent.fromValue(metadata.get("event")),
                paymentId,
                reasonCode,
                message);
        return new NormalizedLog(record, truncated);
    }

    private void copyTextProperties(JsonNode source, Map<String, String> target) {
        source.properties().forEach(entry -> {
            if (entry.getValue().isTextual()) {
                target.put(entry.getKey(), entry.getValue().asText());
            }
        });
    }

    private void copyResponseMetadata(JsonNode envelope, Map<String, String> target) {
        // Loki can return metadata either as a flat object or, when label
        // categorization is enabled, under parsed/structuredMetadata. Source
        // structured metadata must win over query-time parsed labels.
        copyTextProperties(envelope.path("parsed"), target);
        copyTextProperties(envelope, target);
        copyTextProperties(envelope.path("structured_metadata"), target);
        copyTextProperties(envelope.path("structuredMetadata"), target);
    }

    private String first(Map<String, String> values, String... keys) {
        for (var key : keys) {
            if (!isBlank(values.get(key))) {
                return values.get(key);
            }
        }
        return null;
    }

    private String validateBackendId(String value, Pattern pattern, String field) {
        if (value == null) {
            return null;
        }
        if (!pattern.matcher(value).matches()) {
            throw malformed("Loki " + field + " is malformed", null);
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private String boundedMetadata(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        if (value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw malformed("Loki " + field + " is malformed", null);
        }
        return value;
    }

    private Instant parseEpochNanos(String value) {
        try {
            long epochNanos = Long.parseLong(value);
            return Instant.ofEpochSecond(
                    Math.floorDiv(epochNanos, 1_000_000_000L),
                    Math.floorMod(epochNanos, 1_000_000_000L));
        } catch (NumberFormatException exception) {
            throw malformed("Loki timestamp is malformed", exception);
        }
    }

    private String epochNanos(Instant instant) {
        return Long.toString(Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L),
                instant.getNano()));
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ObservabilityBackendException malformed(String message, Throwable cause) {
        return new ObservabilityBackendException(
                ObservabilityBackendException.Code.MALFORMED_RESPONSE,
                message,
                cause);
    }

    private record NormalizedLog(StructuredLogRecord record, boolean contentTruncated) {
    }
}
