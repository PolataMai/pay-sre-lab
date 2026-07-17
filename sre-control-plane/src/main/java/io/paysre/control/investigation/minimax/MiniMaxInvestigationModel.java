package io.paysre.control.investigation.minimax;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paysre.contracts.Money;
import io.paysre.control.evidence.Evidence;
import io.paysre.control.investigation.InvestigationContext;
import io.paysre.control.investigation.InvestigationConclusion;
import io.paysre.control.investigation.InvestigationDecision;
import io.paysre.control.investigation.InvestigationModel;
import io.paysre.control.investigation.RootCauseCode;
import io.paysre.control.tools.ToolResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Stateless single-shot decision adapter: every call rebuilds the full
 * investigation state from the context, asks MiniMax for exactly one tool
 * call, and maps it onto the sealed decision type. Anything the model gets
 * wrong either fails closed here or is rejected downstream by the tool
 * gateway and the conclusion validator.
 */
public final class MiniMaxInvestigationModel implements InvestigationModel {

    private static final int MAX_EVIDENCE_CONTENT_CHARS = 16_384;

    private static final String SYSTEM_PROMPT = """
        You are the investigation agent of PaySRE Lab, a payment SRE control plane.
        Each turn you receive the full current investigation state as JSON and you
        must respond with EXACTLY ONE tool call and no free-form text.

        Rules:
        1. Investigate step by step: query aggregate metrics first, then drill into
           the representative payment with search_structured_logs, follow its traceId
           into get_distributed_trace, confirm get_payment_timeline and
           query_channel_final_state, and finally compute the authoritative impact
           with calculate_incident_impact.
        2. Never invent identifiers. Payment IDs, trace IDs, channels and time
           windows must come from the investigation state or from collected evidence.
        3. Trace IDs must be taken from structured log records of the representative
           payment, never guessed.
        4. conclude_investigation is only allowed when the evidence list already
           contains INCIDENT_IMPACT evidence. affectedPaymentCount, affectedAmount
           and currency MUST be copied verbatim from that evidence content
           (fields affectedPaymentCount, totalAmount, currency). Never compute,
           round or convert any impact figure yourself.
        5. A conclusion must reference at least three existing evidence IDs from the
           state, including the INCIDENT_IMPACT evidence. Unknown evidence IDs fail
           validation.
        6. Use confidence of 0.80 or higher only when the referenced evidence
           includes usable SERVICE_METRICS evidence and at least two of
           PAYMENT_TIMELINE, STRUCTURED_LOGS, DISTRIBUTED_TRACE,
           CHANNEL_FINAL_STATE.
        7. The only allowed recommendedRunbook is "query-and-sync-unknown-payments"
           and requiresHumanReview must be true.
        8. The only supported rootCause is CHANNEL_TIMEOUT_RESPONSE_LOST. If the
           evidence does not support it, call escalate_to_human instead of guessing.
        9. If lastValidationError is present, your previous conclusion was rejected
           for exactly that reason; fix that specific problem before concluding again.
        10. If required telemetry is unavailable or no progress is possible, call
            escalate_to_human with a concrete reason.
        """;

    private final MiniMaxChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final ArrayNode tools;

    public MiniMaxInvestigationModel(MiniMaxChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.tools = MiniMaxInvestigationTools.catalog(objectMapper);
    }

    @Override
    public InvestigationDecision decide(InvestigationContext context) {
        var turn = chatClient.complete(SYSTEM_PROMPT, userPayload(context), tools);
        if (turn.toolCalls().isEmpty()) {
            throw new MiniMaxModelException(
                    "MINIMAX_NO_TOOL_DECISION",
                    "the model returned no tool call");
        }
        var call = turn.toolCalls().get(0);
        return switch (call.name()) {
            case "conclude_investigation" -> conclude(context, call.arguments());
            case "escalate_to_human" -> escalate(call.arguments());
            default -> new InvestigationDecision.CallTool(call.name(), call.arguments());
        };
    }

    private InvestigationDecision escalate(JsonNode arguments) {
        var reason = arguments.path("reason").asText("");
        return new InvestigationDecision.Escalate(
                reason.isBlank() ? "the model escalated without a reason" : reason);
    }

    private InvestigationDecision conclude(InvestigationContext context, JsonNode arguments) {
        try {
            var conclusion = new InvestigationConclusion(
                    context.incident().incidentId(),
                    RootCauseCode.valueOf(arguments.path("rootCause").asText()),
                    new BigDecimal(arguments.path("confidence").asText()),
                    textList(arguments.path("evidenceIds")),
                    Long.parseLong(arguments.path("affectedPaymentCount").asText()),
                    new Money(
                            new BigDecimal(arguments.path("affectedAmount").asText()),
                            Currency.getInstance(arguments.path("currency").asText())),
                    arguments.path("recommendedRunbook").asText(),
                    arguments.path("requiresHumanReview").asBoolean(false));
            return new InvestigationDecision.Conclude(conclusion);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new MiniMaxModelException(
                    "MINIMAX_MALFORMED_CONCLUSION",
                    "the model conclusion could not be parsed: " + exception.getMessage());
        }
    }

    private List<String> textList(JsonNode node) {
        var values = new ArrayList<String>();
        if (node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private String userPayload(InvestigationContext context) {
        var payload = objectMapper.createObjectNode();
        var incident = payload.putObject("incident");
        incident.put("incidentId", context.incident().incidentId());
        incident.put("aggregateKey", context.incident().aggregateKey());
        incident.put("status", context.incident().status().name());
        incident.put("detectedAt", context.incident().detectedAt().toString());

        var seed = payload.putObject("seed");
        seed.put("representativePaymentId", context.seed().representativePaymentId());
        seed.put("channel", context.seed().channel());
        seed.put("from", context.seed().from().toString());
        seed.put("to", context.seed().to().toString());

        var evidenceList = payload.putArray("evidence");
        context.evidence().forEach(item -> evidenceList.add(evidenceNode(item)));

        var results = payload.putArray("toolResults");
        context.toolResults().forEach(result -> results.add(toolResultNode(result)));

        if (context.lastValidationError() == null) {
            payload.putNull("lastValidationError");
        } else {
            payload.put("lastValidationError", context.lastValidationError());
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new MiniMaxModelException(
                    "MINIMAX_STATE_SERIALIZATION_FAILED",
                    "the investigation state could not be serialized");
        }
    }

    private ObjectNode evidenceNode(Evidence evidence) {
        var node = objectMapper.createObjectNode();
        node.put("evidenceId", evidence.evidenceId());
        node.put("evidenceType", evidence.evidenceType());
        node.put("sourceTool", evidence.sourceTool());
        node.put("collectedAt", evidence.collectedAt().toString());
        var content = evidence.content().toString();
        if (content.length() > MAX_EVIDENCE_CONTENT_CHARS) {
            node.put("contentTruncated", true);
            node.put("contentPrefix", content.substring(0, MAX_EVIDENCE_CONTENT_CHARS));
        } else {
            node.set("content", evidence.content());
        }
        return node;
    }

    private ObjectNode toolResultNode(ToolResult result) {
        var node = objectMapper.createObjectNode();
        node.put("toolName", result.toolName());
        node.put("successful", result.successful());
        if (result.errorCode() != null) {
            node.put("errorCode", result.errorCode());
        }
        var ids = node.putArray("evidenceIds");
        result.evidenceIds().forEach(ids::add);
        return node;
    }
}
