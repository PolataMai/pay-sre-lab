package io.paysre.control.investigation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record InvestigationSeed(
        String representativePaymentId,
        String channel,
        Instant from,
        Instant to) {

    public InvestigationSeed {
        Objects.requireNonNull(representativePaymentId, "representativePaymentId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (representativePaymentId.isBlank() || channel.isBlank()) {
            throw new IllegalArgumentException("payment ID and channel must not be blank");
        }
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("investigation end must be after start");
        }
        if (Duration.between(from, to).compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("investigation window must not exceed 24 hours");
        }
    }
}
