package io.paysre.control.remediation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpPaymentWriteClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void postsTheStateSyncAndParsesTheOutcome() {
        var fixture = fixture();
        fixture.server.expect(requestTo("http://payment.test/api/payments/P1/state-sync"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"paymentId":"P1","previousStatus":"UNKNOWN",
                         "currentStatus":"SUCCESS","channelResult":"SUCCESS",
                         "outcome":"SYNCED"}
                        """, MediaType.APPLICATION_JSON));

        var sync = fixture.client.sync("P1");

        assertThat(sync.outcome()).isEqualTo("SYNCED");
        assertThat(sync.currentStatus()).isEqualTo("SUCCESS");
        fixture.server.verify();
    }

    @Test
    void mapsHttpFailuresToTypedBackendErrors() {
        var fixture = fixture();
        fixture.server.expect(requestTo("http://payment.test/api/payments/P404/state-sync"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> fixture.client.sync("P404"))
                .isInstanceOf(RemediationBackendException.class)
                .hasMessageContaining("PAYMENT_SYNC_HTTP_404");
    }

    @Test
    void rejectsResponsesWithUnknownOutcomes() {
        var fixture = fixture();
        fixture.server.expect(requestTo("http://payment.test/api/payments/P1/state-sync"))
                .andRespond(withSuccess(
                        "{\"paymentId\":\"P1\",\"outcome\":\"SURPRISE\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client.sync("P1"))
                .isInstanceOf(RemediationBackendException.class)
                .hasMessageContaining("PAYMENT_SYNC_MALFORMED");
    }

    private Fixture fixture() {
        var builder = RestClient.builder().baseUrl("http://payment.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(new HttpPaymentWriteClient(builder.build(), MAPPER), server);
    }

    private record Fixture(HttpPaymentWriteClient client, MockRestServiceServer server) {
    }
}
