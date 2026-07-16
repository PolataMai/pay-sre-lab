package io.paysre.contracts;

import java.time.Instant;

/**
 * Final state reported by a synthetic payment channel.
 */
public record ChannelPaymentResponse(
        String requestId,
        String paymentId,
        ChannelResult result,
        String channelCode,
        Instant channelTime) {
}
