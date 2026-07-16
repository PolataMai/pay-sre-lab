package io.paysre.control.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpTempoReadClientTest {

    private static final String TRACE_ID = "5b8efff798038103d269b633813fc700";
    private static final String TRACE_ID_BASE64 = "W47/95gDgQPSabYzgT/HAA==";
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @Test
    void readsV2OtlpTraceAndProjectsAStableSpanTree() {
        var fixture = fixture();
        fixture.server.expect(request -> {
                    assertThat(request.getURI().getPath())
                            .isEqualTo("/api/v2/traces/" + TRACE_ID);
                    assertThat(request.getURI().getQuery())
                            .contains("start=1784203140", "end=1784203200");
                    assertThat(request.getHeaders().getAccept())
                            .contains(MediaType.APPLICATION_JSON);
                })
                .andRespond(withSuccess("""
                        {
                          "trace":{"resourceSpans":[{
                            "resource":{"attributes":[
                              {"key":"service.name","value":{"stringValue":"payment-service"}},
                              {"key":"deployment.environment","value":{"stringValue":"secret-env"}}
                            ]},
                            "scopeSpans":[{"spans":[{
                              "traceId":"W47/95gDgQPSabYzgT/HAA==",
                              "spanId":"7uGbfsPBsQA=",
                              "parentSpanId":"AQIDBAUGBwg=",
                              "name":"payment.channel.invoke",
                              "startTimeUnixNano":"1784203199000000000",
                              "endTimeUnixNano":"1784203199125000000",
                              "attributes":[
                                {"key":"payment.id","value":{"stringValue":"PAY-42"}},
                                {"key":"payment.channel","value":{"stringValue":"CHANNEL_A"}},
                                {"key":"authorization","value":{"stringValue":"must-not-leak"}}
                              ],
                              "status":{"code":"STATUS_CODE_ERROR","message":"private detail"}
                            },{
                              "traceId":"W47/95gDgQPSabYzgT/HAA==",
                              "spanId":"AQIDBAUGBwg=",
                              "name":"http.post /api/payments",
                              "startTimeUnixNano":"1784203198000000000",
                              "endTimeUnixNano":"1784203199200000000",
                              "attributes":[
                                {"key":"http.route","value":{"stringValue":"/api/payments"}},
                                {"key":"http.response.status_code","value":{"intValue":"504"}}
                              ],
                              "status":{"code":"STATUS_CODE_OK"}
                            }]}]
                          }]},
                          "status":"COMPLETE"
                        }
                        """, MediaType.APPLICATION_JSON));

        var trace = fixture.client.get(new DistributedTraceQuery(
                TRACE_ID.toUpperCase(), NOW.minusSeconds(60), NOW));

        assertThat(trace.traceId()).isEqualTo(TRACE_ID);
        assertThat(trace.partial()).isFalse();
        assertThat(trace.truncated()).isFalse();
        assertThat(trace.spans()).hasSize(2);
        assertThat(trace.spans().get(0)).satisfies(root -> {
            assertThat(root.service()).isEqualTo("payment-service");
            assertThat(root.spanId()).isEqualTo(hex("AQIDBAUGBwg="));
            assertThat(root.parentSpanId()).isNull();
            assertThat(root.duration()).isEqualTo(Duration.ofMillis(1200));
            assertThat(root.status()).isEqualTo(TraceSpanStatus.OK);
            assertThat(root.attributes())
                    .containsEntry("http.route", "/api/payments")
                    .containsEntry("http.response.status_code", "504");
        });
        assertThat(trace.spans().get(1)).satisfies(child -> {
            assertThat(child.parentSpanId()).isEqualTo(hex("AQIDBAUGBwg="));
            assertThat(child.duration()).isEqualTo(Duration.ofMillis(125));
            assertThat(child.status()).isEqualTo(TraceSpanStatus.ERROR);
            assertThat(child.attributes())
                    .containsEntry("payment.id", "PAY-42")
                    .containsEntry("payment.channel", "CHANNEL_A")
                    .doesNotContainKey("authorization");
            assertThat(child.toString()).doesNotContain("private detail", "secret-env");
        });
        fixture.server.verify();
    }

    @Test
    void rejectsInvalidTraceIdentifiersAndRangesBeforeIo() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.client.get(new DistributedTraceQuery(
                        "../api/search", NOW.minusSeconds(60), NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trace ID");
        assertThatThrownBy(() -> fixture.client.get(new DistributedTraceQuery(
                        TRACE_ID, NOW, NOW.minusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from before to");
        assertThatThrownBy(() -> fixture.client.get(new DistributedTraceQuery(
                        TRACE_ID, NOW.minusSeconds(7201), NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two hours");
        assertThatThrownBy(() -> fixture.client.get(new DistributedTraceQuery(
                        TRACE_ID, NOW.minusSeconds(1), NOW.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
        fixture.server.verify();
    }

    @Test
    void distinguishesNotFoundAndMalformedTraceResponses() {
        var notFound = fixture();
        notFound.server.expect(request -> {}).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> notFound.client.get(new DistributedTraceQuery(
                        TRACE_ID, NOW.minusSeconds(60), NOW)))
                .isInstanceOfSatisfying(ObservabilityBackendException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(ObservabilityBackendException.Code.NOT_FOUND));
        notFound.server.verify();

        var malformed = fixture();
        malformed.server.expect(request -> {})
                .andRespond(withSuccess("{\"trace\":{}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> malformed.client.get(new DistributedTraceQuery(
                        TRACE_ID, NOW.minusSeconds(60), NOW)))
                .isInstanceOfSatisfying(ObservabilityBackendException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(ObservabilityBackendException.Code.MALFORMED_RESPONSE));
        malformed.server.verify();

        var unavailable = fixture();
        unavailable.server.expect(request -> {}).andRespond(withServerError());

        assertThatThrownBy(() -> unavailable.client.get(new DistributedTraceQuery(
                        TRACE_ID, NOW.minusSeconds(60), NOW)))
                .isInstanceOfSatisfying(ObservabilityBackendException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(ObservabilityBackendException.Code.BACKEND_UNAVAILABLE));
        unavailable.server.verify();
    }

    @Test
    void capsOversizedTracesAtTwoHundredSpans() throws Exception {
        var fixture = fixture();
        var mapper = mapper();
        var root = mapper.createObjectNode();
        var trace = root.putObject("trace");
        var resourceSpans = trace.putArray("resourceSpans").addObject();
        resourceSpans.putObject("resource")
                .putArray("attributes")
                .addObject()
                .put("key", "service.name")
                .putObject("value")
                .put("stringValue", "payment-service");
        var spans = resourceSpans.putArray("scopeSpans")
                .addObject()
                .putArray("spans");
        for (int index = 0; index < 205; index++) {
            spans.addObject()
                    .put("traceId", TRACE_ID_BASE64)
                    .put("spanId", Base64.getEncoder().encodeToString(new byte[] {
                        0, 0, 0, 0, 0, 0, (byte) (index / 256), (byte) index
                    }))
                    .put("name", "span-" + index)
                    .put("startTimeUnixNano", Long.toString(1784203000000000000L + index))
                    .put("endTimeUnixNano", Long.toString(1784203000000000001L + index));
        }
        fixture.server.expect(request -> {})
                .andRespond(withSuccess(mapper.writeValueAsString(root), MediaType.APPLICATION_JSON));

        var result = fixture.client.get(new DistributedTraceQuery(
                TRACE_ID, NOW.minusSeconds(60), NOW));

        assertThat(result.spans()).hasSize(200);
        assertThat(result.truncated()).isTrue();
        fixture.server.verify();
    }

    private Fixture fixture() {
        var builder = RestClient.builder().baseUrl("http://tempo.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(
                new HttpTempoReadClient(
                        builder.build(), mapper(), Clock.fixed(NOW, ZoneOffset.UTC)),
                server);
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private String hex(String base64) {
        return HexFormat.of().formatHex(Base64.getDecoder().decode(base64));
    }

    private record Fixture(HttpTempoReadClient client, MockRestServiceServer server) {
    }
}
