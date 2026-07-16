package io.paysre.channel;

import java.time.Instant;
import java.util.Optional;

public interface FaultRuleRepository {

    Optional<FaultRule> findActive(String channel, Instant now);

    void replace(FaultRule rule);
}
