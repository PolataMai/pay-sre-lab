package io.paysre.channel;

public final class ChannelTimeoutException extends RuntimeException {

    public ChannelTimeoutException(String paymentId) {
        super("channel timeout for payment " + paymentId);
    }
}
