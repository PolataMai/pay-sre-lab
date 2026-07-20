package io.paysre.payment.application;

import java.util.Map;
import java.util.Objects;

/**
 * Config-driven selector that maps a merchant id to the channel
 * code a payment should be routed to. The mapping is sourced from
 * Spring configuration (and can be overridden via Nacos in the
 * {@code nacos} profile); a misconfigured mapping produces a
 * {@code ROUTING_MISCONFIGURED} root cause that the lab can
 * replay.
 */
public interface ChannelRouter {

    String selectChannel(String merchantId);

    final class Static implements ChannelRouter {
        private final String defaultChannel;
        private final Map<String, String> overrides;

        public Static(String defaultChannel, Map<String, String> overrides) {
            this.defaultChannel = Objects.requireNonNull(defaultChannel, "defaultChannel");
            this.overrides = Map.copyOf(Objects.requireNonNull(overrides, "overrides"));
        }

        @Override
        public String selectChannel(String merchantId) {
            return overrides.getOrDefault(merchantId, defaultChannel);
        }
    }
}