package io.paysre.control.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.web.client.RestClient;

public final class HttpChannelReadClient implements ChannelReadClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpChannelReadClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode finalState(String paymentId) {
        var body = Objects.requireNonNull(restClient.get()
                .uri("/api/channel/payments/{paymentId}", paymentId)
                .retrieve()
                .body(String.class));
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Channel simulator returned invalid JSON", exception);
        }
    }
}
