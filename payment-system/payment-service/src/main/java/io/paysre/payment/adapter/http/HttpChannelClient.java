package io.paysre.payment.adapter.http;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelPaymentResponse;
import io.paysre.payment.application.ChannelCallTimeoutException;
import io.paysre.payment.application.ChannelClient;
import io.paysre.payment.application.ChannelStateMissingException;
import io.paysre.payment.application.ChannelStateQuery;
import java.util.Objects;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public final class HttpChannelClient implements ChannelClient, ChannelStateQuery {

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

    @Override
    public ChannelPaymentResponse query(String paymentId) {
        try {
            var response = restClient.get()
                    .uri("/api/channel/payments/{paymentId}", paymentId)
                    .retrieve()
                    .body(ChannelPaymentResponse.class);
            return Objects.requireNonNull(response, "channel response body");
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new ChannelStateMissingException(paymentId);
            }
            if (exception.getStatusCode().value() == 504) {
                throw new ChannelCallTimeoutException(paymentId);
            }
            throw exception;
        } catch (ResourceAccessException exception) {
            throw new ChannelCallTimeoutException(paymentId);
        }
    }
}
