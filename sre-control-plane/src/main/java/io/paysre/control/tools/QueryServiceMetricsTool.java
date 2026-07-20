package io.paysre.control.tools;

import io.paysre.control.observability.PrometheusReadClient;
import io.paysre.control.observability.ServiceMetricsQuery;
import io.paysre.control.observability.ServiceMetricsResult;
import java.time.Duration;
import java.util.Objects;

public final class QueryServiceMetricsTool
        implements ToolHandler<ServiceMetricsQuery, ServiceMetricsResult> {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "query_service_metrics",
            1,
            "Read one allowlisted payment SRE metric over a bounded time range",
            Duration.ofSeconds(4),
            65_536,
            ToolRisk.READ_ONLY);

    private final PrometheusReadClient client;

    public QueryServiceMetricsTool(PrometheusReadClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Class<ServiceMetricsQuery> inputType() {
        return ServiceMetricsQuery.class;
    }

    @Override
    public ServiceMetricsResult execute(ServiceMetricsQuery input) {
        return client.query(input);
    }
}
