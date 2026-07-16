package io.paysre.payment.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UnknownPaymentQueryServiceTest {

    private static final Instant FROM = Instant.parse("2026-07-16T00:00:00Z");

    @Test
    void delegatesAValidBoundedQueryToTheRepository() {
        var repository = mock(PaymentRepository.class);
        var service = new UnknownPaymentQueryService(repository);
        var to = FROM.plusSeconds(3_600);

        service.find(FROM, to, 50);

        verify(repository).findUnknown(FROM, to, 50);
    }

    @Test
    void rejectsATimeRangeLongerThanTwentyFourHours() {
        var service = new UnknownPaymentQueryService(mock(PaymentRepository.class));

        assertThatThrownBy(() -> service.find(FROM, FROM.plusSeconds(86_401), 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("time range must not exceed 24 hours");
    }

    @Test
    void rejectsAPageSizeAboveTwoHundred() {
        var service = new UnknownPaymentQueryService(mock(PaymentRepository.class));

        assertThatThrownBy(() -> service.find(FROM, FROM.plusSeconds(3_600), 201))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("size must be between 1 and 200");
    }
}
