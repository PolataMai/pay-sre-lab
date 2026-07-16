package io.paysre.payment.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.paysre.payment.domain.PaymentStatus;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class PaymentMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, AtomicInteger> unknownCurrent =
            new ConcurrentHashMap<>();

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
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
            unknownGauge(channel).incrementAndGet();
        } else if (from == PaymentStatus.UNKNOWN) {
            unknownGauge(channel).updateAndGet(current -> Math.max(0, current - 1));
        }
    }

    private AtomicInteger unknownGauge(String channel) {
        return unknownCurrent.computeIfAbsent(channel, key -> {
            var value = new AtomicInteger();
            Gauge.builder("payment_unknown_current", value, AtomicInteger::get)
                    .description("Payments whose local state is not yet final")
                    .tag("channel", key)
                    .register(registry);
            return value;
        });
    }
}
