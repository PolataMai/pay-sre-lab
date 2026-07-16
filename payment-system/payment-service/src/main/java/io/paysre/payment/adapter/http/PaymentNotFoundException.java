package io.paysre.payment.adapter.http;

public final class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String paymentId) {
        super("payment not found: " + paymentId);
    }
}
