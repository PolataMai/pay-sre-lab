package io.paysre.contracts;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * An immutable monetary value that keeps decimal precision and ISO 4217 currency metadata together.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (amount.scale() > currency.getDefaultFractionDigits()) {
            throw new IllegalArgumentException("amount scale exceeds currency fraction digits");
        }
    }
}
