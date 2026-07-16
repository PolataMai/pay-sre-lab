package io.paysre.channel;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelPaymentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/channel/payments")
public final class ChannelPaymentController {

    private final ChannelSimulationService service;

    public ChannelPaymentController(ChannelSimulationService service) {
        this.service = service;
    }

    @PostMapping
    public ChannelPaymentResponse pay(@RequestBody ChannelPaymentRequest request) {
        return service.pay(request);
    }

    @GetMapping("/{paymentId}")
    public ChannelPaymentResponse query(@PathVariable String paymentId) {
        return service.query(paymentId);
    }

    @ExceptionHandler(ChannelTimeoutException.class)
    ProblemDetail timeout(ChannelTimeoutException exception) {
        var detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.GATEWAY_TIMEOUT, exception.getMessage());
        detail.setProperty("code", "CHANNEL_TIMEOUT");
        return detail;
    }

    @ExceptionHandler(ChannelPaymentNotFoundException.class)
    ProblemDetail notFound(ChannelPaymentNotFoundException exception) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setProperty("code", "CHANNEL_PAYMENT_NOT_FOUND");
        return detail;
    }
}
