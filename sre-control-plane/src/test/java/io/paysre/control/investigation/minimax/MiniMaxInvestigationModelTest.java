package io.paysre.control.investigation.minimax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.paysre.contracts.Money;
import io.paysre.control.evidence.Evidence;
import io.paysre.control.incident.Incident;
import io.paysre.control.investigation.InvestigationContext;
import io.paysre.control.investigation.InvestigationDecision;
import io.paysre.control.investigation.InvestigationSeed;
import io.paysre.control.investigation.RootCauseCode;
import io.paysre.control.tools.ToolResult;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class MiniMaxInvestigationModelTest {

    private static final Instant NOW = Instant.parse("2026-07-17T02:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void passesGatewayToolCallsThroughUnchanged() {
        var chat = new RecordingChatClient(turnWithToolCall(
                "query_service_metrics",
                """
                {"signal":"PAYMENT_UNKNOWN_CURRENT","service":"payment-service",
                 "channel":"CHANNEL_A","from":"2026-07-17T01:00:00Z",
                 "to":"2026-07-17T02:00:00Z","step":"PT30S"}
                """));
        var model = new MiniMaxInvestigationModel(chat, MAPPER);

        var decision = model.decide(context());

        assertThat(decision).isInstanceOfSatisfying(
                InvestigationDecision.CallTool.class,
                callTool -> {
                    assertThat(callTool.toolName()).isEqualTo("query_service_metrics");
                    assertThat(callTool.arguments().path("signal").asText())
                            .isEqualTo("PAYMENT_UNKNOWN_CURRENT");
                    assertThat(callTool.arguments().path("step").asText())
                            .isEqualTo("PT30S");
                });
    }

    @Test
    void passesUnknownToolNamesThroughForGatewayAudit() {
        var chat = new RecordingChatClient(turnWithToolCall(
                "drop_database", "{\"target\":\"payments\"}"));
        var model = new MiniMaxInvestigationModel(chat, MAPPER);

        var decision = model.decide(context());

        assertThat(decision).isInstanceOfSatisfying(
                InvestigationDecision.CallTool.class,
                callTool -> assertThat(callTool.toolName()).isEqualTo("drop_database"));
    }

    @Test
    void mapsConcludeIntoConclusionOwnedByCurrentIncident() {
        var chat = new RecordingChatClient(turnWithToolCall(
                "conclude_investigation",
                """
                {"incidentId":"INC-SPOOFED",
                 "rootCause":"CHANNEL_TIMEOUT_RESPONSE_LOST",
                 "confidence":0.95,
                 "evidenceIds":["EVD-1","EVD-2","EVD-3"],
                 "affectedPaymentCount":5,
                 "affectedAmount":"50.00",
                 "currency":"CNY",
                 "recommendedRunbook":"query-and-sync-unknown-payments",
                 "requiresHumanReview":true}
                """));
        var model = new MiniMaxInvestigationModel(chat, MAPPER);

        var decision = model.decide(context());

        assertThat(decision).isInstanceOfSatisfying(
                InvestigationDecision.Conclude.class,
                conclude -> {
                    var conclusion = conclude.conclusion();
                    assertThat(conclusion.incidentId()).isEqualTo("INC-1");
                    assertThat(conclusion.rootCause())
                            .isEqualTo(RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST);
                    assertThat(conclusion.confidence())
                            .isEqualByComparingTo(new BigDecimal("0.95"));
                    assertThat(conclusion.evidenceIds())
                            .containsExactly("EVD-1", "EVD-2", "EVD-3");
                    assertThat(conclusion.affectedPaymentCount()).isEqualTo(5);
                    assertThat(conclusion.affectedAmount()).isEqualTo(new Money(
                            new BigDecimal("50.00"), Currency.getInstance("CNY")));
                    assertThat(conclusion.recommendedRunbook())
                            .isEqualTo("query-and-sync-unknown-payments");
                    assertThat(conclusion.requiresHumanReview()).isTrue();
                });
    }

    @Test
    void mapsEscalateToHuman() {
        var chat = new RecordingChatClient(turnWithToolCall(
                "escalate_to_human", "{\"reason\":\"metrics backend is unavailable\"}"));
        var model = new MiniMaxInvestigationModel(chat, MAPPER);

        var decision = model.decide(context());

        assertThat(decision).isInstanceOfSatisfying(
                InvestigationDecision.Escalate.class,
                escalate -> assertThat(escalate.reason())
                        .isEqualTo("metrics backend is unavailable"));
    }

    @Test
    void failsClosedWhenModelReturnsNoToolCall() {
        var chat = new RecordingChatClient(
                new MiniMaxAssistantTurn("I believe the channel timed out.", List.of()));
        var model = new MiniMaxInvestigationModel(chat, MAPPER);

        assertThatThrownBy(() -> model.decide(context()))
                .isInstanceOf(MiniMaxModelException.class)
                .hasMessageContaining("MINIMAX_NO_TOOL_DECISION");
    }

    @Test
    void failsClosedOnUnsupportedRootCause() {
        var chat = new RecordingChatClient(turnWithToolCall(
                "conclude_investigation",
                """
                {"rootCause":"COSMIC_RAYS","confidence":0.9,
                 "evidenceIds":["EVD-1","EVD-2","EVD-3"],
                 "affectedPaymentCount":5,"affectedAmount":"50.00","currency":"CNY",
                 "recommendedRunbook":"query-and-sync-unknown-payments",
                 "requiresHumanReview":true}
                """));
        var model = new MiniMaxInvestigationModel(chat, MAPPER);

        assertThatThrownBy(() -> model.decide(context()))
                .isInstanceOf(MiniMaxModelException.class)
                .hasMessageContaining("MINIMAX_MALFORMED_CONCLUSION");
    }

    @Test
    void failsClosedOnMalformedConclusionAmount() {
        var chat = new RecordingChatClient(turnWithToolCall(
                "conclude_investigation",
                """
                {"rootCause":"CHANNEL_TIMEOUT_RESPONSE_LOST","confidence":0.9,
                 "evidenceIds":["EVD-1","EVD-2","EVD-3"],
                 "affectedPaymentCount":5,"affectedAmount":"fifty","currency":"CNY",
                 "recommendedRunbook":"query-and-sync-unknown-payments",
                 "requiresHumanReview":true}
                """));
        var model = new MiniMaxInvestigationModel(chat, MAPPER);

        assertThatThrownBy(() -> model.decide(context()))
                .isInstanceOf(MiniMaxModelException.class)
                .hasMessageContaining("MINIMAX_MALFORMED_CONCLUSION");
    }

    @Test
    void sendsInvestigationStateAndValidationFeedbackToModel() {
        var chat = new RecordingChatClient(turnWithToolCall(
                "escalate_to_human", "{\"reason\":\"stop\"}"));
        var model = new MiniMaxInvestigationModel(chat, MAPPER);

        model.decide(context());

        assertThat(chat.userPayload)
                .contains("INC-1")
                .contains("PAY-1")
                .contains("CHANNEL_A")
                .contains("EVD-1")
                .contains("STRUCTURED_LOGS")
                .contains("TOOL_TIMEOUT")
                .contains("conclusion impact does not match incident impact evidence");
        assertThat(chat.systemPrompt)
                .contains("query-and-sync-unknown-payments")
                .contains("CHANNEL_TIMEOUT_RESPONSE_LOST")
                .contains("escalate_to_human");
        assertThat(toolNames(chat.tools)).containsExactlyInAnyOrder(
                "query_service_metrics",
                "search_structured_logs",
                "get_distributed_trace",
                "get_payment_timeline",
                "query_channel_final_state",
                "calculate_incident_impact",
                "conclude_investigation",
                "escalate_to_human");
    }

    @Test
    void truncatesOversizedEvidenceContentInsteadOfInliningIt() {
        var hugeText = "x".repeat(40_000);
        var evidence = new Evidence(
                "EVD-BIG",
                "INC-1",
                "STRUCTURED_LOGS",
                "search_structured_logs",
                1,
                MAPPER.createObjectNode().put("blob", hugeText),
                "hash",
                NOW);
        var chat = new RecordingChatClient(turnWithToolCall(
                "escalate_to_human", "{\"reason\":\"stop\"}"));
        var model = new MiniMaxInvestigationModel(chat, MAPPER);

        model.decide(context(List.of(evidence), List.of(), null));

        assertThat(chat.userPayload).contains("EVD-BIG").contains("contentTruncated");
        assertThat(chat.userPayload).doesNotContain(hugeText);
    }

    private InvestigationContext context() {
        var evidence = new Evidence(
                "EVD-1",
                "INC-1",
                "STRUCTURED_LOGS",
                "search_structured_logs",
                1,
                MAPPER.createObjectNode().put("records", "..."),
                "hash",
                NOW);
        var failedCall = new ToolResult(
                "get_distributed_trace",
                false,
                List.of(),
                Duration.ofSeconds(4),
                "TOOL_TIMEOUT");
        return context(
                List.of(evidence),
                List.of(failedCall),
                "conclusion impact does not match incident impact evidence");
    }

    private InvestigationContext context(
            List<Evidence> evidence,
            List<ToolResult> toolResults,
            String lastValidationError) {
        return new InvestigationContext(
                Incident.detected("INC-1", "payment-unknown:CHANNEL_A", NOW),
                new InvestigationSeed(
                        "PAY-1", "CHANNEL_A", NOW.minus(Duration.ofMinutes(30)), NOW),
                evidence,
                toolResults,
                lastValidationError);
    }

    private static MiniMaxAssistantTurn turnWithToolCall(String name, String argumentsJson) {
        try {
            return new MiniMaxAssistantTurn("", List.of(new MiniMaxToolCall(
                    "call_1", name, MAPPER.readTree(argumentsJson))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static List<String> toolNames(ArrayNode tools) {
        return java.util.stream.StreamSupport.stream(tools.spliterator(), false)
                .map(tool -> tool.path("function").path("name").asText())
                .toList();
    }

    private static final class RecordingChatClient implements MiniMaxChatClient {

        private final MiniMaxAssistantTurn next;
        private String systemPrompt;
        private String userPayload;
        private ArrayNode tools;

        private RecordingChatClient(MiniMaxAssistantTurn next) {
            this.next = next;
        }

        @Override
        public MiniMaxAssistantTurn complete(
                String systemPrompt, String userPayload, ArrayNode tools) {
            this.systemPrompt = systemPrompt;
            this.userPayload = userPayload;
            this.tools = tools;
            return next;
        }
    }
}
