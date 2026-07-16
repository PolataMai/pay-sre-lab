package io.paysre.payment.adapter.http;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelPaymentResponse;
import io.paysre.payment.application.ChannelCallTimeoutException;
import io.paysre.payment.application.ChannelClient;
import java.util.Objects;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public final class HttpChannelClient implements ChannelClient {

    private final RestClient restClient;

    public HttpChannelClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ChannelPaymentResponse pay(ChannelPaymentRequest request) {
        try {
            var response = restClient.post()
                    .uri("/api/channel/payments")
                    .body(request)
                    .retrieve()
                    .body(ChannelPaymentResponse.class);
            return Objects.requireNonNull(response, "channel response body");
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 504) {
                throw new ChannelCallTimeoutException(request.paymentId());
            }
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new ChannelCallTimeoutException(request.paymentId());
        }
    }
}
