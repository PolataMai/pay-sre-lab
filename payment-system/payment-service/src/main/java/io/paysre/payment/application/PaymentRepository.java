package io.paysre.payment.application;

import io.paysre.payment.domain.PaymentOrder;
import java.util.Optional;
import java.time.Instant;
import java.util.List;

public interface PaymentRepository {

    Optional<PaymentOrder> findByMerchantAndIdempotencyKey(
            String merchantId, String idempotencyKey);

    Optional<PaymentOrder> findByPaymentId(String paymentId);

    List<UnknownPaymentSummary> findUnknown(Instant from, Instant to, int size);

    PaymentOrder save(PaymentOrder payment, String idempotencyKey);
}
