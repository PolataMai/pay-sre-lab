package io.paysre.payment.adapter.http;

import io.paysre.payment.application.AcceptPaymentCommand;
import io.paysre.payment.application.PaymentApplicationService;
import io.paysre.payment.application.PaymentRepository;
import io.paysre.payment.domain.PaymentEvent;
import io.paysre.payment.domain.PaymentOrder;
import io.paysre.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public final class PaymentController {

    private final PaymentApplicationService applicationService;
    private final PaymentRepository repository;

    public PaymentController(
            PaymentApplicationService applicationService, PaymentRepository repository) {
        this.applicationService = applicationService;
        this.repository = repository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentView accept(@RequestBody AcceptPaymentCommand command) {
        return PaymentView.from(applicationService.accept(command));
    }

    @GetMapping("/{paymentId}")
    public PaymentView get(@PathVariable String paymentId) {
        return PaymentView.from(find(paymentId));
    }

    @GetMapping("/{paymentId}/timeline")
    public List<PaymentEvent> timeline(@PathVariable String paymentId) {
        return find(paymentId).events();
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail notFound(PaymentNotFoundException exception) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setProperty("code", "PAYMENT_NOT_FOUND");
        return detail;
    }

    private PaymentOrder find(String paymentId) {
        return repository.findByPaymentId(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    public record PaymentView(
            String paymentId,
            String orderId,
            String merchantId,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            String channel,
            String routeVersion,
            long version,
            Instant updatedAt) {

        static PaymentView from(PaymentOrder payment) {
            return new PaymentView(
                    payment.paymentId(),
                    payment.orderId(),
                    payment.merchantId(),
                    payment.money().amount(),
                    payment.money().currency().getCurrencyCode(),
                    payment.status(),
                    payment.channel(),
                    payment.routeVersion(),
                    payment.version(),
                    payment.updatedAt());
        }
    }
}
