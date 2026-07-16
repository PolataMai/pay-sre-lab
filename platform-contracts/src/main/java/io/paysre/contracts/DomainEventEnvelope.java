package io.paysre.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Transport-neutral event metadata shared by the payment data plane and SRE control plane.
 */
public record DomainEventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String aggregateType,
        String aggregateId,
        String traceId,
        String correlationId,
        T payload) {
}
