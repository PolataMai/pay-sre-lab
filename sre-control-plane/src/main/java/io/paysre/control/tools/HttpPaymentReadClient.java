package io.paysre.control.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.web.client.RestClient;

public final class HttpPaymentReadClient implements PaymentReadClient {

    private static final TypeReference<List<UnknownPaymentRecord>> UNKNOWN_LIST = new TypeReference<>() {
    };

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpPaymentReadClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode timeline(String paymentId) {
        var body = Objects.requireNonNull(restClient.get()
                .uri("/api/payments/{paymentId}/timeline", paymentId)
                .retrieve()
                .body(String.class));
        return readTree(body);
    }

    @Override
    public List<UnknownPaymentRecord> unknownPayments(Instant from, Instant to, int size) {
        var body = Objects.requireNonNull(restClient.get()
                .uri(builder -> builder
                        .path("/api/payments")
                        .queryParam("status", "UNKNOWN")
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .queryParam("size", size)
                        .build())
                .retrieve()
                .body(String.class));
        try {
            return List.copyOf(objectMapper.readValue(body, UNKNOWN_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payment service returned invalid JSON", exception);
        }
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Payment service returned invalid JSON", exception);
        }
    }
}
