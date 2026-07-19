package io.paysre.control.investigation.minimax;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paysre.control.investigation.RootCausePolicy;
import io.paysre.control.investigation.RootCausePolicyCatalog;
import java.util.Objects;
import java.util.TreeSet;

/**
 * OpenAI-format function schemas offered to MiniMax: the six read-only gateway
 * tools, the escalation tool, and a {@code conclude_investigation} tool whose
 * {@code rootCause} / {@code recommendedRunbook} / {@code requiresHumanReview}
 * enums are derived from the {@link RootCausePolicyCatalog} so the model
 * cannot pick a value that the validator would reject. Gateway tool schemas
 * stay aligned with each ToolHandler input record; the gateway remains the
 * authority and rejects anything that drifts.
 */
final class MiniMaxInvestigationTools {

    private static final String CATALOG = """
        [
          {"type":"function","function":{
            "name":"query_service_metrics",
            "description":"Read one allowlisted payment SRE metric over a bounded time range.",
            "parameters":{"type":"object","properties":{
              "signal":{"type":"string","enum":[
                "PAYMENT_UNKNOWN_CURRENT","PAYMENT_ATTEMPT_OUTCOME_RATE",
                "PAYMENT_PROCESSING_P95","CHANNEL_REQUEST_ERROR_RATE","CHANNEL_REQUEST_P95"],
                "description":"PAYMENT_* signals require service payment-service; CHANNEL_* signals require service channel-simulator"},
              "service":{"type":"string","enum":["payment-service","channel-simulator"]},
              "channel":{"type":"string","description":"Channel code, e.g. CHANNEL_A"},
              "from":{"type":"string","description":"ISO-8601 instant, e.g. 2026-07-17T02:00:00Z"},
              "to":{"type":"string","description":"ISO-8601 instant, must be after from"},
              "step":{"type":"string","description":"ISO-8601 duration, e.g. PT30S; keep (to-from)/step at 240 points or fewer"}},
              "required":["signal","service","channel","from","to","step"]}}},
          {"type":"function","function":{
            "name":"search_structured_logs",
            "description":"Read projected payment events from Loki using allowlisted filters.",
            "parameters":{"type":"object","properties":{
              "service":{"type":"string","enum":["payment-service","channel-simulator","sre-control-plane"]},
              "event":{"type":"string","enum":[
                "CHANNEL_FAULT_RULE_REPLACED","CHANNEL_FINAL_STATE_PERSISTED",
                "PAYMENT_STATE_CHANGED","ALERT_INGESTED","INCIDENT_CREATED",
                "INCIDENT_ALERT_AGGREGATED","INCIDENT_TOOL_EXECUTED","INCIDENT_TOOL_FAILED",
                "INCIDENT_INVESTIGATION_COMPLETED","INCIDENT_INVESTIGATION_ESCALATED"]},
              "minimumLevel":{"type":"string","enum":["TRACE","DEBUG","INFO","WARN","ERROR","FATAL"]},
              "paymentId":{"type":"string","description":"Filter to one payment; use the representative payment"},
              "traceId":{"type":"string","description":"Optional 32-hex-character W3C trace ID filter"},
              "from":{"type":"string","description":"ISO-8601 instant"},
              "to":{"type":"string","description":"ISO-8601 instant"},
              "limit":{"type":"integer","minimum":1,"maximum":100}},
              "required":["service","minimumLevel","from","to","limit"]}}},
          {"type":"function","function":{
            "name":"get_distributed_trace",
            "description":"Read a projected distributed trace from Tempo by validated trace ID. The trace ID must come from structured log evidence.",
            "parameters":{"type":"object","properties":{
              "traceId":{"type":"string","description":"32 lowercase hex characters taken from a structured log record"},
              "from":{"type":"string","description":"ISO-8601 instant"},
              "to":{"type":"string","description":"ISO-8601 instant"}},
              "required":["traceId","from","to"]}}},
          {"type":"function","function":{
            "name":"get_payment_timeline",
            "description":"Read the ordered business state transitions of a synthetic payment.",
            "parameters":{"type":"object","properties":{
              "paymentId":{"type":"string"}},
              "required":["paymentId"]}}},
          {"type":"function","function":{
            "name":"query_channel_final_state",
            "description":"Read the synthetic channel's final state for a payment.",
            "parameters":{"type":"object","properties":{
              "paymentId":{"type":"string"}},
              "required":["paymentId"]}}},
          {"type":"function","function":{
            "name":"calculate_incident_impact",
            "description":"Count and sum UNKNOWN synthetic payments for one channel and bounded time range. This is the only authoritative source for affectedPaymentCount, totalAmount and currency.",
            "parameters":{"type":"object","properties":{
              "channel":{"type":"string"},
              "from":{"type":"string","description":"ISO-8601 instant"},
              "to":{"type":"string","description":"ISO-8601 instant, at most 24 hours after from"}},
              "required":["channel","from","to"]}}},
          {"type":"function","function":{
            "name":"escalate_to_human",
            "description":"Hand the incident to a human when evidence is insufficient, telemetry is unavailable, or the observed behaviour does not match any supported root cause.",
            "parameters":{"type":"object","properties":{
              "reason":{"type":"string","description":"Concrete, evidence-referencing reason"}},
              "required":["reason"]}}}
        ]
        """;

    private MiniMaxInvestigationTools() {
    }

    static ArrayNode catalog(
            ObjectMapper objectMapper, RootCausePolicyCatalog policies) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(policies, "policies");
        try {
            var catalog = (ArrayNode) objectMapper.readTree(CATALOG);
            catalog.add(buildConcludeTool(objectMapper, policies));
            return catalog;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("tool catalog must be valid JSON", exception);
        }
    }

    private static ObjectNode buildConcludeTool(
            ObjectMapper objectMapper, RootCausePolicyCatalog policies) {
        var rootCauses = new TreeSet<String>();
        var runbooks = new TreeSet<String>();
        var requiresReviewValues = new TreeSet<Boolean>();
        for (RootCausePolicy policy : policies.all()) {
            rootCauses.add(policy.rootCause().name());
            runbooks.addAll(policy.allowedRunbooks());
            requiresReviewValues.add(policy.requiresHumanReview());
        }
        var function = objectMapper.createObjectNode();
        function.put("name", "conclude_investigation");
        function.put("description",
                "Submit the final evidence-backed conclusion. Only allowed once "
                        + "INCIDENT_IMPACT evidence exists; every impact figure must be "
                        + "copied verbatim from it. rootCause and recommendedRunbook "
                        + "must come from the catalogued root cause policies below.");
        var parameters = function.putObject("parameters");
        parameters.put("type", "object");
        var properties = parameters.putObject("properties");
        var rootCauseProp = properties.putObject("rootCause");
        rootCauseProp.put("type", "string");
        var rootCauseEnum = rootCauseProp.putArray("enum");
        rootCauses.forEach(rootCauseEnum::add);
        properties.putObject("confidence")
                .put("type", "number")
                .put("minimum", 0)
                .put("maximum", 1);
        var evidenceIds = properties.putObject("evidenceIds");
        evidenceIds.put("type", "array");
        evidenceIds.putObject("items").put("type", "string");
        evidenceIds.put("minItems", 3);
        evidenceIds.put("description",
                "Existing evidence IDs from the investigation state, including the "
                        + "INCIDENT_IMPACT evidence");
        properties.putObject("affectedPaymentCount")
                .put("type", "integer")
                .put("minimum", 0)
                .put("description",
                        "Copy affectedPaymentCount from INCIDENT_IMPACT evidence");
        properties.putObject("affectedAmount")
                .put("type", "string")
                .put("description",
                        "Copy totalAmount from INCIDENT_IMPACT evidence as a decimal "
                                + "string, e.g. 50.00");
        properties.putObject("currency")
                .put("type", "string")
                .put("description", "ISO 4217 code copied from INCIDENT_IMPACT evidence");
        var runbookProp = properties.putObject("recommendedRunbook");
        runbookProp.put("type", "string");
        var runbookEnum = runbookProp.putArray("enum");
        if (runbooks.isEmpty()) {
            runbookProp.put("description",
                    "No runbook is allowed for the supported root causes; this "
                            + "policy is advisory only");
        } else {
            runbooks.forEach(runbookEnum::add);
        }
        var humanReview = properties.putObject("requiresHumanReview");
        humanReview.put("type", "boolean");
        var humanReviewEnum = humanReview.putArray("enum");
        requiresReviewValues.forEach(humanReviewEnum::add);
        var required = parameters.putArray("required");
        required.add("rootCause");
        required.add("confidence");
        required.add("evidenceIds");
        required.add("affectedPaymentCount");
        required.add("affectedAmount");
        required.add("currency");
        required.add("recommendedRunbook");
        required.add("requiresHumanReview");

        var tool = objectMapper.createObjectNode();
        tool.put("type", "function");
        tool.set("function", function);
        return tool;
    }
}