package io.paysre.control.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpReadClientsTest {

    @Test
    void readsPaymentTimelineAndUnknownPaymentProjection() {
        var builder = RestClient.builder().baseUrl("http://payment.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new HttpPaymentReadClient(builder.build(), mapper());
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/api/payments/P10001/timeline"))
                .andRespond(withSuccess(
                        "[{\"reasonCode\":\"CHANNEL_TIMEOUT\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/api/payments");
                    assertThat(request.getURI().getQuery()).contains("status=UNKNOWN");
                })
                .andRespond(withSuccess("""
                        [{
                          "paymentId":"P10001",
                          "orderId":"O10001",
                          "amount":10.00,
                          "currency":"CNY",
                          "channel":"CHANNEL_A",
                          "status":"UNKNOWN",
                          "updatedAt":"1970-01-01T00:00:01Z"
                        }]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.timeline("P10001").get(0).path("reasonCode").asText())
                .isEqualTo("CHANNEL_TIMEOUT");
        assertThat(client.unknownPayments(
                        Instant.EPOCH, Instant.EPOCH.plusSeconds(60), 200))
                .singleElement()
                .satisfies(payment -> assertThat(payment.currency()).isEqualTo("CNY"));
        server.verify();
    }

    @Test
    void readsChannelFinalState() {
        var builder = RestClient.builder().baseUrl("http://channel.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new HttpChannelReadClient(builder.build(), mapper());
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/api/channel/payments/P10001"))
                .andRespond(withSuccess(
                        "{\"paymentId\":\"P10001\",\"result\":\"SUCCESS\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.finalState("P10001").path("result").asText())
                .isEqualTo("SUCCESS");
        server.verify();
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
