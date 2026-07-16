package io.paysre.payment.application;

public final class ChannelCallTimeoutException extends RuntimeException {

    public ChannelCallTimeoutException(String paymentId) {
        super("channel timeout for payment " + paymentId);
    }
}
