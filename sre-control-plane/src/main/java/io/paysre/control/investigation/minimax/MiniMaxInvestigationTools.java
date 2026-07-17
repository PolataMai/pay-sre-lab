package io.paysre.control.investigation.minimax;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * OpenAI-format function schemas offered to MiniMax: the six read-only gateway
 * tools plus the two decision tools. Gateway tool schemas must stay aligned
 * with each ToolHandler input record; the gateway remains the authority and
 * rejects anything that drifts.
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
            "name":"conclude_investigation",
            "description":"Submit the final evidence-backed conclusion. Only allowed once INCIDENT_IMPACT evidence exists; every impact figure must be copied verbatim from it.",
            "parameters":{"type":"object","properties":{
              "rootCause":{"type":"string","enum":["CHANNEL_TIMEOUT_RESPONSE_LOST"]},
              "confidence":{"type":"number","minimum":0,"maximum":1},
              "evidenceIds":{"type":"array","items":{"type":"string"},"minItems":3,
                "description":"Existing evidence IDs from the investigation state, including the INCIDENT_IMPACT evidence"},
              "affectedPaymentCount":{"type":"integer","minimum":0,
                "description":"Copy affectedPaymentCount from INCIDENT_IMPACT evidence"},
              "affectedAmount":{"type":"string",
                "description":"Copy totalAmount from INCIDENT_IMPACT evidence as a decimal string, e.g. 50.00"},
              "currency":{"type":"string","description":"ISO 4217 code copied from INCIDENT_IMPACT evidence"},
              "recommendedRunbook":{"type":"string","enum":["query-and-sync-unknown-payments"]},
              "requiresHumanReview":{"type":"boolean","enum":[true]}},
              "required":["rootCause","confidence","evidenceIds","affectedPaymentCount",
                "affectedAmount","currency","recommendedRunbook","requiresHumanReview"]}}},
          {"type":"function","function":{
            "name":"escalate_to_human",
            "description":"Hand the incident to a human when evidence is insufficient, telemetry is unavailable, or the observed behaviour does not match the supported root cause.",
            "parameters":{"type":"object","properties":{
              "reason":{"type":"string","description":"Concrete, evidence-referencing reason"}},
              "required":["reason"]}}}
        ]
        """;

    private MiniMaxInvestigationTools() {
    }

    static ArrayNode catalog(ObjectMapper objectMapper) {
        try {
            return (ArrayNode) objectMapper.readTree(CATALOG);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("tool catalog must be valid JSON", exception);
        }
    }
}
