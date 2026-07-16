package io.paysre.channel;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ChannelMetricsTest {

    @Test
    void recordsOutcomeAndDurationUsingOnlyBoundedLabels() {
        var registry = new SimpleMeterRegistry();
        var metrics = new ChannelMetrics(registry);

        metrics.recordRequest("CHANNEL_A", "TIMEOUT", Duration.ofMillis(125));

        assertThat(registry.get("channel_request_total")
                        .tags("channel", "CHANNEL_A", "result", "TIMEOUT")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("channel_request_duration_seconds")
                        .tags("channel", "CHANNEL_A")
                        .timer()
                        .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(125.0);
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .doesNotContain("payment.id", "paymentId", "request.id", "requestId");
    }
}
