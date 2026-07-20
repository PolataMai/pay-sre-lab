package io.paysre.control.remediation;

import java.util.Objects;

public record PaymentStateSync(
        String paymentId,
        String previousStatus,
        String currentStatus,
        String channelResult,
        String outcome) {

    public PaymentStateSync {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(previousStatus, "previousStatus");
        Objects.requireNonNull(currentStatus, "currentStatus");
        Objects.requireNonNull(outcome, "outcome");
    }
}
