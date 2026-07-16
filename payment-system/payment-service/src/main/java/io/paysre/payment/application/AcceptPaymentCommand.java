package io.paysre.payment.application;

import io.paysre.contracts.Money;

public record AcceptPaymentCommand(
        String orderId,
        String merchantId,
        String idempotencyKey,
        Money money) {
}
