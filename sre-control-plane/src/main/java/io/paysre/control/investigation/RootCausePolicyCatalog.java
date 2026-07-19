package io.paysre.control.investigation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Registry of every supported {@link RootCausePolicy}. New fault
 * families register one more entry here; the validator, the stub
 * investigation model and the MiniMax prompt/tools all derive their
 * behaviour from this catalog.
 */
public final class RootCausePolicyCatalog {

    private final Map<RootCauseCode, RootCausePolicy> policies;

    private RootCausePolicyCatalog(Map<RootCauseCode, RootCausePolicy> policies) {
        this.policies = Map.copyOf(policies);
    }

    /**
     * Built-in catalog containing every policy that ships with the
     * control plane. Tests and wiring use this unless they have a
     * reason to substitute their own subset.
     */
    public static RootCausePolicyCatalog defaults() {
        return new RootCausePolicyCatalog(Map.of(
                RootCauseCode.CHANNEL_TIMEOUT_RESPONSE_LOST,
                new ChannelTimeoutResponseLostPolicy(),
                RootCauseCode.CHANNEL_DECLINE_SPIKE,
                new ChannelDeclineSpikePolicy(),
                RootCauseCode.CHANNEL_CODE_MAPPING_ERROR,
                new ChannelCodeMappingErrorPolicy(),
                RootCauseCode.CHANNEL_CALLBACK_LOST,
                new ChannelCallbackLostPolicy()));
    }

    public static Builder builder() {
        return new Builder();
    }

    public RootCausePolicy forRootCause(RootCauseCode code) {
        var policy = policies.get(Objects.requireNonNull(code, "code"));
        if (policy == null) {
            throw new InvalidConclusionException(
                    "unsupported root cause: " + code);
        }
        return policy;
    }

    public Optional<RootCausePolicy> find(RootCauseCode code) {
        return Optional.ofNullable(policies.get(code));
    }

    public Collection<RootCausePolicy> all() {
        return policies.values();
    }

    /**
     * Compact, model-readable summary used by the MiniMax system
     * prompt and any future admin surface. Format is deterministic so
     * tests can assert against it.
     */
    public String describe() {
        var sorted = new TreeMap<>(policies);
        var builder = new StringBuilder();
        var first = true;
        for (var policy : sorted.values()) {
            if (!first) {
                builder.append('\n');
            }
            first = false;
            builder.append("- rootCause=").append(policy.rootCause().name());
            builder.append(", allowedRunbooks=").append(policy.allowedRunbooks());
            builder.append(", requiresHumanReview=").append(policy.requiresHumanReview());
        }
        return builder.toString();
    }

    public static final class Builder {
        private final Map<RootCauseCode, RootCausePolicy> policies = new LinkedHashMap<>();

        public Builder register(RootCausePolicy policy) {
            Objects.requireNonNull(policy, "policy");
            policies.put(policy.rootCause(), policy);
            return this;
        }

        public RootCausePolicyCatalog build() {
            if (policies.isEmpty()) {
                throw new IllegalStateException(
                        "RootCausePolicyCatalog must register at least one policy");
            }
            return new RootCausePolicyCatalog(new LinkedHashMap<>(policies));
        }
    }

    // Kept for callers that still want a defensive copy of the keys.
    List<RootCauseCode> rootCauses() {
        return List.copyOf(policies.keySet());
    }
}