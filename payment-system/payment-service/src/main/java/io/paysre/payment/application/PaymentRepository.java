package io.paysre.payment.application;

import io.paysre.payment.domain.PaymentOrder;
import java.util.Optional;

public interface PaymentRepository {

    Optional<PaymentOrder> findByMerchantAndIdempotencyKey(
            String merchantId, String idempotencyKey);

    Optional<PaymentOrder> findByPaymentId(String paymentId);

    PaymentOrder save(PaymentOrder payment, String idempotencyKey);
}
