package io.paysre.control.remediation;

@FunctionalInterface
public interface PaymentWriteClient {

    PaymentStateSync sync(String paymentId);
}
