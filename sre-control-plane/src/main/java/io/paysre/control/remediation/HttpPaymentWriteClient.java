package io.paysre.control.remediation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Set;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public final class HttpPaymentWriteClient implements PaymentWriteClient {

    private static final Set<String> OUTCOMES =
            Set.of("SYNCED", "STILL_UNKNOWN", "NOT_UNKNOWN");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpPaymentWriteClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public PaymentStateSync sync(String paymentId) {
        String body;
        try {
            body = restClient.post()
                    .uri("/api/payments/{paymentId}/state-sync", paymentId)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException exception) {
            throw new RemediationBackendException(
                    "PAYMENT_SYNC_HTTP_" + exception.getStatusCode().value(),
                    "payment service rejected the state sync for " + paymentId);
        } catch (ResourceAccessException exception) {
            throw new RemediationBackendException(
                    "PAYMENT_SERVICE_UNREACHABLE",
                    "payment service is unreachable: " + exception.getMessage());
        }
        if (body == null || body.isBlank()) {
            throw new RemediationBackendException(
                    "PAYMENT_SYNC_EMPTY_RESPONSE", "state sync returned no body");
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new RemediationBackendException(
                    "PAYMENT_SYNC_MALFORMED", "state sync response is not valid JSON");
        }
        var outcome = parsed.path("outcome").asText("");
        if (!OUTCOMES.contains(outcome)) {
            throw new RemediationBackendException(
                    "PAYMENT_SYNC_MALFORMED", "unsupported sync outcome: " + outcome);
        }
        return new PaymentStateSync(
                parsed.path("paymentId").asText(paymentId),
                parsed.path("previousStatus").asText(""),
                parsed.path("currentStatus").asText(""),
                parsed.path("channelResult").isTextual()
                        ? parsed.path("channelResult").asText()
                        : null,
                outcome);
    }
}
