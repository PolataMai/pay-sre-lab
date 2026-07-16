package io.paysre.control.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paysre.contracts.Money;
import io.paysre.control.evidence.Evidence;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class StubInvestigationModel implements InvestigationModel {

    private static final String RUNBOOK = "query-and-sync-unknown-payments";
    private static final String METRICS_TOOL = "query_service_metrics";
    private static final String LOGS_TOOL = "search_structured_logs";
    private static final String TRACE_TOOL = "get_distributed_trace";
    private static final String TIMELINE_TOOL = "get_payment_timeline";
    private static final String CHANNEL_TOOL = "query_channel_final_state";
    private static final String IMPACT_TOOL = "calculate_incident_impact";
    private static final Set<String> TRANSACTION_EVIDENCE_TYPES = Set.of(
            "PAYMENT_TIMELINE",
            "STRUCTURED_LOGS",
            "DISTRIBUTED_TRACE",
            "CHANNEL_FINAL_STATE");

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StubInvestigationModel(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public InvestigationDecision decide(InvestigationContext context) {
        var telemetryWindow = telemetryWindow(context.seed());
        if (telemetryWindow.isEmpty()) {
            return new InvestigationDecision.Escalate(
                    "no bounded telemetry window is available");
        }
        var window = telemetryWindow.get();
        if (!attempted(context, METRICS_TOOL)) {
            long rangeSeconds = Duration.between(window.from(), window.to()).toSeconds();
            long stepSeconds = Math.max(30, (rangeSeconds + 238) / 239);
            return new InvestigationDecision.CallTool(
                    METRICS_TOOL,
                    objectMapper.createObjectNode()
                            .put("signal", "PAYMENT_UNKNOWN_CURRENT")
                            .put("service", "payment-service")
                            .put("channel", context.seed().channel())
                            .put("from", window.from().toString())
                            .put("to", window.to().toString())
                            .put("step", Duration.ofSeconds(stepSeconds).toString()));
        }
        if (!hasUsableMetrics(context)) {
            return new InvestigationDecision.Escalate(
                    "required aggregate metrics are unavailable");
        }
        if (!attempted(context, LOGS_TOOL)) {
            return new InvestigationDecision.CallTool(
                    LOGS_TOOL,
                    objectMapper.createObjectNode()
                            .put("service", "payment-service")
                            .put("event", "PAYMENT_STATE_CHANGED")
                            .put("minimumLevel", "INFO")
                            .put("paymentId", context.seed().representativePaymentId())
                            .put("from", window.from().toString())
                            .put("to", window.to().toString())
                            .put("limit", 20));
        }
        var traceId = traceIdFromPersistedLogEvidence(context);
        if (traceId.isPresent() && !attempted(context, TRACE_TOOL)) {
            return new InvestigationDecision.CallTool(
                    TRACE_TOOL,
                    objectMapper.createObjectNode()
                            .put("traceId", traceId.get())
                            .put("from", window.from().toString())
                            .put("to", window.to().toString()));
        }
        if (!attempted(context, TIMELINE_TOOL)) {
            return new InvestigationDecision.CallTool(
                    TIMELINE_TOOL,
                    objectMapper.createObjectNode()
                            .put("paymentId", context.seed().representativePaymentId()));
        }
        if (!attempted(context, CHANNEL_TOOL)) {
            return new InvestigationDecision.CallTool(
                    CHANNEL_TOOL,
                    objectMapper.createObjectNode()
                            .put("paymentId", context.seed().representativePaymentId()));
        }
        if (!attempted(context, IMPACT_TOOL)) {
            return new InvestigationDecision.CallTool(
                    IMPACT_TOOL,
                    objectMapper.createObjectNode()
                            .put("channel", context.seed().channel())
                            .put("from", context.seed().from().toString())
                            .put("to", context.seed().to().toString()));
        }
        if (!hasEvidenceType(context, "INCIDENT_IMPACT")) {
            return new InvestigationDecision.Escalate(
                    "authoritative incident impact is unavailable");
        }
        if (transactionEvidenceTypeCount(context) < 2) {
            return new InvestigationDecision.Escalate(
                    "fewer than two transaction-specific evidence sources are available");
        }
        return conclude(context);
    }

    private Optional<TelemetryWindow> telemetryWindow(InvestigationSeed seed) {
        var now = clock.instant();
        var to = seed.to().isBefore(now) ? seed.to() : now;
        var earliest = to.minus(Duration.ofHours(2));
        var from = seed.from().isAfter(earliest) ? seed.from() : earliest;
        return from.isBefore(to)
                ? Optional.of(new TelemetryWindow(from, to))
                : Optional.empty();
    }

    private boolean attempted(InvestigationContext context, String toolName) {
        return context.toolResults().stream()
                .anyMatch(result -> result.toolName().equals(toolName));
    }

    private boolean hasUsableMetrics(InvestigationContext context) {
        return context.evidence().stream()
                .filter(item -> item.evidenceType().equals("SERVICE_METRICS"))
                .map(item -> item.content().path("series"))
                .filter(JsonNode::isArray)
                .flatMap(series -> java.util.stream.StreamSupport.stream(
                        series.spliterator(), false))
                .map(series -> series.path("samples"))
                .filter(JsonNode::isArray)
                .flatMap(samples -> java.util.stream.StreamSupport.stream(
                        samples.spliterator(), false))
                .anyMatch(sample -> sample.path("value").isNumber()
                        && (!sample.path("available").isBoolean()
                                || sample.path("available").asBoolean()));
    }

    private Optional<String> traceIdFromPersistedLogEvidence(
            InvestigationContext context) {
        var expectedPaymentId = context.seed().representativePaymentId();
        return context.evidence().stream()
                .filter(item -> item.evidenceType().equals("STRUCTURED_LOGS"))
                .map(item -> item.content().path("records"))
                .filter(JsonNode::isArray)
                .flatMap(records -> java.util.stream.StreamSupport.stream(
                        records.spliterator(), false))
                .filter(record -> expectedPaymentId.equals(
                        record.path("paymentId").asText()))
                .map(record -> record.path("traceId").asText())
                .filter(value -> value.matches("[a-fA-F0-9]{32}"))
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .findFirst();
    }

    private boolean hasEvidenceType(InvestigationContext context, String type) {
        return context.evidence().stream()
                .anyMatch(item -> item.evidenceType().equals(type));
    }

    private long transactionEvidenceTypeCount(InvestigationContext context) {
        return context.evidence().stream()
                .filter(item -> isUsableTransactionEvidence(item, context))
                .map(item -> item.evidenceType())
                .filter(TRANSACTION_EVIDENCE_TYPES::contains)
                .distinct()
                .count();
    }

    private boolean isUsableTransactionEvidence(
            Evidence evidence, InvestigationContext context) {
        var content = evidence.content();
        var expectedPaymentId = context.seed().representativePaymentId();
        return switch (evidence.evidenceType()) {
            case "PAYMENT_TIMELINE" -> hasNonEmptyArray(content)
                    || hasNonEmptyArray(content.path("events"));
            case "STRUCTURED_LOGS" -> recordsForPayment(content, expectedPaymentId);
            case "DISTRIBUTED_TRACE" -> hasNonEmptyArray(content.path("spans"))
                    && traceIdFromPersistedLogEvidence(context)
                            .filter(id -> id.equalsIgnoreCase(
                                    content.path("traceId").asText()))
                            .isPresent();
            case "CHANNEL_FINAL_STATE" -> expectedPaymentId.equals(
                            content.path("paymentId").asText())
                    && Set.of("SUCCESS", "FAILED").contains(
                            content.path("result").asText());
            default -> false;
        };
    }

    private boolean recordsForPayment(JsonNode content, String paymentId) {
        var records = content.path("records");
        return records.isArray()
                && java.util.stream.StreamSupport.stream(
                                records.spliterator(), false)
                        .anyMatch(record -> paymentId.equals(
                                record.path("paymentId").asText()));
    }

    private boolean hasNonEmptyArray(JsonNode node) {
        return node.isArray() && !node.isEmpty();
    }

    private InvestigationDecision conclude(InvestigationContext context) {
        var impact = context.evidence().stream()
                .filter(item -> item.evidenceType().equals("INCIDENT_IMPACT"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("impact evidence is missing"));
        var content = impact.content();
        var amount = content.path("totalAmount").decimalValue();
        var currency = Currency.getInstance(content.path("currency").asText());
        var conclusion = new InvestigationConclusion(
                context.incident().incidentId(),
                RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST,
                new BigDecimal("0.95"),
                context.evidence().stream().map(item -> item.evidenceId()).toList(),
                content.path("affectedPaymentCount").asLong(),
                new Money(amount, currency),
                RUNBOOK,
                true);
        return new InvestigationDecision.Conclude(conclusion);
    }

    private record TelemetryWindow(Instant from, Instant to) {
    }
}
