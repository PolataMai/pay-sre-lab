package io.paysre.channel;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelPaymentResponse;
import io.paysre.contracts.ChannelResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ChannelSimulationService {

    private final FaultRuleRepository rules;
    private final FaultDecider decider;
    private final Clock clock;
    private final Map<String, ChannelPaymentResponse> finalStates = new ConcurrentHashMap<>();

    public ChannelSimulationService(FaultRuleRepository rules, FaultDecider decider, Clock clock) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.decider = Objects.requireNonNull(decider, "decider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ChannelPaymentResponse pay(ChannelPaymentRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = Instant.now(clock);
        var response = new ChannelPaymentResponse(
                request.requestId(),
                request.paymentId(),
                ChannelResult.SUCCESS,
                "00",
                now);

        var rule = rules.findActive(request.channel(), now);
        if (rule.isPresent()
                && rule.get().type() == FaultType.TIMEOUT_BUT_SUCCESS
                && decider.applies(request.paymentId(), rule.get())) {
            finalStates.put(request.paymentId(), response);
            throw new ChannelTimeoutException(request.paymentId());
        }

        finalStates.put(request.paymentId(), response);
        return response;
    }

    public ChannelPaymentResponse query(String paymentId) {
        var response = finalStates.get(paymentId);
        if (response == null) {
            throw new ChannelPaymentNotFoundException(paymentId);
        }
        return response;
    }
}
