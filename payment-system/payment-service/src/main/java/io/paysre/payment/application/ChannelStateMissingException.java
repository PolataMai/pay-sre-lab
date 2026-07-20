package io.paysre.payment.application;

public final class ChannelStateMissingException extends RuntimeException {

    public ChannelStateMissingException(String paymentId) {
        super("channel has no final state for payment " + paymentId);
    }
}
