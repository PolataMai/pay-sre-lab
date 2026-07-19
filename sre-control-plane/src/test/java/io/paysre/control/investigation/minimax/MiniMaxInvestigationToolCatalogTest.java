package io.paysre.control.investigation.minimax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paysre.control.investigation.InvestigationContext;
import io.paysre.control.investigation.InvestigationSeed;
import io.paysre.control.investigation.RootCauseCode;
import io.paysre.control.investigation.RootCausePolicy;
import io.paysre.control.investigation.RootCausePolicyCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies the MiniMax tool schema and system prompt are derived from
 * the {@link RootCausePolicyCatalog}. Adding a fault family must only
 * require registering a new policy; these tests pin the contract.
 */
class MiniMaxInvestigationToolCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-17T02:00:00Z");

    @Test
    void concludeInvetigationSchemaEnumsFollowTheCatalog() {
        var catalog = RootCausePolicyCatalog.builder()
                .register(new AdvisoryPolicy(
                        RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST))
                .build();

        ArrayNode tools = MiniMaxInvestigationTools.catalog(MAPPER, catalog);
        var conclude = findFunction(tools, "conclude_investigation");

        var rootCauseEnum = conclude.path("parameters").path("properties")
                .path("rootCause").path("enum");

        assertThat(toStringList(rootCauseEnum))
                .containsExactly("CHANNEL_TIMEOUT_RESPONSE_LOST");
        // Advisory-only policy: no runbooks at all, the schema's
        // recommendedRunbook has no enum and instead describes the
        // advisory contract in text.
        var runbookNode = conclude.path("parameters").path("properties")
                .path("recommendedRunbook");
        var runbookEnum = runbookNode.path("enum");
        assertThat(runbookEnum.isMissingNode()
                || (runbookEnum.isArray() && runbookEnum.isEmpty())).isTrue();
        assertThat(runbookNode.path("description").asText())
                .contains("advisory");
    }

    @Test
    void catalogExposesTheStaticGatewayToolsAndEscalation() {
        ArrayNode tools = MiniMaxInvestigationTools.catalog(
                MAPPER, RootCausePolicyCatalog.defaults());

        assertThat(toolNames(tools)).containsExactly(
                "query_service_metrics",
                "search_structured_logs",
                "get_distributed_trace",
                "get_payment_timeline",
                "query_channel_final_state",
                "calculate_incident_impact",
                "escalate_to_human",
                "conclude_investigation");
    }

    @Test
    void catalogDefaultsEnumerateEveryRegisteredRootCause() {
        ArrayNode tools = MiniMaxInvestigationTools.catalog(
                MAPPER, RootCausePolicyCatalog.defaults());
        var conclude = findFunction(tools, "conclude_investigation");
        var rootCauseEnum = conclude.path("parameters").path("properties")
                .path("rootCause").path("enum");
        var runbookEnum = conclude.path("parameters").path("properties")
                .path("recommendedRunbook").path("enum");
        var reviewEnum = conclude.path("parameters").path("properties")
                .path("requiresHumanReview").path("enum");

        assertThat(toStringList(rootCauseEnum))
                .containsExactly(
                        "CHANNEL_CODE_MAPPING_ERROR",
                        "CHANNEL_DECLINE_SPIKE",
                        "CHANNEL_TIMEOUT_RESPONSE_LOST");
        assertThat(toStringList(runbookEnum))
                .containsExactly("query-and-sync-unknown-payments");
        assertThat(toBooleanList(reviewEnum)).containsExactly(true);
    }

    @Test
    void systemPromptIncludesCataloguedPolicySummary() {
        var model = new MiniMaxInvestigationModel(
                new RecordingChatClient(), MAPPER);
        var prompt = model.systemPrompt();

        assertThat(prompt).contains("Supported root cause policies:");
        assertThat(prompt).contains("CHANNEL_TIMEOUT_RESPONSE_LOST");
        assertThat(prompt).contains("CHANNEL_DECLINE_SPIKE");
        assertThat(prompt).contains("query-and-sync-unknown-payments");
    }

    @Test
    void modelRejectsConclusionRunbookOutsidePolicyAllowList() {
        var chat = new RecordingChatClient(turnWithConclude(
                "CHANNEL_TIMEOUT_RESPONSE_LOST",
                "0.95",
                List.of("E1", "E2", "E3"),
                "1",
                "10.00",
                "CNY",
                "totally-not-allowed",
                true));
        var model = new MiniMaxInvestigationModel(chat, MAPPER);

        assertThatThrownBy(() -> model.decide(context()))
                .isInstanceOf(MiniMaxModelException.class)
                .hasMessageContaining("MINIMAX_DISALLOWED_RUNBOOK");
    }

    @Test
    void modelRejectsHumanReviewMismatchWithPolicy() {
        var chat = new RecordingChatClient(turnWithConclude(
                "CHANNEL_TIMEOUT_RESPONSE_LOST",
                "0.95",
                List.of("E1", "E2", "E3"),
                "1",
                "10.00",
                "CNY",
                "query-and-sync-unknown-payments",
                false));
        var model = new MiniMaxInvestigationModel(chat, MAPPER);

        assertThatThrownBy(() -> model.decide(context()))
                .isInstanceOf(MiniMaxModelException.class)
                .hasMessageContaining("MINIMAX_HUMAN_REVIEW_MISMATCH");
    }

    private static ObjectNode findFunction(ArrayNode catalog, String name) {
        for (var tool : catalog) {
            if (name.equals(tool.path("function").path("name").asText())) {
                return (ObjectNode) tool.path("function");
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    private static List<String> toolNames(ArrayNode catalog) {
        var names = new ArrayList<String>();
        catalog.forEach(tool -> names.add(tool.path("function").path("name").asText()));
        return names;
    }

    private static List<String> toStringList(com.fasterxml.jackson.databind.JsonNode node) {
        var values = new ArrayList<String>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static List<Boolean> toBooleanList(com.fasterxml.jackson.databind.JsonNode node) {
        var values = new ArrayList<Boolean>();
        node.forEach(item -> values.add(item.asBoolean()));
        return values;
    }

    private static MiniMaxAssistantTurn turnWithConclude(
            String rootCause, String confidence, List<String> evidenceIds,
            String affectedPaymentCount, String affectedAmount, String currency,
            String runbook, boolean requiresHumanReview) {
        var args = JsonNodeFactory.instance.objectNode();
        args.put("rootCause", rootCause);
        args.put("confidence", confidence);
        var ids = args.putArray("evidenceIds");
        evidenceIds.forEach(ids::add);
        args.put("affectedPaymentCount", affectedPaymentCount);
        args.put("affectedAmount", affectedAmount);
        args.put("currency", currency);
        args.put("recommendedRunbook", runbook);
        args.put("requiresHumanReview", requiresHumanReview);
        return new MiniMaxAssistantTurn("", List.of(
                new MiniMaxToolCall("call_1", "conclude_investigation", args)));
    }

    private static InvestigationContext context() {
        var incident = io.paysre.control.incident.Incident.detected(
                "INC-1", "payment-unknown:CHANNEL_A", NOW);
        var seed = new InvestigationSeed(
                "PAY-1", "CHANNEL_A",
                NOW.minus(java.time.Duration.ofMinutes(30)), NOW);
        return new InvestigationContext(
                incident, seed, List.of(), List.of(), null);
    }

    /** Advisory-only policy: no runbooks, no human review required. */
    private record AdvisoryPolicy(RootCauseCode rootCause) implements RootCausePolicy {
        @Override
        public Set<String> allowedRunbooks() {
            return Set.of();
        }

        @Override
        public boolean requiresHumanReview() {
            return false;
        }

        @Override
        public void validateConclusion(
                RootCausePolicy.IncidentContext context,
                io.paysre.control.investigation.InvestigationConclusion conclusion,
                List<io.paysre.control.evidence.Evidence> referenced) {
            // no-op
        }
    }

    /** Test double returning the canned turn supplied at construction. */
    private record RecordingChatClient(MiniMaxAssistantTurn next) implements MiniMaxChatClient {
        RecordingChatClient() {
            this(new MiniMaxAssistantTurn("", List.of()));
        }

        @Override
        public MiniMaxAssistantTurn complete(
                String systemPrompt, String userPayload, ArrayNode tools) {
            return next;
        }
    }
}