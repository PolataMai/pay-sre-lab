package io.paysre.channel;

import io.paysre.contracts.ChannelCallback;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Channel-side HTTP entry point for asynchronous callback
 * notifications. The simulator records what arrived so the lab can
 * assert delivery / duplicate behaviour; CALLBACK_LOST /
 * CALLBACK_DUPLICATED faults intercept here.
 */
@RestController
@RequestMapping("/api/channel/callbacks")
public final class ChannelCallbackController {

    private final ChannelSimulationService service;
    private final Clock clock;

    public ChannelCallbackController(ChannelSimulationService service, Clock clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping
    public CallbackReceipt submit(@RequestBody CallbackSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        var callback = new ChannelCallback(
                submission.callbackId() == null
                        ? UUID.randomUUID().toString()
                        : submission.callbackId(),
                submission.paymentId(),
                submission.channel(),
                submission.result(),
                submission.channelCode(),
                submission.sequenceNumber(),
                clock.instant());
        Optional<ChannelCallback> recorded = service.ingestCallback(callback);
        return new CallbackReceipt(
                recorded.isPresent(),
                callback.callbackId(),
                recorded.isPresent() ? "ACCEPTED" : "DROPPED");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException exception) {
        var detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setProperty("code", "INVALID_CALLBACK");
        return detail;
    }

    public record CallbackSubmission(
            String callbackId,
            String paymentId,
            String channel,
            io.paysre.contracts.ChannelResult result,
            String channelCode,
            long sequenceNumber) {
    }

    public record CallbackReceipt(boolean recorded, String callbackId, String status) {
    }
}