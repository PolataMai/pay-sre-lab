package io.paysre.contracts;

import java.time.Instant;

/**
 * Asynchronous notification a channel can send to payment-service
 * after it has finalised a payment. The {@code sequenceNumber}
 * combined with {@code paymentId} forms the idempotency key — the
 * receiver must ignore any callback it has already processed under
 * the same pair. A negative or repeated {@code sequenceNumber} lets
 * the simulator deliberately exercise the duplicate-detection path.
 */
public record ChannelCallback(
        String callbackId,
        String paymentId,
        String channel,
        ChannelResult result,
        String channelCode,
        long sequenceNumber,
        Instant receivedAt) {
}