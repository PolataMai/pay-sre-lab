package io.paysre.control.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paysre.control.observability.DistributedTrace;
import io.paysre.control.observability.DistributedTraceQuery;
import io.paysre.control.observability.HttpLokiReadClient;
import io.paysre.control.observability.HttpPrometheusReadClient;
import io.paysre.control.observability.HttpTempoReadClient;
import io.paysre.control.observability.LogEvent;
import io.paysre.control.observability.LogLevel;
import io.paysre.control.observability.LokiReadClient;
import io.paysre.control.observability.MetricSignal;
import io.paysre.control.observability.PrometheusReadClient;
import io.paysre.control.observability.ServiceMetricsQuery;
import io.paysre.control.observability.ServiceMetricsResult;
import io.paysre.control.observability.StructuredLogResult;
import io.paysre.control.observability.StructuredLogSearch;
import io.paysre.control.observability.TempoReadClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservabilityToolsTest {

    private static final Instant TO = Instant.parse("2026-07-16T12:00:00Z");
    private static final Instant FROM = TO.minusSeconds(60);

    @Test
    void delegatesOnlyTypedMetricLogAndTraceQueries() {
        var prometheus = mock(PrometheusReadClient.class);
        var loki = mock(LokiReadClient.class);
        var tempo = mock(TempoReadClient.class);
        var metricQuery = new ServiceMetricsQuery(
                MetricSignal.PAYMENT_UNKNOWN_CURRENT,
                "payment-service",
                "CHANNEL_A",
                FROM,
                TO,
                Duration.ofSeconds(30));
        var logSearch = new StructuredLogSearch(
                "payment-service",
                LogEvent.PAYMENT_STATE_CHANGED,
                LogLevel.INFO,
                "PAY-42",
                null,
                FROM,
                TO,
                20);
        var traceQuery = new DistributedTraceQuery(
                "5b8efff798038103d269b633813fc700", FROM, TO);
        var metricResult = new ServiceMetricsResult(
                metricQuery.signal(), List.of(), List.of(), false);
        var logResult = new StructuredLogResult(List.of(), false);
        var traceResult = new DistributedTrace(traceQuery.traceId(), List.of(), false, false);
        when(prometheus.query(metricQuery)).thenReturn(metricResult);
        when(loki.search(logSearch)).thenReturn(logResult);
        when(tempo.get(traceQuery)).thenReturn(traceResult);

        var metricsTool = new QueryServiceMetricsTool(prometheus);
        var logsTool = new SearchStructuredLogsTool(loki);
        var traceTool = new GetDistributedTraceTool(tempo);

        assertThat(metricsTool.execute(metricQuery)).isEqualTo(metricResult);
        assertThat(logsTool.execute(logSearch)).isEqualTo(logResult);
        assertThat(traceTool.execute(traceQuery)).isEqualTo(traceResult);
        verify(prometheus).query(metricQuery);
        verify(loki).search(logSearch);
        verify(tempo).get(traceQuery);
    }

    @Test
    void definitionsAreBoundedAndReadOnly() {
        var tools = List.of(
                new QueryServiceMetricsTool(mock(PrometheusReadClient.class)),
                new SearchStructuredLogsTool(mock(LokiReadClient.class)),
                new GetDistributedTraceTool(mock(TempoReadClient.class)));

        assertThat(tools)
                .extracting(tool -> tool.definition().name())
                .containsExactly(
                        "query_service_metrics",
                        "search_structured_logs",
                        "get_distributed_trace");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.definition().risk()).isEqualTo(ToolRisk.READ_ONLY);
            assertThat(tool.definition().timeout()).isLessThanOrEqualTo(Duration.ofSeconds(4));
            assertThat(tool.definition().maximumResultBytes()).isEqualTo(65_536);
        });
    }

    @Test
    void observabilityClientsExposeNoMutationOperation() {
        assertThat(List.of(
                        HttpPrometheusReadClient.class,
                        HttpLokiReadClient.class,
                        HttpTempoReadClient.class))
                .flatExtracting(type -> List.of(type.getDeclaredMethods()))
                .extracting(java.lang.reflect.Method::getName)
                .noneMatch(name -> List.of("post", "put", "patch", "delete", "write", "execute")
                        .contains(name.toLowerCase(java.util.Locale.ROOT)));
    }
}
