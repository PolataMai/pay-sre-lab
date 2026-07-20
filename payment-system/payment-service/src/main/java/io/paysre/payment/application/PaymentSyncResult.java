package io.paysre.payment.application;

import io.paysre.contracts.ChannelResult;
import io.paysre.payment.domain.PaymentStatus;
import java.util.Objects;

public record PaymentSyncResult(
        String paymentId,
        PaymentStatus previousStatus,
        PaymentStatus currentStatus,
        ChannelResult channelResult,
        PaymentSyncOutcome outcome) {

    public PaymentSyncResult {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(previousStatus, "previousStatus");
        Objects.requireNonNull(currentStatus, "currentStatus");
        Objects.requireNonNull(outcome, "outcome");
    }
}
