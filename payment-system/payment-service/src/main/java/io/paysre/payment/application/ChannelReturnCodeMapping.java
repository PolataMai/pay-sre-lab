package io.paysre.payment.application;

import io.paysre.contracts.ChannelResult;
import java.util.Objects;
import java.util.Set;

/**
 * Maps a raw channel return code (the string the channel simulator
 * sends back) to a {@link ChannelResult} that drives the payment
 * state machine. The mapping is config-driven so it can be overridden
 * from Nacos in the same way as other Spring properties; a
 * misconfigured mapping produces a {@code CHANNEL_CODE_MAPPING_ERROR}
 * root cause that the lab can replay.
 */
public interface ChannelReturnCodeMapping {

    ChannelResult resultFor(String channelCode);

    final class Fixed implements ChannelReturnCodeMapping {
        private final Set<String> successCodes;
        private final Set<String> failureCodes;
        private final ChannelResult fallback;

        public Fixed(Set<String> successCodes, Set<String> failureCodes,
                     ChannelResult fallback) {
            this.successCodes = Set.copyOf(Objects.requireNonNull(successCodes, "successCodes"));
            this.failureCodes = Set.copyOf(Objects.requireNonNull(failureCodes, "failureCodes"));
            this.fallback = Objects.requireNonNull(fallback, "fallback");
            if (!successCodes.isEmpty() && !failureCodes.isEmpty()
                    && !java.util.Collections.disjoint(successCodes, failureCodes)) {
                throw new IllegalArgumentException(
                        "channel code mapping cannot overlap success and failure sets");
            }
        }

        @Override
        public ChannelResult resultFor(String channelCode) {
            if (successCodes.contains(channelCode)) {
                return ChannelResult.SUCCESS;
            }
            if (failureCodes.contains(channelCode)) {
                return ChannelResult.FAILED;
            }
            return fallback;
        }
    }
}