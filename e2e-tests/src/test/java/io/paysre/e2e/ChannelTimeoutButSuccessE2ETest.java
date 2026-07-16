package io.paysre.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "PAY_SRE_COMPOSE_E2E", matches = "true")
class ChannelTimeoutButSuccessE2ETest {

    private static final Duration INGESTION_TIMEOUT = Duration.ofSeconds(90);
    private static final String CHANNEL_BASE_URL = environment(
            "PAY_SRE_CHANNEL_BASE_URL", "http://localhost:8081");
    private static final String PAYMENT_BASE_URL = environment(
            "PAY_SRE_PAYMENT_BASE_URL", "http://localhost:8080");
    private static final String CONTROL_BASE_URL = environment(
            "PAY_SRE_CONTROL_BASE_URL", "http://localhost:8082");
    private static final String PROMETHEUS_BASE_URL = environment(
            "PAY_SRE_PROMETHEUS_BASE_URL", "http://localhost:9090");
    private static final String LOKI_BASE_URL = environment(
            "PAY_SRE_LOKI_BASE_URL", "http://localhost:3100");
    private static final String TEMPO_BASE_URL = environment(
            "PAY_SRE_TEMPO_BASE_URL", "http://localhost:3200");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    void provesAnAuditedMetricLogTraceInvestigationOfALostChannelResponse()
            throws Exception {
        var groundTruth = ScenarioGroundTruth.load(
                "/fault-scenarios/channel-timeout-but-success-v1.yaml");
        Instant startedAt = Instant.now();
        installFault(groundTruth, startedAt);
        var paymentIds = createUnknownPayments(groundTruth);
        var telemetry = awaitTelemetry(
                paymentIds.get(0),
                groundTruth.fault().channel(),
                groundTruth.traffic().payments(),
                startedAt);
        String incidentId = createIncident(groundTruth, startedAt);

        var conclusion = postJson(
                controlUri("/api/incidents/" + incidentId + "/investigations"),
                objectMapper.createObjectNode()
                        .put("representativePaymentId", paymentIds.get(0))
                        .put("channel", groundTruth.fault().channel())
                        .put("from", startedAt.minusSeconds(300).toString())
                        .put("to", startedAt.plusSeconds(300).toString()),
                200);
        var evidenceIndex = getJson(
                controlUri("/api/incidents/" + incidentId + "/evidence"), 200);
        var evidenceByType = loadEvidenceDetails(incidentId, evidenceIndex);
        var audits = getJson(
                controlUri("/api/incidents/" + incidentId + "/tool-audits"), 200);

        assertEvidenceChain(
                evidenceByType,
                evidenceIndex,
                audits,
                paymentIds.get(0),
                telemetry.traceId(),
                groundTruth.traffic().payments());
        assertConclusionAndGroundTruth(
                groundTruth, conclusion, evidenceByType.keySet(), evidenceIndex.size());
    }

    private void assertEvidenceChain(
            Map<String, JsonNode> evidenceByType,
            JsonNode evidenceIndex,
            JsonNode audits,
            String representativePaymentId,
            String traceId,
            int expectedUnknownCount) throws Exception {
        assertThat(evidenceByType.keySet()).contains(
                "SERVICE_METRICS",
                "STRUCTURED_LOGS",
                "DISTRIBUTED_TRACE",
                "PAYMENT_TIMELINE",
                "CHANNEL_FINAL_STATE",
                "INCIDENT_IMPACT");

        var metrics = evidenceByType.get("SERVICE_METRICS").path("content");
        assertThat(metricSamples(metrics))
                .anySatisfy(sample -> {
                    assertThat(sample.path("available").asBoolean()).isTrue();
                    assertThat(sample.path("value").decimalValue())
                            .isGreaterThanOrEqualTo(new BigDecimal(expectedUnknownCount));
                });
        assertThat(metrics.toString()).doesNotContain(
                "paymentId", "payment.id", representativePaymentId);

        var logs = evidenceByType.get("STRUCTURED_LOGS")
                .path("content").path("records");
        assertThat(iterable(logs)).anySatisfy(record -> {
            assertThat(record.path("paymentId").asText())
                    .isEqualTo(representativePaymentId);
            assertThat(record.path("reasonCode").asText())
                    .isEqualTo("CHANNEL_TIMEOUT");
            assertThat(record.path("traceId").asText()).isEqualTo(traceId);
        });

        var trace = evidenceByType.get("DISTRIBUTED_TRACE").path("content");
        assertThat(trace.path("traceId").asText()).isEqualTo(traceId);
        var spans = iterable(trace.path("spans"));
        assertThat(spans).extracting(span -> span.path("service").asText())
                .contains("payment-service", "channel-simulator");
        assertThat(spans).extracting(span -> span.path("name").asText())
                .contains("payment.channel.invoke", "payment.channel.process");
        assertThat(spans).anySatisfy(span -> assertThat(
                        span.path("attributes").path("payment.id").asText())
                .isEqualTo(representativePaymentId));

        for (var entry : evidenceByType.values()) {
            assertThat(entry.path("sha256").asText())
                    .isEqualTo(sha256(canonicalize(entry.path("content"))));
        }

        var indexedEvidenceIds = iterable(evidenceIndex).stream()
                .map(item -> item.path("evidenceId").asText())
                .collect(Collectors.toSet());
        var auditedEvidenceIds = iterable(audits).stream()
                .peek(item -> assertThat(item.path("successful").asBoolean()).isTrue())
                .flatMap(item -> iterable(item.path("evidenceIds")).stream())
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
        assertThat(audits).hasSize(evidenceIndex.size());
        assertThat(auditedEvidenceIds).isEqualTo(indexedEvidenceIds);
    }

    private void assertConclusionAndGroundTruth(
            ScenarioGroundTruth groundTruth,
            JsonNode conclusion,
            Set<String> evidenceTypes,
            int evidenceCount) {
        var actual = new ScenarioEvaluator.ActualInvestigation(
                conclusion.path("rootCause").asText(),
                evidenceTypes,
                evidenceCount,
                conclusion.path("recommendedRunbook").asText(),
                conclusion.path("requiresHumanReview").asBoolean());
        var score = new ScenarioEvaluator().evaluate(groundTruth.expected(), actual);

        assertThat(conclusion.path("affectedPaymentCount").asLong())
                .isEqualTo(groundTruth.traffic().payments());
        assertThat(conclusion.path("affectedAmount").path("amount").decimalValue())
                .isEqualByComparingTo(groundTruth.traffic().amount()
                        .multiply(BigDecimal.valueOf(groundTruth.traffic().payments())));
        assertThat(conclusion.path("affectedAmount").path("currency").asText())
                .isEqualTo(groundTruth.traffic().currency());
        assertThat(conclusion.path("recommendedRunbook").asText())
                .isEqualTo("query-and-sync-unknown-payments");
        assertThat(conclusion.path("requiresHumanReview").asBoolean()).isTrue();
        assertThat(score.passed()).isTrue();
    }

    private Map<String, JsonNode> loadEvidenceDetails(
            String incidentId, JsonNode evidenceIndex) throws Exception {
        var details = new LinkedHashMap<String, JsonNode>();
        for (var item : evidenceIndex) {
            var evidenceId = item.path("evidenceId").asText();
            var detail = getJson(controlUri(
                    "/api/incidents/" + incidentId + "/evidence/" + evidenceId), 200);
            details.put(detail.path("evidenceType").asText(), detail);
        }
        return Map.copyOf(details);
    }

    private TelemetrySnapshot awaitTelemetry(
            String paymentId, String channel, int expectedUnknownCount, Instant from)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(INGESTION_TIMEOUT);
        String traceId = null;
        boolean metricsReady = false;
        boolean traceReady = false;
        while (Instant.now().isBefore(deadline)) {
            metricsReady = metricShowsUnknown(channel, expectedUnknownCount);
            if (traceId == null) {
                traceId = findTraceIdInLogs(paymentId, from).orElse(null);
            }
            traceReady = traceId != null && tempoHasTrace(traceId);
            if (metricsReady && traceReady) {
                return new TelemetrySnapshot(traceId);
            }
            Thread.sleep(Duration.ofSeconds(2).toMillis());
        }
        throw new AssertionError("telemetry was not ingested within "
                + INGESTION_TIMEOUT
                + ": metricsReady=" + metricsReady
                + ", logTraceId=" + traceId
                + ", tempoReady=" + traceReady);
    }

    private boolean metricShowsUnknown(String channel, int expectedUnknownCount) {
        var query = "payment_unknown_current{service=\"payment-service\",channel=\""
                + channel + "\"}";
        return tryGetJson(queryUri(
                        PROMETHEUS_BASE_URL,
                        "/api/v1/query",
                        Map.of("query", query, "time", Instant.now().toString())))
                .map(root -> iterable(root.path("data").path("result")).stream()
                        .map(item -> item.path("value"))
                        .filter(value -> value.isArray() && value.size() == 2)
                        .map(value -> value.get(1).asText())
                        .map(BigDecimal::new)
                        .anyMatch(value -> value.compareTo(
                                BigDecimal.valueOf(expectedUnknownCount)) >= 0))
                .orElse(false);
    }

    private Optional<String> findTraceIdInLogs(String paymentId, Instant from) {
        var query = "{service_name=\"payment-service\"}"
                + " | paymentId=\"" + paymentId + "\""
                + " | event=\"PAYMENT_STATE_CHANGED\""
                + " | reasonCode=\"CHANNEL_TIMEOUT\"";
        return tryGetJson(queryUri(
                        LOKI_BASE_URL,
                        "/loki/api/v1/query_range",
                        Map.of(
                                "query", query,
                                "start", epochNanos(from.minusSeconds(60)),
                                "end", epochNanos(Instant.now()),
                                "limit", "20",
                                "direction", "backward")))
                .flatMap(root -> iterable(root.path("data").path("result")).stream()
                        .flatMap(stream -> iterable(stream.path("values")).stream()
                                .map(value -> firstText(
                                        value.isArray()
                                                        && value.size() >= 3
                                                        && value.get(2).isObject()
                                                ? value.get(2)
                                                : objectMapper.createObjectNode(),
                                        "trace_id",
                                        "traceId")))
                        .filter(id -> id.matches("[a-fA-F0-9]{32}"))
                        .map(id -> id.toLowerCase(java.util.Locale.ROOT))
                        .findFirst());
    }

    private boolean tempoHasTrace(String traceId) {
        return tryGetJson(URI.create(TEMPO_BASE_URL + "/api/v2/traces/" + traceId))
                .map(root -> root.has("trace") ? root.path("trace") : root)
                .map(root -> root.path("resourceSpans"))
                .filter(JsonNode::isArray)
                .filter(Predicate.not(JsonNode::isEmpty))
                .isPresent();
    }

    private void installFault(ScenarioGroundTruth groundTruth, Instant now)
            throws Exception {
        var fault = objectMapper.createObjectNode()
                .put("channel", groundTruth.fault().channel())
                .put("type", groundTruth.fault().type())
                .put("probability", groundTruth.fault().probability())
                .put("activeFrom", now.minusSeconds(30).toString())
                .put("activeUntil", now.plus(groundTruth.fault().activeFor()).toString())
                .put("randomSeed", groundTruth.seed());
        putJson(channelUri("/api/admin/faults/" + groundTruth.fault().channel()), fault, 200);
    }

    private List<String> createUnknownPayments(ScenarioGroundTruth groundTruth)
            throws Exception {
        var paymentIds = new ArrayList<String>();
        for (int index = 0; index < groundTruth.traffic().payments(); index++) {
            var command = objectMapper.createObjectNode()
                    .put("orderId", "ORDER-" + index)
                    .put("merchantId", "MERCHANT-001")
                    .put("idempotencyKey", "SCENARIO-" + groundTruth.id() + "-" + index);
            command.putObject("money")
                    .put("amount", groundTruth.traffic().amount())
                    .put("currency", groundTruth.traffic().currency());
            var payment = postJson(paymentUri("/api/payments"), command, 201);
            assertThat(payment.path("status").asText()).isEqualTo("UNKNOWN");
            paymentIds.add(payment.path("paymentId").asText());
        }
        assertThat(paymentIds).hasSize(groundTruth.traffic().payments());
        return List.copyOf(paymentIds);
    }

    private String createIncident(ScenarioGroundTruth groundTruth, Instant startedAt)
            throws Exception {
        var alert = objectMapper.createObjectNode().put("status", "firing");
        var item = alert.putArray("alerts").addObject()
                .put("status", "firing")
                .put("startsAt", startedAt.toString())
                .put("fingerprint", groundTruth.id() + "-alert");
        item.putObject("labels")
                .put("service", "payment-service")
                .put("alertname", "PaymentUnknownHigh")
                .put("channel", groundTruth.fault().channel())
                .put("severity", "high");
        item.putObject("annotations")
                .put("observedValue", groundTruth.traffic().payments())
                .put("threshold", 1);
        var incidents = postJson(
                controlUri("/api/alerts/alertmanager"), alert, 200);
        return incidents.get(0).path("incidentId").asText();
    }

    private List<JsonNode> metricSamples(JsonNode metrics) {
        return iterable(metrics.path("series")).stream()
                .flatMap(series -> iterable(series.path("samples")).stream())
                .toList();
    }

    private List<JsonNode> iterable(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false).toList();
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            var result = objectMapper.createObjectNode();
            node.propertyStream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> result.set(
                            entry.getKey(), canonicalize(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = objectMapper.createArrayNode();
            node.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return node.deepCopy();
    }

    private String sha256(JsonNode content) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(objectMapper.writeValueAsBytes(content)));
    }

    private Optional<JsonNode> tryGetJson(URI uri) {
        try {
            var response = http.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readTree(response.body()));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private JsonNode getJson(URI uri, int expectedStatus) throws Exception {
        return exchange(HttpRequest.newBuilder(uri).GET().build(), expectedStatus);
    }

    private JsonNode postJson(URI uri, JsonNode body, int expectedStatus)
            throws Exception {
        return exchange(jsonRequest(uri, "POST", body), expectedStatus);
    }

    private void putJson(URI uri, JsonNode body, int expectedStatus) throws Exception {
        exchange(jsonRequest(uri, "PUT", body), expectedStatus);
    }

    private HttpRequest jsonRequest(URI uri, String method, JsonNode body)
            throws IOException {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body)))
                .build();
    }

    private JsonNode exchange(HttpRequest request, int expectedStatus) throws Exception {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .withFailMessage("%s returned %s: %s",
                        request.uri(), response.statusCode(), response.body())
                .isEqualTo(expectedStatus);
        return response.body().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(response.body());
    }

    private URI queryUri(String baseUrl, String path, Map<String, String> parameters) {
        var query = parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        return URI.create(baseUrl + path + "?" + query);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private String epochNanos(Instant instant) {
        return Long.toString(Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L),
                instant.getNano()));
    }

    private String firstText(JsonNode node, String... names) {
        for (var name : names) {
            if (node.path(name).isTextual() && !node.path(name).asText().isBlank()) {
                return node.path(name).asText();
            }
        }
        return "";
    }

    private URI channelUri(String path) {
        return URI.create(CHANNEL_BASE_URL + path);
    }

    private URI paymentUri(String path) {
        return URI.create(PAYMENT_BASE_URL + path);
    }

    private URI controlUri(String path) {
        return URI.create(CONTROL_BASE_URL + path);
    }

    private static String environment(String name, String defaultValue) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private record TelemetrySnapshot(String traceId) {
    }
}
