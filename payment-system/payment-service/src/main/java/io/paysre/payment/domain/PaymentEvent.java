package io.paysre.payment.domain;

import java.time.Instant;

public record PaymentEvent(
        Instant eventTime,
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        String source,
        String reasonCode) {
}
