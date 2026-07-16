package io.paysre.payment.application;

import io.paysre.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record UnknownPaymentSummary(
        String paymentId,
        String orderId,
        BigDecimal amount,
        String currency,
        String channel,
        PaymentStatus status,
        Instant updatedAt) {
}
