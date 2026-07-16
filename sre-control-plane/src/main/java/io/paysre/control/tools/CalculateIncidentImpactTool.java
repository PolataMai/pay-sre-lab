package io.paysre.control.tools;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class CalculateIncidentImpactTool
        implements ToolHandler<CalculateIncidentImpactTool.Input, CalculateIncidentImpactTool.Output> {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "calculate_incident_impact",
            1,
            "Count and sum UNKNOWN synthetic payments for one channel and bounded time range",
            Duration.ofSeconds(3),
            65_536,
            ToolRisk.READ_ONLY);

    private final PaymentReadClient paymentClient;

    public CalculateIncidentImpactTool(PaymentReadClient paymentClient) {
        this.paymentClient = paymentClient;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public Output execute(Input input) {
        var candidates = paymentClient.unknownPayments(input.from(), input.to(), 200);
        if (candidates.size() == 200) {
            throw new IllegalStateException(
                    "impact candidate limit reached; exact total is unknown");
        }
        var payments = candidates.stream()
                .filter(payment -> input.channel().equals(payment.channel()))
                .toList();
        var currencies = payments.stream()
                .map(UnknownPaymentRecord::currency)
                .distinct()
                .toList();
        if (currencies.size() > 1) {
            throw new IllegalArgumentException("cannot aggregate mixed currencies");
        }
        var total = payments.stream()
                .map(UnknownPaymentRecord::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Output(
                payments.size(),
                total,
                currencies.isEmpty() ? null : currencies.get(0),
                payments.stream().map(UnknownPaymentRecord::paymentId).toList());
    }

    public record Input(String channel, Instant from, Instant to) {
        public Input {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (!to.isAfter(from)) {
                throw new IllegalArgumentException("to must be after from");
            }
            if (Duration.between(from, to).compareTo(Duration.ofHours(24)) > 0) {
                throw new IllegalArgumentException("time range must not exceed 24 hours");
            }
        }
    }

    public record Output(
            long affectedPaymentCount,
            BigDecimal totalAmount,
            String currency,
            List<String> paymentIds) {

        public Output {
            paymentIds = List.copyOf(paymentIds);
        }
    }
}
