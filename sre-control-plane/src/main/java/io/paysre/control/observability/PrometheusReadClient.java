package io.paysre.control.observability;

@FunctionalInterface
public interface PrometheusReadClient {

    ServiceMetricsResult query(ServiceMetricsQuery query);
}
