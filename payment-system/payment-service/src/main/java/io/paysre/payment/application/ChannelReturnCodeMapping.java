package io.paysre.payment.application;

import io.paysre.contracts.ChannelResult;
import java.util.Objects;
import java.util.Set;

/**
 * Maps a raw channel return code to a {@link ChannelResult}. The
 * mapping is config-driven so it can be overridden through Nacos in
 * the same way as other Spring properties; a misconfigured mapping
 * produces a {@code CHANNEL_CODE_MAPPING_ERROR} root cause that the
 * lab can replay.
 *
 * <p>An unmapped code (one not in either the success or the failure
 * set) is treated as indeterminate — the mapping returns
 * {@code UNMAPPED}. Callers must surface that as
 * {@code PaymentStatus.UNKNOWN} with reason code
 * {@code CHANNEL_CODE_UNMAPPED}. The mapping never guesses a final
 * state for an unknown code, in line with the project's "no evidence
 * no state change" invariant.
 */
public interface ChannelReturnCodeMapping {

    Result map(String channelCode);

    enum ResultKind { MAPPED_SUCCESS, MAPPED_FAILURE, UNMAPPED }

    record Result(ChannelResult channelResult, ResultKind kind) {
        public static Result success(ChannelResult result) {
            return new Result(result, ResultKind.MAPPED_SUCCESS);
        }

        public static Result failure(ChannelResult result) {
            return new Result(result, ResultKind.MAPPED_FAILURE);
        }

        public static Result unmapped() {
            return new Result(ChannelResult.TIMEOUT, ResultKind.UNMAPPED);
        }
    }

    final class Fixed implements ChannelReturnCodeMapping {
        private final Set<String> successCodes;
        private final Set<String> failureCodes;

        public Fixed(Set<String> successCodes, Set<String> failureCodes) {
            this.successCodes = Set.copyOf(Objects.requireNonNull(successCodes, "successCodes"));
            this.failureCodes = Set.copyOf(Objects.requireNonNull(failureCodes, "failureCodes"));
            if (!successCodes.isEmpty() && !failureCodes.isEmpty()
                    && !java.util.Collections.disjoint(successCodes, failureCodes)) {
                throw new IllegalArgumentException(
                        "channel code mapping cannot overlap success and failure sets");
            }
        }

        @Override
        public Result map(String channelCode) {
            if (successCodes.contains(channelCode)) {
                return Result.success(ChannelResult.SUCCESS);
            }
            if (failureCodes.contains(channelCode)) {
                return Result.failure(ChannelResult.FAILED);
            }
            return Result.unmapped();
        }
    }
}