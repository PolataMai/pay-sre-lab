package io.paysre.payment.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.paysre.payment.domain.PaymentStatus;
import java.util.concurrent.atomic.AtomicInteger;

public final class PaymentMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger unknownCurrent = new AtomicInteger();

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("payment_unknown_current", unknownCurrent, AtomicInteger::get)
                .description("Payments whose local state is not yet final")
                .register(registry);
    }

    public void recordTransition(String channel, PaymentStatus from, PaymentStatus to) {
        registry.counter(
                        "payment_state_transition_total",
                        "channel", channel,
                        "from", from.name(),
                        "to", to.name())
                .increment();

        if (to == PaymentStatus.UNKNOWN
                || to == PaymentStatus.SUCCESS
                || to == PaymentStatus.FAILED) {
            registry.counter(
                            "payment_attempt_outcome_total",
                            "channel", channel,
                            "status", to.name())
                    .increment();
        }

        if (to == PaymentStatus.UNKNOWN) {
            unknownCurrent.incrementAndGet();
        } else if (from == PaymentStatus.UNKNOWN) {
            unknownCurrent.updateAndGet(current -> Math.max(0, current - 1));
        }
    }
}
