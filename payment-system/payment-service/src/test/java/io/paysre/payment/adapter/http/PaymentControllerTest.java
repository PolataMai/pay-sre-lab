package io.paysre.payment.adapter.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paysre.contracts.Money;
import io.paysre.payment.application.PaymentApplicationService;
import io.paysre.payment.application.PaymentRepository;
import io.paysre.payment.domain.PaymentOrder;
import io.paysre.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PaymentControllerTest {

    @Test
    void returnsAStablePaymentViewAndOrderedTimeline() {
        var payment = unknownPayment();
        var repository = mock(PaymentRepository.class);
        when(repository.findByPaymentId("P10001")).thenReturn(Optional.of(payment));
        var controller = new PaymentController(mock(PaymentApplicationService.class), repository);

        var view = controller.get("P10001");

        assertThat(view.paymentId()).isEqualTo("P10001");
        assertThat(view.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(controller.timeline("P10001"))
                .extracting(event -> event.reasonCode())
                .containsExactly("CREATED", "CHANNEL_SELECTED", "CHANNEL_TIMEOUT");
    }

    @Test
    void mapsMissingPaymentToANotFoundProblem() {
        var repository = mock(PaymentRepository.class);
        when(repository.findByPaymentId("MISSING")).thenReturn(Optional.empty());
        var controller = new PaymentController(mock(PaymentApplicationService.class), repository);

        assertThatThrownBy(() -> controller.get("MISSING"))
                .isInstanceOf(PaymentNotFoundException.class);
        var problem = controller.notFound(new PaymentNotFoundException("MISSING"));
        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getProperties()).containsEntry("code", "PAYMENT_NOT_FOUND");
    }

    private PaymentOrder unknownPayment() {
        var payment = PaymentOrder.create(
                "P10001",
                "O10001",
                "M001",
                new Money(new BigDecimal("10.00"), Currency.getInstance("CNY")),
                Instant.EPOCH);
        payment.start("CHANNEL_A", "route-v1", Instant.EPOCH);
        payment.markUnknown("CHANNEL_TIMEOUT", Instant.EPOCH.plusSeconds(1));
        return payment;
    }
}
