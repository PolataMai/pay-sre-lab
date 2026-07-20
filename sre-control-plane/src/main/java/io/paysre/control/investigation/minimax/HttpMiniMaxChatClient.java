package io.paysre.control.investigation.minimax;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Bounded client for the MiniMax OpenAI-compatible chat completions API.
 * MiniMax may report failures as HTTP 200 with a non-zero base_resp status
 * code, so both layers are checked before any tool call is trusted.
 */
public final class HttpMiniMaxChatClient implements MiniMaxChatClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final double temperature;
    private final int maxCompletionTokens;

    public HttpMiniMaxChatClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            String model,
            double temperature,
            int maxCompletionTokens) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.model = Objects.requireNonNull(model, "model");
        this.temperature = temperature;
        this.maxCompletionTokens = maxCompletionTokens;
    }

    @Override
    public MiniMaxAssistantTurn complete(
            String systemPrompt, String userPayload, ArrayNode tools) {
        var body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_completion_tokens", maxCompletionTokens);
        body.put("tool_choice", "auto");
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPayload);
        body.set("tools", tools.deepCopy());

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new MiniMaxModelException(
                    "MINIMAX_REQUEST_SERIALIZATION_FAILED",
                    "the chat request could not be serialized");
        }
        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException exception) {
            throw new MiniMaxModelException(
                    "MINIMAX_HTTP_" + exception.getStatusCode().value(),
                    "the minimax endpoint rejected the request");
        } catch (ResourceAccessException exception) {
            throw new MiniMaxModelException(
                    "MINIMAX_UNREACHABLE",
                    "the minimax endpoint is unreachable: " + exception.getMessage());
        }
        if (responseBody == null || responseBody.isBlank()) {
            throw new MiniMaxModelException(
                    "MINIMAX_EMPTY_RESPONSE", "the response body is empty");
        }
        JsonNode response;
        try {
            response = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new MiniMaxModelException(
                    "MINIMAX_MALFORMED_RESPONSE", "the response body is not valid JSON");
        }
        var baseRespCode = response.path("base_resp").path("status_code").asInt(0);
        if (baseRespCode != 0) {
            throw new MiniMaxModelException(
                    "MINIMAX_BASE_RESP_" + baseRespCode,
                    response.path("base_resp").path("status_msg").asText("request failed"));
        }
        var message = response.path("choices").path(0).path("message");
        if (message.isMissingNode()) {
            throw new MiniMaxModelException(
                    "MINIMAX_EMPTY_RESPONSE", "the response contains no choices");
        }
        return new MiniMaxAssistantTurn(
                message.path("content").asText(""),
                toolCalls(message.path("tool_calls")));
    }

    private List<MiniMaxToolCall> toolCalls(JsonNode toolCallsNode) {
        var calls = new ArrayList<MiniMaxToolCall>();
        if (!toolCallsNode.isArray()) {
            return calls;
        }
        for (JsonNode call : toolCallsNode) {
            var function = call.path("function");
            calls.add(new MiniMaxToolCall(
                    call.path("id").asText(""),
                    function.path("name").asText(""),
                    parseArguments(function.path("arguments").asText(""))));
        }
        return calls;
    }

    private JsonNode parseArguments(String argumentsJson) {
        if (argumentsJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(argumentsJson);
        } catch (JsonProcessingException exception) {
            throw new MiniMaxModelException(
                    "MINIMAX_MALFORMED_TOOL_ARGUMENTS",
                    "tool call arguments are not valid JSON");
        }
        if (!parsed.isObject()) {
            throw new MiniMaxModelException(
                    "MINIMAX_MALFORMED_TOOL_ARGUMENTS",
                    "tool call arguments must be a JSON object");
        }
        return parsed;
    }
}
