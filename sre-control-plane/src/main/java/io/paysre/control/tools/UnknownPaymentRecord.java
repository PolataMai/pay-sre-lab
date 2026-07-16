package io.paysre.control.tools;

import java.math.BigDecimal;
import java.time.Instant;

public record UnknownPaymentRecord(
        String paymentId,
        String orderId,
        BigDecimal amount,
        String currency,
        String channel,
        String status,
        Instant updatedAt) {
}
