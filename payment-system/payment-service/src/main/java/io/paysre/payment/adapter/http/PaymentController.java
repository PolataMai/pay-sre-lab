package io.paysre.payment.adapter.http;

import io.paysre.payment.application.AcceptPaymentCommand;
import io.paysre.payment.application.PaymentApplicationService;
import io.paysre.payment.application.PaymentRepository;
import io.paysre.payment.application.PaymentSyncResult;
import io.paysre.payment.application.UnknownPaymentQueryService;
import io.paysre.payment.application.UnknownPaymentSummary;
import io.paysre.payment.application.UnknownPaymentSyncService;
import io.paysre.payment.domain.PaymentEvent;
import io.paysre.payment.domain.PaymentOrder;
import io.paysre.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public final class PaymentController {

    private final PaymentApplicationService applicationService;
    private final PaymentRepository repository;
    private final UnknownPaymentQueryService unknownPaymentQuery;
    private final UnknownPaymentSyncService unknownPaymentSync;

    public PaymentController(
            PaymentApplicationService applicationService,
            PaymentRepository repository,
            UnknownPaymentQueryService unknownPaymentQuery,
            UnknownPaymentSyncService unknownPaymentSync) {
        this.applicationService = applicationService;
        this.repository = repository;
        this.unknownPaymentQuery = unknownPaymentQuery;
        this.unknownPaymentSync = unknownPaymentSync;
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

    @PostMapping("/{paymentId}/state-sync")
    public PaymentSyncResult stateSync(@PathVariable String paymentId) {
        return unknownPaymentSync.sync(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    @GetMapping(params = "status")
    public List<UnknownPaymentSummary> search(
            @RequestParam PaymentStatus status,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "50") int size) {
        if (status != PaymentStatus.UNKNOWN) {
            throw new IllegalArgumentException("only UNKNOWN status query is supported");
        }
        return unknownPaymentQuery.find(from, to, size);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail notFound(PaymentNotFoundException exception) {
        var detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setProperty("code", "PAYMENT_NOT_FOUND");
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidQuery(IllegalArgumentException exception) {
        var detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setProperty("code", "INVALID_PAYMENT_QUERY");
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
