package io.paysre.control.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpPrometheusReadClientTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @ParameterizedTest
    @MethodSource("metricSignals")
    void ownsThePromQlTemplateForEverySupportedSignal(
            MetricSignal signal, String expectedMetric) {
        var fixture = fixture();
        fixture.server.expect(request -> {
                    var decoded = URLDecoder.decode(
                            request.getURI().getRawQuery(), StandardCharsets.UTF_8);
                    assertThat(decoded)
                            .contains(expectedMetric)
                            .contains("service=\"" + signal.service() + "\"")
                            .contains("channel=\"CHANNEL_A\"");
                })
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"matrix","result":[]}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client.query(query(
                                signal,
                                NOW.minusSeconds(60),
                                NOW,
                                Duration.ofSeconds(30)))
                        .series())
                .isEmpty();
        fixture.server.verify();
    }

    @Test
    void mapsSignalToEncodedPromQlAndNormalizesMatrixWithoutInventingValues() {
        var fixture = fixture();
        fixture.server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/api/v1/query_range");
                    assertThat(request.getURI().getRawQuery()).doesNotContain("{", " ");
                    var query = URLDecoder.decode(
                            request.getURI().getRawQuery(), StandardCharsets.UTF_8);
                    assertThat(query)
                            .contains("query=payment_unknown_current{service=\"payment-service\",channel=\"CHANNEL_A\"}")
                            .contains("start=2026-07-16T11:59:00Z")
                            .contains("end=2026-07-16T12:00:00Z")
                            .contains("step=30s")
                            .contains("limit=20")
                            .contains("timeout=3s");
                })
                .andRespond(withSuccess("""
                        {
                          "status":"success",
                          "warnings":["partial response"],
                          "data":{"resultType":"matrix","result":[{
                            "metric":{"__name__":"payment_unknown_current","service":"payment-service","channel":"CHANNEL_A","pod":"must-not-leak"},
                            "values":[[1784203140,"2"],[1784203170,"NaN"],[1784203200,"+Inf"]]
                          }]}
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = fixture.client.query(query(
                MetricSignal.PAYMENT_UNKNOWN_CURRENT,
                NOW.minusSeconds(60),
                NOW,
                Duration.ofSeconds(30)));

        assertThat(result.signal()).isEqualTo(MetricSignal.PAYMENT_UNKNOWN_CURRENT);
        assertThat(result.warnings()).containsExactly("partial response");
        assertThat(result.truncated()).isFalse();
        assertThat(result.series()).singleElement().satisfies(series -> {
            assertThat(series.labels())
                    .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                            "service", "payment-service", "channel", "CHANNEL_A"));
            assertThat(series.samples()).hasSize(3);
            assertThat(series.samples().get(0).value()).isEqualByComparingTo(new BigDecimal("2"));
            assertThat(series.samples().get(0).available()).isTrue();
            assertThat(series.samples().get(1).value()).isNull();
            assertThat(series.samples().get(1).available()).isFalse();
            assertThat(series.samples().get(2).value()).isNull();
            assertThat(series.samples().get(2).available()).isFalse();
        });
        fixture.server.verify();
    }

    @Test
    void alignsTheRangeGridWithItsEndSoTheLatestPrometheusEvaluationIsIncluded() {
        var fixture = fixture();
        fixture.server.expect(request -> {
                    var query = URLDecoder.decode(
                            request.getURI().getRawQuery(), StandardCharsets.UTF_8);
                    assertThat(query)
                            .contains("start=2026-07-16T11:59:00Z")
                            .contains("end=2026-07-16T12:00:00Z")
                            .contains("step=30s");
                })
                .andRespond(withSuccess("""
                        {"status":"success","data":{"resultType":"matrix","result":[]}}
                        """, MediaType.APPLICATION_JSON));

        fixture.client.query(query(
                MetricSignal.PAYMENT_UNKNOWN_CURRENT,
                NOW.minusSeconds(65),
                NOW,
                Duration.ofSeconds(30)));

        fixture.server.verify();
    }

    @Test
    void rejectsUnboundedOrUnsupportedQueriesBeforeIo() {
        var fixture = fixture();

        assertThatThrownBy(() -> fixture.client.query(new ServiceMetricsQuery(
                        MetricSignal.CHANNEL_REQUEST_P95,
                        "payment-service",
                        "CHANNEL_A",
                        NOW.minusSeconds(60),
                        NOW,
                        Duration.ofSeconds(30))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service");
        assertThatThrownBy(() -> fixture.client.query(query(
                        MetricSignal.PAYMENT_UNKNOWN_CURRENT,
                        NOW,
                        NOW.plusSeconds(1),
                        Duration.ofSeconds(15))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
        assertThatThrownBy(() -> fixture.client.query(query(
                        MetricSignal.PAYMENT_UNKNOWN_CURRENT,
                        NOW.minus(Duration.ofHours(2)).minusSeconds(1),
                        NOW,
                        Duration.ofSeconds(60))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two hours");
        assertThatThrownBy(() -> fixture.client.query(query(
                        MetricSignal.PAYMENT_UNKNOWN_CURRENT,
                        NOW.minusSeconds(60),
                        NOW,
                        Duration.ofSeconds(14))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("15 seconds");
        assertThatThrownBy(() -> fixture.client.query(query(
                        MetricSignal.PAYMENT_UNKNOWN_CURRENT,
                        NOW.minusSeconds(3600),
                        NOW,
                        Duration.ofSeconds(15))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("240 samples");
        assertThatThrownBy(() -> fixture.client.query(new ServiceMetricsQuery(
                        MetricSignal.PAYMENT_UNKNOWN_CURRENT,
                        "payment-service",
                        "CHANNEL_X",
                        NOW.minusSeconds(60),
                        NOW,
                        Duration.ofSeconds(30))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
        fixture.server.verify();
    }

    @Test
    void enforcesSeriesAndTotalSampleCapsWhenBackendIgnoresLimit() throws Exception {
        var fixture = fixture();
        var mapper = mapper();
        var root = mapper.createObjectNode();
        root.put("status", "success");
        var data = root.putObject("data");
        data.put("resultType", "matrix");
        var backendSeries = data.putArray("result");
        for (int seriesIndex = 0; seriesIndex < 21; seriesIndex++) {
            var series = backendSeries.addObject();
            series.putObject("metric")
                    .put("service", "payment-service")
                    .put("channel", "CHANNEL_A")
                    .put("status", "S" + seriesIndex);
            var values = series.putArray("values");
            for (int sampleIndex = 0; sampleIndex < 20; sampleIndex++) {
                values.addArray().add(1784203140 + sampleIndex).add("1");
            }
        }
        fixture.server.expect(request -> {})
                .andRespond(withSuccess(mapper.writeValueAsString(root), MediaType.APPLICATION_JSON));

        var result = fixture.client.query(query(
                MetricSignal.PAYMENT_ATTEMPT_OUTCOME_RATE,
                NOW.minusSeconds(60),
                NOW,
                Duration.ofSeconds(30)));

        assertThat(result.series()).hasSize(12);
        assertThat(result.series()).flatExtracting(MetricSeries::samples).hasSize(240);
        assertThat(result.truncated()).isTrue();
        fixture.server.verify();
    }

    @Test
    void classifiesBackendAndMalformedResponseFailuresWithoutReturningBodies() {
        var unavailable = fixture();
        unavailable.server.expect(request -> {}).andRespond(withServerError().body("secret backend detail"));

        assertThatThrownBy(() -> unavailable.client.query(query(
                        MetricSignal.PAYMENT_UNKNOWN_CURRENT,
                        NOW.minusSeconds(60),
                        NOW,
                        Duration.ofSeconds(30))))
                .isInstanceOfSatisfying(ObservabilityBackendException.class, exception -> {
                    assertThat(exception.code())
                            .isEqualTo(ObservabilityBackendException.Code.BACKEND_UNAVAILABLE);
                    assertThat(exception.getMessage()).doesNotContain("secret backend detail");
                });
        unavailable.server.verify();

        var malformed = fixture();
        malformed.server.expect(request -> {})
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> malformed.client.query(query(
                        MetricSignal.PAYMENT_UNKNOWN_CURRENT,
                        NOW.minusSeconds(60),
                        NOW,
                        Duration.ofSeconds(30))))
                .isInstanceOfSatisfying(ObservabilityBackendException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(ObservabilityBackendException.Code.MALFORMED_RESPONSE));
        malformed.server.verify();
    }

    private ServiceMetricsQuery query(
            MetricSignal signal, Instant from, Instant to, Duration step) {
        return new ServiceMetricsQuery(
                signal, signal.service(), "CHANNEL_A", from, to, step);
    }

    private Fixture fixture() {
        var builder = RestClient.builder().baseUrl("http://prometheus.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        return new Fixture(
                new HttpPrometheusReadClient(
                        builder.build(), mapper(), Clock.fixed(NOW, ZoneOffset.UTC)),
                server);
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private static java.util.stream.Stream<Arguments> metricSignals() {
        return java.util.stream.Stream.of(
                Arguments.of(
                        MetricSignal.PAYMENT_UNKNOWN_CURRENT,
                        "payment_unknown_current"),
                Arguments.of(
                        MetricSignal.PAYMENT_ATTEMPT_OUTCOME_RATE,
                        "payment_attempt_outcome_total"),
                Arguments.of(
                        MetricSignal.PAYMENT_PROCESSING_P95,
                        "payment_processing_duration_seconds_bucket"),
                Arguments.of(
                        MetricSignal.CHANNEL_REQUEST_ERROR_RATE,
                        "channel_request_total"),
                Arguments.of(
                        MetricSignal.CHANNEL_REQUEST_P95,
                        "channel_request_duration_seconds_bucket"));
    }

    private record Fixture(
            HttpPrometheusReadClient client,
            MockRestServiceServer server) {
    }
}
