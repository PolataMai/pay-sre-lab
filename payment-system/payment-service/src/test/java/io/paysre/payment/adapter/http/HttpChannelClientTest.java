package io.paysre.payment.adapter.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.Money;
import io.paysre.payment.application.ChannelCallTimeoutException;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpChannelClientTest {

    @Test
    void mapsGatewayTimeoutToTheApplicationTimeoutException() {
        var builder = RestClient.builder().baseUrl("http://channel.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new HttpChannelClient(builder.build());
        server.expect(once(), requestTo("http://channel.test/api/channel/payments"))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        assertThatThrownBy(() -> client.pay(request()))
                .isInstanceOf(ChannelCallTimeoutException.class)
                .hasMessage("channel timeout for payment P10001");
        server.verify();
    }

    private ChannelPaymentRequest request() {
        return new ChannelPaymentRequest(
                "REQ-1",
                "P10001",
                "CHANNEL_A",
                new Money(new BigDecimal("10.00"), Currency.getInstance("CNY")));
    }
}
