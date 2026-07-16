package io.paysre.payment.application;

@FunctionalInterface
public interface PaymentIdGenerator {

    String nextPaymentId();
}
