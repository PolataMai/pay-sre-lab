package io.paysre.payment.application;

import io.paysre.contracts.ChannelPaymentResponse;

@FunctionalInterface
public interface ChannelStateQuery {

    ChannelPaymentResponse query(String paymentId);
}
