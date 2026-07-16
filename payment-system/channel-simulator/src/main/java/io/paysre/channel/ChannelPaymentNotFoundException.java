package io.paysre.channel;

public final class ChannelPaymentNotFoundException extends RuntimeException {

    public ChannelPaymentNotFoundException(String paymentId) {
        super("channel payment not found: " + paymentId);
    }
}
