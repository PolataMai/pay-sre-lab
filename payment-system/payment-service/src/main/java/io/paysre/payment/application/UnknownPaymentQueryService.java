package io.paysre.payment.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class UnknownPaymentQueryService {

    private static final Duration MAX_RANGE = Duration.ofHours(24);
    private static final int MAX_SIZE = 200;

    private final PaymentRepository repository;

    public UnknownPaymentQueryService(PaymentRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public List<UnknownPaymentSummary> find(Instant from, Instant to, int size) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("to must be after from");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new IllegalArgumentException("time range must not exceed 24 hours");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and 200");
        }
        return repository.findUnknown(from, to, size);
    }
}
