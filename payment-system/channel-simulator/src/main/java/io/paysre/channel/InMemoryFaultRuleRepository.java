package io.paysre.channel;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryFaultRuleRepository implements FaultRuleRepository {

    private final Map<String, FaultRule> rules = new ConcurrentHashMap<>();

    @Override
    public Optional<FaultRule> findActive(String channel, Instant now) {
        return Optional.ofNullable(rules.get(channel))
                .filter(rule -> !now.isBefore(rule.activeFrom()))
                .filter(rule -> now.isBefore(rule.activeUntil()));
    }

    @Override
    public void replace(FaultRule rule) {
        if (!rule.activeUntil().isAfter(rule.activeFrom())) {
            throw new IllegalArgumentException("activeUntil must be after activeFrom");
        }
        rules.put(rule.channel(), rule);
    }
}
