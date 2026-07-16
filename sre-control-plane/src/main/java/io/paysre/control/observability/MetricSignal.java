package io.paysre.control.observability;

public enum MetricSignal {
    PAYMENT_UNKNOWN_CURRENT("payment-service"),
    PAYMENT_ATTEMPT_OUTCOME_RATE("payment-service"),
    PAYMENT_PROCESSING_P95("payment-service"),
    CHANNEL_REQUEST_ERROR_RATE("channel-simulator"),
    CHANNEL_REQUEST_P95("channel-simulator");

    private final String service;

    MetricSignal(String service) {
        this.service = service;
    }

    public String service() {
        return service;
    }

    String promQl(String channel) {
        var selector = "service=\"" + service + "\",channel=\"" + channel + "\"";
        return switch (this) {
            case PAYMENT_UNKNOWN_CURRENT -> "payment_unknown_current{" + selector + "}";
            case PAYMENT_ATTEMPT_OUTCOME_RATE ->
                "sum by (service,channel,status) (rate(payment_attempt_outcome_total{"
                        + selector + "}[5m]))";
            case PAYMENT_PROCESSING_P95 ->
                "histogram_quantile(0.95, sum by (service,channel,le) "
                        + "(rate(payment_processing_duration_seconds_bucket{"
                        + selector + "}[5m])))";
            case CHANNEL_REQUEST_ERROR_RATE ->
                "sum by (service,channel) (rate(channel_request_total{"
                        + selector + ",result=~\"TIMEOUT|FAILED\"}[5m])) / "
                        + "clamp_min(sum by (service,channel) (rate(channel_request_total{"
                        + selector + "}[5m])), 0.001)";
            case CHANNEL_REQUEST_P95 ->
                "histogram_quantile(0.95, sum by (service,channel,le) "
                        + "(rate(channel_request_duration_seconds_bucket{"
                        + selector + "}[5m])))";
        };
    }
}
