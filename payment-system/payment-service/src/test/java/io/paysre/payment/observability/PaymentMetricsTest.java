package io.paysre.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.paysre.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;

class PaymentMetricsTest {

    @Test
    void recordsUnknownAsAnAttemptOutcomeWithBoundedTags() {
        var registry = new SimpleMeterRegistry();
        var metrics = new PaymentMetrics(registry);

        metrics.recordTransition(
                "CHANNEL_A", PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN);

        assertThat(registry.get("payment_attempt_outcome_total")
                        .tags("channel", "CHANNEL_A", "status", "UNKNOWN")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(registry.get("payment_unknown_current")
                        .tag("channel", "CHANNEL_A")
                        .gauge()
                        .value())
                .isEqualTo(1.0);
        assertThat(registry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .noneMatch(tag -> tag.getKey().equalsIgnoreCase("paymentId"));
    }

    @Test
    void confirmedUnknownPaymentDecrementsTheCurrentUnknownGauge() {
        var registry = new SimpleMeterRegistry();
        var metrics = new PaymentMetrics(registry);
        metrics.recordTransition(
                "CHANNEL_A", PaymentStatus.PROCESSING, PaymentStatus.UNKNOWN);

        metrics.recordTransition(
                "CHANNEL_A", PaymentStatus.UNKNOWN, PaymentStatus.SUCCESS);

        assertThat(registry.get("payment_unknown_current")
                        .tag("channel", "CHANNEL_A")
                        .gauge()
                        .value())
                .isZero();
    }
}
