package io.paysre.control.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpLokiReadClientTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00.123456789Z");

    @Test
    void buildsBoundedNativeOtlpQueryAndProjectsOnlySafeFields() {
        var fixture = fixture();
        fixture.server.expect(request -> {
                    assertThat(request.getURI().getPath())
                            .isEqualTo("/loki/api/v1/query_range");
                    assertThat(request.getURI().getRawQuery()).doesNotContain("{", " ");
                    var decoded = URLDecoder.decode(
                            request.getURI().getRawQuery(), StandardCharsets.UTF_8);
                    assertThat(decoded)
                            .contains("query={service_name=\"payment-service\"}")
                            .contains("severity_text=~\"INFO|WARN|ERROR|FATAL\"")
                            .contains("event=\"PAYMENT_STATE_CHANGED\"")
                            .contains("paymentId=\"PAY-\\\"42\\\\A\"")
                            .contains("start=1784203140123456789")
                            .contains("end=1784203200123456789")
                            .contains("limit=200")
                            .contains("direction=backward");
                })
                .andRespond(withSuccess("""
                        {
                          "status":"success",
                          "data":{"resultType":"streams","result":[{
                            "stream":{"service_name":"payment-service"},
                            "values":[[
                              "1784203199123456789",
                              "Payment state changed",
                              {
                                "severity_text":"WARN",
                                "traceId":"5b8efff798038103d269b633813fc700",
                                "spanId":"eee19b7ec3c1b100",
                                "event":"PAYMENT_STATE_CHANGED",
                                "paymentId":"PAY-42",
                                "reasonCode":"CHANNEL_TIMEOUT",
                                "authorization":"must-not-leak"
                              }
                            ]]
                          }]}
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.client.search(new StructuredLogSearch(
                "payment-service",
                LogEvent.PAYMENT_STATE_CHANGED,
                LogLevel.INFO,
                "PAY-\"42\\A",
                null,
                NOW.minusSeconds(60),
                NOW,
                200));

        assertThat(result.truncated()).isFalse();
        assertThat(result.records()).singleElement().satisfies(record -> {
            assertThat(record.service()).isEqualTo("payment-service");
            assertThat(record.level()).isEqualTo(LogLevel.WARN);
            assertThat(record.event()).isEqualTo(LogEvent.PAYMENT_STATE_CHANGED);
            assertThat(record.traceId()).isEqualTo("5b8efff798038103d269b633813fc700");
            assertThat(record.spanId()).isEqualTo("eee19b7ec3c1b100");
            assertThat(record.paymentId()).isEqualTo("PAY-42");
            assertThat(record.reasonCode()).isEqualTo("CHANNEL_TIMEOUT");
            assertThat(record.message()).isEqualTo("Payment state changed");
            assertThat(record.toString()).doesNotContain("authorization", "must-not-leak");
        });
        fixture.server.verify();
    }

    @Test
    void rejectsEmptyUnsupportedAndUnboundedSearchesBeforeIo() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.client.search(new StructuredLogSearch(
                        "payment-service", null, LogLevel.INFO, null, null,
                        NOW.minusSeconds(60), NOW, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filter");
        assertThatThrownBy(() -> fixture.client.search(new StructuredLogSearch(
                        "unknown-service", LogEvent.PAYMENT_STATE_CHANGED,
                        LogLevel.INFO, null, null, NOW.minusSeconds(60), NOW, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service");
        assertThatThrownBy(() -> fixture.client.search(new StructuredLogSearch(
                        "payment-service", null, LogLevel.INFO, null, "not-a-trace",
                        NOW.minusSeconds(60), NOW, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trace ID");
        assertThatThrownBy(() -> fixture.client.search(new StructuredLogSearch(
                        "payment-service", LogEvent.PAYMENT_STATE_CHANGED,
                        LogLevel.INFO, null, null, NOW.minusSeconds(60), NOW, 201)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("200");
        assertThatThrownBy(() -> fixture.client.search(new StructuredLogSearch(
                        "payment-service", LogEvent.PAYMENT_STATE_CHANGED,
                        LogLevel.INFO, null, null,
                        NOW.minusSeconds(7201), NOW, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two hours");
        fixture.server.verify();
    }

    @Test
    void globallySortsAndCapsEntriesWhenBackendIgnoresLimit() throws Exception {
        var fixture = fixture();
        var mapper = mapper();
        var root = mapper.createObjectNode();
        root.put("status", "success");
        var data = root.putObject("data");
        data.put("resultType", "streams");
        var stream = data.putArray("result").addObject();
        stream.putObject("stream").put("service_name", "payment-service");
        var values = stream.putArray("values");
        for (int index = 0; index < 205; index++) {
            values.addArray()
                    .add(Long.toString(1784203000000000000L + index))
                    .add("event-" + index)
                    .addObject()
                    .put("event", "PAYMENT_STATE_CHANGED")
                    .put("severity_text", "INFO");
        }
        fixture.server.expect(request -> {})
                .andRespond(withSuccess(mapper.writeValueAsString(root), MediaType.APPLICATION_JSON));

        var result = fixture.client.search(new StructuredLogSearch(
                "payment-service", LogEvent.PAYMENT_STATE_CHANGED, LogLevel.INFO,
                null, null, NOW.minusSeconds(60), NOW, 200));

        assertThat(result.records()).hasSize(200);
        assertThat(result.records().get(0).message()).isEqualTo("event-204");
        assertThat(result.truncated()).isTrue();
        fixture.server.verify();
    }

    @Test
    void classifiesBackendAndMalformedResponseFailures() {
        var unavailable = fixture();
        unavailable.server.expect(request -> {}).andRespond(withServerError());

        assertThatThrownBy(() -> unavailable.client.search(new StructuredLogSearch(
                        "payment-service", LogEvent.PAYMENT_STATE_CHANGED, LogLevel.INFO,
                        null, null, NOW.minusSeconds(60), NOW, 20)))
                .isInstanceOfSatisfying(ObservabilityBackendException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(ObservabilityBackendException.Code.BACKEND_UNAVAILABLE));
        unavailable.server.verify();

        var malformed = fixture();
        malformed.server.expect(request -> {})
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> malformed.client.search(new StructuredLogSearch(
                        "payment-service", LogEvent.PAYMENT_STATE_CHANGED, LogLevel.INFO,
                        null, null, NOW.minusSeconds(60), NOW, 20)))
                .isInstanceOfSatisfying(ObservabilityBackendException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(ObservabilityBackendException.Code.MALFORMED_RESPONSE));
        malformed.server.verify();

        var oversized = fixture();
        oversized.server.expect(request -> {}).andRespond(withSuccess(
                "x".repeat(HttpLokiReadClient.MAX_RESPONSE_CHARS + 1),
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> oversized.client.search(new StructuredLogSearch(
                        "payment-service", LogEvent.PAYMENT_STATE_CHANGED, LogLevel.INFO,
                        null, null, NOW.minusSeconds(60), NOW, 20)))
                .isInstanceOfSatisfying(ObservabilityBackendException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(ObservabilityBackendException.Code.LIMIT_EXCEEDED));
        oversized.server.verify();
    }

    private Fixture fixture() {
        var builder = RestClient.builder().baseUrl("http://loki.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(
                new HttpLokiReadClient(
                        builder.build(), mapper(), Clock.fixed(NOW, ZoneOffset.UTC)),
                server);
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private record Fixture(HttpLokiReadClient client, MockRestServiceServer server) {
    }
}
