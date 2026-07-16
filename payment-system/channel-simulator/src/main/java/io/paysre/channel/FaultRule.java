package io.paysre.channel;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record FaultRule(
        String channel,
        FaultType type,
        BigDecimal probability,
        Instant activeFrom,
        Instant activeUntil,
        long randomSeed) {

    public FaultRule {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(probability, "probability");
        Objects.requireNonNull(activeFrom, "activeFrom");
        Objects.requireNonNull(activeUntil, "activeUntil");
        if (probability.signum() < 0 || probability.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("probability must be between zero and one");
        }
    }
}
