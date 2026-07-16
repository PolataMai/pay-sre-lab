package io.paysre.payment.application;

import io.paysre.contracts.ChannelPaymentRequest;
import io.paysre.contracts.ChannelPaymentResponse;

@FunctionalInterface
public interface ChannelClient {

    ChannelPaymentResponse pay(ChannelPaymentRequest request);
}
