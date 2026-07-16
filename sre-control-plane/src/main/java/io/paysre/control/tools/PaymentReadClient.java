package io.paysre.control.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public interface PaymentReadClient {

    JsonNode timeline(String paymentId);

    List<UnknownPaymentRecord> unknownPayments(Instant from, Instant to, int size);
}
