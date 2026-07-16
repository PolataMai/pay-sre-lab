package io.paysre.contracts;

/**
 * Synthetic channel request. It intentionally contains no cardholder or account data.
 */
public record ChannelPaymentRequest(
        String requestId,
        String paymentId,
        String channel,
        Money money) {
}
