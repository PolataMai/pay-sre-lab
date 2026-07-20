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

    @Test
    void queriesTheChannelFinalStateByPaymentId() {
        var builder = RestClient.builder().baseUrl("http://channel.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new HttpChannelClient(builder.build());
        server.expect(once(), requestTo("http://channel.test/api/channel/payments/P10001"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withSuccess("""
                                {"requestId":"REQ-1","paymentId":"P10001","result":"SUCCESS",
                                 "channelCode":"00","channelTime":"2026-07-17T03:00:00Z"}
                                """, org.springframework.http.MediaType.APPLICATION_JSON));

        var response = client.query("P10001");

        org.assertj.core.api.Assertions.assertThat(response.result())
                .isEqualTo(io.paysre.contracts.ChannelResult.SUCCESS);
        org.assertj.core.api.Assertions.assertThat(response.channelCode()).isEqualTo("00");
        server.verify();
    }

    @Test
    void mapsAMissingChannelRecordToTheStateMissingException() {
        var builder = RestClient.builder().baseUrl("http://channel.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new HttpChannelClient(builder.build());
        server.expect(once(), requestTo("http://channel.test/api/channel/payments/P404"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.query("P404"))
                .isInstanceOf(io.paysre.payment.application.ChannelStateMissingException.class)
                .hasMessage("channel has no final state for payment P404");
    }

    @Test
    void mapsAQueryGatewayTimeoutToTheApplicationTimeoutException() {
        var builder = RestClient.builder().baseUrl("http://channel.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new HttpChannelClient(builder.build());
        server.expect(once(), requestTo("http://channel.test/api/channel/payments/P504"))
                .andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));

        assertThatThrownBy(() -> client.query("P504"))
                .isInstanceOf(ChannelCallTimeoutException.class)
                .hasMessage("channel timeout for payment P504");
    }

    private ChannelPaymentRequest request() {
        return new ChannelPaymentRequest(
                "REQ-1",
                "P10001",
                "CHANNEL_A",
                new Money(new BigDecimal("10.00"), Currency.getInstance("CNY")));
    }
}
