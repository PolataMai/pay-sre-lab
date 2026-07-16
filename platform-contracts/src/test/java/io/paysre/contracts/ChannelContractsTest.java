package io.paysre.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChannelContractsTest {

    @Test
    void preservesPaymentCorrelationAcrossRequestResponseAndEventEnvelope() {
        var money = new Money(new BigDecimal("10.00"), Currency.getInstance("CNY"));
        var request = new ChannelPaymentRequest("REQ-1", "PAY-1", "CHANNEL_A", money);
        var response = new ChannelPaymentResponse(
                request.requestId(), request.paymentId(), ChannelResult.SUCCESS, "00", Instant.EPOCH);
        var envelope = new DomainEventEnvelope<>(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "ChannelPaymentCompleted",
                1,
                Instant.EPOCH,
                "PAYMENT",
                request.paymentId(),
                "trace-1",
                request.requestId(),
                response);

        assertThat(response.requestId()).isEqualTo(request.requestId());
        assertThat(response.paymentId()).isEqualTo(request.paymentId());
        assertThat(response.result()).isEqualTo(ChannelResult.SUCCESS);
        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.correlationId()).isEqualTo(request.requestId());
        assertThat(envelope.payload()).isSameAs(response);
    }
}
